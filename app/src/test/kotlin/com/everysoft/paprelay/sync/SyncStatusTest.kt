// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncStatusTest {
    private fun status(respiratoryRate: Boolean, rateReadable: Boolean) =
        SyncStatus(running = false, lastReport = null, lastCompleteAtMs = null, storeUnreadable = false, respiratoryRate = respiratoryRate, rateReadable = rateReadable)

    @Test
    fun `respiratory rate is readable until the machine is seen to run software it is not read from`() {
        assertTrue(rateReadableFrom(null))
        assertTrue(rateReadableFrom("SW04600.19.8.8.0.dced143ae"))
        // Another version, whose nightly figures are read.
        assertFalse(rateReadableFrom("SW04600.20.1.0.0.abcdef"))
        assertFalse(rateReadableFrom("SW04600.19.8.9.0.dced143ae"))
    }

    @Test
    fun `respiratory rate is wanted only when it is switched on and can be read`() {
        assertTrue(status(respiratoryRate = true, rateReadable = true).rateWanted)
        assertFalse(status(respiratoryRate = true, rateReadable = false).rateWanted)
        assertFalse(status(respiratoryRate = false, rateReadable = true).rateWanted)
        // A change of something else leaves both as they were.
        val kept = status(respiratoryRate = true, rateReadable = false).with(running = true)
        assertTrue(kept.respiratoryRate && !kept.rateReadable && !kept.rateWanted)
    }
}
