# Architecture

This page explains how PAPRelay is made and why. Read it before you change the code.

PAPRelay does one thing: it copies what a ResMed AirSense 11 has already recorded to the
phone, and passes it on unchanged. Two demands shape every part of it:

- **Accuracy.** A figure is passed on exactly as the machine stored it, or not at all.
  PAPRelay never estimates, rounds, scores or recomputes.
- **Resilience.** The phone leaves home every day; the machine does not. Being out of
  range, losing the connection halfway, a crash, a restart and a reinstall are all ordinary
  events. None of them may lose a night, write a night twice, or leave a wrong value behind.

## The three modules

![app uses as11 and core. as11 uses core.](images/modules.svg)

| Module | Kind | What it holds |
|---|---|---|
| `core` | Kotlin/JVM | The model shared by everything: measurements with their units, the machine's clock, a night and its records, and the ledger rules. No Android, no Bluetooth. |
| `as11` | Kotlin/JVM | The AirSense 11 protocol: framing, pairing, the encrypted session, the read-only request set, downloads, the decoders of the stored logs, and the plans that turn decoded records into what is passed on. Its test fixtures hold a simulated machine. |
| `app` | Android | Everything that needs Android: Bluetooth, the stored pairing, Health Connect, the sync engine with its stores and background runs, and the screens. |

`core` and `as11` are plain JVM libraries. They build and test without the Android SDK
(`-Ppaprelay.skipAndroid=true`), and almost all of the logic that can be wrong lives there
or behind interfaces that the tests replace.

## The way of the data

![The machine, then the link, the protocol, the decoders and the plans, then the sync engine, which writes to Health Connect, to a file and to the screen.](images/flow.svg)

| Step | Where | What happens |
|---|---|---|
| Link | `app/ble/GattLink`, `GattOperations` | One Bluetooth (GATT) connection as a byte pipe. One request in flight at a time, each matched to its own completion. Writes never longer than the link allows. A connection is never reused. |
| Protocol | `as11/As11Client`, `Fig`, `Srp6a`, `SessionCrypto`, `As11Request` | Frames with two checksums; SRP-6a pairing with the code from the machine's screen; an AES session that the machine must prove; requests; downloads in rounds, each verified by its SHA-256. |
| Decoders | `as11/decode` | Turn the downloaded bytes of the four stored logs into records. Layouts and scale factors are in one place, `As11Scales`. |
| Plans | `as11/plan` | `NightPlan` turns one daily summary into a `Night`. `RespiratoryRatePlan` turns one per-minute record into readings. Nothing is computed here; fields are chosen and units are carried over exactly. |
| Sync engine | `app/sync/SyncEngine` | Decides what to download, what is new or changed, and what to write. Holds the rules for every kind of trouble. |
| Outputs | `app/hc`, `app/sync/HealthConnectSink`, `NightFile`, `app/ui` | The health records and vitals in Health Connect, the table of nights in a file, and the screens. |

### What the machine stores

PAPRelay reads four logs ("spools") and nothing else:

| Log | Holds | Used for |
|---|---|---|
| `Summary` | One record for each day, from midday to midday: usage, indices, percentiles | The nightly figures |
| `TherapyOneMinutePeriodic` | One sample for each minute of therapy | Respiratory rate |
| `UsageEvents-TherapyStatusEvents` | Mask on and off | Downloaded and verified by the device check |
| `TherapyEvents-RespiratoryEvents` | Each breathing event | Downloaded and verified by the device check |

A day's summary exists only after that day has ended at midday by the machine's clock.
This is why a night's figures arrive in the afternoon.

## Decisions, and the reasons for them

### Read-only by construction

The encrypted channel of the machine also accepts commands that change settings, start
therapy or erase data. PAPRelay cannot send them: `As11Request` is a sealed class, and the
session encrypts nothing else. The items it can read (`GetItem`) and the logs it can
download (`SpoolName`) are closed lists. The list holds one request that changes anything
on the machine: `DiscardPairKey`, which asks the machine to forget PAPRelay's own pairing.
The app does not send it at present; **Forget machine** deletes the pairing on the phone
only.

