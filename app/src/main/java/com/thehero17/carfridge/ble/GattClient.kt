package com.thehero17.carfridge.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.thehero17.carfridge.protocol.Alpicool
import com.thehero17.carfridge.protocol.toHex
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

enum class LinkState { DISCONNECTED, CONNECTING, DISCOVERING, READY }

class Notification(val characteristic: UUID, val value: ByteArray)

/** Thin coroutine wrapper around BluetoothGatt with a serial operation queue. */
@SuppressLint("MissingPermission")
class GattClient(
    private val context: Context,
    val device: BluetoothDevice,
    private val log: LogBuffer,
) {
    private val _state = MutableStateFlow(LinkState.DISCONNECTED)
    val state: StateFlow<LinkState> = _state

    private val _services = MutableStateFlow<List<BluetoothGattService>>(emptyList())
    val services: StateFlow<List<BluetoothGattService>> = _services

    private val _notifications = MutableSharedFlow<Notification>(extraBufferCapacity = 256)
    val notifications: SharedFlow<Notification> = _notifications

    /** Usable bytes per write (MTU - 3). */
    @Volatile var payloadSize = Alpicool.DEFAULT_CHUNK
        private set

    private var gatt: BluetoothGatt? = null
    private val opLock = Mutex()
    @Volatile private var pending: CompletableDeferred<Pair<Int, ByteArray?>>? = null
    private val main = Handler(Looper.getMainLooper())

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                log.info("Connected (status $status), discovering services…")
                _state.value = LinkState.DISCOVERING
                // Some stacks need a short pause before discovery.
                main.postDelayed({ g.discoverServices() }, 600)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                log.info("Disconnected (status $status${if (status == 133) " – GATT_ERROR, try again" else ""})")
                _state.value = LinkState.DISCONNECTED
                _services.value = emptyList()
                pending?.complete(-1 to null)
                g.close()
                if (gatt === g) gatt = null
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log.error("Service discovery failed: $status")
                return
            }
            _services.value = g.services
            g.services.forEach { s ->
                log.info("Service ${s.uuid.short()}")
                s.characteristics.forEach { c ->
                    log.info("   char ${c.uuid.short()} [${c.propertiesString()}]")
                }
            }
            _state.value = LinkState.READY
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) payloadSize = (mtu - 3).coerceAtLeast(20)
            log.info("MTU $mtu (status $status)")
            pending?.complete(status to null)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            pending?.complete(status to null)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            pending?.complete(status to null)
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            pending?.complete(status to value)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                pending?.complete(status to c.value)
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            onNotify(c, value)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            // On Android 13+ both callbacks fire; only handle the legacy one on older versions.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                onNotify(c, c.value ?: return)
            }
        }
    }

    private fun onNotify(c: BluetoothGattCharacteristic, value: ByteArray) {
        log.add(LogKind.RX, "${c.uuid.short()}: ${value.toHex()}")
        _notifications.tryEmit(Notification(c.uuid, value.copyOf()))
    }

    fun connect() {
        if (_state.value != LinkState.DISCONNECTED) return
        log.info("Connecting to ${device.address}…")
        _state.value = LinkState.CONNECTING
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        val g = gatt ?: return
        log.info("Disconnecting")
        g.disconnect()
        // If we never got connected, no callback will come: close directly.
        if (_state.value == LinkState.CONNECTING) {
            g.close()
            gatt = null
            _state.value = LinkState.DISCONNECTED
        }
    }

    fun findCharacteristic(service: String, characteristic: String): BluetoothGattCharacteristic? =
        gatt?.getService(UUID.fromString(service))?.getCharacteristic(UUID.fromString(characteristic))

    private suspend fun <T> op(name: String, start: (BluetoothGatt) -> Boolean, map: (Pair<Int, ByteArray?>) -> T): T? =
        opLock.withLock {
            val g = gatt ?: run { log.error("$name: not connected"); return null }
            val d = CompletableDeferred<Pair<Int, ByteArray?>>()
            pending = d
            val started = try {
                start(g)
            } catch (e: SecurityException) {
                log.error("$name: permission denied"); false
            }
            if (!started) {
                pending = null
                log.error("$name: could not start")
                return null
            }
            val result = withTimeoutOrNull(5000) { d.await() }
            pending = null
            if (result == null) {
                log.error("$name: timeout")
                return null
            }
            if (result.first != BluetoothGatt.GATT_SUCCESS) log.error("$name: status ${result.first}")
            map(result)
        }

    suspend fun requestMtu(mtu: Int = 247): Boolean =
        op("requestMtu", { it.requestMtu(mtu) }) { it.first == BluetoothGatt.GATT_SUCCESS } ?: false

    suspend fun read(c: BluetoothGattCharacteristic): ByteArray? =
        op("read ${c.uuid.short()}", { it.readCharacteristic(c) }) { it.second }.also { v ->
            if (v != null) log.add(LogKind.RX, "read ${c.uuid.short()}: ${v.toHex()}")
        }

    /** Writes [value], split into MTU-sized chunks. */
    suspend fun write(c: BluetoothGattCharacteristic, value: ByteArray, withResponse: Boolean? = null): Boolean {
        val response = withResponse
            ?: (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE == 0)
        val type = if (response) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        log.add(LogKind.TX, "${c.uuid.short()}: ${value.toHex()}")
        val chunks = Alpicool.chunk(value, payloadSize)
        for ((i, chunk) in chunks.withIndex()) {
            if (i > 0) kotlinx.coroutines.delay(150)
            val ok = op("write ${c.uuid.short()}", { g ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeCharacteristic(c, chunk, type) == BluetoothGatt.GATT_SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    run {
                        c.writeType = type
                        c.value = chunk
                        g.writeCharacteristic(c)
                    }
                }
            }) { it.first == BluetoothGatt.GATT_SUCCESS } ?: false
            if (!ok) return false
        }
        return true
    }

    /** Enables notifications (or indications) on [c]. */
    suspend fun enableNotify(c: BluetoothGattCharacteristic, enable: Boolean = true): Boolean {
        val g = gatt ?: return false
        if (!g.setCharacteristicNotification(c, enable)) {
            log.error("setCharacteristicNotification failed for ${c.uuid.short()}")
            return false
        }
        val cccd = c.getDescriptor(UUID.fromString(Alpicool.CCCD_UUID)) ?: run {
            log.info("${c.uuid.short()} has no CCCD; relying on local subscription")
            return true
        }
        val value = when {
            !enable -> BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 ->
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            else -> BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        }
        val ok = op("CCCD ${c.uuid.short()}", { gg ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gg.writeDescriptor(cccd, value) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run {
                    cccd.value = value
                    gg.writeDescriptor(cccd)
                }
            }
        }) { it.first == BluetoothGatt.GATT_SUCCESS } ?: false
        log.info("${if (enable) "Subscribed to" else "Unsubscribed from"} ${c.uuid.short()}: ${if (ok) "ok" else "failed"}")
        return ok
    }
}

private val BASE_UUID_SUFFIX = "-0000-1000-8000-00805f9b34fb"

/** "00001234-0000-1000-8000-00805f9b34fb" -> "0x1234"; other UUIDs unchanged. */
fun UUID.short(): String {
    val s = toString()
    return if (s.startsWith("0000") && s.endsWith(BASE_UUID_SUFFIX)) "0x" + s.substring(4, 8).uppercase() else s
}

fun BluetoothGattCharacteristic.propertiesString(): String = buildList {
    val p = properties
    if (p and BluetoothGattCharacteristic.PROPERTY_READ != 0) add("read")
    if (p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) add("write")
    if (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) add("writeNoResp")
    if (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) add("notify")
    if (p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) add("indicate")
}.joinToString(", ")

val BluetoothGattCharacteristic.canWrite: Boolean
    get() = properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0

val BluetoothGattCharacteristic.canNotify: Boolean
    get() = properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0

val BluetoothGattCharacteristic.canRead: Boolean
    get() = properties and BluetoothGattCharacteristic.PROPERTY_READ != 0
