## What changes, and why

<!-- A few short sentences. Link the issue if there is one. -->

## How it was tested

<!-- Which tests are new. If a real device was involved: the machine software and the phone. -->

## Checklist

- [ ] `./gradlew :core:test :as11:test :app:testDebugUnitTest :app:lintDebug :app:lintRelease :app:assembleRelease` ends with `BUILD SUCCESSFUL`.
- [ ] A fix has a test that fails without it. New logic has tests for the bad cases.
- [ ] PAPRelay still sends the machine no request that changes therapy, settings or data.
- [ ] No value is computed, estimated or rounded. A value is exact, or it is left out.
- [ ] No health value, serial number, Bluetooth address or key can reach a log or a report.
- [ ] No capture, SD-card file or signing key is in this pull request.
- [ ] New text on the screen follows the wording rules in CONTRIBUTING.md.
- [ ] I read CONTRIBUTING.md and docs/ARCHITECTURE.md.
