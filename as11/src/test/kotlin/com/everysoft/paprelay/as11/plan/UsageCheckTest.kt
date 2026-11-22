// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.as11.plan

import com.everysoft.paprelay.as11.decode.EventDecoder
import com.everysoft.paprelay.as11.decode.Firmware
import com.everysoft.paprelay.as11.decode.Proto
import com.everysoft.paprelay.as11.decode.SummaryDecoder
import com.everysoft.paprelay.as11.decode.UsageEvent
import com.everysoft.paprelay.as11.decode.proto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UsageCheckTest {
    private val firmware = Firmware.reportedByMachine("SW04600.19.8.8.0.dced143ae")
    private val noon = 1_794_000_000_000L
    private val day = 86_400_000L
    private val minute = 60_000L

    private fun Proto.usage(code: Long, at: Long) = message(1) { varint(1, code); varint(2, at); varint(3, at) }

    private fun summary(vararg sessions: Pair<Long, Long>) = SummaryDecoder.decode(
        proto {
            message(2) {
                varint(1, 1); varint(2, noon); varint(3, noon + day); varint(5, sessions.sumOf { it.second })
                message(6) { for ((start, minutes) in sessions) message(1) { varint(1, start); varint(2, minutes) } }
                varint(39, sessions.size.toLong())
            }
        },
        firmware,
    ).decoded.single().sessions

    private fun usage(build: Proto.() -> Unit) = EventDecoder.usage(proto { message(6) { message(1, build) } }, firmware).decoded

    @Test
    fun `mask on and off that agree with the summary's sessions are counted, with the largest differences in seconds`() {
        val first = noon + 10 * 60 * minute
        val second = noon + 16 * 60 * minute
        val sessions = summary(first to 203, second to 102)
        val log = usage {
            usage(7, first - 2_000)
            usage(3, first + 5_500)
            usage(8, first + 203 * minute + 20_000)
            usage(2, first + 203 * minute + 21_000)
            usage(3, second + 1_000)
            usage(2, second + 102 * minute + 40_000)
        }
        val result = UsageCheck.compare(sessions, log)
        assertEquals(2, result.sessions)
        assertEquals(2, result.withMaskOn)
        assertEquals(2, result.withMaskOff)
        assertEquals(2, result.lengthAgrees)
        assertEquals(5, result.maxStartDifferenceS)
        assertEquals(39, result.maxLengthDifferenceS)
        assertEquals(0, result.maskOnWithoutOff)
        assertEquals(0, result.maskOffWithoutOn)
        assertEquals(mapOf("THERAPY_START" to 1, "THERAPY_STOP" to 1), result.otherKinds)
    }

    @Test
    fun `what does not agree is counted apart`() {
        val first = noon + 10 * 60 * minute
        val sessions = summary(first to 203, noon + 20 * 60 * minute to 30)
        val log = usage {
            usage(3, first)
            // A length three minutes off the summary's.
            usage(2, first + 206 * minute)
            // A mask-off with no mask-on, a mask-on far from any session, and one never closed.
            usage(2, first + 207 * minute)
            usage(3, first + 300 * minute)
            usage(3, noon + 20 * 60 * minute)
        }
        val result = UsageCheck.compare(sessions, log)
        assertEquals(2, result.sessions)
        assertEquals(2, result.withMaskOn)
        assertEquals(1, result.withMaskOff)
        assertEquals(0, result.lengthAgrees)
        assertEquals(180, result.maxLengthDifferenceS)
        // The one at +300 min has no mask-off before the next mask-on; the last is still open.
        assertEquals(2, result.maskOnWithoutOff)
        assertEquals(1, result.maskOffWithoutOn)
        assertEquals(1, result.maskOnWithoutSession)
        assertTrue(UsageCheck.compare(sessions, emptyList()).sessions == 0)
    }

    @Test
    fun `mask on and mask off are confirmed, and nothing else of the log`() {
        assertEquals(setOf(UsageEvent.MASK_ON, UsageEvent.MASK_OFF), UsageEvent.entries.filter { it.confirmed }.toSet())
        assertTrue(UsagePlan.ready(UsagePlan.CONFIRMED))
    }

    @Test
    fun `a session whose start, in whole minutes, comes before the log's first record is still compared`() {
        // The summary says 23:00:00; the log begins with therapy start a few seconds later, then mask on.
        val start = noon + 11 * 60 * minute
        val sessions = summary(start to 120)
        val log = usage {
            usage(7, start + 3_000)
            usage(3, start + 9_000)
            usage(8, start + 120 * minute + 30_000)
            usage(2, start + 120 * minute + 30_000)
        }
        val result = UsageCheck.compare(sessions, log)
        assertEquals(1, result.sessions)
        assertEquals(1, result.withMaskOn)
        assertEquals(1, result.lengthAgrees)
        assertEquals(0, result.maskOnWithoutSession)
        assertEquals(9, result.maxStartDifferenceS)
        // Beyond the window before the log: not in its span.
        assertEquals(0, UsageCheck.compare(summary(start - UsageCheck.MATCH_WINDOW_MS - 10_000 to 120), log).sessions)
    }
}

class UsagePlanTest {
    private val firmware = Firmware.reportedByMachine("SW04600.19.8.8.0.dced143ae")
    private val t = 1_794_000_000_000L
    private val both = setOf(UsageEvent.MASK_ON, UsageEvent.MASK_OFF)

    private fun log(software: Firmware = firmware) = EventDecoder.usage(
        proto { message(6) { message(1) { for ((code, at) in listOf(7L to t, 3L to t + 5_000, 2L to t + 60_000)) message(1) { varint(1, code); varint(2, at); varint(3, at) } } } },
        software,
    )

    @Test
    fun `only mask on and off become marks, in machine time and corrected by the one rule`() {
        val log = log()
        val machineNow = com.everysoft.paprelay.core.MachineTime(t + hour)
        val correction = ClockCorrection(com.everysoft.paprelay.core.ClockOffset.measured(machineNow, t + hour - 360_000), machineNow, t + hour - 360_000)
        val marks = log.decoded.map { UsagePlan.mark(log, it, correction, both) }
        assertEquals(listOf(null, true, false), marks.map { it?.on })
        assertEquals(t + 5_000, marks[1]!!.machineTime.epochMs)
        assertEquals(t + 5_000 - 360_000, marks[1]!!.correctedMs)
    }

    @Test
    fun `nothing is used while mask on or off is unconfirmed, or from software whose data is not read`() {
        val log = log()
        assertTrue(log.decoded.all { UsagePlan.mark(log, it, ClockCorrection.NONE, emptySet()) == null })
        assertTrue(log.decoded.all { UsagePlan.mark(log, it, ClockCorrection.NONE, setOf(UsageEvent.MASK_ON)) == null })
        // With the confirmed kinds (the default), mask on and off are used; therapy start is not.
        assertEquals(listOf(null, true, false), log.decoded.map { UsagePlan.mark(log, it, ClockCorrection.NONE)?.on })
        val other = log(Firmware.reportedByMachine("SW04700.20.0.0.0.abc"))
        assertTrue(other.decoded.all { UsagePlan.mark(other, it, ClockCorrection.NONE, both) == null })
    }

    private val hour = 3_600_000L
}
