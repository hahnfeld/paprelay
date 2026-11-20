# First device run and SD-card comparison

This is the record of the first run against a real machine, and of the comparison that
confirmed the decoders. It also serves as the example of how a machine software version is
confirmed (see [CONTRIBUTING.md](../CONTRIBUTING.md#a-new-machine-software-version)).

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

## Accuracy against the SD card

The downloaded bytes were decoded and compared with the same machine's SD card for the
same period, read directly from its EDF files.

| What | Compared | Result |
|---|---|---|
| Daily summaries | 30 days: each index, percentile, duration and mask-on/off time | All equal at the stored resolution |
| Breathing events | Every event of three sessions | Same kind, same order, same duration; times within 1.5 s (the card stores whole seconds) |
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
  hundredths of L/s, as the reference says.
- Not confirmed by either source: per-minute inspiratory time and I:E ratio.

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
