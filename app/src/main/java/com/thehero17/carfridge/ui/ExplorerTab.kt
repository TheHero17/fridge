package com.thehero17.carfridge.ui

import android.bluetooth.BluetoothGattCharacteristic
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.thehero17.carfridge.AppViewModel
import com.thehero17.carfridge.Connection
import com.thehero17.carfridge.ble.canNotify
import com.thehero17.carfridge.ble.canRead
import com.thehero17.carfridge.ble.canWrite
import com.thehero17.carfridge.ble.propertiesString
import com.thehero17.carfridge.ble.short
import com.thehero17.carfridge.protocol.Alpicool
import com.thehero17.carfridge.protocol.hexToBytes
import com.thehero17.carfridge.protocol.toHex

/** Generic GATT explorer for devices that speak something other than the Alpicool protocol. */
@Composable
fun ExplorerTab(vm: AppViewModel, conn: Connection) {
    val services by conn.client.services.collectAsState()
    var writeTarget by remember { mutableStateOf<BluetoothGattCharacteristic?>(null) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Card {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Everything you do here is recorded in the Log tab. Share that log to get support for an unknown fridge.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(onClick = { vm.probe() }, enabled = services.isNotEmpty()) {
                        Text("Auto-probe (subscribe all + send fridge queries)")
                    }
                }
            }
        }
        if (services.isEmpty()) item { Text("No services yet – waiting for connection.", Modifier.padding(8.dp)) }
        services.forEach { service ->
            item(key = "s" + service.uuid + service.instanceId) {
                Text(
                    "Service ${service.uuid.short()}",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            items(service.characteristics, key = { "c" + service.uuid + it.uuid + it.instanceId }) { c ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(c.uuid.short(), fontFamily = FontFamily.Monospace)
                        Text(c.propertiesString(), style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (c.canRead) TextButton(onClick = { vm.read(c) }) { Text("Read") }
                            if (c.canNotify) TextButton(onClick = { vm.subscribe(c) }) { Text("Subscribe") }
                            if (c.canWrite) TextButton(onClick = { writeTarget = c }) { Text("Write…") }
                        }
                    }
                }
            }
        }
    }

    writeTarget?.let { c ->
        WriteDialog(
            title = "Write to ${c.uuid.short()}",
            onDismiss = { writeTarget = null },
            onSend = { bytes, withResponse -> vm.write(c, bytes, withResponse) },
        )
    }
}

@Composable
private fun WriteDialog(title: String, onDismiss: () -> Unit, onSend: (ByteArray, Boolean) -> Unit) {
    var text by remember { mutableStateOf("") }
    var withResponse by remember { mutableStateOf(false) }
    var addFrame by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; error = null },
                    label = { Text("Hex bytes") },
                    placeholder = { Text("fe fe 03 01 02 00") },
                    isError = error != null,
                    supportingText = { error?.let { Text(it) } },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { text = Alpicool.query().toHex(); addFrame = false }) { Text("Query") }
                    TextButton(onClick = { text = Alpicool.bind().toHex(); addFrame = false }) { Text("Bind") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = addFrame, onCheckedChange = { addFrame = it })
                    Text("Wrap as FE FE frame (first byte = cmd)", style = MaterialTheme.typography.bodySmall)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = withResponse, onCheckedChange = { withResponse = it })
                    Text("Write with response", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                try {
                    var bytes = text.hexToBytes()
                    require(bytes.isNotEmpty()) { "enter at least one byte" }
                    if (addFrame) bytes = Alpicool.buildFrame(bytes[0].toInt() and 0xFF, bytes.copyOfRange(1, bytes.size))
                    onSend(bytes, withResponse)
                } catch (e: Exception) {
                    error = e.message ?: "invalid hex"
                }
            }) { Text("Send") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
