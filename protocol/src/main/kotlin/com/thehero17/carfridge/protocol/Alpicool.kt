package com.thehero17.carfridge.protocol

/**
 * The "Alpicool" BLE protocol, spoken by most Chinese 12/24 V compressor car fridges
 * (Alpicool, Vevor, BrassMonkey, Hyckes, MAENTUM, many no-name/rebadged units whose
 * BLE module advertises names like `WT-0001`, `A1-xxxx`, `AK1-xxxx`).
 *
 * GATT: service 0x1234, write characteristic 0x1235, notify characteristic 0x1236.
 *
 * Frame: `FE FE <len> <cmd> <data...> <sumHi> <sumLo>`
 *  - `len` counts every byte after itself (cmd + data + 2 checksum bytes).
 *  - checksum = 16-bit sum of all preceding bytes, big endian. Some firmwares send sum*2.
 *
 * See docs/PROTOCOL.md for the full byte layout.
 */
object Alpicool {
    const val SERVICE_UUID = "00001234-0000-1000-8000-00805f9b34fb"
    const val WRITE_UUID = "00001235-0000-1000-8000-00805f9b34fb"
    const val NOTIFY_UUID = "00001236-0000-1000-8000-00805f9b34fb"
    const val CCCD_UUID = "00002902-0000-1000-8000-00805f9b34fb"

    const val CMD_BIND = 0x00
    const val CMD_QUERY = 0x01
    const val CMD_SET = 0x02
    const val CMD_RESET = 0x04
    const val CMD_SET_LEFT_TARGET = 0x05
    const val CMD_SET_RIGHT_TARGET = 0x06

    const val HEADER: Int = 0xFE

    /** Default ATT payload (MTU 23 - 3). Longer writes are chunked. */
    const val DEFAULT_CHUNK = 20

    fun checksum(bytes: ByteArray, len: Int = bytes.size): Int {
        var sum = 0
        for (i in 0 until len) sum += bytes[i].toInt() and 0xFF
        return sum and 0xFFFF
    }

    fun buildFrame(cmd: Int, data: ByteArray = ByteArray(0)): ByteArray {
        val out = ByteArray(data.size + 6)
        out[0] = HEADER.toByte()
        out[1] = HEADER.toByte()
        out[2] = (data.size + 3).toByte()
        out[3] = cmd.toByte()
        data.copyInto(out, 4)
        val sum = checksum(out, data.size + 4)
        out[out.size - 2] = (sum shr 8).toByte()
        out[out.size - 1] = sum.toByte()
        return out
    }

    fun bind(): ByteArray = buildFrame(CMD_BIND)
    fun query(): ByteArray = buildFrame(CMD_QUERY)
    fun reset(): ByteArray = buildFrame(CMD_RESET)

    fun setTarget(zone: Zone, temp: Int): ByteArray = buildFrame(
        if (zone == Zone.LEFT) CMD_SET_LEFT_TARGET else CMD_SET_RIGHT_TARGET,
        byteArrayOf(temp.toByte()),
    )

    /**
     * SET (0x02) always carries every setting; build it from the last status with changes
     * applied (use [FridgeStatus.copy]).
     *
     * Layout from the decompiled vendor app (`encodeSetOthers`):
     *  - single zone, or dual zone with model == 1: 14 bytes (status bytes 0..13)
     *  - dual zone, model 0..8: 14 bytes + right zone target/max/min/hyst/tc*4 (8)
     *    + hotTarget + model + 0x00 = 25 bytes
     *  - dual zone, model >= 9: the 25 bytes + runningStatus + sleep + 7 zero bytes (unverified)
     */
    fun set(s: FridgeStatus): ByteArray {
        val l = s.left
        val data = ArrayList<Int>(34)
        data += listOf(
            s.locked.bit(), s.poweredOn.bit(), s.runMode, s.batterySaver,
            l.target, s.tempMax, s.tempMin, l.hysteresis,
            s.startDelay, s.unit,
            l.tcHot, l.tcMid, l.tcCold, l.tcHalt,
        )
        val r = s.right
        val model = s.model ?: 0
        if (r != null && model != 1) {
            data += listOf(
                r.target, s.rightTempMax ?: s.tempMax, s.rightTempMin ?: s.tempMin, r.hysteresis,
                r.tcHot, r.tcMid, r.tcCold, r.tcHalt,
                s.hotTarget ?: 0, model, 0,
            )
            if (model >= 9) {
                data += listOf(s.runningStatus ?: 0, s.sleep ?: 0, 0, 0, 0, 0, 0, 0, 0)
            }
        }
        return buildFrame(CMD_SET, ByteArray(data.size) { data[it].toByte() })
    }

    /** Split a frame into ATT-sized chunks. */
    fun chunk(frame: ByteArray, size: Int = DEFAULT_CHUNK): List<ByteArray> =
        (frame.indices step size).map { frame.copyOfRange(it, minOf(it + size, frame.size)) }

    private fun Boolean.bit() = if (this) 1 else 0
}

enum class Zone { LEFT, RIGHT }

enum class ChecksumState { VALID, DOUBLED, MISMATCH }

class Frame(val command: Int, val data: ByteArray, val checksum: ChecksumState, val raw: ByteArray) {
    override fun toString() = "Frame(cmd=0x%02X, data=%s, %s)".format(command, data.toHex(), checksum)
}

/** Reassembles BLE notification chunks into frames. One notification may hold several frames. */
class FrameAccumulator(private val maxFrameLength: Int = 64) {
    private val buf = ArrayList<Byte>()

