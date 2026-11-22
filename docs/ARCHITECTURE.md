# Architecture

Read this before you change the code.

PAPRelay copies what a ResMed AirSense 11 has recorded to the phone and passes it on
unchanged. A figure is passed on as the machine stored it, or not at all. Being out of
range, a lost connection, a crash or a reinstall must not lose a night, write one twice,
or leave a wrong value behind.

## Modules

![app uses as11 and core. as11 uses core.](images/modules.svg)

| Module | Kind | Contents |
|---|---|---|
| `core` | Kotlin/JVM | Measurements with their units, the machine's clock, a night and its records, the ledger rules. No Android, no Bluetooth. |
| `as11` | Kotlin/JVM | The AirSense 11 protocol: framing, pairing, the encrypted session, the request set, downloads, the decoders of the stored logs, and the plans that turn decoded records into what is passed on. Its test fixtures hold a simulated machine. |
| `app` | Android | Bluetooth, the stored pairing, Health Connect, the sync engine with its stores and background runs, and the screens. |

`core` and `as11` build and test without the Android SDK (`-Ppaprelay.skipAndroid=true`).
Most of the logic that can be wrong is there, or behind interfaces that the tests replace.

## How the data flows

![From the machine through the link, the protocol, the decoders and the plans to the sync engine, which keeps the nights, the breathing events and the mask-on times on the phone and writes to Health Connect, to two files in a folder and to the screen. The night "so far", from the mask-on times, is shown on the screen only.](images/flow.svg)

