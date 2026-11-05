@file:OptIn(ExperimentalMaterial3Api::class)

package com.thehero17.carfridge.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.thehero17.carfridge.AppViewModel
import com.thehero17.carfridge.Connection
import com.thehero17.carfridge.ble.LinkState
import com.thehero17.carfridge.fridge.ProtocolState

@Composable
fun DeviceScreen(vm: AppViewModel, conn: Connection) {
    val link by conn.client.state.collectAsState()
    val proto by conn.fridge.protocol.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { vm.disconnect() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Disconnect")
                    }
                },
                title = {
                    Column {
                        Text(vm.lastDevice.collectAsState().value?.name ?: conn.client.device.address)
                        Text(
                            when (link) {
                                LinkState.DISCONNECTED -> "Disconnected"
                                LinkState.CONNECTING -> "Connecting…"
                                LinkState.DISCOVERING -> "Discovering services…"
                                LinkState.READY -> when (proto) {
                                    ProtocolState.ONLINE -> "Connected"
                                    ProtocolState.HANDSHAKE -> "Waiting for fridge…"
                                    ProtocolState.NO_RESPONSE -> "No answer from fridge"
                                    ProtocolState.UNSUPPORTED -> "Unknown device protocol"
                                    ProtocolState.WAITING -> "Connected"
                                }
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
                actions = {
                    if (link == LinkState.DISCONNECTED) {
                        TextButton(onClick = { vm.reconnect() }) { Text("Reconnect") }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                listOf("Fridge", "Explorer", "Log").forEachIndexed { i, title ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(title) })
                }
            }
            when (tab) {
                0 -> FridgeTab(vm, conn) { tab = 1 }
                1 -> ExplorerTab(vm, conn)
                else -> LogTab(vm)
            }
        }
    }
}
