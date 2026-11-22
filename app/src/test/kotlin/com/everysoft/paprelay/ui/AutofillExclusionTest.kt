// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.ui

import android.view.View
import kotlin.test.Test
import kotlin.test.assertEquals

/** The form is kept from autofill while it is shown, and the screen gets its own setting back after. */
class AutofillExclusionTest {
    @Test
    fun `the view is excluded while held, and given back what it had`() {
        for (had in listOf(View.IMPORTANT_FOR_AUTOFILL_AUTO, View.IMPORTANT_FOR_AUTOFILL_YES, View.IMPORTANT_FOR_AUTOFILL_NO)) {
            var setting = had
            val exclusion = AutofillExclusion({ setting }, { setting = it })
            assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS, setting)
            exclusion.release()
            assertEquals(had, setting)
        }
    }

    @Test
    fun `a form shown again, as after a rotation, still gives back the first setting`() {
        var setting = View.IMPORTANT_FOR_AUTOFILL_AUTO
        val first = AutofillExclusion({ setting }, { setting = it })
        first.release()
        val second = AutofillExclusion({ setting }, { setting = it })
        assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS, setting)
        second.release()
        assertEquals(View.IMPORTANT_FOR_AUTOFILL_AUTO, setting)
    }
}
