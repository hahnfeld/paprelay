# First device run (M1)

A record of the first run against a real machine, and of the comparison that confirmed
the decoders. It is kept as an example of how a machine software version is confirmed
(see [CONTRIBUTING.md](../CONTRIBUTING.md#a-new-machine-software-version)).

Run on 2026-11-16 with build `9e84a1e` (debug), a Google Pixel 10 Pro on
Android 17, and an AirSense 11 AutoSet (firmware `SW04600.19.8.8.0`, data
model `v2.19.0`). Every step of the device check passed on the first attempt.
The saved report is not kept in this repository.

## Gates

| Gate | Result | Evidence | Consequence |
|---|---|---|---|
| (a) A build installed outside Google Play can write to Health Connect | **Pass** | One record written and removed | GitHub distribution is viable |
| (b) The machine can be found | **Pass** | Seen by an unfiltered scan and by the system scan filter (service `FD56`); the advertisement is truncated but parses | Background discovery can use a filtered scan |
| (c) Reconnecting without a system bond | **Open** | A second connection straight after pairing was accepted and proved itself; the Bluetooth-toggle, reboot, out-of-range and next-day checks are still to run | See "Still open" |
| (d) Pair, session, identity, clock, all four logs | **Pass** | Pairing by code; encrypted session confirmed by the machine; all four logs downloaded, each in one round with a matching SHA-256 | The protocol port is correct for this firmware |
| (e) Re-pairing kills the old key | Not tested | | |
| (f) Clock-change behaviour, minute-block stability, missing-sample marker | Not tested | Needs the downloaded bytes and a second run | |

## Facts learned

- **MTU 247** on the first request, so writes of 244 bytes are in order.
- **GATT:** service `FD56` with `a6220002` (write, write-without-response) and
  `a6220003` (notify), as in the reference. One further vendor service
  (`948652e2-…`, three readable characteristics) is present and unused.
- **The machine sends integrity hashes** with every round, and its fragments
  carry sequence numbers: the strict rules in the client cost nothing.
- **The link was clean:** no discarded bytes, CRC failures, abandoned frames,
  stray fragments, unmatched answers or undecodable frames.
- **Clock:** the machine was 361.6 s ahead of the phone (three readings within
  9 ms of each other, round trips of 30–47 ms). Drift of minutes is real, so
  correcting it is necessary and the measurement is precise enough to do so.
- **Sizes:** 30 days of Summary was 3.3 kB; three days of events and per-minute
  data were under 2 kB each. A full backfill is small.
- **Two machines were in range.** The check asked which one, and the identity
  step confirmed the choice. Choosing by signal strength alone would be unsafe.
- Identity answers: `ProductName`, `ProductCode`, `ApplicationIdentifier`,
  `DataModelVersionIdentifier`; version sections `FlowGenerator`,
  `BluetoothModule`, `CellularModule`.

## Accuracy against the SD card

The bytes downloaded in that run were decoded (branch `m2-decoders`) and
compared with the same machine's SD card for the same period, read directly
from its EDF files. Neither the capture nor the card's files are kept in this
repository.

| What | Compared | Result |
|---|---|---|
| Daily summaries | 30 days; on the 12 days with therapy, every index, percentile, duration and mask-on/off time: 554 values, 28 sessions | All equal at the stored resolution. Unused days are empty on both sides |
| Breathing events | 11 events over three sessions | Same kind, same order, same duration; times within 1.5 s (the card stores whole seconds) |
| Per-minute log, length | Three sessions | One sample per minute of mask-on time on both sides, in every session |
| Per-minute leak and pressures | Against the card's 2-second signals, averaged per minute | Within one step of the per-minute resolution |
| Per-minute respiratory rate | Same | Mean difference 0.13 to 0.28 breaths/min; within 0.5 on 94.6 % of 426 minutes. The machine's per-minute figure is its own average, not a resample of the 2-second signal, so exact equality is not expected |

Facts this established:

- **Two Rice moduli in the published reference are swapped** for this firmware:
  respiratory rate is 8 and minute ventilation 4. The decoder refused every
  per-minute record until this was corrected, because the signals of a record
  disagreed about their length.
- **The machine pads a compressed block with ones.**
- **A per-minute sample is stamped at the end of its minute:** the first sample
  of a session carries mask-on plus 60 s and equals the card's first minute.
- **A breathing event is stamped at its end,** with its duration alongside. The
  card's annotations carry the same instant.
- **Zeros are written explicitly** in a used day's summary; an absent value means
  no data. The machine truncates indices to tenths.
- **Summary field 37 (respiratory flow, 5th and 95th percentile) is a plain
  value in hundredths of L/s,** as the reference says.
- Not confirmed by either source: per-minute inspiratory time and I:E ratio.

## Still open

1. Reconnect checks 2–5 in `DEVICE-CHECK.md` (Bluetooth off and on, phone
   restart, an hour out of range, after a night of therapy).
2. The machine's error codes for a wrong code and for an unknown pairing: not
   exercised, since nothing failed.
3. Whether a long log arrives as several rounds, and whether a round can be
   empty with more pending: every log fitted in one round.
4. More nights for the comparison above, including a night with a timezone
   change and one with an oximeter attached.
