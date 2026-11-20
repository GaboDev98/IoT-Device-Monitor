package com.gabodev.iotdevicemonitor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.gabodev.iotdevicemonitor.models.AlertConfig
import com.gabodev.iotdevicemonitor.models.AlertTemplate
import com.gabodev.iotdevicemonitor.models.Threshold
import com.gabodev.iotdevicemonitor.platform.rememberNotificationPermissionRequest
import com.gabodev.iotdevicemonitor.ui.components.AlertBanner
import com.gabodev.iotdevicemonitor.ui.components.BannerTone
import com.gabodev.iotdevicemonitor.ui.components.Metric
import com.gabodev.iotdevicemonitor.ui.components.thresholdFor
import com.gabodev.iotdevicemonitor.ui.theme.Spacing
import com.gabodev.iotdevicemonitor.viewmodel.PacaViewModel

private val OFFLINE_OPTIONS = listOf(15, 30, 60, 120)

/** Editable copy of a [Threshold]; bounds are kept as text so the user can type freely. */
private class ThresholdForm(t: Threshold) {
    var enabled by mutableStateOf(t.enabled)
    var min by mutableStateOf(t.min?.let(::formatBound).orEmpty())
    var max by mutableStateOf(t.max?.let(::formatBound).orEmpty())

    private fun parse(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()
    private fun invalid(text: String) = text.isNotBlank() && parse(text) == null

    val minError get() = invalid(min)
    val maxError get() = invalid(max)
    val rangeError get() = enabled && parse(min) != null && parse(max) != null && parse(min)!! > parse(max)!!
    val emptyError get() = enabled && min.isBlank() && max.isBlank()
    val hasError get() = enabled && (minError || maxError || rangeError || emptyError)

    fun load(t: Threshold) {
        enabled = t.enabled
        min = t.min?.let(::formatBound).orEmpty()
        max = t.max?.let(::formatBound).orEmpty()
    }

    fun toThreshold() = Threshold(enabled, parse(min), parse(max))
}

private fun formatBound(v: Double): String = if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AlertSettingsScreen(viewModel: PacaViewModel, deviceId: String, onBack: () -> Unit) {
    val devices by viewModel.devices.collectAsState()
    val device = devices.firstOrNull { it.id == deviceId }
    val requestNotifications = rememberNotificationPermissionRequest()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Alertas") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Volver") }
                },
            )
        },
    ) { padding ->
        if (device == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }

        // Form state starts from the saved config once the device is loaded.
        val forms = remember(device.id) { Metric.entries.associateWith { ThresholdForm(device.thresholdFor(it)) } }
        var offlineEnabled by remember(device.id) { mutableStateOf(device.alerts.offlineEnabled) }
        var offlineMinutes by remember(device.id) { mutableStateOf(device.alerts.offlineMinutes) }
        var appliedTemplate by remember(device.id) { mutableStateOf<AlertTemplate?>(null) }
        var saving by remember { mutableStateOf(false) }
        var saveFailed by remember { mutableStateOf(false) }

        fun buildConfig() = AlertConfig(
            temperature = forms.getValue(Metric.Temperature).toThreshold(),
            humidity = forms.getValue(Metric.Humidity).toThreshold(),
            pressure = forms.getValue(Metric.Pressure).toThreshold(),
            offlineEnabled = offlineEnabled,
            offlineMinutes = offlineMinutes,
        )

        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            Text(
                "Te avisaremos con una notificación cuando un valor salga del rango o el dispositivo deje de enviar datos.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Templates
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text("Plantillas", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    AlertTemplate.entries.forEach { template ->
                        SuggestionChip(
                            onClick = {
                                val c = template.config
                                forms.getValue(Metric.Temperature).load(c.temperature)
                                forms.getValue(Metric.Humidity).load(c.humidity)
                                forms.getValue(Metric.Pressure).load(c.pressure)
                                offlineEnabled = c.offlineEnabled
                                offlineMinutes = c.offlineMinutes
                                appliedTemplate = template
                            },
                            label = { Text(template.label) },
                            icon = { Icon(Icons.Rounded.AutoAwesome, contentDescription = null, Modifier.size(18.dp)) },
                        )
                    }
                }
                appliedTemplate?.let {
                    Text(
                        "${it.label}: ${it.description}. Revisa los valores y guarda.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            Metric.entries.forEach { metric -> ThresholdCard(metric, forms.getValue(metric)) }

            // Offline alert
            SettingCard {
                SwitchRow(
                    icon = { Icon(Icons.Rounded.WifiOff, contentDescription = null) },
                    title = "Sin conexión",
                    subtitle = "Avisar si no envía datos",
                    checked = offlineEnabled,
                    onCheckedChange = { offlineEnabled = it },
                )
                if (offlineEnabled) {
                    Text("Después de", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        OFFLINE_OPTIONS.forEach { minutes ->
                            FilterChip(
                                selected = offlineMinutes == minutes,
                                onClick = { offlineMinutes = minutes },
                                label = { Text(if (minutes < 60) "$minutes min" else "${minutes / 60} h") },
                            )
                        }
                    }
                }
            }

            if (saveFailed) {
                AlertBanner("No pudimos guardar las alertas. Verifica tu conexión e inténtalo de nuevo.", BannerTone.Error)
            }
            val hasErrors = forms.values.any { it.hasError }
            Button(
                onClick = {
                    val config = buildConfig()
                    if (config.anyEnabled) requestNotifications()
                    saving = true
                    saveFailed = false
                    viewModel.saveAlerts(device.id, config) { ok ->
                        saving = false
                        if (ok) onBack() else saveFailed = true
                    }
                },
                enabled = !hasErrors && !saving,
                modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.lg),
            ) {
                if (saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Guardar alertas")
            }
        }
    }
}

@Composable
private fun ThresholdCard(metric: Metric, form: ThresholdForm) {
    SettingCard {
        SwitchRow(
            icon = { Icon(metric.icon, contentDescription = null) },
            title = metric.label,
            subtitle = "Rango permitido en ${metric.unit}",
            checked = form.enabled,
            onCheckedChange = { form.enabled = it },
        )
        if (form.enabled) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                BoundField("Mínimo", form.min, { form.min = it }, metric.unit, form.minError || form.rangeError, Modifier.weight(1f))
                BoundField("Máximo", form.max, { form.max = it }, metric.unit, form.maxError || form.rangeError, Modifier.weight(1f))
            }
            val message = when {
                form.minError || form.maxError -> "Usa solo números, por ejemplo 25.5"
                form.rangeError -> "El mínimo no puede ser mayor que el máximo"
                form.emptyError -> "Escribe al menos un límite"
                else -> "Deja un campo vacío si no quieres ese límite"
            }
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = if (form.hasError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BoundField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    unit: String,
    isError: Boolean,
    modifier: Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.filter { c -> c.isDigit() || c in ".,-" }.take(8)) },
        label = { Text(label) },
        suffix = { Text(unit) },
        isError = isError,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}

@Composable
private fun SettingCard(content: @Composable () -> Unit) {
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.md)) { content() }
    }
}

@Composable
private fun SwitchRow(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
        icon()
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
