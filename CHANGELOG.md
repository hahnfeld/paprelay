# Changelog

Newest first. Before 1.0, any release may change behaviour.

## 0.2.1 (2026-11-20)

First public release.

- The About screen says that PAPRelay is for personal use only and is not made, endorsed
  or supported by ResMed.
- Some sentences in the app are clearer, and two are corrected: a changed machine clock
  holds back only respiratory rate, and **Start again** resets the switches in Sharing.
- Documentation for users and contributors, with line diagrams.

## 0.2.0 (2026-11-16)

PAPRelay now copies each night's figures, not only respiratory rate.

- Each night's figures as the machine recorded them: hours of use, AHI and its parts
  (obstructive, central, hypopnoea), leak and pressure. The latest night is on the home
  screen. **Nights** lists them all.
- Health records in Health Connect (Android 16 and later). Each night is written as up to
  five coded observations: usage, events, leak, pressure and breathing. They are filed
  under lab results, and each says that it is a CPAP machine's nightly figure. On by
  default.
- A table of nights (CSV, one line for each night, units in the column names), kept up to
  date in a file that you create through Android's own save screen.
- Share the recent nights as text to any app.
- The device check tests the health records with a made-up night.
- Respiratory rate is now a switch under **Sharing**, off by default. An installation that
  was already writing it continues to do so.

## 0.1.0 (2026-11-16)

- Pairing with a ResMed AirSense 11 over Bluetooth, with the code from the machine's screen.
- Respiratory rate, one reading for each minute of therapy, written to Health Connect at
  the corrected time. The machine's clock is measured on every connection.
- Synchronising on request, when the phone hears the machine come into range, and about
  every hour.
- A device check that tests each connection and can save a report.
- A reinstall or a repeated run writes over the same records, so no night is written twice.

Decoding was checked against the same nights on the machine's SD card.
