# Intended use

PAPRelay transfers, stores, converts and displays data that a ResMed AirSense 11 has
already recorded about its own operation. It keeps a copy on the phone and can write a
copy to Android Health Connect on the same phone, to a file that the user chooses, and to
another app that the user chooses.

## For personal use only

PAPRelay is made for one person, to copy the data of their own machine to their own phone.

- It is not made or tested for clinics, hospitals, care providers, equipment providers,
  insurers, employers or researchers.
- It is not made to collect, monitor or report the data of other people.
- Do not rely on it as proof of therapy use (compliance). It is not made for that. Use the
  manufacturer's own reporting.

This says what PAPRelay is made and tested for. It is not an extra condition on the
licence. The licence alone (see `LICENSE` and `NOTICE`) says what you may do with the
software.

## Not from the manufacturer

PAPRelay is an independent project. It is not made, endorsed, supported or approved by
ResMed, the company that makes the machine. It is not affiliated with ResMed or Google.
It contains no code from ResMed. It talks to the machine over the machine's own Bluetooth
interface, with the pairing code that the machine shows to its owner.

The manufacturer has not tested PAPRelay with the machine. If you have a question about
your machine or your therapy, ask the manufacturer, your equipment provider or your doctor,
not this project.

## What it does not do

- It does not interpret, score, grade or trend the data.
- It does not raise alerts or thresholds about the data.
- It does not give therapy or medical advice.
- It does not control the machine. It cannot change settings, start or stop therapy, or
  erase data, and the code is built so that such commands cannot be expressed (see
  `As11Request`).
- It does not recompute clinical indices such as AHI. Those values are shown as the
  machine reported them, or not at all.

Any filtering PAPRelay applies before writing to Health Connect is a fixed, documented
rule (for example, leaving out a minute for which the machine stored a respiratory rate of
zero), not a judgement about the data.

In Health Connect the nightly figures are filed under lab results, because Health Connect
has no other place for them. They are not laboratory results.

## Disclaimer

PAPRelay is not a medical device. It has not been reviewed, cleared or approved by any
regulator. It is not intended to diagnose, treat, cure or prevent any condition, and must
not be used to make diagnosis or treatment decisions. Values may be wrong, late or missing.
It does not replace myAir, your equipment provider's reporting, or advice from a clinician.

PAPRelay is provided without warranty of any kind. You use it at your own risk.
