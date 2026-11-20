/**
 * Push alerts for IoT Device Monitor.
 *
 * Data (see database.rules.json):
 *   devices/{id}/alerts/{temperature|humidity|pressure} = { enabled, min?, max? }
 *   devices/{id}/alerts/offline = { enabled, minutes }
 *   devices/{id}/alertState/{metric} = "ok" | "low" | "high"; alertState/offline = bool
 *   devices/{id}/lastSeen = epoch seconds of the last reading (server time)
 *   users/{uid}/fcmTokens/{token} = true
 *   offlineWatch/{id} = { minutes }   (server-only index of devices with offline alerts)
 *
 * We only notify on state changes (ok -> high, high -> ok, ...) so a device that keeps
 * reporting an out-of-range value doesn't send a push every few minutes.
 */
const { onValueCreated, onValueWritten } = require("firebase-functions/v2/database");
const { onSchedule } = require("firebase-functions/v2/scheduler");
const { logger } = require("firebase-functions");
const { initializeApp } = require("firebase-admin/app");
const { getDatabase } = require("firebase-admin/database");
const { getMessaging } = require("firebase-admin/messaging");

initializeApp();
const db = getDatabase();

const METRICS = {
  temperature: { label: "Temperatura", unit: "°C" },
  humidity: { label: "Humedad", unit: "%" },
  pressure: { label: "Presión", unit: "hPa" },
};

const nowSeconds = () => Math.floor(Date.now() / 1000);
const fmt = (v) => (Number.isInteger(v) ? String(v) : v.toFixed(1));

/** "low" | "high" | "ok" for a value against { min, max }. */
function evaluate(value, cfg) {
  if (typeof cfg.min === "number" && value < cfg.min) return "low";
  if (typeof cfg.max === "number" && value > cfg.max) return "high";
  return "ok";
}

async function getDeviceFields(deviceId, fields) {
  const ref = db.ref(`devices/${deviceId}`);
  // Read single children: the device node also holds the whole reading history.
  const values = await Promise.all(fields.map((f) => ref.child(f).get().then((s) => s.val())));
  return Object.fromEntries(fields.map((f, i) => [f, values[i]]));
}

/** Sends each message to every phone of [uid] and prunes tokens FCM reports as dead. */
async function notify(uid, deviceId, messages) {
  if (messages.length === 0) return;
  const tokensSnap = await db.ref(`users/${uid}/fcmTokens`).get();
  const tokens = Object.keys(tokensSnap.val() || {});
  if (tokens.length === 0) {
    logger.info("No FCM tokens", { uid, deviceId });
    return;
  }

  const dead = new Set();
  for (const m of messages) {
    const res = await getMessaging().sendEachForMulticast({
      tokens,
      notification: { title: m.title, body: m.body },
      data: { deviceId, metric: m.metric, title: m.title, body: m.body },
      android: {
        priority: "high",
        // Same tag per device+metric: a recovery replaces its alert instead of stacking.
        notification: { channelId: "alerts", tag: `${deviceId}:${m.metric}` },
      },
    });
    res.responses.forEach((r, i) => {
      const code = r.error && r.error.code;
      if (code === "messaging/registration-token-not-registered" || code === "messaging/invalid-registration-token") {
        dead.add(tokens[i]);
      }
    });
  }
  if (dead.size > 0) {
    const removals = Object.fromEntries([...dead].map((t) => [t, null]));
    await db.ref(`users/${uid}/fcmTokens`).update(removals);
  }
}

/** Every new reading: compare with the configured ranges and notify on changes. */
exports.onReading = onValueCreated("/devices/{deviceId}/readings/{readingId}", async (event) => {
  const { deviceId } = event.params;
  const reading = event.data.val() || {};
  const { owner, name, alerts, alertState } = await getDeviceFields(deviceId, ["owner", "name", "alerts", "alertState"]);
  if (!owner) return;

  const label = name || deviceId;
  const state = alertState || {};
  const updates = { lastSeen: nowSeconds() };
  const messages = [];

  for (const [metric, meta] of Object.entries(METRICS)) {
    const cfg = alerts && alerts[metric];
    if (!cfg || !cfg.enabled) continue;
    const value = parseFloat(reading[metric]);
    if (Number.isNaN(value)) continue;

    const current = evaluate(value, cfg);
    const previous = state[metric] || "ok";
    if (current === previous) continue;
    updates[`alertState/${metric}`] = current;

    const v = `${fmt(value)} ${meta.unit}`;
    if (current === "ok") {
      messages.push({ metric, title: `✅ ${label}: ${meta.label} normal`, body: `Volvió al rango: ${v}.` });
    } else if (current === "high") {
      messages.push({ metric, title: `⚠️ ${label}: ${meta.label} alta`, body: `${v}, por encima del máximo de ${cfg.max} ${meta.unit}.` });
    } else {
      messages.push({ metric, title: `⚠️ ${label}: ${meta.label} baja`, body: `${v}, por debajo del mínimo de ${cfg.min} ${meta.unit}.` });
    }
  }

  if (state.offline === true) {
    updates["alertState/offline"] = false;
    messages.push({ metric: "offline", title: `✅ ${label} volvió a conectarse`, body: "Está enviando datos de nuevo." });
  }

  await db.ref(`devices/${deviceId}`).update(updates);
  await notify(owner, deviceId, messages);
});

/** Keeps offlineWatch in sync with each device's offline alert setting (and deletions). */
exports.onOfflineConfig = onValueWritten("/devices/{deviceId}/alerts/offline", async (event) => {
  const { deviceId } = event.params;
  const cfg = event.data.after.val();
  const watch = db.ref(`offlineWatch/${deviceId}`);
  if (cfg && cfg.enabled) {
    await watch.set({ minutes: cfg.minutes || 30 });
  } else {
    await watch.remove();
  }
});

/** Every 5 minutes: notify owners of watched devices that stopped reporting. */
exports.checkOffline = onSchedule("every 5 minutes", async () => {
  const watched = (await db.ref("offlineWatch").get()).val() || {};
  const now = nowSeconds();

  await Promise.all(Object.entries(watched).map(async ([deviceId, { minutes }]) => {
    const { owner, name, lastSeen, alertState } = await getDeviceFields(deviceId, ["owner", "name", "lastSeen", "alertState"]);
    if (!owner) {
      await db.ref(`offlineWatch/${deviceId}`).remove(); // device was deleted
      return;
    }
    if (!lastSeen || (alertState && alertState.offline === true)) return;
    if (now - lastSeen <= minutes * 60) return;

    await db.ref(`devices/${deviceId}/alertState/offline`).set(true);
    const label = name || deviceId;
    const since = minutes < 60 ? `${minutes} min` : `${Math.round(minutes / 60)} h`;
    await notify(owner, deviceId, [{
      metric: "offline",
      title: `📴 ${label} sin conexión`,
      body: `No envía datos desde hace más de ${since}. Revisa la alimentación y el WiFi.`,
    }]);
  }));
});
