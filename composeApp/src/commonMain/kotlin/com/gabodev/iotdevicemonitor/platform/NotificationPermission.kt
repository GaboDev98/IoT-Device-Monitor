package com.gabodev.iotdevicemonitor.platform

import androidx.compose.runtime.Composable

/** Returns a callback that asks for permission to show notifications (no-op where not needed). */
@Composable
expect fun rememberNotificationPermissionRequest(): () -> Unit
