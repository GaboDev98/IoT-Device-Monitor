package com.gabodev.iotdevicemonitor.ui.components

import com.gabodev.iotdevicemonitor.models.Device
import com.gabodev.iotdevicemonitor.models.Reading
import com.gabodev.iotdevicemonitor.models.Threshold
import com.gabodev.iotdevicemonitor.utils.currentEpochSeconds

/** Device health shown by [StatusBadge]. */
enum class DeviceStatus(val label: String) {
    Online("En línea"),
    Warning("Revisar"),
    Critical("Crítico"),
    Offline("Sin conexión"),
}

private const val ONLINE_WINDOW_S = 5 * 60L
private const val OFFLINE_AFTER_S = 30 * 60L

val Reading.epochSeconds: Long? get() = timestamp.toLongOrNull()

val Device.latestReading: Reading?
    get() = readings.values.maxByOrNull { it.epochSeconds ?: Long.MIN_VALUE }

/** Threshold configured for [metric] on this device. */
fun Device.thresholdFor(metric: Metric): Threshold = when (metric) {
    Metric.Temperature -> alerts.temperature
    Metric.Humidity -> alerts.humidity
    Metric.Pressure -> alerts.pressure
}

/** Metrics whose value in [reading] is outside the configured alert range. */
fun Device.violations(reading: Reading? = latestReading): Set<Metric> {
    if (reading == null) return emptySet()
    return Metric.entries.filter { thresholdFor(it).isViolatedBy(reading.valueOf(it)?.toDoubleOrNull()) }.toSet()
}

/**
 * Crítico if the latest reading breaks an alert range; otherwise by age: En línea under
 * 5 min, Revisar under 30 min (or under the device's offline alert window), else Sin conexión.
 */
fun Device.status(now: Long = currentEpochSeconds()): DeviceStatus {
    val last = latestReading?.epochSeconds ?: return DeviceStatus.Offline
    val age = now - last
    val offlineAfter = if (alerts.offlineEnabled) alerts.offlineMinutes * 60L else OFFLINE_AFTER_S
    return when {
        age > offlineAfter -> DeviceStatus.Offline
        violations().isNotEmpty() -> DeviceStatus.Critical
        age <= ONLINE_WINDOW_S -> DeviceStatus.Online
        else -> DeviceStatus.Warning
    }
}

/** "hace 2 min", "hace 3 h", "hace 4 d". */
fun relativeTime(epochSeconds: Long?, now: Long = currentEpochSeconds()): String {
    if (epochSeconds == null) return "Sin lecturas"
    val diff = (now - epochSeconds).coerceAtLeast(0)
    return when {
        diff < 60 -> "hace un momento"
        diff < 3600 -> "hace ${diff / 60} min"
        diff < 86_400 -> "hace ${diff / 3600} h"
        else -> "hace ${diff / 86_400} d"
    }
}
