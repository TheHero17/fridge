@file:OptIn(ExperimentalMaterial3Api::class)

package com.thehero17.carfridge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thehero17.carfridge.AppViewModel
import com.thehero17.carfridge.Connection
import com.thehero17.carfridge.ble.LinkState
import com.thehero17.carfridge.fridge.FridgeController
import com.thehero17.carfridge.fridge.ProtocolState
import com.thehero17.carfridge.protocol.FridgeStatus
import com.thehero17.carfridge.protocol.Zone
import com.thehero17.carfridge.protocol.toHex
import kotlin.math.roundToInt

@Composable
fun FridgeTab(vm: AppViewModel, conn: Connection, openExplorer: () -> Unit) {
    val status by conn.fridge.status.collectAsState()
    val proto by conn.fridge.protocol.collectAsState()
    val link by conn.client.state.collectAsState()
    val channel by conn.fridge.channel.collectAsState()
    val s = status

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when {
            s == null && proto == ProtocolState.UNSUPPORTED -> Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("This device does not expose a known fridge service.", fontWeight = FontWeight.Bold)
                    Text(
                        "Open the Explorer tab to see its services, subscribe to notifications and send test bytes. " +
                            "Share the log so the protocol can be added.",
                    )
                    Button(onClick = openExplorer) { Text("Open Explorer") }
                }
            }
            s == null -> Card {
                Column(
                    Modifier.fillMaxWidth().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (link != LinkState.DISCONNECTED) CircularProgressIndicator()
                    Text(
                        when {
                            link == LinkState.DISCONNECTED -> "Disconnected."
                            proto == ProtocolState.NO_RESPONSE ->
                                "Connected, but the fridge does not answer.\n\n" +
                                    "• If the display shows \"APP\" or a flashing Bluetooth icon, press the button it asks for.\n" +
                                    "• Make sure no other app (official app, Home Assistant…) is connected.\n" +
                                    "• Try toggling \"Bind on connect\" on the scan screen."
                            else -> "Waiting for the fridge…"
                        },
                        textAlign = TextAlign.Center,
                    )
                    channel?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (link == LinkState.DISCONNECTED) Button(onClick = { vm.reconnect() }) { Text("Reconnect") }
                        if (link == LinkState.READY) {
                            OutlinedButton(onClick = { conn.fridge.bind() }) { Text("Send bind") }
                            OutlinedButton(onClick = { conn.fridge.refresh() }) { Text("Query") }
                        }
                    }
                }
            }
            else -> FridgeControls(s, conn.fridge, stale = link != LinkState.READY || proto != ProtocolState.ONLINE)
        }
    }
}

@Composable
private fun FridgeControls(s: FridgeStatus, fridge: FridgeController, stale: Boolean) {
    if (stale) {
        Text("Showing last known values – connection lost.", color = MaterialTheme.colorScheme.error)
    }

    // Overview
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Power", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Switch(checked = s.poweredOn, onCheckedChange = { fridge.setPower(it) }, enabled = !stale)
            }
            Text(
                buildString {
                    append("Battery %.1f V".format(s.batteryVoltage))
                    s.batteryPercent?.let { append(" · $it %") }
                    s.runningStatus?.let { append(" · compressor ${if (it != 0) "running" else "idle"}") }
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }

    val dual = s.right != null
    ZoneCard(
        title = if (dual) "Left zone" else "Temperature",
        current = s.left.current,
        target = s.left.target,
        min = s.tempMin,
        max = s.tempMax,
        unit = s.unitSymbol,
        enabled = !stale && s.poweredOn,
    ) { fridge.setTarget(Zone.LEFT, it) }

    s.right?.let { r ->
        val rMin = s.rightTempMin?.takeIf { it < (s.rightTempMax ?: 0) } ?: s.tempMin
        val rMax = s.rightTempMax?.takeIf { it > rMin } ?: s.tempMax
        ZoneCard(
            title = "Right zone",
            current = r.current,
            target = r.target,
            min = rMin,
            max = rMax,
            unit = s.unitSymbol,
            enabled = !stale && s.poweredOn,
        ) { fridge.setTarget(Zone.RIGHT, it) }
    }

    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Labeled("Mode") {
                Segmented(listOf("MAX", "ECO"), if (s.eco) 1 else 0, !stale) { fridge.setEco(it == 1) }
            }
            Labeled("Battery protection") {
                Segmented(listOf("Low", "Mid", "High"), s.batterySaver.coerceIn(0, 2), !stale) { fridge.setBatterySaver(it) }
            }
            Labeled("Unit") {
                Segmented(listOf("°C", "°F"), if (s.fahrenheit) 1 else 0, !stale) { fridge.setUnit(it == 1) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Panel lock", modifier = Modifier.weight(1f))
                Switch(checked = s.locked, onCheckedChange = { fridge.setLocked(it) }, enabled = !stale)
            }
        }
    }

    Advanced(s, fridge, stale)
}

