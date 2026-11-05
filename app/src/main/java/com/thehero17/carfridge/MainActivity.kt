package com.thehero17.carfridge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.thehero17.carfridge.ui.AppTheme
import com.thehero17.carfridge.ui.DeviceScreen
import com.thehero17.carfridge.ui.ScanScreen

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppTheme {
                val connection by vm.connection.collectAsState()
                val conn = connection
                if (conn == null) {
                    ScanScreen(vm)
                } else {
                    BackHandler { vm.disconnect() }
                    DeviceScreen(vm, conn)
                }
            }
        }
    }
}