| Step | Where | What happens |
|---|---|---|
| Link | `app/ble/GattLink`, `GattOperations` | One Bluetooth (GATT) connection as a byte pipe. One request in flight at a time, each matched to its own completion. A connection is never reused. |
| Protocol | `as11/As11Client`, `Fig`, `Srp6a`, `SessionCrypto`, `As11Request` | Frames with two checksums. SRP-6a pairing with the code from the machine's screen. An AES session that the machine must prove. Downloads in rounds, each verified by its SHA-256. |
| Decoders | `as11/decode` | Turn the downloaded bytes into records. Layouts and scale factors are in one file, `As11Scales`. |
| Plans | `as11/plan` | `NightPlan` turns one daily summary into a `Night`. `EventPlan` turns one breathing-event record into a `BreathingEvent`. `RespiratoryRatePlan` turns one per-minute record into readings. `ClockCorrection` is the one rule that puts a machine time into true time. |
| Sync engine | `app/sync/SyncEngine` | Decides what to download, what is new or changed, and what to write. Handles every failure case. |
| Outputs | `app/hc`, `app/sync/HealthConnectSink`, `ExportFolder`, `app/ui` | Health records and respiratory rate in Health Connect, the two tables (`core/nights/NightReport`, `EventReport`) in a folder, and the screens. |
| Stored state | `app/pairing`, `app/sync/SyncStore`, `NightStore`, `EventStore` | See [Stored state](#stored-state). |

PAPRelay can request four of the machine's stored logs:

| Log | Holds | Used by |
|---|---|---|
| `Summary` | One record for each day, midday to midday: usage, indices, percentiles | The sync engine, for the nightly figures |
| `TherapyOneMinutePeriodic` | One sample for each minute of therapy | The sync engine, for respiratory rate |
| `UsageEvents-TherapyStatusEvents` | Mask on and off | The sync engine, for the morning preview; and the device check |
| `TherapyEvents-RespiratoryEvents` | Each breathing event | The sync engine, for the list of events; and the device check |

PAPRelay passes on a day's summary only after that day has ended, at midday by the
machine's clock. This is why a night's figures arrive in the afternoon.

The nights' sessions come from the daily summary, where they were compared with the SD
card. The mask-on and mask-off log is read only for the morning preview (below). Its
`MASK_ON` and `MASK_OFF` are confirmed: `UsageCheck`, run by the real-data probe, found every
session of the first device run's capture to match the summary's sessions. Mask on is a
session's start; the log's other kinds are not used, and with either of the two
unconfirmed the sync would not ask for the log at all.

### The morning preview

The machine sums a night up only after the next midday. Until then `NightPreview` (core)
gives the night after the newest finished one, midday to midday by the machine's clock at
the offset of the night before: its mask-on sessions from the usage log, the whole minutes
of use so far (a session still on is counted up to when the log was last read whole, and
said to have not ended then; marks that do not pair up give no sum), and the night's
breathing events, which are kept as all events are. A record of the log that cannot be
read is kept as a gap, from the last record read before it to the first read after; a night
a gap reaches gets no total, and a session still on there no time beyond its start. A
mask-off at the start of the night ends the night before's session only when a mask-on
before midday is there for it to close. A failing record holds the place to resume from
for at most the two days the preview can need. Nothing is worked out from them: no index, no
rate. The usage marks are kept in `usage.json` (the last night or two, each once,
corrected by `ClockCorrection` like the events) and read from a little before where the
last run reached. Reading them is the last step of a run; a failure of it keeps nothing
else from the phone, and only a lost link is said.

The preview is for the screen only. It is not a `Night`, so it never reaches the health
records or the table of nights, and its events lie after the newest finished night, which
`EventReport.shown` leaves out of the events table. When the machine's own night for the
date arrives, the preview moves on to the next night. Home shows the newest finished night
at all times, with the preview, dated and marked "so far", above it.

Known limits:

- A mask-on with no mask-off yet is counted up to the last read. After a power cut, which
  may leave no mask-off, the time can be too long until a mask-off or the machine's own
  night arrives.
- The preview's day is the night before's offset and 24 hours. On a day the clocks change,
  its far end is an hour off until the machine's own night arrives.

A run reads the logs in this order: the summaries (the nights), the per-minute log if
respiratory rate is switched on, then the breathing events. Whatever stops respiratory rate
(Health Connect missing or not allowing it, a changed clock) or the events (a log that
cannot be read) is said at the end of the run, so neither keeps the other, or the nights,
from the phone. Only a lost connection ends the reading; the next run takes it up.

## Design decisions

### A closed request set

The machine's encrypted channel also accepts commands that change settings, start therapy
or erase data. PAPRelay cannot send them: `As11Request` is a sealed class, and the session
encrypts nothing else. The items it can read (`GetItem`) and the logs it can download
(`SpoolName`) are closed lists. `As11RequestTest` pins the request types.

No request changes therapy, settings or stored data. The pairing on the machine is changed
by the key exchange (`StartKeyExchange`, `ConfirmKeyExchange`), which stores PAPRelay's
pairing. `DiscardPairKey` would remove it; the app does not send it at present. **Forget
machine** deletes the pairing on the phone only.

The machine keeps one Bluetooth pairing at a time, so it must be removed from the myAir app
before PAPRelay can pair (source: [SomnoTrace's pairing guide](https://github.com/ilyakruchinin/somnotrace/blob/main/docs/pairing.md),
"Before You Start"). Whether pairing myAir again later drops PAPRelay's pairing is still
open (see [FIRST-DEVICE-RUN.md](FIRST-DEVICE-RUN.md#still-open)).

### Values

A measurement is kept as the integer that the machine stored, with its scale and unit
(`core/Quantity`). A night's figures are whole hundredths (`core/nights/Night`). Runs are
compared on these integers, so two runs over the same data give the same result.

PAPRelay does not estimate or score, and does not recompute a clinical index such as AHI.
A value that fails a check is left out. An empty cell is not a guessed zero.

Three things are derived from the stored integers:

- Leak, respiratory flow and blower flow are converted from litres per second to litres
  per minute (times 60), and tidal volume from litres to millilitres (times 1000). Both
  are exact in hundredths.
- Hours of use is the stored minutes divided by 60, rounded to two decimals. The exact
  minutes are written next to it.
- Health Connect takes respiratory rate as a floating-point number, so the stored
  hundredths are divided by 100 at the last step (`RateReading.breathsPerMinute`).

### Machine software versions

The manufacturer does not publish the layouts of the stored logs. They were confirmed
against the files on the machine's SD card, on software 19.8.8.0. Data is also read from
every other version whose identifier has the form of the compared machine's (`SW04600`, a
version, a build), on the assumption that it is laid out the same (`Firmware` in
`As11Scales.kt`). The assumption is not relied on alone.

The daily summary is made of tagged fields. Each value is checked before it is passed on.
A day of the wrong length, or with a session count that does not match, is left out whole.
A figure above its upper limit, or a metric that does not have exactly the percentiles
known for it, is left out alone.

The per-minute log is compressed with a constant for each signal. With a wrong constant it
can decode to values that are wrong but plausible, mostly in short sessions. So the engine
writes no session until one long session has decoded cleanly under the software the
machine reports. The code calls this "vouching", and it is asked for again whenever the
identifier changes.

On the compared version a session of 30 minutes or more vouches. After a change of
identifier it must be a session that began after the change was seen.

On any other version the rule is stricter, and it does not rely on the machine's clock:

- **Which sessions are its own.** The first time the engine reads the per-minute log to the
  end under that version, everything the machine then holds counts as older: an earlier
  version may have recorded it. The log is read only while respiratory rate is switched on,
  so nights recorded before it is first switched on under that version count as older too. The software's own sessions are those that begin after the
  last minute present then. (Not after "now": a clock that was once set back leaves
  sessions stamped later than now.)
- **What vouches.** One of its own sessions, 60 minutes or more, in which all seven known
  signals are readable, and which is not stamped later than now. With a wrong constant the
  signals then practically always disagree about the session's length.
- **Older sessions.** They are never newly written. A session that PAPRelay already wrote
  under trusted software stays in its care: it is written again after "Write everything
  again", and a write that was cut short is finished.
- **Damaged records.** A record that cannot be decoded is dated by its own start time, or
  else by the records around it. One from before the software's own sessions is ignored;
  one among them is reported like any night that cannot be read.
- **Changed clock.** When the user accepts a changed clock, what is the software's own
  stays as it was. If the clock was set back past that point, it is settled again, and the
  software must be vouched for again.

Until the software is vouched for, respiratory rate waits. Waiting is not a failure: the
run ends as complete (unless a record could not be read), and the home screen and the
Sharing page say that respiratory rate starts after a night of an hour or more.

Software whose identifier has another form is not read: the engine stops with
`SOFTWARE_NOT_CONFIRMED` and writes nothing.

Known limits:

- The checks test the framing, not the meaning. A version that keeps the fields and the
  constants and changes a scale would pass. Only a comparison with a second source shows
  that.
- The machine does not say which software recorded a session. On the compared version,
  sessions that an earlier version recorded are read with its constants.
- If another version does use other constants, no session ever vouches, and respiratory
  rate keeps waiting. If its records fail to decode, runs also report that not every night
  could be copied. The user's way out is to switch respiratory rate off. The nightly
  figures are not affected.
- If the machine's clock is set back soon after the engine settled which sessions the new
  software owns, sessions recorded in the following hours may be stamped before that point
  and are then not written.

### Clocks

The machine's clock drifts by minutes. Every connection measures the difference between
the machine's clock and the phone's (`MachineTime`, `ClockOffset`).

- Respiratory-rate readings are written at the corrected time.
- A night is dated by the machine's own local day, taken from the start of its summary
  period. The date does not move when the phone travels.
- If the machine's clock was changed, the engine stops writing respiratory rate and asks
  the user (`CLOCK_CHANGED`). The nightly figures are still written, because they are dated
  by the machine's calendar. If a different day claims a date that is already kept, the
  kept night stays and the new one is not taken. If two different days in one download
  claim the same date, neither is taken. The clock comparison is made only while
  respiratory rate is switched on.
- If the phone's own clock is clearly wrong, nothing is written (`PHONE_CLOCK_WRONG`).
- Session starts and breathing events keep the machine's own time, and beside it a time
  corrected to the phone's clock (`as11/plan/ClockCorrection`). A time is corrected only
  when it is within a week of the measurement (the drift since is then under half a
  minute), when the result is not in the future, and never in a run that finds the clock
  changed: since the last run that read the events (`SyncState.correctionClock`, moved by
  every such run whatever is switched on), or, while respiratory rate is switched on,
  since its own baseline, while that change waits for the user. Otherwise the corrected time is left empty. A
  corrected time is given once, when the session or the event is first copied, and kept:
  later runs measure a slightly different offset and do not change it.
- An event belongs to the night whose day (local midday to midday, as in the summary)
  holds its time stamp (`EventReport.nightOf`). A day where the clocks changed is 23 or
  25 hours long, as the machine said. Events after the newest finished night (the day
  still running) are kept, and left out of the file and the screen until that night is in
  the table of nights (`EventReport.shown`). An event outside every night is written at
  the offset of the nearest night.

### No duplicates: fixed names and a ledger

Every record has a name that is derived from the machine and the time, not from the run:

- a health record: `<machine tag>-<date>-<group>`, for example `…-20261114-leak`;
- a respiratory-rate reading: `rr-<machine tag>-<time of the minute>`.

Writing a record again replaces it, so a reinstall or a repeated run cannot produce
duplicates.

The machine tag is derived from the serial number by a slow one-way function. The full
serial number is not written to Health Connect, to the files or to a report. The saved
report of the device check shows its last four characters.

For respiratory rate the engine keeps a ledger (`core/sync/Reconcile`, `LedgerEntry`):
for each session, what Health Connect may hold. The ledger is written before Health Connect
is touched and again after it has answered. After a crash, the next run compares what
should be there with what may be there, and removes or rewrites the difference.

For the nightly figures, `NightStore` keeps each night with a mark of what was last
written for it (the record format and a fingerprint of what goes to the health records,
`Night.healthFingerprint`). A night whose mark differs is written again, and records that
a night no longer has are removed. A change in a figure that describes the machine, or in
the sessions, does not write the records again.
`FhirObservations.FORMAT` is raised whenever the records change shape, so every night is
then written again over the same ids.

`NightPlan.VERSION` follows the set of figures a night can have, and `nights.json` keeps
the version its nights were read under. Under another version, the next run reads the
summaries again from 400 days back (as on the first run), once, so the nights the machine
still holds gain what they lacked. A figure whose metric is not confirmed is named in `NightFigure` and mapped
in `NightPlan`, but never given: confirming it is the change of its flag in `As11Scales`,
and the version, the table's columns and the records follow.

A breathing event's identity is its kind, its time stamp and its length, all as stored.
Each run reads the event log from a day before where the last one reached, and an event
already kept is not added again (and keeps the corrected time it was first given). A run
reads at most 40 rounds or 4 MB of events; a longer history is taken up by the next run.
A record that cannot be read leaves the run short, and the place to resume from stays
before it (within the hold limit), so it is read again. A run that read no event at all
leaves the place where it was.

`events.json` keeps the set of kinds passed on (`EventPlan.VERSION`) under which its
history was read. Under another set (a kind confirmed since), the log is read again from
400 days back, once. The re-reading is marked as begun, so that a history longer than one
run takes is taken up where it reached; the set is stored when the log was read to its
end. The events table says what each time is in `time_is`: `end` for an apnoea with a
stored length, otherwise `start_or_end`.

### Stored state

The app keeps these files in its private storage. Android backup is switched off.

| File | Holds | If it is missing or damaged |
|---|---|---|
| `pairing.bin` | The pairing secret, encrypted with a key in the Android Keystore (StrongBox when the phone has it) | The machine must be paired again. |
| `nonces.txt` | Hashes of the session nonces already seen, so that a recorded session cannot be replayed | Missing: it starts empty. Unreadable: the session fails with a storage error. Damaged lines are dropped. |
| `sync.json` | The ledger, the point to resume from, the last report, the Sharing settings | Missing: PAPRelay starts as if new, with the Sharing settings at their defaults. Damaged: reported, not replaced. The user chooses **Start again**, which deletes the file. |
| `nights.json` | The nights, with their sessions and the plan version they were read under | Damaged: renamed and kept; the list is built again from the machine. Written by a version before 0.3.0: deleted, and built again. |
| `usage.json` | The mask-on and mask-off marks of the last night or two, the point to resume from, and when the log was last read whole | Missing or damaged: dropped, and read again from the machine. Nothing else depends on it. |
| `events.json` | The breathing events of the kinds passed on, one line each, the point to resume from, and the set of kinds its history was read under | Damaged: renamed and kept; the list is built again from the machine. Bounded by the nights kept and by 250,000 events. |
| `raw/`, `check/` | The downloaded bytes of recent runs and of the last device check, unencrypted | Not needed for synchronising. Deleted by **Forget machine**. |

The six named files are each replaced whole and atomically (`AtomicFiles`). A crash leaves the old state
or the new one.

### When a run starts

`app/sync/SyncRunner` and `SyncWorker` start a run:

1. when the user taps **Synchronise now**;
2. when the phone hears the machine. `ble/PresenceWatch` asks the phone's Bluetooth chip to
   listen for the one stored address at low power and to tell the app once when it is first
   heard;
3. about every hour, through WorkManager. This is the fallback, because not every phone
   supports point 2.

A run also starts when the app is opened and the last run is older than 15 minutes, and
when a Sharing switch is turned on. One more run follows a background run that lost the
connection, a run where the machine was heard but did not answer, and (after about eleven
minutes) a run that left out a session that had only just ended.

Being out of range is normal, not an error (`MACHINE_AWAY`). A run that is cut off resumes
at its last checkpoint. A run is limited to eight minutes; a longer backlog continues in
the next run. A session that ended less than ten minutes ago is left for the next run,
because the machine may still be adding to it.

The machine accepts one connection at a time. `ble/MachineClaim` keeps a device check, a
synchronisation and forgetting the machine from overlapping.

### Health Connect

PAPRelay holds write permissions only. It reads no health data; it finds its own records
by the names it gave them.

Respiratory rate is a native Health Connect type (`HealthConnectSink`, through the Jetpack
`connect-client` library).

The nightly figures have no native type. From Android 16, Health Connect keeps clinical
records in the FHIR format, and PAPRelay writes each night there as up to five FHIR R4
Observations: usage, events, leak, pressure and breathing (`core/nights/FhirObservations`,
`hc/HealthRecords`). Health Connect accepts only a few kinds of Observation. *Laboratory*
is the one that takes a coded number with a unit, so the records are filed as lab results,
and each record's text says that it is a CPAP machine's nightly figure. The codes are
PAPRelay's own. The standard sleep-study codes mean indices for each hour of *sleep*,
scored in a sleep study, and are not used.

The leak record carries the median, 70th and 95th percentile and maximum; the breathing
record carries respiratory rate, tidal volume and minute ventilation (median, 95th
percentile, maximum) and respiratory flow (5th and 95th percentile). The figures that
describe the machine rather than the person (humidifier, heated tube, blower;
`NightFigure.healthRecord` false) and the breathing events go to the files only.

Everything PAPRelay writes to the health records is in one data source named
"PAPRelay (CPAP machine)", so it can be told apart and removed as a whole.

### No network

The app has no `INTERNET` permission, and the automatic checks fail if the release manifest
gains one. The two files reach a cloud drive only when the user chose a folder there
through Android's own folder screen. The drive's own app does the sending.

### The folder

The user chooses a folder once (`OpenDocumentTree`, a persisted grant). In it PAPRelay
finds its two files by exact name, `paprelay_nights.csv` and `paprelay_events.csv`, or
makes them, and writes each whole, each on its own (modes `"rwt"`, `"wt"`, then `"w"`; a
`"w"` write to a file on the phone is cut to its new length). It reads no file there and
lists names only. A file is made only when the folder was listed whole: a listing that
failed, or that the provider marks as still loading, makes nothing, so no second file
appears beside the first. A folder still loading (a cloud drive just chosen) is listed
again for about two seconds; if it is still loading then, the files wait for the next run,
and this is not reported as a failure. These decisions are in `FolderWrites`, apart from Android, and
tested. A folder of this kind offers no atomic replace; a file left short by a failed
write is written whole again by the next run. Nothing is written while there is no finished night, so an empty table never
replaces a full one. A file chosen by a version before 0.3.0 is not used.

### The debug build does not synchronise

`BuildConfig.SYNC_ENABLED` is false in debug builds, so they run the device check only.
[BUILDING.md](BUILDING.md#the-debug-build) gives the reason.

## Screens

`MainActivity` holds the pages Home, Nights, Breathing events, Sharing, Device check and
About, written with Jetpack Compose (`app/ui`). Nights (`NightScreens.kt`) is a pager of
single finished nights, each with a line drawing of the night and every figure by section;
its rows are listed in the screen's code, not taken from `NightFigure`, and a test holds
them to what `NightPlan` gives. Breathing events is a lazy list grouped by night, built a
few nights at a time. It is the one page reachable from two places (Home, and a night);
Back returns to where it was opened. Sharing happens only on the Sharing page. The
morning preview, where there is one, is the last page of Nights (`NightPageItem`), takes the
night summary's place on Home, and heads the list of events, each marked "so far"; a night
and a preview draw their picture from the same `TimelineModel`. With no
pairing to use, **Pair the machine** first opens a page with the steps
(`PairStepsScreen`); the device check, and its search for the machine, starts only when
the user says that the machine shows its serial number (as it does in pairing mode; the
code appears only once the phone has connected and started pairing), and backing out of
the code leads to that
page again (`PairingFlow`). `PrivacyActivity` is the page that Health Connect opens for
the privacy text. The picture at the top of the home screen and of the device check is
`FlowDiagram.kt`; the README explains its lines. Which state each of its two lines is in is
worked out in `Links.kt`, apart from the screens, so it can be tested: during and after a
check from the steps, otherwise from synchronising, and on the home screen from whichever of
the two ended last. Pairing runs on the home screen, so the picture can be watched.

Home reaches the other pages through rows with drawn glyphs (`Glyphs.kt`); About is the
**i** in the top bar. All drawings are made in code; the app has no icon library.

All text is in `app/src/main/res/values/strings.xml`. The top of that file has the wording
rules.

## Tests

| Module | What is tested |
|---|---|
| `core` | Units and exact arithmetic; nights, the FHIR records and the two tables, events and the night each belongs to; the ledger rules. |
| `as11` | Framing and cryptography against known answers made with a separate Python script and OpenSSL (`tools/gen_protocol_vectors.py`); the client against a simulated machine (`FakeAirSense11`) that can lose, repeat, reorder and damage what it sends; every decoder; every plan and the clock correction. |
| `app` | The Bluetooth request queue; the sync engine and its stores against a fake machine and a fake Health Connect, with injected clocks: failures between steps, clock changes, software changes, refusals; the breathing events read again without duplicates; the two tables; the rows of the night page. |

The tests need no phone and no machine. See [BUILDING.md](BUILDING.md#3-run-the-tests).

The tests cannot show how a real phone and a real machine behave. That is what the device
check ([DEVICE-CHECK.md](DEVICE-CHECK.md)) and the comparison with the machine's SD card
([FIRST-DEVICE-RUN.md](FIRST-DEVICE-RUN.md)) are for.

The protocol code is ported from SomnoTrace; see [PORTING.md](PORTING.md).
