// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.ui

import com.everysoft.paprelay.pairing.PairingState
import kotlin.test.Test
import kotlin.test.assertEquals

class PairingFlowTest {
    @Test
    fun `with no pairing to use, the steps come before any search, and backing out of the code leads to them again`() {
        assertEquals(PairingFlow.Next.SHOW_STEPS, PairingFlow.onCheckTapped(PairingState.Absent))
        assertEquals(PairingFlow.Next.SHOW_STEPS, PairingFlow.onCheckTapped(PairingState.Unreadable))
        // Paired, or Bluetooth not there to read it: the check at once, as before.
        assertEquals(PairingFlow.Next.START_CHECK, PairingFlow.onCheckTapped(PairingState.Unavailable))
        // Not yet known: the steps, never a search for a machine that may not be in pairing mode.
        assertEquals(PairingFlow.Next.SHOW_STEPS, PairingFlow.onCheckTapped(PairingState.Loading))
        assertEquals(PairingFlow.Next.SHOW_STEPS, PairingFlow.afterCodeCancelled())
    }
}
