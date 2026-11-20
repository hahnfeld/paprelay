# The device check

The device check tests every connection that PAPRelay depends on, one after the other,
against the real machine and the real phone. Pairing runs it the first time. You can run
it again at any time from **Device check** on the home screen.

It reads from the machine and changes nothing on it. For each thing that is switched on
under **Sharing**, it writes a made-up test entry to Health Connect and removes it again.

## Before you pair

1. In the myAir app, remove the machine. Then forget it under the phone's Bluetooth
   settings. The machine keeps one pairing, and while it is paired with myAir it may not
   answer at all.
2. On the machine choose **More**, then **myAir App**, then **Connect**. The machine now
   waits to be paired and shows a code with four digits.
3. Keep the phone within a few metres.

## Run it

- **The first time:** on the home screen, choose **Pair the machine**. When Android asks,
  allow PAPRelay to find nearby devices (Android's name for the Bluetooth permission) and
  to write to Health Connect. Type the code that the machine shows.
- **Later:** open **Device check** and choose **Run device check**, or **Check connection
  only** for the short form. The short form does not search for the machine and does not
  pair: it connects to the paired machine directly and runs the other steps.

When it finishes, **Save report** saves the result as a file. The report holds no pairing
key, no code, no full serial number, no Bluetooth address and no advertised name. It is
made to be attached to a problem report.

## What each step shows

| Step | Question it answers | If it fails |
|---|---|---|
| Health Connect | Can PAPRelay write what is switched on in Sharing, and remove it again? | Allow PAPRelay in Health Connect, or switch the item off in Sharing. On a phone before Android 16 the health records are not available. |
| Find the machine | Can the phone hear the machine? | Put the machine in pairing mode. Make sure it is not paired with myAir. |
| Pair | Does the key exchange with the code work? | Start pairing on the machine again; the code works for about two minutes. |
| Connect securely | Does the machine accept a new connection and prove that it holds the key? | Move closer. If the machine no longer accepts the phone, choose **Forget machine** and pair again. |
| Confirm the machine | Is this the machine that was paired? | The check stops; nothing is downloaded. |
| Machine clock | How far is the machine's clock from the phone's? | For information. PAPRelay corrects for the difference. |
| Download saved data | Are all four logs reachable, and does every round pass its hash? | The report has the reason. |

## Reconnect checks

These show whether synchronising resumes by itself when the phone comes home. After a
successful full check, use **Check connection only** in each situation:

1. Straight away.
2. After turning Bluetooth off and on.
3. After restarting the phone.
4. After leaving the phone out of range for an hour and returning.
5. After a night of therapy.

If one of these fails where the first succeeded, please
[report it](https://github.com/hahnfeld/paprelay/issues) with the saved report.

## Forget machine

**Forget machine** deletes the pairing and the data that PAPRelay downloaded from this
phone. It sends nothing to the machine. Data already in Health Connect stays there.

To use the myAir app again, pair the machine with myAir as usual. The new pairing replaces
PAPRelay's.

## Data the check keeps

The downloaded logs are stored in the app's private storage. They are therapy data. They
are replaced on each run and deleted by **Forget machine** and by uninstalling.

Do not publish them, a Bluetooth log, or anything else captured from a real machine: a
capture contains what is needed to read it. See [CONTRIBUTING.md](../CONTRIBUTING.md#never-share-these).
