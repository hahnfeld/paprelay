# Porting record

PAPRelay's `as11` module is derived from SomnoTrace
(https://github.com/ilyakruchinin/somnotrace), Copyright (C) 2026 Ilya Kruchinin,
GPL-3.0-or-later with the section 7(b) attribution term reproduced in `NOTICE`.

Upstream revision read for this port: `f68cb7a1d22c13c907f04978ba11a64fc13f0c5d`.

| PAPRelay file | Derived from (SomnoTrace) | Changes |
|---|---|---|
| `as11/.../Fig.kt` | `main/as11_ble.c`: `fig_encode`, `fig_take_packet` | Kotlin port. Payload CRC verified on receive (upstream checks the header CRC only). Declared lengths bounded. Resynchronisation advances one byte so frames after a truncated one are still found. |
| `as11/.../Srp6a.kt` | `main/as11_ble.c`: `srp_begin`, `srp_compute` | Kotlin port. Server public value range-checked, scrambling parameter checked for zero, device-supplied lengths bounded, proofs kept as bytes. |
| `as11/.../SessionCrypto.kt` | `main/as11_ble.c`: `aes_cbc_encrypt`, `aes_cbc_decrypt`, `do_setup_encrypted_session` | Kotlin port. Declared length validated; padding canonicality reported. |
| `as11/.../As11Request.kt` | `main/as11_ble.c`, `main/post_therapy.c`: request strings and identity keys | Rewritten as a sealed, read-only command set. Commands that control the machine are deliberately absent. |
| `as11/.../As11Client.kt` | `main/as11_ble.c`: `pair_task`, `confirm_task`, `do_setup_encrypted_session`, `handle_notify`, `get_datetime_impl`, `spool_one_round` | Kotlin coroutine port. Request ids matched; server proof compared in constant time; a missing download hash is fatal (upstream warns); plaintext ignored after session start; replayed session nonces refused; a frame cut short by a lost chunk is abandoned after a quiet period; sends are time-limited; a damaged download round fails alone and can be pulled again; a run of undecodable encrypted frames ends the conversation. |

| `as11/.../Advertisement.kt` | `main/as11_adv.c`: `as11_adv_salvage` | Kotlin port returning decoded values, plus a raw-byte fallback for payloads where a scan response follows the malformed field. |
| `app/.../ble/GattLink.kt` | `main/as11_ble.c`: `do_connect_and_discover`, `send_fig` (GATT layout and write-chunking rules) | Written for Android's GATT API. One operation in flight; writes capped at MTU-3; a connection is never reused. |

Not ported: streaming, therapy control, EDF generation, uploaders, oximeter
support, display, audio, and the optional SomnoStage model.

Each ported file keeps the upstream copyright and attribution in its header and
states the date and nature of the modification.

Stored-data record layouts and scale factors come from the protocol notes in
airbreak-plus (MIT), used as a reference only; see `THIRD-PARTY-NOTICES.md`.
