package com.thehero17.carfridge.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thehero17.carfridge.AppViewModel
import com.thehero17.carfridge.ble.LogKind

@Composable
fun LogTab(vm: AppViewModel) {
    val ctx = LocalContext.current
    val lines by vm.log.lines.collectAsState()
    var follow by remember { mutableStateOf(true) }
    val listState = rememberLazyListState()

    LaunchedEffect(lines.size, follow) {
        if (follow && lines.isNotEmpty()) listState.scrollToItem(lines.size - 1)
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "Car fridge BLE log")
                    putExtra(Intent.EXTRA_TEXT, vm.log.export())
                }
                ctx.startActivity(Intent.createChooser(send, "Share log"))
            }) { Text("Share") }
            OutlinedButton(onClick = { vm.log.clear() }) { Text("Clear") }
            FilterChip(selected = follow, onClick = { follow = !follow }, label = { Text("Follow") })
        }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp), state = listState) {
            items(lines) { line ->
                Text(
                    line.format(),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = when (line.kind) {
                        LogKind.ERROR -> MaterialTheme.colorScheme.error
                        LogKind.TX -> MaterialTheme.colorScheme.primary
                        LogKind.RX -> MaterialTheme.colorScheme.tertiary
                        LogKind.INFO -> MaterialTheme.colorScheme.onSurface
                    },
                )
            }
        }
    }
}
