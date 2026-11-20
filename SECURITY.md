# Security

PAPRelay connects to a medical device and stores health data. Security problems are taken
seriously.

## Report a problem in private

Please do **not** open a public issue for a security problem.

Use GitHub's private reporting instead: open the
[Security tab](https://github.com/hahnfeld/paprelay/security) of this repository and choose
**Report a vulnerability**. Only the maintainer can read the report.

Please say:

- what the problem is, and what someone could do with it;
- how to reproduce it;
- the version of PAPRelay (see **About** in the app), the phone and the Android version.

Do **not** attach captures from a real machine, Bluetooth logs, SD-card files or the app's
private files. They contain what is needed to read them, and they contain health data.
If such data is needed, the maintainer will ask and agree a safe way with you.

This is a small volunteer project. You can expect a first answer within about two weeks.
There is no reward programme.

## Supported versions

Only the newest release receives fixes.

## What counts as a security problem

Examples:

- a way to make PAPRelay send the machine a request that changes therapy, settings or data;
- a way for another app, or another Bluetooth device, to obtain the pairing secret or
  health data from PAPRelay;
- a way to make PAPRelay accept a device that is not the paired machine;
- a way to make PAPRelay write values to Health Connect that the machine did not record;
- health data, a serial number, a Bluetooth address or a key in a log or in the saved report.

## What PAPRelay does to protect you

- The app has no internet permission.
- The pairing secret is encrypted with a key in the Android Keystore and is not included
  in backups.
- The request set is closed and read-only.
- Every download is verified by its SHA-256, and the machine must prove that it holds the
  pairing key before any data is read.
- The app holds no permission to read health data.

Details: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Things that are not problems in PAPRelay

- The machine's own Bluetooth protocol, firmware or behaviour. PAPRelay is an independent
  project and has no connection to the manufacturer.
- The data stored by the app is readable on a phone that is rooted or unlocked by its owner.
- Anyone who can read the machine's screen can pair with it. This is how the machine works.
