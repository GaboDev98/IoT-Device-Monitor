package com.gabodev.iotdevicemonitor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.gabodev.iotdevicemonitor.data.FirebaseDeviceRepository
import com.gabodev.iotdevicemonitor.viewmodel.AuthViewModel
import com.gabodev.iotdevicemonitor.viewmodel.DeviceViewModel

class MainActivity : ComponentActivity() {
    private val deviceViewModel by lazy { DeviceViewModel(FirebaseDeviceRepository()) }
    private val authViewModel by lazy { AuthViewModel() }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        AlertNotifications.createChannel(this)

        setContent {
            App(deviceViewModel, authViewModel)
        }
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    val deviceViewModel by lazy { DeviceViewModel(FirebaseDeviceRepository()) }
    val authViewModel by lazy { AuthViewModel() }
    App(deviceViewModel, authViewModel)
}