# Intended use

PAPRelay transfers, stores, converts and displays data that a ResMed AirSense 11
has already recorded about its own operation, and writes a copy of it to Android
Health Connect on the same phone.

## What it does

- Reads stored therapy records from the machine over Bluetooth.
- Keeps a copy on the phone and can export it as a file.
- Converts units and timestamps so the records line up with other data on the phone.
- Writes the records Health Connect has a type for.

## What it does not do

- It does not interpret, score, grade or trend the data.
- It does not raise alerts or thresholds about the data.
- It does not give therapy or medical advice.
- It does not control the machine. It cannot change settings, start or stop
  therapy, or erase data, and the code is built so that such commands cannot be
  expressed (see `As11Request`).
- It never recomputes clinical indices such as AHI. Those values are shown exactly
  as the machine reported them, or not at all.

Any filtering PAPRelay applies before writing to Health Connect is a fixed,
documented rule (for example, leaving out minutes when the mask was off), not a
judgement about the data.

## Disclaimer

PAPRelay is not a medical device. It is not intended to diagnose, treat, cure or
prevent any condition, and must not be used to make diagnosis or treatment
decisions. Values may be wrong, late or missing. It does not replace myAir, your
equipment provider's reporting, or advice from a clinician. It is provided
without warranty of any kind.

PAPRelay is an independent project and is not affiliated with or endorsed by
ResMed or Google.
