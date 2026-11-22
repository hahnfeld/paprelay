# Security

PAPRelay connects to a medical device and stores health data. Please report security
problems in private.

## How to report

Open the [Security tab](https://github.com/hahnfeld/paprelay/security) of this repository
and choose **Report a vulnerability**. Only the maintainer can read the report.

Please say what the problem is, how to reproduce it, and which version of PAPRelay, phone
and Android version you used.

Do not attach captures from a real machine, Bluetooth logs, SD-card files, the app's
private files, or the files of nights and events. They contain health data and what is needed to read it. If such data is
needed, the maintainer will arrange a safe way with you.

This is a small volunteer project. Expect a first answer within about two weeks. There is
no reward programme. Only the newest release receives fixes.

## What counts

- A way to make PAPRelay send the machine a request that changes therapy, settings or data.
- A way for another app or another Bluetooth device to obtain the pairing secret, the name
  and date of birth, or health data from PAPRelay.
- A way to make PAPRelay accept a device that is not the paired machine.
- A way to make PAPRelay write values to Health Connect, or to the files in the chosen
  folder, that the machine did not record.
- A way to make PAPRelay read, change or delete a file in the chosen folder other than its
  own two files.
- Health data, a name or date of birth, a full serial number, a Bluetooth address or a key
  in a log or in the saved report.

[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) describes how PAPRelay is built to prevent
these.

## Out of scope

- The machine's own Bluetooth protocol and software. PAPRelay has no connection to the
  manufacturer. One known limit of that protocol: its encryption does not authenticate
  each message, so someone who alters the radio traffic can damage a request in transit.
  PAPRelay verifies every download by its hash.
- The app's stored data is readable on a phone that its owner has rooted or unlocked.
- Anyone who can read the machine's screen can pair with it.
