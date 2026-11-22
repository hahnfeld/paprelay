# Changelog

Newest first. Before 1.0, any release may change behaviour.

## 0.4.1 (2026-11-22)

- **Name and date of birth for the health records.** Google Health shows PAPRelay's records
  only with a name and a date of birth. Add them in **Sharing › Name and date of birth**;
  they are kept encrypted on the phone and, if health records are switched on, every night is
  written to them once more.
- The health records carry standard LOINC codes beside PAPRelay's own, for usage, AHI, the
  obstructive and central apnoea indices, leak and therapy pressure.

## 0.4.0 (2026-11-22)

- **The night so far.** Before the machine has summed up a night (it does this after
  the next midday), the home screen shows that night, dated and marked "so far", above the
  newest night the machine has summed up: how long the mask was on and how many breathing
  events the machine had logged at the last synchronisation, with a link to them. A
  session that had not ended then says so, with that time. **Nights** shows the night as its
  newest page, marked "so far", with its picture; **Breathing events** lists its events
  first. No AHI or other figure is worked out, and where a record of the machine's log
  could not be read no time of use is given. It stays in the app and is never written to
  the files, Health Connect or the shared text. After midday, at the next synchronisation,
  the machine's own night takes its place. The time of use comes from the machine's log of
  mask on and mask off, confirmed against the machine's nightly sessions.

## 0.3.1 (2026-11-22)

- Pairing: the steps now ask you to wait for the machine's serial number, which it shows in
  pairing mode, and name the **OK, Downloaded** screen. The four-digit code appears on the
  machine only after the phone connects; the code prompt then asks for it.

## 0.3.0 (2026-11-22)

Nothing stored by an earlier version needs to be kept: the first synchronisation reads
again the last 400 days that the machine holds. Choose the folder for the files again in **Sharing**.

- **Every confirmed nightly figure.** Besides usage, the indices, leak and pressure:
  leak 70th percentile; respiratory rate, tidal volume and minute ventilation (95th
  percentile and maximum); respiratory flow (5th and 95th percentile); and, for the
  machine, ambient humidity, humidifier and heated-tube temperature and power, blower
  pressure and blower flow. Each session's start and length. Figures whose layout has not
  been confirmed (target and alveolar ventilation, I:E ratio, inspiratory duration, SpO2,
  heart rate, Cheyne-Stokes, spontaneous trigger and cycle) are not passed on.
- **Units.** All flows are in litres per minute, and tidal volume is in millilitres. The
  conversions are exact.
- **Breathing events.** Each obstructive apnoea, central apnoea, unclassified apnoea and
  hypopnoea is copied to the phone with its time and length as the machine stored them,
  and beside it the time corrected to the phone's clock where that can be done with
  confidence.
- **Health records.** The leak and breathing records carry the new percentiles. The
  figures of the humidifier, the tube and the blower, and the events, are not written
  there. All nights are written again once in the new form.
- **Two files in a folder.** In place of one file, PAPRelay keeps `paprelay_nights.csv`
  (one line for each night, with its sessions) and `paprelay_events.csv` (one line for each
  breathing event, with its night) in a folder that you choose once.
- **Nights** shows one night at a time, with all its figures and a picture of the night.
  Swipe or use the arrows to see an earlier or later night.
- **Breathing events**: a new page, newest first, grouped by night.
- The Share action is only on **Sharing** now. The shared text is unchanged.
- **Pairing** starts with a page of steps and a picture of the machine showing its code.
  The search for the machine begins only after you tap **The machine shows a code**. The
  code prompt says what to do if the machine shows no code.
- The files and the night's figures were checked against the machine's SD card: every
  value of every compared night and every compared breathing event matched.

## 0.2.3 (2026-11-22)

- Pairing runs on the home screen again. The lines in the picture fill in as each
  connection is tested, and the ticks stay until the first synchronisation. The picture and
  the sentence under it show the device check or the synchronisation, whichever was last.
- The device check has one button. **Check connection only** is removed: a check of a
  paired machine does not pair again. **Synchronise now** is no longer on that page. The
  page shows the picture too.
- The home screen leads to **Nights**, **Sharing** and **Device check** with rows that have
  small drawings. About is the **i** at the top. **Sharing** has the same drawings beside
  its items. **Nights** says when there are no nights yet.
- Before a machine is paired, the home screen shows the three steps of pairing as a
  numbered list.

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
