# First device run (M1)

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

## Still open

1. Reconnect checks 2–5 in `DEVICE-CHECK.md` (Bluetooth off and on, phone
   restart, an hour out of range, after a night of therapy).
2. The machine's error codes for a wrong code and for an unknown pairing: not
   exercised, since nothing failed.
3. Whether a long log arrives as several rounds, and whether a round can be
   empty with more pending: every log fitted in one round.
4. Decoding the downloaded bytes and comparing them with the SD card in OSCAR.
