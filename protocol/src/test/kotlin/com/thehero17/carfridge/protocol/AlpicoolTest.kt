package com.thehero17.carfridge.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AlpicoolTest {
    // Single zone status: unlocked, on, MAX, saver low, target 4, max 20, min -20, hyst 2,
    // delay 0, °C, tc 0/0/0/0, current 7°C, battery 100%, 12.8 V
    private val singleZonePayload = byteArrayOf(
        0, 1, 0, 0, 4, 20, -20, 2, 0, 0, 0, 0, 0, 0, 7, 100, 12, 8,
    )

    private val dualZonePayload = singleZonePayload + byteArrayOf(
        -18, 20, -20, 1, 0, 0, 0, 0, -15, 1, 3, 0,
    )

    @Test fun fixedCommandsMatchKnownBytes() {
        assertEquals("FE FE 03 01 02 00", Alpicool.query().toHex())
        assertEquals("FE FE 03 00 01 FF", Alpicool.bind().toHex())
    }

    @Test fun setTargetEncodesSignedTemperature() {
        assertEquals("FE FE 04 05 FB 03 00", Alpicool.setTarget(Zone.LEFT, -5).toHex())
        assertEquals("FE FE 04 06 04 02 0A", Alpicool.setTarget(Zone.RIGHT, 4).toHex())
    }

    @Test fun parsesSingleZoneStatus() {
        val frame = FrameAccumulator().feed(Alpicool.buildFrame(Alpicool.CMD_QUERY, singleZonePayload)).single()
        assertEquals(ChecksumState.VALID, frame.checksum)
        val s = assertNotNull(FridgeStatus.from(frame))
        assertTrue(s.poweredOn)
        assertEquals(4, s.left.target)
        assertEquals(7, s.left.current)
        assertEquals(-20, s.tempMin)
        assertEquals(100, s.batteryPercent)
        assertEquals(12.8, s.batteryVoltage, 0.001)
        assertNull(s.right)
    }

    @Test fun parsesDualZoneStatus() {
        val s = assertNotNull(FridgeStatus.parse(dualZonePayload))
        val r = assertNotNull(s.right)
        assertEquals(-18, r.target)
        assertEquals(-15, r.current)
        assertEquals(3, s.model)
    }

    @Test fun longFrameWithMissingZoneIsSingleZone() {
        val p = dualZonePayload.copyOf()
        p[26] = -128
        assertNull(assertNotNull(FridgeStatus.parse(p)).right)
    }

    @Test fun batteryUnknownSentinel() {
        val p = singleZonePayload.copyOf()
        p[15] = 0x7F
        assertNull(FridgeStatus.parse(p)!!.batteryPercent)
    }

    @Test fun accumulatorReassemblesChunksAndSplitsConcatenatedFrames() {
        val status = Alpicool.buildFrame(Alpicool.CMD_QUERY, singleZonePayload)
        val echo = Alpicool.buildFrame(Alpicool.CMD_SET, singleZonePayload.copyOf(14))
        val stream = byteArrayOf(0x00, 0x13) + echo + status
        val acc = FrameAccumulator()
        val frames = Alpicool.chunk(stream, 7).flatMap { acc.feed(it) }
        assertEquals(2, frames.size)
        assertNull(FridgeStatus.from(frames[0]), "SET echo must not be treated as status")
        assertNotNull(FridgeStatus.from(frames[1]))
    }

    @Test fun acceptsDoubledChecksum() {
        val f = Alpicool.buildFrame(Alpicool.CMD_QUERY, singleZonePayload)
        val sum = (Alpicool.checksum(f, f.size - 2) * 2) and 0xFFFF
        f[f.size - 2] = (sum shr 8).toByte(); f[f.size - 1] = sum.toByte()
        assertEquals(ChecksumState.DOUBLED, FrameAccumulator().feed(f).single().checksum)
    }

    @Test fun setSingleZoneEchoesFirst14StatusBytesWithChanges() {
        val s = FridgeStatus.parse(singleZonePayload)!!.copy(poweredOn = false, runMode = 1)
        val frame = Alpicool.set(s)
        assertEquals(14 + 6, frame.size)
        val expected = singleZonePayload.copyOf(14).also { it[1] = 0; it[2] = 1 }
        assertEquals(expected.toHex(), frame.copyOfRange(4, 18).toHex())
        assertEquals(ChecksumState.VALID, FrameAccumulator.parse(frame).checksum)
    }

    @Test fun setDualZoneUsesVendorLayout() {
        val s = FridgeStatus.parse(dualZonePayload)!!
        val data = FrameAccumulator.parse(Alpicool.set(s)).data
        assertEquals(25, data.size)
        assertEquals(dualZonePayload.copyOfRange(18, 26).toHex(), data.copyOfRange(14, 22).toHex())
        assertEquals("00 03 00", data.copyOfRange(22, 25).toHex()) // hotTarget, model, pad
    }

    @Test fun hexRoundTrip() {
        assertEquals("FE FE 03 01 02 00", "fefe0301 02:00".hexToBytes().toHex())
    }
}
