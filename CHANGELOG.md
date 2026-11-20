# Changelog

All notable changes to PAPRelay are listed here, newest first. Versions follow
[Semantic Versioning](https://semver.org/). Before 1.0, any release may change behaviour.

## Unreleased

### Added

- Documentation for users and contributors, with line diagrams.
- The About screen says that PAPRelay is for personal use.

## 0.2.0 — 2026-11-16

PAPRelay now brings across the figures of each night, not only respiratory rate.

### Added

- **Each night's figures, as the machine recorded them:** hours of use, the residual AHI
  and its split into obstructive, central and hypopnoea, leak, and pressure. The latest
  night is on the home screen. **Nights** lists them all.
- **Health records in Health Connect** (Android 16 and later). Each night is written as up
  to five coded observations: usage, events, leak, pressure and breathing. They are filed
  as lab results, and each says that it is a CPAP machine's nightly figure. On by default.
- **A table of nights** (CSV, one line for each night, units in the column names), kept up
  to date in a file that you create through Android's own save screen.
- **Share** the recent nights as text to any app.
- The device check tests the health records with a made-up night.

### Changed

- **Respiratory rate** is now a switch under **Sharing**, off unless you switch it on. An
  installation that was already writing it continues to do so.

### Known limits

- A night's figures arrive after the machine closes the day at midday.
- Not yet verified on a real phone: that Health Connect accepts and shows the health
  records, and that the file is written to a cloud drive from the background.

## 0.1.0 — 2026-11-16

First release.

### Added

- Pairing with a ResMed AirSense 11 over Bluetooth, with the code from the machine's screen.
- Respiratory rate, one reading for each minute of therapy, written to Health Connect at
  the true time. The machine's clock is measured and corrected for.
- Synchronising on request, when the phone hears the machine come into range, and about
  once an hour.
- A device check that proves each connection and can save a report.
- No night is written twice: a reinstall or a repeated run writes over the same records.

### Tested with

- AirSense 11 AutoSet, software `SW04600.19.8.8.0`; Pixel 10 Pro, Android 17.
- Decoding checked against the same nights on the machine's SD card.
