# Third-party notices

## SomnoTrace

- Source: https://github.com/ilyakruchinin/somnotrace (revision `f68cb7a1d22c13c907f04978ba11a64fc13f0c5d`)
- Licence: GPL-3.0-or-later with a GPLv3 section 7(b) attribution term (reproduced in `NOTICE`)
- Use: PAPRelay's `as11` module is a Kotlin port of parts of SomnoTrace's AirSense 11
  Bluetooth client. See `docs/PORTING.md`.

## airbreak-plus

- Source: https://github.com/m-kozlowski/airbreak-plus (revision `a6d1dc36728fed2795df38a9191fd5385d32fb4a`)
- Licence: MIT (`LICENSE.txt` at the repository root)
- Use: reference material only. Its protocol notes under `docs/as11/` informed
  PAPRelay's understanding of stored-data ("spool") names, record layouts and scale
  factors. No source code or prose from that project is included in PAPRelay.

Recorded in case any incidental copyrightable material is ever found to have been
incorporated:

```
Copyright 2020, Airbreak.dev contributors

Permission is hereby granted, free of charge, to any person obtaining a
copy of this software and associated documentation files (the "Software"),
to deal in the Software without restriction, including without limitation
the rights to use, copy, modify, merge, publish, distribute, sublicense,
and/or sell copies of the Software, and to permit persons to whom the
Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included
in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL
THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR
OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
OTHER DEALINGS IN THE SOFTWARE.
```

## Libraries

PAPRelay uses the following libraries directly, all under the Apache License 2.0:
Kotlin standard library and kotlinx.coroutines, kotlinx.serialization (JetBrains);
AndroidX Core, Activity, Lifecycle, Compose, WorkManager and Health Connect client
(The Android Open Source Project). They bring further AndroidX libraries with them, among
them Room and SQLite inside WorkManager.

The Apache License 2.0 is compatible with the GNU GPL version 3.

## Interoperability note

PAPRelay talks to a machine its user owns, over that machine's own Bluetooth
interface, using the pairing code the machine displays to its owner. It does not
modify the machine's firmware, does not include any ResMed code, and sends the
machine no command that changes therapy, settings or stored data. This is a
statement of fact, not legal advice.
