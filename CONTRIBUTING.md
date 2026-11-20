# Contributing to PAPRelay

PAPRelay talks to a medical device and writes health data, so changes are made slowly and
carefully. Please read this page first.

You do not need perfect English. Be kind and patient with each other; see the
[code of conduct](CODE_OF_CONDUCT.md).

## Ways to help

- Report a problem with the [issue form](https://github.com/hahnfeld/paprelay/issues).
  Attach the report from **Device check › Save report**.
- Tell us if PAPRelay works with your machine software and your phone. See
  [A new machine software version](#a-new-machine-software-version).
- Improve the documentation.
- Change the code. Read the rest of this page first.

For a security problem, do not open a public issue. See [SECURITY.md](SECURITY.md).

## Never share these

A capture from a real machine contains what is needed to read it, and it contains your
health data. Never put any of these into an issue, a pull request or a commit:

- a Bluetooth log (`btsnoop_hci.log`, `.pcap`, `.pcapng`) or an Android bug report;
- files from the machine's SD card;
- the app's private files (`pairing.bin`, `sync.json`, `nights.json`, the raw downloads);
- a pairing code, a serial number or a Bluetooth address;
- a signing key (`.p12`, `.jks`, `.keystore`).

The saved report from the device check is safe to share.

The automatic checks fail when a tracked file has a typical capture or key-file name. They
look at names only, and only after the push, when the file is already public. Check before
you push.

## Build and test

[docs/BUILDING.md](docs/BUILDING.md) has every step. Before you open a pull request, this
command must end with `BUILD SUCCESSFUL`:

```bash
./gradlew :core:test :as11:test :app:testDebugUnitTest :app:lintDebug :app:lintRelease :app:assembleRelease
```

The automatic checks run the same tasks. Lint warnings are errors.

Read [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) before you change the code. It gives the
reasons for the rules below.

## Rules

1. No request that changes therapy, settings or stored data. `As11Request` is a closed list.
2. PAPRelay passes on the machine's figures. It does not estimate, score, alert or advise.
   See [docs/INTENDED-USE.md](docs/INTENDED-USE.md).
3. A value is exact or absent. No guessed zero. The few derived values are listed in the
   architecture page; do not add to them without a reason.
4. A layout, a scale factor or a software version counts as confirmed only after it was
   compared with a second source. Say in the pull request which one.
5. Every write can be repeated. Records have fixed names. State is stored before Health
   Connect is touched.
6. No network permission, no analytics, no crash reporting service.
7. Write-only access to Health Connect.
8. No health value, full serial number, Bluetooth address or key in a log, a report or an
   error message.
9. A fix has a test that fails without it. New logic has tests for the bad cases.

## Style

- Kotlin, official code style. Follow the code around your change.
- Every source file starts with `// SPDX-License-Identifier: GPL-3.0-or-later`.
- Comments say why, not what.
- A new library needs a reason and a licence that is compatible with the GPL, version 3.
  Add it to [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).
- Files ported from SomnoTrace keep their header. Add a line to
  [docs/PORTING.md](docs/PORTING.md) when you port more.

### Words on the screen

Many users do not read English well, and the text will be translated. For every sentence
in `strings.xml`:

- Use short sentences and plain words. Give every sentence a subject.
- Use no idioms, no jokes and no abbreviations (AHI and CPAP are allowed).
- Use the same word for the same thing everywhere: *machine*, *phone*, *night*,
  *synchronise*.
- Prefer a picture to a sentence.

The pictures in `docs/images` are made by a script and use the shapes of the app's own
picture (`FlowDiagram.kt`). Change the script, then run it:

```bash
python3 tools/gen_doc_diagrams.py
```

## Pull requests

1. For anything larger than a small fix, open an issue first.
2. Make a branch from `main`. One subject for each pull request.
3. Say in the pull request how you tested. If a real device was involved, name the machine
   software and the phone.
4. Wait for the automatic checks to pass.

The maintainer reviews every change and looks for wrong values, lost or doubled records,
and anything that could reach the machine. This can take a while.

Your contribution is licensed under the GNU General Public License, version 3 or later,
like the rest of PAPRelay.

## A new machine software version

PAPRelay refuses machine software that has not been checked. If you see "PAPRelay cannot
yet read this machine's software version", you can help to add it.

1. Open an issue with the form "Report a machine or a phone". Attach the saved report from
   **Device check**. The report names the software version.
2. Do not attach downloads from the machine or SD-card files. They are your health data.
3. To confirm a version, someone must compare what PAPRelay decodes with a second source
   for the same nights. The usual second source is the machine's SD card, read with
   [OSCAR](https://www.sleepfiles.com/OSCAR/). You do this on your own computer. You share
   only the result: which kinds of figures you compared, and whether they were equal.
4. The version is then added to `CONFIRMED` in
   `as11/src/main/kotlin/com/everysoft/paprelay/as11/decode/As11Scales.kt`, with a test.

[docs/FIRST-DEVICE-RUN.md](docs/FIRST-DEVICE-RUN.md) shows such a comparison.
