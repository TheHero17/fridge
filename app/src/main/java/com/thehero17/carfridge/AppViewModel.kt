package com.thehero17.carfridge

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.thehero17.carfridge.ble.BleScanner
import com.thehero17.carfridge.ble.GattClient
import com.thehero17.carfridge.ble.LogBuffer
import com.thehero17.carfridge.ble.canNotify
import com.thehero17.carfridge.ble.canWrite
import com.thehero17.carfridge.protocol.Alpicool
import com.thehero17.carfridge.fridge.FridgeController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class Connection(
    val client: GattClient,
    val fridge: FridgeController,
    val scope: CoroutineScope,
)

data class SavedDevice(val address: String, val name: String?)

@SuppressLint("MissingPermission")
class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val adapter = (app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?)?.adapter

    val log = LogBuffer()
    val scanner = BleScanner(adapter, log)

    private val _connection = MutableStateFlow<Connection?>(null)
    val connection: StateFlow<Connection?> = _connection

    private val _bindOnConnect = MutableStateFlow(prefs.getBoolean("bind", true))
    val bindOnConnect: StateFlow<Boolean> = _bindOnConnect

    private val _lastDevice = MutableStateFlow(
        prefs.getString("lastAddress", null)?.let { SavedDevice(it, prefs.getString("lastName", null)) }
    )
    val lastDevice: StateFlow<SavedDevice?> = _lastDevice

    val bluetoothEnabled: Boolean get() = adapter?.isEnabled == true
    val bluetoothAvailable: Boolean get() = adapter != null

    fun setBindOnConnect(v: Boolean) {
        _bindOnConnect.value = v
        prefs.edit().putBoolean("bind", v).apply()
    }

    fun connect(address: String, name: String?) {
        scanner.stop()
        disconnect()
        val device = adapter?.getRemoteDevice(address) ?: return
        prefs.edit().putString("lastAddress", address).putString("lastName", name).apply()
        _lastDevice.value = SavedDevice(address, name)
        log.info("---- ${name ?: "device"} ($address) ----")
        val scope = CoroutineScope(viewModelScope.coroutineContext + Job())
        val client = GattClient(getApplication(), device, log)
        val fridge = FridgeController(client, scope, log, bindOnConnect = { _bindOnConnect.value })
        fridge.start()
        _connection.value = Connection(client, fridge, scope)
        client.connect()
    }

    fun reconnect() {
        _connection.value?.client?.connect()
    }

    fun disconnect() {
        val c = _connection.value ?: return
        c.client.disconnect()
        c.scope.cancel()
        _connection.value = null
    }

    // ---- GATT explorer helpers ----

    fun read(c: BluetoothGattCharacteristic) {
        val conn = _connection.value ?: return
        conn.scope.launch { conn.client.read(c) }
    }

    fun subscribe(c: BluetoothGattCharacteristic) {
        val conn = _connection.value ?: return
        conn.scope.launch { conn.client.enableNotify(c) }
    }

    fun write(c: BluetoothGattCharacteristic, bytes: ByteArray, withResponse: Boolean?) {
        val conn = _connection.value ?: return
        conn.scope.launch { conn.client.write(c, bytes, withResponse) }
    }

    /**
     * Reverse-engineering helper: subscribes to every notifying characteristic, then writes the
     * Alpicool query and bind frames to every writable one and logs whatever comes back.
     */
    fun probe() {
        val conn = _connection.value ?: return
        conn.scope.launch {
            val chars = conn.client.services.value.flatMap { it.characteristics }
            log.info("Probe: subscribing to all notify/indicate characteristics")
            chars.filter { it.canNotify }.forEach { conn.client.enableNotify(it) }
            for (c in chars.filter { it.canWrite }) {
                for (frame in listOf(Alpicool.query(), Alpicool.bind(), Alpicool.query())) {
                    conn.client.write(c, frame)
                    delay(700)
                }
            }
            log.info("Probe finished – look for << lines above")
        }
    }

    override fun onCleared() {
        scanner.stop()
        disconnect()
    }
}
