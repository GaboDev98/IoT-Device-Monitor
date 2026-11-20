package com.gabodev.iotdevicemonitor

import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.window.ComposeUIViewController
import com.gabodev.iotdevicemonitor.viewmodel.DeviceViewModel

fun MainViewController() = ComposeUIViewController {
    val viewModel: DeviceViewModel = viewModel()
    App(viewModel = viewModel)
}
