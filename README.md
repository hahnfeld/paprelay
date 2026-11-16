# PAPRelay

PAPRelay copies therapy data from a ResMed AirSense 11 into Android Health Connect.
It talks to the machine directly over Bluetooth, keeps everything on the phone, and
has no internet permission.

**Status: early releases.** Tested against one AirSense 11 AutoSet (software
`SW04600.19.8.8.0`) and one phone (Pixel 10 Pro, Android 17), and checked against the
same nights on the machine's SD card. Other software versions are refused until they have
been checked: PAPRelay says so and writes nothing.

Based on SomnoTrace, originally created by Ilya Kruchinin (https://github.com/ilyakruchinin).

## Before you use it

- PAPRelay replaces the myAir app's Bluetooth link. The machine keeps one pairing.
- It is not a medical device. Read [`docs/INTENDED-USE.md`](docs/INTENDED-USE.md).
- It is read-only: it cannot change settings, start or stop therapy, or erase data.

## What it does

- Keeps **each night's figures as the machine recorded them**: hours of use, AHI and its
  split into obstructive, central and hypopnoea, leak and pressure. It shows them, and can
  pass them on three ways:
  - to the **health records in Health Connect** (Android 16 and later), as one coded
    observation per figure and night. Health Connect has no place of its own for these, so
    they appear there under lab results, marked as coming from PAPRelay;
  - to a **table of nights** (`PAPRelay nights.csv`) kept up to date in a folder you choose,
    for anything that reads files;
  - as a few lines of text through the phone's share sheet.
- Can also write the machine's **respiratory rate, one value per minute of therapy**, to
  Health Connect at the true time (the machine's clock is measured and corrected for). This
  is off unless you switch it on.
- Synchronises when you ask, when the phone hears the machine come into range, and about
  once an hour as a backstop. A night's figures arrive after the machine has closed the day,
  at midday. Being out of range is normal and is simply tried again later.
- Never writes anything twice: every record has a fixed name derived from the machine, the
  night and the figure, so a reinstall or another run writes over the same records.

## Installing

1. Remove the machine from the myAir app, and forget it in the phone's Bluetooth settings.
2. Install the APK from the [releases page](https://github.com/hahnfeld/paprelay/releases).
   Releases are signed with a certificate whose SHA-256 fingerprint is
   `8E:1A:FB:37:FF:59:27:4A:5C:8D:C5:21:B8:0D:37:83:3C:0C:1F:0A:51:98:A7:D9:DF:5E:8D:AE:EC:F8:98:06`.
3. On the machine choose More, then myAir App, then Connect. In PAPRelay choose
   **Pair the machine** and type the code the machine shows. The device check that follows
   proves each link: Health Connect, the machine, its clock, a download.
4. Go back and choose **Synchronise now**. From then on it looks after itself.

The debug build (`.debug` suffix) only runs the device check and never synchronises:
Health Connect would treat it as a different app and hold every night twice.

## Building

Requires JDK 17 or newer. The Android module also needs the Android SDK (API 37).

```bash
./gradlew :core:test :as11:test -Ppaprelay.skipAndroid=true
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
