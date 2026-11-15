# PAPRelay

PAPRelay copies therapy data from a ResMed AirSense 11 into Android Health Connect.
It talks to the machine directly over Bluetooth, keeps everything on the phone, and
has no internet permission.

**Status: early development. Not yet validated against a real machine.**

Based on SomnoTrace, originally created by Ilya Kruchinin (https://github.com/ilyakruchinin).

## Before you use it

- PAPRelay replaces the myAir app's Bluetooth link. The machine keeps one pairing.
- It is not a medical device. Read [`docs/INTENDED-USE.md`](docs/INTENDED-USE.md).
- It is read-only: it cannot change settings, start or stop therapy, or erase data.

## What goes where

| Data | Health Connect | Kept on the phone and exportable |
|---|---|---|
| Respiratory rate, per minute | Yes | Yes |
| SpO2 and pulse (with an oximeter) | Optional, off by default | Yes |
| AHI, leak, pressure, event times | No type exists for these | Yes |
| CPAP session with a summary note | Optional, off by default | Yes |

## Building

Requires JDK 17 or newer. The Android module also needs the Android SDK (API 36).

```bash
./gradlew :as11:test -Ppaprelay.skipAndroid=true
```

```bash
./gradlew :app:assembleDebug
```

## Layout

- `core` — domain model and rules shared by every data source.
- `as11` — the AirSense 11 Bluetooth protocol, plus a simulated machine for tests.
- `app` — the Android app.

## Licence

GPL-3.0-or-later, with an attribution term inherited from SomnoTrace. See
[`LICENSE`](LICENSE), [`NOTICE`](NOTICE) and
[`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md).

PAPRelay is not affiliated with or endorsed by ResMed or Google.
