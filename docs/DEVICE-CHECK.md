# First device check

The device check is the first thing PAPRelay does against a real machine. It
answers the questions that cannot be settled any other way, and every later
feature depends on its answers.

It reads from the machine and changes nothing on it. Its one write anywhere is a
Health Connect test value, which it removes again.

## Before you start

1. In the myAir app, remove the machine. Then forget it under the phone's
   Bluetooth settings. The machine keeps one pairing, and while it is paired
   with myAir it may not advertise at all.
2. On the machine: **More → MyAir App → OK, downloaded → Connect**. It is now
   waiting to be paired.
3. Keep the phone within a few metres.

## Run it

Open PAPRelay and choose **Run device check**. Grant the Bluetooth permission
and the Health Connect permission when asked. When the machine shows a
four-digit code, type it in.

When it finishes, choose **Export report** and save the file. The report holds
no pairing key, no code, no full serial number, no Bluetooth address and no
advertised name.

## What each step establishes

| Step | Question it answers | If it fails |
|---|---|---|
| Health Connect accepts writes | Can a build installed outside Google Play write to Health Connect? | Nothing else matters until this works; the app would need Play distribution. |
| Machine is in range | Does the machine's malformed advertisement survive Android's scanner? | Pairing by address only. |
| System scan filter | Can the system filter scans for this machine? | Noted, not a failure. Background discovery would have to scan unfiltered. |
| Pairing | Does the key exchange work from Android? | Compare the Bluetooth log with the reference client's sequence. |
| Encrypted session | Does the machine accept a new connection and prove itself? | See "Reconnect checks" below. |
| Machine identity | Is this the machine that was paired? | The check stops; nothing is downloaded. |
| Machine clock | How far has the machine's clock drifted? | Informational. |
| Stored data downloads and verifies | Are all four logs reachable, and does every round pass its hash? | A log that is unreachable changes what can be written to Health Connect. |

## Reconnect checks

These decide whether syncing can resume by itself when the phone comes home.
After a successful full check, use **Reconnect only** in each situation and
export the report each time:

1. Straight away.
2. After turning Bluetooth off and on.
3. After restarting the phone.
4. After leaving the phone out of range for an hour and returning.
5. After a night of therapy.

If any of these fails where the first succeeded, the machine probably needs a
system-level Bluetooth bond, which PAPRelay is designed to add.

## Data the check keeps

The downloaded logs are stored in the app's private storage so the decoders can
be developed against them. They are therapy data. They are replaced on each run
and deleted by **Forget machine** and by uninstalling.

Do not commit them, the Bluetooth log, or anything captured from a real machine
to a public repository: a capture contains what is needed to read it.
