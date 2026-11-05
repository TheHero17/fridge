@file:OptIn(ExperimentalMaterial3Api::class)

package com.thehero17.carfridge.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.thehero17.carfridge.AppViewModel
import com.thehero17.carfridge.ble.FoundDevice

private fun requiredPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

@Composable
fun ScanScreen(vm: AppViewModel) {
    val ctx = LocalContext.current
    var granted by remember {
        mutableStateOf(requiredPermissions().all {
            ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
        })
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        granted = res.values.all { it }
    }
    var btOn by remember { mutableStateOf(vm.bluetoothEnabled) }
    val enableLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        btOn = vm.bluetoothEnabled
    }

    val devices by vm.scanner.devices.collectAsState()
    val scanning by vm.scanner.scanning.collectAsState()
    val last by vm.lastDevice.collectAsState()
    val bind by vm.bindOnConnect.collectAsState()
    var showAll by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { if (!granted) permLauncher.launch(requiredPermissions()) }
    LaunchedEffect(granted, btOn) { if (granted && btOn) vm.scanner.start() }
    DisposableEffect(Unit) { onDispose { vm.scanner.stop() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Car Fridge") },
                actions = {
                    IconButton(onClick = {
                        if (scanning) vm.scanner.stop() else vm.scanner.start()
                    }, enabled = granted && btOn) {
                        Icon(if (scanning) Icons.Default.Refresh else Icons.Default.Search, contentDescription = "Scan")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (scanning) LinearProgressIndicator(Modifier.fillMaxWidth())
            when {
                !vm.bluetoothAvailable -> Message("This phone has no Bluetooth LE.")
                !granted -> Message("Bluetooth permission is needed to find the fridge.") {
                    Button(onClick = { permLauncher.launch(requiredPermissions()) }) { Text("Grant permission") }
                }
                !btOn -> Message("Bluetooth is off.") {
                    Button(onClick = {
                        enableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                    }) { Text("Turn on Bluetooth") }
                }
                else -> {
                    val sorted = devices.values
                        .filter { showAll || it.name != null || it.likelyFridge }
                        .sortedWith(compareByDescending<FoundDevice> { it.likelyFridge }.thenByDescending { it.rssi })
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        last?.let { saved ->
                            item {
                                Card(
                                    Modifier.fillMaxWidth().clickable { vm.connect(saved.address, saved.name) },
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                                ) {
                                    Column(Modifier.padding(16.dp)) {
                                        Text("Reconnect to last fridge", fontWeight = FontWeight.Bold)
                                        Text("${saved.name ?: "Unnamed"} · ${saved.address}", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                        item {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                FilterChip(selected = showAll, onClick = { showAll = !showAll }, label = { Text("Show unnamed") })
                                Spacer(Modifier.width(8.dp))
                                FilterChip(selected = bind, onClick = { vm.setBindOnConnect(!bind) }, label = { Text("Bind on connect") })
                            }
                        }
                        if (sorted.isEmpty()) {
                            item {
                                Text(
                                    "Searching… Switch the fridge on. Close the official app – the fridge accepts only one connection.",
                                    Modifier.padding(16.dp),
                                )
                            }
                        }
                        items(sorted, key = { it.address }) { d -> DeviceRow(d) { vm.connect(d.address, d.name) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceRow(d: FoundDevice, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(d.name ?: "(no name)", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (d.likelyFridge) AssistChip(onClick = onClick, label = { Text("Fridge?") })
                Spacer(Modifier.width(8.dp))
                Text("${d.rssi} dBm", style = MaterialTheme.typography.bodySmall)
            }
            Text(d.address, style = MaterialTheme.typography.bodySmall)
            if (d.serviceUuids.isNotEmpty()) {
                Text("Services: ${d.serviceUuids.joinToString { it.substring(0, 8) }}", style = MaterialTheme.typography.bodySmall)
            }
            d.manufacturerData?.let { Text("Mfr: $it", style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun Message(text: String, action: @Composable () -> Unit = {}) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.padding(8.dp))
        action()
    }
}

