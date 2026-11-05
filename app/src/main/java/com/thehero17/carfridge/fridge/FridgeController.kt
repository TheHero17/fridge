package com.thehero17.carfridge.fridge

import android.bluetooth.BluetoothGattCharacteristic
import com.thehero17.carfridge.ble.GattClient
import com.thehero17.carfridge.ble.LinkState
import com.thehero17.carfridge.ble.LogBuffer
import com.thehero17.carfridge.ble.canNotify
import com.thehero17.carfridge.ble.canWrite
import com.thehero17.carfridge.ble.short
import com.thehero17.carfridge.protocol.Alpicool
import com.thehero17.carfridge.protocol.ChecksumState
import com.thehero17.carfridge.protocol.FrameAccumulator
import com.thehero17.carfridge.protocol.FridgeStatus
import com.thehero17.carfridge.protocol.Zone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID

enum class ProtocolState {
    /** Waiting for the BLE link. */
    WAITING,
    /** Link up, known characteristics found, waiting for the first status. */
    HANDSHAKE,
    /** Status frames arriving. */
    ONLINE,
    /** Link up, but the fridge does not answer (needs pairing button?). */
    NO_RESPONSE,
    /** No characteristics we know how to talk to: use the GATT explorer. */
    UNSUPPORTED,
}

/**
 * Speaks the Alpicool protocol over a [GattClient]: subscribes to notifications,
 * optionally binds, polls the status and sends commands.
 */