A test (`As11RequestTest`) pins the list. Adding a request is a deliberate, reviewed change.

### Exact values

A measurement is kept as the integer that the machine stored, together with its scale and
unit (`core/Quantity`). A night's figures are whole hundredths (`core/nights/Night`).
There is no floating-point arithmetic between the machine and Health Connect, so two runs
over the same data always produce the same bytes.

Clinical indices such as AHI are never recomputed. A value that cannot be read with
certainty is left out; an empty cell is never a guessed zero.

### Only checked machine software

The layouts of the stored logs are not published by the manufacturer. They were confirmed
on one machine against the files on its SD card. A different software version could use a
different layout, and the per-minute log in particular can decode to values that are wrong
but plausible.

So `Firmware.layoutsConfirmed` lists the exact versions that have been checked, and the
engine refuses every other version (`SOFTWARE_NOT_CONFIRMED`) and writes nothing. In
addition, after a software change the engine waits for a long session that decodes cleanly
before it trusts short ones ("vouching").

### Two clocks

The machine's clock drifts by minutes. Every connection measures the difference between
the machine's clock and the phone's (`MachineTime`, `ClockOffset`):

- Respiratory-rate readings are written to Health Connect at the corrected, true time.
- A night is dated by the machine's own local day, taken from the start of its summary
  period. This does not move when the phone travels.
- If the machine's clock was changed, the engine stops and asks the user
  (`sync_clock_changed`). It does not guess.
- If the phone's own clock is clearly wrong, nothing is written (`PHONE_CLOCK_WRONG`).

### Nothing twice: fixed names and a ledger

Every record has a name that is derived from the machine and the time, not from the run:

- a health record: `<machine tag>-<date>-<group>`, for example `…-20261114-leak`;
- a respiratory-rate reading: the machine tag, the session and the minute.

Writing a record again replaces it. A reinstall, a second phone run or a repeated night
therefore cannot produce duplicates.

The *machine tag* is derived from the serial number by a slow one-way function. The serial
number itself is never written to Health Connect, to the file, or to a report.

For respiratory rate, the engine also keeps a **ledger** (`core/sync/Reconcile`,
`LedgerEntry`): for each session, what Health Connect may hold. The ledger is written
*before* Health Connect is touched and again after it answered (write-ahead). At any moment
it claims at least everything that may be there. After a crash, the next run compares what
should be there with what may be there, and removes or rewrites the difference. A changed
night never leaves old readings behind.

For the nightly figures, `NightStore` keeps each night together with a mark of what was
last written for it (the record format and a fingerprint of the night). A night whose mark
differs is written again, and records that a night no longer has are removed.

### Stores that survive a crash

The app keeps a few small files in its private, not-backed-up storage:

| File | Holds | If it is lost or damaged |
|---|---|---|
| `pairing.bin` | The pairing secret, encrypted with a key in the Android Keystore (StrongBox when the phone has it) | The machine must be paired again. |
| `nonces.txt` | Hashes of the session nonces already seen, so that a recorded session cannot be replayed | It starts again, empty. |
| `sync.json` | The ledger, the point to resume from, the last report, the settings | Reported, never silently replaced. The user chooses **Start again**; every night is then cleared and written afresh under the same names. |
| `nights.json` | The nights | Set aside under another name and rebuilt from the machine. |

Each file is replaced whole and atomically. A crash leaves the old state or the new one,
never a mixture.

### Coming home

![PAPRelay synchronises when you tap the button, when the phone comes near the machine, and about every hour.](images/when.svg)

A run starts in one of three ways (`app/sync/SyncRunner`, `SyncWorker`):

