// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.as11.decode

import kotlin.test.Test
import kotlin.test.assertTrue

class FirmwareTest {
    private fun accepted(identifier: String) = Firmware.reportedByMachine(identifier).layoutsAccepted

    @Test
    fun `summaries are read from every version whose identifier has the known form`() {
        val same = listOf(
            "SW04600.19.8.8.0.dced143ae", "SW04600.19.8.9.0.dced143ae", "SW04600.20.0.0.0.abc", "SW04600.21.1",
            "SW04600.19.8.8.01.x", "SW04600.19.8.8.0.1.abc", "SW04600.19.8.8.0.ABCDEF", "SW04600.1.2.3.4.5.6.7.8",
        )
        for (identifier in same) assertTrue(accepted(identifier), identifier)
    }

    @Test
    fun `an identifier of another form is not read`() {
        val others = listOf(
            "SW04601.19.8.8.0.x", "SW04700.19.8.8.0.abc", "", "19.8.8.0", "SW04600", "SW04600.19",
            // The right prefix is not enough, nor the right text somewhere inside, nor another case.
            "SW04600.19.8.8.0.", "SW04600..19.8", "SW04600.19.8.8.0.abc\nSW04600.20.0.0.0.abc", "xSW04600.19.8.8.0.abc", "sw04600.19.8.8.0.abc",
            "SW04600.19.8.8.0.abc def", "SW04600.19.8.8.0.abc-1", "SW04600.19.8.8.0.١٢", "SW04600.1.2.3.4.5.6.7.8.9",
            // A part longer than any build name.
            "SW04600.19.8.8.0." + "a".repeat(41),
        )
        for (identifier in others) assertTrue(!accepted(identifier), identifier)
    }

    @Test
    fun `an over-long identifier is not read`() {
        val longest = "SW04600.19.8.8.0." + "a".repeat(40) + "." + "b".repeat(6)
        assertTrue(longest.length == 64 && accepted(longest))
        assertTrue(!accepted(longest + "b"))
    }

    @Test
    fun `the per-minute log is read from the compared version alone, which is one whose summaries are read`() {
        val compared = Firmware.reportedByMachine("SW04600.19.8.8.0.dced143ae")
        assertTrue(compared.perMinuteAccepted && compared.layoutsAccepted)
        val newer = Firmware.reportedByMachine("SW04600.20.0.0.0.abc")
        assertTrue(!newer.perMinuteAccepted && newer.layoutsAccepted)
        val other = Firmware.reportedByMachine("SW04700.19.8.8.0.abc")
        assertTrue(!other.perMinuteAccepted && !other.layoutsAccepted)
    }
}
