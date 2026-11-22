# First device run and SD-card comparison

This is the record of the first run against a real machine, and of the comparison that
confirmed the decoders. It also serves as the example of a comparison on one software
version (see [CONTRIBUTING.md](../CONTRIBUTING.md#machines-and-software-versions)).

Run on 2026-11-16 with a debug build, a Google Pixel 10 Pro on Android 17, and
an AirSense 11 AutoSet (software `SW04600.19.8.8.0`, data model `v2.19.0`). Every step of
the device check passed on the first attempt. The saved report, the downloaded bytes and
the SD-card files are not kept in this repository.

## What was checked

| Question | Result | Evidence |
|---|---|---|
| Can a build installed outside Google Play write to Health Connect? | Yes | One record written and removed |
| Can the phone find the machine? | Yes | Seen by an unfiltered scan and by the system scan filter (service `FD56`). The advertisement is truncated but parses |
| Do pairing, the session, identity, the clock and all four logs work? | Yes | Pairing by code; encrypted session confirmed by the machine; all four logs downloaded, each in one round with a matching SHA-256 |
| Does a second connection work without a system-level Bluetooth bond? | Partly | A second connection straight after pairing was accepted. The other reconnect checks are still open |

## Findings

- In pairing mode the machine shows its serial number; the code appears once the phone
  starts pairing. (Seen on the released 0.3.0, whose steps asked the user to wait for the
  code before the phone searched; 0.3.1 asks for the serial number.)
- MTU 247 on the first request, so writes of 244 bytes are in order.
- GATT: service `FD56` with `a6220002` (write, write-without-response) and `a6220003`
  (notify), as in the reference. One further vendor service (`948652e2-…`, three readable
  characteristics) is present and unused.
- The machine sends an integrity hash with every round, and its fragments carry sequence
  numbers.
- The link was clean: no discarded bytes, CRC failures, abandoned frames, stray fragments,
  unmatched answers or undecodable frames.
- The machine's clock was about six minutes ahead of the phone (three readings within
  9 ms of each other, round trips of 30–47 ms). Drift of minutes is real, and the
  measurement is precise enough to correct it.
- 30 days of Summary was 3.3 kB; three days of events and per-minute data were under 2 kB
  each. A full download is small.
- More than one machine can be in range. The check asks which one, and the identity step
  confirms the choice. Choosing by signal strength alone would be unsafe.
- Identity answers: `ProductName`, `ProductCode`, `ApplicationIdentifier`,
  `DataModelVersionIdentifier`; version sections `FlowGenerator`, `BluetoothModule`,
  `CellularModule`.
- Health records (0.4.1-test, Android 16): Health Connect accepted a FHIR Patient resource
  in PAPRelay's data source, and Observations whose subject is the relative reference
  `"Patient/paprelay-patient"`. Google Health showed PAPRelay's records once each
  Observation's subject referred to a Patient record with a name and a date of birth.
  Before that, it reported that the medical data could not be synced.

From other sources:
[SomnoTrace's pairing guide](https://github.com/ilyakruchinin/somnotrace/blob/main/docs/pairing.md): the machine keeps one Bluetooth bond at
a time, and must be released from myAir before another app can pair.

## Accuracy against the SD card

The downloaded bytes were decoded and compared with the same machine's SD card for the
same period, read directly from its EDF files.

| What | Compared | Result |
|---|---|---|
| Daily summaries | 30 days: each index, percentile, duration and mask-on/off time | All equal at the stored resolution |
| Breathing events | Every event of three sessions | Same kind, same order, same duration; times within 2 s (the card stores whole seconds) |
| Per-minute log, length | Three sessions | One sample for each minute of mask-on time on both sides |
| Per-minute leak, pressures and minute ventilation | Against the card's 2-second signals, averaged per minute | Within one step of the per-minute resolution |
| Per-minute respiratory rate | Same | Mean difference 0.13 to 0.28 breaths/min; within 0.5 on 94.6 % of the minutes. The machine's per-minute figure is its own average, not a resample of the 2-second signal, so exact equality is not expected |

What this established:

- Two Rice moduli in the published reference are swapped for this software: respiratory
  rate is 8 and minute ventilation 4. The decoder refused every per-minute record until
  this was corrected, because the signals of a record disagreed about their length.
- The machine pads a compressed block with ones.
- A per-minute sample is stamped at the end of its minute: the first sample of a session
  carries mask-on plus 60 s and equals the card's first minute.
- An apnoea is stamped at its end, with its duration alongside. The card's annotations
  carry the same instant. A hypopnoea has no stored duration, and whether its stamp is its
  start or its end cannot be told.
- Zeros are written explicitly in a used day's summary; an absent value means no data. A
  day without therapy has a duration of zero and nothing else. The machine truncates
  indices to tenths.
- Summary field 37 (respiratory flow, 5th and 95th percentile) is a plain value in
  hundredths of a litre per second, as the reference says.
- Not confirmed by either source: per-minute inspiratory time and I:E ratio.

## What is passed on, and what is not yet

What the comparison confirmed is passed on; nothing else is. The flags are in
`as11/.../decode/As11Scales.kt` (`SummaryMetric.confirmed`, `RespiratoryEvent.confirmed`).

Daily summary, **confirmed and passed on** (field numbers in brackets):

| What | Given as |
|---|---|
| Duration, session count, each session's start and length (5, 6, 39) | Minutes; starts by the machine's clock, and corrected |
| AHI, apnoea, hypopnoea, obstructive, central, unclassified apnoea and RERA indices (7–13) | Events per hour |
| Leak (14): median, 70th, 95th percentile, maximum | L/min (stored in litres per second, times 60, exact) |
| Inspiratory, expiratory and mean mask pressure (15, 20, 21): median, 95th, maximum | cmH2O |
| Tidal volume (22), minute ventilation (23), respiratory rate (25): median, 95th, maximum | mL (stored in hundredths of a litre, times 1000, exact), L/min, breaths/min |
| Ambient humidity (29), humidifier temperature (30), heated-tube temperature (31), humidifier power (32), heated-tube power (33): median | mg/L, °C, % (files only) |
| Blower pressure (36): 5th and 95th percentile | cmH2O (files only) |
| Respiratory flow (37): 5th and 95th percentile | L/min (stored in litres per second, times 60, exact) |
| Blower flow (38): median | L/min (stored in litres per second, times 60, exact; files only) |

Daily summary, **not yet confirmed and not passed on**:

| What | What confirming it takes |
|---|---|
| Target minute ventilation (24), alveolar minute ventilation (42), I:E ratio (27) | Their flag only. The figures are named and mapped, and the plan's version, the table's columns and the breathing record follow. |
| Inspiratory duration (26) | The flag, and a night figure finer than hundredths: it is stored in thousandths of a second. |
| SpO2 (28), heart rate (41) | The flag, and a decision where they go among the five health records. Not present without an oximeter. |
| Cheyne-Stokes respiration (16), SpO2 threshold (17), spontaneous trigger (18) and cycle (19) | Their meaning and unit. They are whole numbers whose scale the comparison did not cover. |
| Humidifier connected (34), heated tube connected (35) | Their meaning. Their values match the card's Humidifier and HeatedTube codes, but what each code means is not known. |

Breathing events: obstructive apnoea (4), central apnoea (3), hypopnoea (2) and unclassified
apnoea (5) are **confirmed** and passed on: to the phone and the files, not to Health
Connect. An apnoea with a length is stamped at its end; for a hypopnoea the time is passed
on as stored, marked as either its start or its end. The unclassified apnoea was confirmed
by a second comparison: every unclassified apnoea ("Apnea") on the card matched one code-5
event, with the same length and the machine's time within 2 s of the card's, as for the
other apnoeas.
**Not yet confirmed**, and not passed on: RERA (6), Cheyne-Stokes start and end (8, 9), and
codes not in the table. Codes 6, 8 and 9 did not occur in the compared history. Confirming
one takes a night that has it, its raw Bluetooth event bytes, and the same night on the
card.

Mask on and off (`UsageEvents-TherapyStatusEvents`): **confirmed**, and used for the
morning preview only (the nights' sessions come from the summary). The real-data probe
(`RealDataProbeTest`, with `UsageCheck`) compared each mask-on and mask-off pair with the
summary's sessions of the same capture: every session in the first device run's capture
matched the summary's sessions, mask-on within a minute after the summary's start, which
is stored in whole minutes, and lengths equal within a minute. Each session opens with
therapy start, then mask on a few seconds later, and closes with therapy stop and mask off
at the same instant; mask on is taken as the start. The other kinds of the log (therapy
start and stop, power off, mask fit, learn targets) are **not used**.

## 0.3.0 files against the SD card

The two files that PAPRelay 0.3.0 wrote on a real phone were compared with the same
machine's `STR.edf` (daily summaries) and its `DATALOG` event annotations (`*_EVE.edf`).

| What | Compared | Result |
|---|---|---|
| `paprelay_nights.csv`: usage minutes and session count | Every compared night | Equal on every one |
| `paprelay_nights.csv`: every value column | Every compared night with therapy | All equal at the stored resolution (leak and the other flows times 60 to L/min, exact) |
| `paprelay_nights.csv`: each session's start by the machine's clock, and its length | Every compared night with therapy | Equal to the card's mask-on and mask-off |
| `paprelay_nights.csv`: nights without therapy | Every compared night without therapy | Every value cell empty |
| `paprelay_events.csv`: obstructive, central and hypopnoea events | Every compared event | Each matches one on the card, by kind, with the same length (apnoeas). The machine's time is always later than the card's, all within 2 s, because the card stores whole seconds |
| `night_of` and `time_is` | Every compared event | Right for every one (noon-to-noon rule; `end` for the apnoeas) |
| Corrected times | The session starts and events within the seven-day window | Corrected by the same measured offset |
| Unclassified apnoea (code 5) | Every unclassified apnoea on the card | Not in the files of that build (code 5 was not yet confirmed); confirmed afterwards by a second comparison (above) |

The card's EDF headers give the units mg/L, Celsius and % for humidity, temperatures and
power.

## Still open

1. Reconnect checks 2–5 in [DEVICE-CHECK.md](DEVICE-CHECK.md#reconnect-checks) (Bluetooth
   off and on, phone restart, an hour out of range, after a night of therapy).
2. Whether pairing again makes the machine drop the earlier pairing.
3. How a change of the machine's clock or time zone shows in the stored time stamps.
4. The machine's error codes for a wrong code and for an unknown pairing.
5. Whether a long log arrives as several rounds, and whether a round can be empty with
   more pending: every log fitted in one round.
6. More nights for the comparison, including a night with a time zone change and one with
   an oximeter attached.
