// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.ui

import android.view.View

/**
 * Keeps a view and everything in it away from autofill services while it is held, and gives the
 * view back the setting it had when [release]d. The setting is read and written through [read] and
 * [write], so that the rule can be tested without a view.
 */
internal class AutofillExclusion(read: () -> Int, private val write: (Int) -> Unit) {
    private val before = read()

    init {
        write(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS)
    }

    fun release() = write(before)
}
