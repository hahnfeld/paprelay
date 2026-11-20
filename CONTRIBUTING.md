# Contributing to PAPRelay

Thank you for helping. PAPRelay talks to a medical device and writes health data, so
changes are made slowly and carefully. Please read this page first.

You do not need perfect English. Short, simple sentences are welcome.

## Ways to help

- **Report a problem.** Use the [issue form](https://github.com/hahnfeld/paprelay/issues).
  Attach the report from **Device check › Save report**.
- **Report your machine and phone.** Tell us if PAPRelay works, or does not work, with
  your machine software and your phone. See
  [A new machine software version](#a-new-machine-software-version).
- **Improve the documentation.** Clearer words and clearer pictures are always welcome.
- **Change the code.** Read the rest of this page.

For a security problem, do not open a public issue. See [SECURITY.md](SECURITY.md).

## Never share these

A capture from a real machine contains what is needed to read it, and it contains your
health data. **Never** put any of these into an issue, a pull request or a commit:

- a Bluetooth log (`btsnoop_hci.log`, `.pcap`, `.pcapng`) or an Android bug report;
- files from the machine's SD card;
- the app's private files (`pairing.bin`, `sync.json`, `nights.json`, the raw downloads);
- a pairing code, a serial number or a Bluetooth address;
- a signing key (`.p12`, `.jks`, `.keystore`).

The saved report from the device check is made to be shared. It holds no pairing key, no
full serial number and no Bluetooth address.

The automatic checks reject a commit that contains a capture or a key file.

## Build and test

[docs/BUILDING.md](docs/BUILDING.md) has every step. Before you open a pull request, this
command must end with `BUILD SUCCESSFUL`:

```bash
./gradlew :core:test :as11:test :app:testDebugUnitTest :app:lintDebug :app:lintRelease :app:assembleRelease
```

It is the same command that the automatic checks run. Lint warnings are errors.

Read [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) before you change the code. It explains
the reasons behind the rules below.

## Rules that do not bend

1. **Read-only.** PAPRelay sends the machine no command that changes therapy, settings or
   stored data. `As11Request` is a closed list. A pull request that adds a request needs a
   very good reason, and one that adds a controlling request will not be merged.
2. **The machine's own figures.** PAPRelay does not compute, estimate, score or interpret.
   It does not raise alerts or give advice. See [docs/INTENDED-USE.md](docs/INTENDED-USE.md).
3. **Exact, or absent.** A value is passed on exactly as stored, with its unit, or it is
   left out. No floating-point arithmetic on stored values. No guessed zero.
4. **Only checked layouts.** A layout, a scale factor or a software version is marked as
   confirmed only when it was compared with a second, independent source (for example the
   machine's SD card). Say in the pull request how it was confirmed.
5. **Nothing twice.** Every record has a fixed name. Every write can be repeated safely.
   State is stored before the outside world is touched, so that a crash at any point is
   repaired by the next run.
6. **No network.** No `INTERNET` permission, no analytics, no crash reporting service.
7. **No read access to Health Connect.** Write permissions only.
8. **Nothing private in logs or reports.** No health value, bedtime, serial number,
   Bluetooth address or key may reach a log, a report or an error message.
9. **Tests come with the change.** A fix has a test that fails without it. New logic has
   tests for the bad cases, not only for the good case.

## Style

- Kotlin, official code style. Follow the code around your change.
- Every source file starts with the licence line:
  `// SPDX-License-Identifier: GPL-3.0-or-later`
- Comments say *why*, not *what*.
- Few dependencies. A new library needs a reason and a licence that is compatible with
  the GPL, version 3. Add it to [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).
- Files ported from SomnoTrace keep their header. Add a line to
  [docs/PORTING.md](docs/PORTING.md) when you port more.

### Words on the screen

Many users do not read English well, and the text will be translated. For every sentence
in `strings.xml`:

- Use short sentences and plain words. Give every sentence a subject.
- Use no idioms, no jokes and no abbreviations (AHI and CPAP are allowed).
- Use the same word for the same thing everywhere: *machine*, *phone*, *night*,
  *synchronise*.
- Prefer a picture to a sentence. The line pictures in the app and in this documentation
  use one visual language; see `FlowDiagram.kt` and `tools/gen_doc_diagrams.py`.

The pictures in `docs/images` are generated. Change the script, then run it:

```bash
python3 tools/gen_doc_diagrams.py
```

## Pull requests

1. Open an issue first for anything larger than a small fix. This avoids wasted work.
2. Make a branch from `main`. Keep one subject for each pull request.
3. Write commit messages that say what changed and why.
4. Fill in the pull request form. Say how you tested, and on which machine software and
   phone if a real device was involved.
5. Wait for the automatic checks to pass.

**Review.** Every change to the code is reviewed twice, independently, before it is
merged. The reviewers look for wrong values, lost or doubled records, and anything that
could reach the machine. This takes time. Thank you for your patience.

By contributing, you agree that your contribution is licensed under the GNU General Public
License, version 3 or later, as the rest of PAPRelay is.

## A new machine software version

PAPRelay refuses machine software that has not been checked. If you see "PAPRelay cannot
yet read this machine's software version", you can help to add it.

1. Open an issue. Attach the saved report from **Device check**. The report names the
   software version.
2. Do **not** attach downloads from the machine or SD-card files. They are your health
   data.
3. To confirm a version, someone must compare what PAPRelay decodes with a second source
   for the same nights. The usual second source is the machine's SD card, read with
   [OSCAR](https://www.sleepfiles.com/OSCAR/). You do this on your own computer; only the
   result is shared: which figures were compared, how many, and whether they were equal.
4. The version is then added to `Firmware.CONFIRMED` in
   `as11/src/main/kotlin/com/everysoft/paprelay/as11/decode/As11Scales.kt`, with a test.

[docs/spike-report.md](docs/spike-report.md) shows what such a comparison looks like.

## Conduct

Be kind and patient. See [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md).