@Composable
private fun ZoneCard(
    title: String,
    current: Int,
    target: Int,
    min: Int,
    max: Int,
    unit: String,
    enabled: Boolean,
    onTarget: (Int) -> Unit,
) {
    var dragging by remember { mutableStateOf(false) }
    var sliderValue by remember { mutableFloatStateOf(target.toFloat()) }
    LaunchedEffect(target) { if (!dragging) sliderValue = target.toFloat() }
    val lo = minOf(min, max, target)
    val hi = maxOf(min, max, target)

    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text("$current$unit", fontSize = 64.sp, fontWeight = FontWeight.Light)
            Text("current", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                FilledTonalButton(onClick = { onTarget((target - 1).coerceAtLeast(lo)) }, enabled = enabled && target > lo) {
                    Text("−", fontSize = 22.sp)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${sliderValue.roundToInt()}$unit", fontSize = 32.sp, fontWeight = FontWeight.Bold)
                    Text("target", style = MaterialTheme.typography.bodySmall)
                }
                FilledTonalButton(onClick = { onTarget((target + 1).coerceAtMost(hi)) }, enabled = enabled && target < hi) {
                    Text("+", fontSize = 22.sp)
                }
            }
            if (hi > lo) {
                Slider(
                    value = sliderValue.coerceIn(lo.toFloat(), hi.toFloat()),
                    onValueChange = { dragging = true; sliderValue = it },
                    onValueChangeFinished = {
                        dragging = false
                        val v = sliderValue.roundToInt()
                        if (v != target) onTarget(v)
                    },
                    valueRange = lo.toFloat()..hi.toFloat(),
                    steps = (hi - lo - 1).coerceAtLeast(0),
                    enabled = enabled,
                )
                Row(Modifier.fillMaxWidth()) {
                    Text("$lo$unit", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    Text("$hi$unit", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun Advanced(s: FridgeStatus, fridge: FridgeController, stale: Boolean) {
    var open by remember { mutableStateOf(false) }
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Advanced", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { open = !open }) { Text(if (open) "Hide" else "Show") }
            }
            if (!open) return@Column
            Stepper("Start delay (min)", s.startDelay, 0..10, !stale) { fridge.setStartDelay(it) }
            Stepper("Hysteresis left", s.left.hysteresis, 1..10, !stale) { v ->
                fridge.update("hysteresis left $v") { it.copy(left = it.left.copy(hysteresis = v)) }
            }
            s.right?.let { r ->
                Stepper("Hysteresis right", r.hysteresis, 1..10, !stale) { v ->
                    fridge.update("hysteresis right $v") { st -> st.copy(right = st.right?.copy(hysteresis = v)) }
                }
            }
            HorizontalDivider()
            Text("Temperature correction (left)", style = MaterialTheme.typography.labelLarge)
            Text(
                "Sensor offsets the controller applies in its hot / mid / cold / halt temperature ranges. Leave at 0 unless the display is off.",
                style = MaterialTheme.typography.bodySmall,
            )
            Stepper("Hot", s.left.tcHot, -10..10, !stale) { v -> fridge.update("tc hot $v") { it.copy(left = it.left.copy(tcHot = v)) } }
            Stepper("Mid", s.left.tcMid, -10..10, !stale) { v -> fridge.update("tc mid $v") { it.copy(left = it.left.copy(tcMid = v)) } }
            Stepper("Cold", s.left.tcCold, -10..10, !stale) { v -> fridge.update("tc cold $v") { it.copy(left = it.left.copy(tcCold = v)) } }
            Stepper("Halt", s.left.tcHalt, -10..10, !stale) { v -> fridge.update("tc halt $v") { it.copy(left = it.left.copy(tcHalt = v)) } }
            HorizontalDivider()
            Text("Limits ${s.tempMin}…${s.tempMax}${s.unitSymbol}" + (s.model?.let { " · model $it" } ?: ""), style = MaterialTheme.typography.bodySmall)
            Text("Raw status (${s.raw.size} bytes)", style = MaterialTheme.typography.labelLarge)
            Text(s.raw.toByteArray().toHex(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun Stepper(label: String, value: Int, range: IntRange, enabled: Boolean, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = { onChange(value - 1) }, enabled = enabled && value > range.first, modifier = Modifier.size(48.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text("−") }
        Text("$value", modifier = Modifier.padding(horizontal = 12.dp), fontWeight = FontWeight.Bold)
        OutlinedButton(onClick = { onChange(value + 1) }, enabled = enabled && value < range.last, modifier = Modifier.size(48.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text("+") }
    }
}

@Composable
private fun Labeled(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        content()
    }
}

@Composable
private fun Segmented(options: List<String>, selected: Int, enabled: Boolean, onSelect: (Int) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, label ->
            SegmentedButton(
                selected = i == selected,
                onClick = { if (i != selected) onSelect(i) },
                shape = SegmentedButtonDefaults.itemShape(index = i, count = options.size),
                enabled = enabled,
            ) { Text(label) }
        }
    }
}
