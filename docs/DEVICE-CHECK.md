# The device check

The device check tests every connection that PAPRelay depends on, one after the other,
with the real machine and the real phone. Pairing runs it the first time, on the home
screen: the picture shows each connection as it is tested. You can run it again at any time
from **Device check** on the home screen.

The check changes none of the machine's settings or data. Pairing replaces the myAir
app's connection to the machine. For each thing that is switched on under **Sharing**,
the check writes a made-up test entry to Health Connect and removes it again.

## Run it

The first time, follow [First-time setup](../README.md#first-time-setup). With no machine
paired, **Pair the machine** first shows the steps. The check, and its search for the
machine, starts when you tap **The machine shows a code**. **Cancel** in the code prompt
ends the check, stores nothing, and shows the steps again.

Later, open **Device check** and choose **Run device check**. When the machine is already
paired, the check does not pair again. *Find the machine* listens briefly for the paired
machine, and *Pair* says that the machine is already paired.

**Save report** saves the result as a file. The report holds no pairing key, no code, no
full serial number (only its last four characters), no Bluetooth address and no advertised
name. It is made to be attached to a problem report.

**Write everything again** and **Forget machine** are also on this page.

## What each step shows

| Step | Question | If it fails |
|---|---|---|
| Health Connect | Can PAPRelay write what is switched on in Sharing, and remove it again? | Allow PAPRelay in Health Connect, or switch the item off in Sharing. Phones before Android 16 have no health records. |
| Find the machine | Can the phone hear the machine? | Put the machine in pairing mode. Make sure it is not paired with myAir. |
| Pair | Does the key exchange with the code work? | Start pairing on the machine again. The code works for about two minutes. |
| Connect securely | Does the machine accept a new connection and prove that it holds the key? | Move closer. If the machine no longer accepts the phone, choose **Forget machine** and pair again. |
| Confirm the machine | Is this the machine that was paired? | The check stops. Nothing is downloaded. |
| Machine clock | How far is the machine's clock from the phone's? | For information. PAPRelay corrects for the difference. |
| Download saved data | Can all four logs be downloaded, and does every round pass its hash? | The report has the reason. |

## Reconnect checks

These show whether synchronising resumes by itself when the phone comes home. After a
successful check, choose **Run device check** in each situation:

1. Straight away.
2. After turning Bluetooth off and on.
3. After restarting the phone.
4. After leaving the phone out of range for an hour and returning.
5. After a night of therapy.

If one of these fails where the first succeeded, please
[report it](https://github.com/hahnfeld/paprelay/issues) with the saved report.

## Forget machine

**Forget machine** deletes from this phone the pairing and the data that PAPRelay
downloaded from the machine. It sends nothing to the machine. Data already in Health
Connect stays there.

To use the myAir app again, pair the machine with myAir as usual.

## Data on the phone

The app keeps the downloaded logs of the last device check and of recent synchronisations
in its private storage, unencrypted. They are therapy data. **Forget machine** and
uninstalling delete them.

Do not publish them, a Bluetooth log, or anything else captured from a real machine. See
[CONTRIBUTING.md](../CONTRIBUTING.md#never-share-these).
