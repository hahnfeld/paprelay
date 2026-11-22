// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.ui

import com.everysoft.paprelay.pairing.PairingState

/**
 * Where "Pair the machine" leads, and where backing out of the code leads, apart from the screens
 * so that it can be tested. Nothing is asked of the machine until the user has seen the steps and
 * says that it is in pairing mode, where it shows its serial number. The four-digit code appears
 * on the machine only after the phone has connected and started pairing; the code prompt asks for
 * it then. A search begun before the machine is in pairing mode does not find it.
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
