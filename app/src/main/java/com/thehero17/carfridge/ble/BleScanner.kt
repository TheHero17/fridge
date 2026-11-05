package com.thehero17.carfridge.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.ParcelUuid
import com.thehero17.carfridge.protocol.Alpicool
import com.thehero17.carfridge.protocol.toHex
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

data class FoundDevice(
    val address: String,
    val name: String?,
    val rssi: Int,
    val serviceUuids: List<String>,
    val manufacturerData: String?,
    val lastSeen: Long,
) {
    /** Heuristic: advertises the Alpicool service, or uses a name typical for these BLE modules. */
    val likelyFridge: Boolean
        get() = serviceUuids.any { it.equals(Alpicool.SERVICE_UUID, ignoreCase = true) } ||
            (name != null && FRIDGE_NAME.containsMatchIn(name))

    companion object {
        private val FRIDGE_NAME =
            Regex("^(WT-|A1-|AK\\d|AL\\d|BM\\d|C\\d{2}|T\\d{2}|K\\d{2})|fridge|cool|freez|alpicool|vevor|ice", RegexOption.IGNORE_CASE)
    }
}

@SuppressLint("MissingPermission")
class BleScanner(private val adapter: BluetoothAdapter?, private val log: LogBuffer) {
    private val _devices = MutableStateFlow<Map<String, FoundDevice>>(emptyMap())
    val devices: StateFlow<Map<String, FoundDevice>> = _devices

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = handle(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::handle)
        override fun onScanFailed(errorCode: Int) {
            log.error("Scan failed, error $errorCode")
            _scanning.value = false
        }
    }

    private fun handle(r: ScanResult) {
        val record = r.scanRecord
        val uuids = record?.serviceUuids?.map(ParcelUuid::toString).orEmpty()
        val mfr = record?.manufacturerSpecificData?.let { sparse ->
            (0 until sparse.size()).joinToString("; ") { i ->
                "%04X: %s".format(sparse.keyAt(i), sparse.valueAt(i).toHex())
            }
        }?.takeIf { it.isNotEmpty() }
        val dev = FoundDevice(
            address = r.device.address,
            name = record?.deviceName ?: r.device.name,
            rssi = r.rssi,
            serviceUuids = uuids,
            manufacturerData = mfr,
            lastSeen = System.currentTimeMillis(),
        )
        _devices.update { it + (dev.address to dev) }
    }

    fun start() {
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            log.error("Bluetooth is off or not available")
            return
        }
        if (_scanning.value) return
        _devices.value = emptyMap()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        try {
            scanner.startScan(null, settings, callback)
            _scanning.value = true
            log.info("Scan started")
        } catch (e: SecurityException) {
            log.error("Missing permission for scanning: ${e.message}")
        }
    }

    fun stop() {
        if (!_scanning.value) return
        try {
            adapter?.bluetoothLeScanner?.stopScan(callback)
        } catch (e: Exception) {
            log.error("stopScan: ${e.message}")
        }
        _scanning.value = false
    }
}
