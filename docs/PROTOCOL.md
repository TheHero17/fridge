# Car fridge BLE protocol ("Alpicool" family)

Most Chinese 12/24 V compressor car fridges with an app (sold as Alpicool, Vevor, BrassMonkey,
Hyckes, MAENTUM, Ocean Comfort and countless no-name brands on Amazon/Kaufland) use the same
Bluetooth Low Energy module and protocol. The module usually advertises as `WT-0001`, `A1-xxxx`
or `AK1-xxxx`.

This document collects what is known. It was assembled from:

- [klightspeed/BrassMonkeyFridgeMonitor](https://github.com/klightspeed/BrassMonkeyFridgeMonitor) – original reverse engineering
- [Gruni22/alpicool_ha_ble](https://github.com/Gruni22/alpicool_ha_ble) – quirks (concatenated frames, chunked writes, `0x7F` battery sentinel)
- [LeanderM99/refridge](https://github.com/LeanderM99/refridge) – dual-zone `SET` layout decompiled from the vendor app and verified on hardware
- [SupermotoJerry/alpicool-esphome](https://github.com/SupermotoJerry/alpicool-esphome) – `-128` "no second zone" sentinel

## GATT

| Role | UUID |
|---|---|
| Service | `0x1234` (`00001234-0000-1000-8000-00805f9b34fb`) |
| Write (commands) | `0x1235` |
| Notify (responses) | `0x1236` |

No BLE pairing/bonding. The fridge accepts **one** connection at a time – close the vendor app.

## Framing

```
FE FE <len> <cmd> <data…> <sum_hi> <sum_lo>
```

- `len` = number of bytes after the length byte (`cmd` + `data` + 2 checksum bytes).
- checksum = 16-bit sum of every preceding byte (including `FE FE`), big endian.
  Some firmwares send `sum * 2`; accept both.
- Writes longer than 20 bytes must be split into 20-byte chunks (default MTU).
- One notification can hold several frames (e.g. an echo of `SET` followed by a status);
  long statuses can be split across notifications. Reassemble by searching for `FE FE`.
- Temperatures are signed 8-bit integers in the unit the fridge is set to.

## Commands

| cmd | Name | Data | Example |
|---|---|---|---|
| `0x00` | Bind | – | `FE FE 03 00 01 FF` |
| `0x01` | Query | – | `FE FE 03 01 02 00` |
| `0x02` | Set (all settings) | see below | |
| `0x04` | Reset to factory | – | |
| `0x05` | Set left/main zone target | 1 signed byte | `FE FE 04 05 FB 03 00` (-5°) |
| `0x06` | Set right zone target | 1 signed byte | `FE FE 04 06 04 02 0A` (4°) |

Bind is what the vendor app sends first. On some units the display shows `APP` / a flashing
Bluetooth icon and you must press a button on the fridge to accept it. Many units answer
queries without binding.

## Status (response to Query, cmd `0x01`)

| Byte | Meaning |
|---|---|
| 0 | locked (panel lock) |
| 1 | powered on |
| 2 | run mode: 0 = MAX, 1 = ECO |
| 3 | battery protection: 0 = low, 1 = mid, 2 = high |
| 4 | left target temperature |
| 5 | max settable temperature |
| 6 | min settable temperature |
| 7 | left hysteresis (return difference) |
| 8 | compressor start delay (minutes) |
| 9 | unit: 0 = °C, 1 = °F |
| 10–13 | left temperature correction: hot, mid, cold, halt |
| 14 | left current temperature |
| 15 | battery percent (`0x7F` = unknown) |
| 16 | battery voltage, integer part |
| 17 | battery voltage, tenths |
| *dual zone (≥ 28 bytes):* | |
| 18 | right target |
| 19 / 20 | right max / min |
| 21 | right hysteresis |
| 22–25 | right temperature correction |
| 26 | right current temperature (`0x80` = −128 = zone absent → single zone) |
| 27 | running status (compressor) |
| 28 | model (device type discriminator) |
| 29 | hot target (heating models) |

## Set (cmd `0x02`)

Always sends **all** settings; take the last status and change what you need.

- Single zone (or dual-zone status with model = 1): status bytes 0–13 (14 bytes).
- Dual zone, model 0–8: bytes 0–13, then right target, max, min, hysteresis, 4 corrections
  (status 18–25), then hot target, model, `00` → 25 bytes.
- Dual zone, model ≥ 9: the 25 bytes plus running status, sleep and 7 zero bytes (unverified).

The fridge answers with an echo of the `SET` (12/14/23/25 data bytes – not a status) and
usually a fresh status frame right after.

## If your fridge does not use this protocol

Use the app's **Explorer** tab: it lists every service/characteristic, lets you subscribe and
write raw bytes, and **Auto-probe** sends the query/bind frames to every writable
characteristic. Share the **Log** to add support for it. An Android
"Bluetooth HCI snoop log" (Developer options) captured while the vendor app talks to the
fridge – if it works on any phone – is the gold standard for reverse engineering it.
