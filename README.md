# IoT Device Monitor

App to monitor IoT devices (ESP32 + temperature, humidity and pressure sensors) in real time, with per-device alerts and push notifications. Built with Kotlin Multiplatform and Compose Multiplatform (Material 3); Android is the supported target today.

## Features

- Email/password accounts; each user only sees their own devices.
- Register a device by its ID and watch its readings live.
- Per-device alerts: min/max for each metric and an "offline" warning, with ready-made templates (compost, greenhouse, fridge, room).
- Push notifications (FCM) sent by Cloud Functions only when a state changes.
- In-app account deletion.

## Project layout

- `/composeApp` – shared Compose code (`commonMain`) plus platform code (`androidMain`, `iosMain`, `desktopMain`).
- `/androidApp` – Android application (`com.gabodev.iotdevicemonitor`), notifications and release signing.
- `/iosApp` – iOS entry point (not yet functional).
- `/functions` – Firebase Cloud Functions (alerts). Requires the Blaze plan to deploy.
- `/esp32` – firmware sketch that sends readings to Firebase.
- `database.rules.json` – Realtime Database rules, deployed with `firebase deploy --only database`.

## Run it

1. Put your Firebase `google-services.json` in `androidApp/` (never commit it).
2. Build and install on a device or emulator:
   ```bash
   ./gradlew :androidApp:installDebug
   ```

## Release build (Google Play)

1. Create an upload keystore and copy `keystore.properties.example` to `keystore.properties` (gitignored) with its paths and passwords. On CI, use the `IOTMONITOR_KEYSTORE_*` / `IOTMONITOR_KEY_*` environment variables instead.
2. Build the bundle, raising `versionCode` on every upload:
   ```bash
   ./gradlew :androidApp:bundleRelease -PversionCode=2
   ```

## ESP32

Copy `esp32/ESP32_Firebase_Client_h/secrets.h.example` to `secrets.h` and fill in your WiFi, Firebase project and device account. The sketch prints its device ID over serial; use that ID when registering the device in the app.

## Learn more about [Kotlin Multiplatform](https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html)
