# Car Fridge – Android Bluetooth app

An open Android app to control Chinese 12/24 V compressor car fridges over Bluetooth LE,
for units where the official app does not work.

It speaks the widely used **"Alpicool" BLE protocol** (service `0x1234`), used by
Alpicool, Vevor, BrassMonkey, Hyckes, MAENTUM and many unbranded fridges whose BLE
module advertises as `WT-0001`, `A1-…` or `AK1-…`. See [docs/PROTOCOL.md](docs/PROTOCOL.md).

## Features

- Scans and highlights likely fridges; remembers the last one for one-tap reconnect
- Live current/target temperature, one or two zones
- Set target temperature (± buttons or slider)
- Power on/off, MAX/ECO mode, battery protection (low/mid/high), °C/°F, panel lock
- Advanced: compressor start delay, hysteresis, temperature correction, raw status bytes
- **Explorer** tab: browse GATT services, read/subscribe/write raw hex, auto-probe —
  for reverse engineering a fridge that does not answer
- **Log** tab: every byte sent and received, shareable

## Install

1. Open the repository's **Actions** tab → latest **Build APK** run → download the
   `car-fridge-apk` artifact (zip containing `app-release.apk`).
2. Copy it to the phone and install it (allow "install unknown apps").
   Android 8.0+ is required.

Or build yourself with Android Studio / `./gradlew :app:assembleRelease`.

## Use

1. Close the official app (the fridge only accepts one connection), switch the fridge on.
2. Open Car Fridge, grant the Bluetooth permission, tap your fridge.
3. If the status does not appear: check whether the fridge display shows `APP` or a flashing
   Bluetooth symbol and press the button it asks for; try toggling **Bind on connect**.
4. Still nothing? Open **Explorer** → **Auto-probe**, then **Log** → **Share** and send the log;
   that is enough to identify the protocol your fridge uses.

## Project layout

| Path | What |
|---|---|
| `protocol/` | Pure Kotlin protocol library (framing, status parsing, commands) with unit tests |
| `app/` | Android app (Kotlin, Jetpack Compose) |
| `docs/PROTOCOL.md` | Protocol documentation |

Run the protocol tests with `./gradlew :protocol:test`.