1. The user taps **Synchronise now**.
2. The phone hears the machine. `ble/PresenceWatch` asks the phone's Bluetooth chip to
   listen for the one stored address at low power and to tell the app once when it is first
   heard. This is what brings a night across soon after the phone comes home.
3. About once an hour, through WorkManager. This is the backstop: not every phone supports
   the listening in point 2.

Being out of range is the ordinary state of affairs, not an error (`MACHINE_AWAY`). A run
that is cut off resumes at its last checkpoint. A run is limited to eight minutes; a longer
backlog continues in the next run. A session that ended less than ten minutes ago is left
for the next run, because the machine may still be adding to it.

The machine accepts one connection at a time. `ble/MachineClaim` makes sure that a device
check, a synchronisation and forgetting the machine never overlap.

### Health Connect

PAPRelay holds **write** permissions only. It reads nothing from Health Connect; it finds
its own records by the names it gave them.

- **Respiratory rate** is a native Health Connect type (`HealthConnectSink`, through the
  Jetpack `connect-client` library).
- **Nightly figures** have no native type. From Android 16, Health Connect keeps clinical
  records in the FHIR format, and PAPRelay writes each night there as up to five FHIR R4
  Observations: usage, events, leak, pressure and breathing (`core/nights/FhirObservations`,
  `hc/HealthRecords`). Health Connect accepts only a few kinds of Observation; *laboratory*
  is the one that takes a coded number with a unit, so the records are filed as lab results.
  Each record's text says plainly that it is a CPAP machine's nightly figure. The codes are
  PAPRelay's own: the standard sleep-study codes mean something else (indices scored in a
  sleep study, for each hour of *sleep*) and are deliberately not used.

Everything PAPRelay writes to the health records sits in one data source named
"PAPRelay (CPAP machine)", so it can be told apart and removed as a whole.

### No network

The app has no `INTERNET` permission, and the automatic checks fail the build if the
release manifest ever gains one. The table of nights reaches a cloud drive only because
the user chose a place there through Android's own "save as" screen; the drive's app does
the sending.

### The debug build never synchronises

Health Connect files records under the app that wrote them. The debug build has another
application id, so to Health Connect it is a different app, and every night would be held
twice. `BuildConfig.SYNC_ENABLED` is therefore false in debug builds. They run the device
check only.

## The screens

One activity, written with Jetpack Compose (`app/ui`): Home, Nights, Sharing, Device check
and About. The picture at the top of the home screen (`FlowDiagram.kt`) shows the state of
both connections without words:

![The meaning of the lines in the picture.](images/picture.svg)

All text is in `app/src/main/res/values/strings.xml`. It is written for readers who are not
fluent in English: short sentences, plain words, no idioms.

## Tests

| Where | What is tested |
|---|---|
| `core` | Units and exact arithmetic; nights, the FHIR records and the table; the ledger rules. |
| `as11` | Framing and cryptography against known answers from independent implementations; the client against a simulated machine (`FakeAirSense11`) that can lose, repeat, reorder and damage what it sends; every decoder; both plans. |
| `app` | The Bluetooth request queue; the sync engine and its stores against fake machines and a fake Health Connect, with injected clocks: failures between steps, clock changes, software changes, refusals. |

The tests need no phone and no machine. See [BUILDING.md](BUILDING.md#3-run-the-tests).

What the tests cannot show is how a real phone and a real machine behave. That is the job
of the device check ([DEVICE-CHECK.md](DEVICE-CHECK.md)) and of the comparison with the
machine's SD card ([spike-report.md](spike-report.md)).

## Where it comes from

The protocol code is a Kotlin port of parts of SomnoTrace by Ilya Kruchinin.
[PORTING.md](PORTING.md) lists every ported file and what was changed. The layouts of the
stored logs were learned from the protocol notes of the airbreak-plus project; see
[THIRD-PARTY-NOTICES.md](../THIRD-PARTY-NOTICES.md).
