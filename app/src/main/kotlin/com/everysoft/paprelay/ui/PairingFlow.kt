// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.ui

import com.everysoft.paprelay.pairing.PairingState

/**
 * Where "Pair the machine" leads, and where backing out of the code leads, apart from the screens
 * so that it can be tested. Nothing is asked of the machine until the user has seen the steps and
 * says that it shows a code: a search begun before the machine is in pairing mode finds it and asks
 * for a code it is not showing.
 */
internal object PairingFlow {
    /** What a tap on the button that starts a check does. */
    enum class Next { SHOW_STEPS, START_CHECK }

    /**
     * With no pairing to use, the steps first; with one, the check at once. While the pairing is still
     * being read, it is not known to be there, so the steps are shown.
     */
    fun onCheckTapped(pairing: PairingState): Next = when (pairing) {
        is PairingState.Paired, PairingState.Unavailable -> Next.START_CHECK
        PairingState.Absent, PairingState.Unreadable, PairingState.Loading -> Next.SHOW_STEPS
    }

    /**
     * After the code was not typed (Cancel): the steps again, so that the machine can be put in
     * pairing mode. The check ends there and stores nothing; the pairing is stored only once the
     * machine has accepted the code.
     */
    fun afterCodeCancelled(): Next = Next.SHOW_STEPS
}