    fun feed(chunk: ByteArray): List<Frame> {
        chunk.forEach { buf.add(it) }
        val frames = mutableListOf<Frame>()
        while (true) {
            val start = findHeader()
            if (start < 0) {
                // keep a trailing 0xFE: it may be the first header byte of the next frame
                val keepLast = buf.isNotEmpty() && buf.last() == Alpicool.HEADER.toByte()
                buf.clear()
                if (keepLast) buf.add(Alpicool.HEADER.toByte())
                break
            }
            repeat(start) { buf.removeAt(0) }
            if (buf.size < 3) break
            val len = buf[2].toInt() and 0xFF
            if (len < 3 || len > maxFrameLength) { // stray 0xFE pair, skip one byte
                buf.removeAt(0)
                continue
            }
            val total = len + 3
            if (buf.size < total) break
            val raw = ByteArray(total) { buf[it] }
            repeat(total) { buf.removeAt(0) }
            frames += parse(raw)
        }
        return frames
    }

    fun clear() = buf.clear()

    private fun findHeader(): Int {
        for (i in 0 until buf.size - 1) {
            if (buf[i] == Alpicool.HEADER.toByte() && buf[i + 1] == Alpicool.HEADER.toByte()) return i
        }
        return -1
    }

    companion object {
        fun parse(raw: ByteArray): Frame {
            val n = raw.size
            val expected = Alpicool.checksum(raw, n - 2)
            val got = ((raw[n - 2].toInt() and 0xFF) shl 8) or (raw[n - 1].toInt() and 0xFF)
            val state = when (got) {
                expected -> ChecksumState.VALID
                (expected * 2) and 0xFFFF -> ChecksumState.DOUBLED
                else -> ChecksumState.MISMATCH
            }
            return Frame(raw[3].toInt() and 0xFF, raw.copyOfRange(4, n - 2), state, raw)
        }
    }
}

data class ZoneStatus(
    val target: Int,
    val current: Int,
    val hysteresis: Int,
    val tcHot: Int = 0,
    val tcMid: Int = 0,
    val tcCold: Int = 0,
    val tcHalt: Int = 0,
)

data class FridgeStatus(
    val locked: Boolean,
    val poweredOn: Boolean,
    /** 0 = MAX, 1 = ECO */
    val runMode: Int,
    /** 0 = low, 1 = mid, 2 = high */
    val batterySaver: Int,
    val tempMax: Int,
    val tempMin: Int,
    /** minutes */
    val startDelay: Int,
    /** 0 = °C, 1 = °F */
    val unit: Int,
    /** null when the fridge reports 0x7F (unknown) */
    val batteryPercent: Int?,
    val batteryVoltage: Double,
    val left: ZoneStatus,
    val right: ZoneStatus? = null,
    val rightTempMax: Int? = null,
    val rightTempMin: Int? = null,
    val runningStatus: Int? = null,
    val model: Int? = null,
    val hotTarget: Int? = null,
    val sleep: Int? = null,
    val raw: List<Byte> = emptyList(),
) {
    val eco get() = runMode == 1
    val fahrenheit get() = unit == 1
    val unitSymbol get() = if (fahrenheit) "°F" else "°C"

    companion object {
        const val SINGLE_ZONE_LEN = 18
        const val DUAL_ZONE_LEN = 28
        private val SET_ECHO_SIZES = setOf(12, 14, 23, 25)

        /** Returns a status if [frame] carries one (query response, or a SET response with full status). */
        fun from(frame: Frame): FridgeStatus? {
            if (frame.command != Alpicool.CMD_QUERY && frame.command != Alpicool.CMD_SET) return null
            if (frame.command == Alpicool.CMD_SET && frame.data.size in SET_ECHO_SIZES) return null
            return parse(frame.data)
        }

        fun parse(p: ByteArray): FridgeStatus? {
            if (p.size < SINGLE_ZONE_LEN) return null
            fun s(i: Int) = p[i].toInt()               // signed
            fun u(i: Int) = p[i].toInt() and 0xFF      // unsigned
            val left = ZoneStatus(
                target = s(4), current = s(14), hysteresis = s(7),
                tcHot = s(10), tcMid = s(11), tcCold = s(12), tcHalt = s(13),
            )
            var right: ZoneStatus? = null
            var rMax: Int? = null
            var rMin: Int? = null
            var running: Int? = null
            if (p.size >= DUAL_ZONE_LEN) {
                running = u(27)
                // some single-zone units send the long frame with -128 as the missing zone's temp
                if (s(26) != -128) {
                    right = ZoneStatus(
                        target = s(18), current = s(26), hysteresis = s(21),
                        tcHot = s(22), tcMid = s(23), tcCold = s(24), tcHalt = s(25),
                    )
                    rMax = s(19)
                    rMin = s(20)
                }
            }
            return FridgeStatus(
                locked = u(0) != 0,
                poweredOn = u(1) != 0,
                runMode = u(2),
                batterySaver = u(3),
                tempMax = s(5),
                tempMin = s(6),
                startDelay = u(8),
                unit = u(9),
                batteryPercent = u(15).takeIf { it != 0x7F },
                batteryVoltage = u(16) + u(17) / 10.0,
                left = left,
                right = right,
                rightTempMax = rMax,
                rightTempMin = rMin,
                runningStatus = running,
                model = if (p.size > 28) s(28) else null,
                hotTarget = if (p.size > 29) s(29) else null,
                sleep = if (p.size > 30) u(30) else null,
                raw = p.toList(),
            )
        }
    }
}

fun ByteArray.toHex(sep: String = " "): String = joinToString(sep) { "%02X".format(it.toInt() and 0xFF) }

/** Parses "fe fe 03 01 02 00", "FEFE030102 00", "fe:fe:..." etc. */
fun String.hexToBytes(): ByteArray {
    val clean = filter { it.isLetterOrDigit() }
    require(clean.length % 2 == 0) { "odd number of hex digits" }
    return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
