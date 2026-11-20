package com.gabodev.iotdevicemonitor.models

/** Allowed range for one metric. A null bound means "no limit on that side". */
data class Threshold(
    val enabled: Boolean = false,
    val min: Double? = null,
    val max: Double? = null,
) {
    /** True when the alert is on and [value] falls outside the range. */
    fun isViolatedBy(value: Double?): Boolean {
        if (!enabled || value == null) return false
        return (min != null && value < min) || (max != null && value > max)
    }
}

/**
 * Stored at /devices/{id}/alerts. The app highlights violations locally; the Cloud
 * Function (functions/index.js) sends the push notifications.
 */
data class AlertConfig(
    val temperature: Threshold = Threshold(),
    val humidity: Threshold = Threshold(),
    val pressure: Threshold = Threshold(),
    val offlineEnabled: Boolean = false,
    val offlineMinutes: Int = 30,
) {
    val anyEnabled: Boolean
        get() = temperature.enabled || humidity.enabled || pressure.enabled || offlineEnabled
}

/** Ready-made configurations for common uses (shown as chips in the alert settings). */
enum class AlertTemplate(val label: String, val description: String, val config: AlertConfig) {
    Compost(
        "Compost",
        "Fase caliente 55–70 °C y humedad mínima de 40 %",
        AlertConfig(
            temperature = Threshold(true, 55.0, 70.0),
            humidity = Threshold(true, 40.0, null),
            offlineEnabled = true, offlineMinutes = 60,
        ),
    ),
    Greenhouse(
        "Invernadero",
        "Temperatura 15–30 °C y humedad 50–85 %",
        AlertConfig(
            temperature = Threshold(true, 15.0, 30.0),
            humidity = Threshold(true, 50.0, 85.0),
            offlineEnabled = true, offlineMinutes = 30,
        ),
    ),
    Fridge(
        "Refrigerador",
        "Cadena de frío 0–5 °C",
        AlertConfig(
            temperature = Threshold(true, 0.0, 5.0),
            offlineEnabled = true, offlineMinutes = 15,
        ),
    ),
    Room(
        "Habitación",
        "Confort 18–28 °C y humedad 30–60 %",
        AlertConfig(
            temperature = Threshold(true, 18.0, 28.0),
            humidity = Threshold(true, 30.0, 60.0),
        ),
    ),
}