class FridgeController(
    private val client: GattClient,
    private val scope: CoroutineScope,
    private val log: LogBuffer,
    private val bindOnConnect: () -> Boolean,
    private val pollIntervalMs: Long = 2000,
) {
    private val _status = MutableStateFlow<FridgeStatus?>(null)
    val status: StateFlow<FridgeStatus?> = _status

    private val _protocol = MutableStateFlow(ProtocolState.WAITING)
    val protocol: StateFlow<ProtocolState> = _protocol

    private val _channel = MutableStateFlow<String?>(null)
    /** Human readable "service / write / notify" in use. */
    val channel: StateFlow<String?> = _channel

    private var writeChar: BluetoothGattCharacteristic? = null
    private var notifyUuid: UUID? = null
    private val accumulator = FrameAccumulator()
    private var lastStatusAt = 0L
    private var sessionJob: Job? = null

    /** Known UUID layouts, best first. The last ones are generic BLE-UART modules we try as a fallback. */
    private val candidates = listOf(
        Triple(Alpicool.SERVICE_UUID, Alpicool.WRITE_UUID, Alpicool.NOTIFY_UUID),
        Triple(uuid16(0xFFE0), uuid16(0xFFE1), uuid16(0xFFE1)),
        Triple(uuid16(0xFFF0), uuid16(0xFFF2), uuid16(0xFFF1)),
        Triple(
            "6e400001-b5a3-f393-e0a9-e50e24dcca9e",
            "6e400002-b5a3-f393-e0a9-e50e24dcca9e",
            "6e400003-b5a3-f393-e0a9-e50e24dcca9e",
        ),
    )

    fun start() {
        scope.launch {
            client.notifications.collect { n ->
                if (n.characteristic == notifyUuid) onData(n.value)
            }
        }
        scope.launch {
            client.state.collect { st ->
                if (st == LinkState.READY) {
                    sessionJob?.cancel()
                    sessionJob = scope.launch { session() }
                } else {
                    sessionJob?.cancel()
                    sessionJob = null
                    _protocol.value = ProtocolState.WAITING
                    writeChar = null
                }
            }
        }
    }

    private suspend fun session() {
        accumulator.clear()
        client.requestMtu()
        if (!selectCharacteristics()) {
            _protocol.value = ProtocolState.UNSUPPORTED
            log.error("No known fridge service found. Use the Explorer tab to look around.")
            return
        }
        _protocol.value = ProtocolState.HANDSHAKE
        if (bindOnConnect()) {
            send(Alpicool.bind(), "bind")
            delay(300)
        }
        val startedAt = System.currentTimeMillis()
        while (true) {
            send(Alpicool.query(), null)
            delay(pollIntervalMs)
            val now = System.currentTimeMillis()
            val reference = if (lastStatusAt > startedAt) lastStatusAt else startedAt
            if (now - reference > 8000 && _protocol.value != ProtocolState.NO_RESPONSE) {
                _protocol.value = ProtocolState.NO_RESPONSE
                log.error("Fridge does not answer. If the display shows 'APP' or a BT symbol flashes, press its Bluetooth/Set button. Bind is re-sent every 10 s.")
            }
            if (_protocol.value == ProtocolState.NO_RESPONSE && (now - startedAt) / pollIntervalMs % 5 == 0L) {
                send(Alpicool.bind(), "bind")
            }
        }
    }

    private suspend fun selectCharacteristics(): Boolean {
        val services = client.services.first { it.isNotEmpty() }
        for ((svc, wr, nt) in candidates) {
            val service = services.firstOrNull { it.uuid.toString().equals(svc, true) } ?: continue
            val w = service.getCharacteristic(UUID.fromString(wr))?.takeIf { it.canWrite }
                ?: service.characteristics.firstOrNull { it.canWrite }
            val n = service.getCharacteristic(UUID.fromString(nt))?.takeIf { it.canNotify }
                ?: service.characteristics.firstOrNull { it.canNotify }
            if (w == null || n == null) continue
            writeChar = w
            notifyUuid = n.uuid
            client.enableNotify(n)
            _channel.value = "service ${service.uuid.short()}, write ${w.uuid.short()}, notify ${n.uuid.short()}"
            log.info("Using ${_channel.value}")
            return true
        }
        return false
    }

    private fun onData(bytes: ByteArray) {
        for (frame in accumulator.feed(bytes)) {
            if (frame.checksum == ChecksumState.MISMATCH) {
                log.error("Checksum mismatch: $frame")
                continue
            }
            val st = FridgeStatus.from(frame)
            if (st != null) {
                lastStatusAt = System.currentTimeMillis()
                _status.value = st
                if (_protocol.value != ProtocolState.ONLINE) log.info("Fridge online: $st")
                _protocol.value = ProtocolState.ONLINE
            } else {
                log.info("Frame $frame")
            }
        }
    }

    private suspend fun send(frame: ByteArray, what: String?): Boolean {
        val c = writeChar ?: return false
        if (what != null) log.info("Sending $what")
        return client.write(c, frame)
    }

    private fun command(what: String, frame: ByteArray) {
        scope.launch {
            send(frame, what)
            delay(400)
            send(Alpicool.query(), null)
        }
    }

    fun setTarget(zone: Zone, temp: Int) {
        command("target $zone = $temp", Alpicool.setTarget(zone, temp))
        // optimistic update so the UI does not jump back until the next status arrives
        _status.value?.let { s ->
            _status.value = if (zone == Zone.LEFT) s.copy(left = s.left.copy(target = temp))
            else s.copy(right = s.right?.copy(target = temp))
        }
    }

    /** Applies [change] to the last known status and sends the full SET (0x02) command. */
    fun update(what: String, change: (FridgeStatus) -> FridgeStatus) {
        val current = _status.value ?: return
        val next = change(current)
        _status.value = next
        command(what, Alpicool.set(next))
    }

    fun setPower(on: Boolean) = update("power ${if (on) "on" else "off"}") { it.copy(poweredOn = on) }
    fun setEco(eco: Boolean) = update(if (eco) "ECO mode" else "MAX mode") { it.copy(runMode = if (eco) 1 else 0) }
    fun setLocked(locked: Boolean) = update("lock $locked") { it.copy(locked = locked) }
    fun setBatterySaver(level: Int) = update("battery saver $level") { it.copy(batterySaver = level) }
    fun setUnit(fahrenheit: Boolean) = update("unit ${if (fahrenheit) "°F" else "°C"}") { it.copy(unit = if (fahrenheit) 1 else 0) }
    fun setStartDelay(minutes: Int) = update("start delay $minutes") { it.copy(startDelay = minutes) }

    fun refresh() = scope.launch { send(Alpicool.query(), "query") }
    fun bind() = scope.launch { send(Alpicool.bind(), "bind") }

    companion object {
        fun uuid16(v: Int) = "0000%04x-0000-1000-8000-00805f9b34fb".format(v)
    }
}
