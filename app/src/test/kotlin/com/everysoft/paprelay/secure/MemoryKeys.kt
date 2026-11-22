// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.secure

import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** A key in memory, in place of the phone's secure hardware. [failing] plays a keystore having a bad moment. */
class MemoryKeys : SecretKeys {
    var key: SecretKey? = null
    var deletes = 0
    var failing: Exception? = null

    override fun existing(): SecretKey? {
        failing?.let { throw it }
        return key
    }

    override fun getOrCreate(): SecretKey = existing() ?: KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also { key = it }

    override fun delete() {
        deletes++
        key = null
        // A new key has none of the old one's faults.
        failing = null
    }
}
