# Changelog

Newest first. Before 1.0, any release may change behaviour.

## Unreleased

- Pairing runs on the home screen again. The lines in the picture fill in as each
  connection is tested, and the ticks stay until the first synchronisation. The picture and
  the sentence under it show the device check or the synchronisation, whichever was last.
- The device check has one button. **Check connection only** is removed: a check of a
  paired machine does not pair again. **Synchronise now** is no longer on that page. The
  page shows the picture too.
- The home screen leads to **Nights**, **Sharing** and **Device check** with rows that have
  small drawings. About is the **i** at the top. **Sharing** has the same drawings beside
  its items. **Nights** says when there are no nights yet.

## 0.2.2 (2026-11-20)

First public release.

- PAPRelay no longer stops when an AirSense 11 has another software version. It reads the
  nightly figures there too. With software other than 19.8.8.0, respiratory rate starts
  with the first night of an hour or more after it is switched on or after the software
  changes; earlier nights are not written. Until then the app says that it waits.
- A nightly figure is left out if its set of percentiles is not the one PAPRelay knows.
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
