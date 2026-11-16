// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.as11.plan

import com.everysoft.paprelay.as11.decode.Firmware
import com.everysoft.paprelay.as11.decode.Proto
import com.everysoft.paprelay.as11.decode.SummaryDecoder
import com.everysoft.paprelay.as11.decode.proto
import com.everysoft.paprelay.core.MachineTime
import com.everysoft.paprelay.core.nights.NightFigure
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NightPlanTest {
    private val confirmed = Firmware.reportedByMachine("SW04600.19.8.8.0.dced143ae")
    private val day = 86_400_000L

    /** Noon on 14 November 2026 where the clock is seven hours behind UTC. */
    private val noon = LocalDate.of(2026, 11, 14).atTime(19, 0).toInstant(java.time.ZoneOffset.UTC).toEpochMilli()

    private fun Proto.night(start: Long = noon, zone: Long = -420, duration: Long = 305, listed: Long = 2, extra: Proto.() -> Unit = {}) {
        message(2) {
            varint(1, 1)
            varint(2, start)
            varint(3, start + day)
            zigzag(4, zone)
            varint(5, duration)
            message(6) {
                message(1) { varint(1, start + 36_000_000); varint(2, 203) }
                message(1) { varint(1, start + 54_000_000); varint(2, 102) }
            }
            varint(7, 170); varint(8, 130); varint(9, 30); varint(10, 110); varint(11, 10); varint(12, 0); varint(13, 0)
            message(14) { varint(2, 6); varint(3, 8); varint(4, 42); varint(5, 86) }
            message(15) { varint(2, 900); varint(3, 1100); varint(4, 1180) }
            message(20) { varint(2, 700); varint(3, 900); varint(4, 980) }
            message(21) { varint(2, 800); varint(3, 960); varint(4, 1020) }
            message(22) { varint(2, 48); varint(3, 60); varint(4, 90) }
            message(23) { varint(2, 650); varint(3, 800); varint(4, 1100) }
            message(25) { varint(2, 1320); varint(3, 1520); varint(4, 1920) }
            varint(39, listed)
            varint(40, start + 73_000_000)
            extra()
        }
    }

    private fun plan(now: Long = noon + 2 * day, build: Proto.() -> Unit) =
        SummaryDecoder.decode(proto(build), confirmed).decoded.map { NightPlan.night(it, MachineTime(now)) }

    @Test
    fun `a night carries the machine's figures, with leak in litres a minute`() {
        val night = plan { night() }.single()!!
        assertEquals(LocalDate.of(2026, 11, 14), night.date)
        assertEquals(-420, night.zoneOffsetMinutes)
        assertEquals(305, night.usageMinutes)
        assertEquals(2, night.sessions)
        assertTrue(night.complete)
        assertEquals("1.7", night.value(NightFigure.AHI)!!.toPlainString())
        assertEquals("1.3", night.value(NightFigure.APNEA_INDEX)!!.toPlainString())
        assertEquals("0.3", night.value(NightFigure.HYPOPNEA_INDEX)!!.toPlainString())
        assertEquals("1.1", night.value(NightFigure.OBSTRUCTIVE_APNEA_INDEX)!!.toPlainString())
        assertEquals("0.1", night.value(NightFigure.CENTRAL_APNEA_INDEX)!!.toPlainString())
        // A stored zero is a zero.
        assertEquals("0", night.value(NightFigure.UNCLASSIFIED_APNEA_INDEX)!!.toPlainString())
        assertEquals("0", night.value(NightFigure.RERA_INDEX)!!.toPlainString())
        // 0.06, 0.42 and 0.86 litres a second.
        assertEquals("3.6", night.value(NightFigure.LEAK_MEDIAN)!!.toPlainString())
        assertEquals("25.2", night.value(NightFigure.LEAK_95)!!.toPlainString())
        assertEquals("51.6", night.value(NightFigure.LEAK_MAX)!!.toPlainString())
        // Three pressures, each under its own name: what the machine aimed for breathing in, breathing out, and what the mask saw.
        assertEquals("9", night.value(NightFigure.THERAPY_PRESSURE_MEDIAN)!!.toPlainString())
        assertEquals("11", night.value(NightFigure.THERAPY_PRESSURE_95)!!.toPlainString())
        assertEquals("11.8", night.value(NightFigure.THERAPY_PRESSURE_MAX)!!.toPlainString())
        assertEquals("7", night.value(NightFigure.EXPIRATORY_PRESSURE_MEDIAN)!!.toPlainString())
        assertEquals("9", night.value(NightFigure.EXPIRATORY_PRESSURE_95)!!.toPlainString())
        assertEquals("9.8", night.value(NightFigure.EXPIRATORY_PRESSURE_MAX)!!.toPlainString())
        assertEquals("8", night.value(NightFigure.MASK_PRESSURE_MEDIAN)!!.toPlainString())
        assertEquals("9.6", night.value(NightFigure.MASK_PRESSURE_95)!!.toPlainString())
        assertEquals("10.2", night.value(NightFigure.MASK_PRESSURE_MAX)!!.toPlainString())
        assertEquals(24, night.hours)
        assertEquals("13.2", night.value(NightFigure.RESPIRATORY_RATE_MEDIAN)!!.toPlainString())
        assertEquals("0.48", night.value(NightFigure.TIDAL_VOLUME_MEDIAN)!!.toPlainString())
        assertEquals("6.5", night.value(NightFigure.MINUTE_VENTILATION_MEDIAN)!!.toPlainString())
        assertEquals("2026-11-14T12:00-07:00", night.start.toString())
    }

    @Test
    fun `a day without therapy is kept, with nothing but that`() {
        val unused = plan { message(2) { varint(1, 1); varint(2, noon); varint(3, noon + day); zigzag(4, -420); varint(5, 0); varint(40, noon) } }.single()!!
        assertEquals(0, unused.usageMinutes)
        assertEquals(0, unused.sessions)
        assertTrue(unused.hundredths.isEmpty())
    }

    @Test
    fun `a day that has not ended by the machine's clock is marked as still running`() {
        assertFalse(plan(now = noon + day - 1) { night() }.single()!!.complete)
        assertTrue(plan(now = noon + day) { night() }.single()!!.complete)
    }

    @Test
    fun `a figure the machine did not record stays absent`() {
        val bare = plan { message(2) { varint(1, 1); varint(2, noon); varint(3, noon + day); zigzag(4, -420); varint(5, 200); varint(7, 90); varint(39, 0) } }.single()!!
        assertEquals(setOf(NightFigure.AHI), bare.hundredths.keys)
    }

    @Test
    fun `the date is read off the day's own noon, whatever the machine's zone was when it sent the record`() {
        // Recorded at home, sent after the machine was set to another zone.
        assertEquals(LocalDate.of(2026, 11, 14), plan { night(zone = 60) }.single()!!.date)
        assertEquals(-420, plan { night(zone = 60) }.single()!!.zoneOffsetMinutes)
        // Half-hour zones: noon in India is 06:30 UTC.
        val india = LocalDate.of(2026, 11, 14).atTime(6, 30).toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
        val there = plan { night(start = india, zone = 330) }.single()!!
        assertEquals(330, there.zoneOffsetMinutes)
        assertEquals(LocalDate.of(2026, 11, 14), there.date)
        // 23:00 UTC is noon both eleven hours west and thirteen hours east. The record's zone decides.
        val line = LocalDate.of(2026, 11, 13).atTime(23, 0).toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
        assertEquals(LocalDate.of(2026, 11, 14), plan { night(start = line, zone = 780) }.single()!!.date)
        assertEquals(LocalDate.of(2026, 11, 13), plan { night(start = line, zone = -660) }.single()!!.date)
    }

    @Test
    fun `where the calendar date turns on the zone, the nearer zone to the machine's own is taken`() {
        // New Zealand: a winter night began at noon twelve hours ahead of UTC, which is midnight UTC,
        // and is sent after the clocks have gone forward to thirteen. It is still that day's night.
        val winter = LocalDate.of(2026, 9, 24).atStartOfDay().toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
        val sentInSummer = plan { night(start = winter, zone = 780) }.single()!!
        assertEquals(LocalDate.of(2026, 9, 24), sentInSummer.date)
        assertEquals(720, sentInSummer.zoneOffsetMinutes)
        // And the other way round in April: a summer night, 23:00 UTC, sent once the clocks are back at twelve.
        val summer = LocalDate.of(2026, 4, 1).atTime(23, 0).toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
        val sentInWinter = plan { night(start = summer, zone = 720) }.single()!!
        assertEquals(LocalDate.of(2026, 4, 2), sentInWinter.date)
        assertEquals(780, sentInWinter.zoneOffsetMinutes)
        // Hawaii, sent from California: nowhere near the date line, and unaffected.
        val hawaii = LocalDate.of(2026, 11, 14).atTime(22, 0).toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
        assertEquals(LocalDate.of(2026, 11, 14), plan { night(start = hawaii, zone = -480) }.single()!!.date)
        // With no zone to go by and two dates possible, none is given.
        assertNull(
            plan {
                message(2) { varint(1, 1); varint(2, winter); varint(3, winter + day); varint(5, 200); varint(7, 90); varint(39, 0) }
            }.single(),
        )
    }

    @Test
    fun `a figure beyond what it can be is left out, and a day the clocks changed in keeps its true length`() {
        val wild = plan { night { message(36) { varint(1, 474); varint(3, 958) } } }.single()!!
        assertEquals("10.2", wild.value(NightFigure.MASK_PRESSURE_MAX)!!.toPlainString())
        val absurd = plan {
            message(2) {
                varint(1, 1); varint(2, noon); varint(3, noon + day); zigzag(4, -420); varint(5, 200); varint(7, 90)
                message(21) { varint(2, 800); varint(3, 960); varint(4, 999_999) }
                message(14) { varint(2, 6); varint(3, 8); varint(4, 42); varint(5, 400) }
                varint(39, 0)
            }
        }.single()!!
        // A mask pressure of ten thousand, and a leak of 240 litres a minute: neither is a measurement.
        assertNull(absurd.value(NightFigure.MASK_PRESSURE_MAX))
        assertNull(absurd.value(NightFigure.LEAK_MAX))
        assertEquals("9.6", absurd.value(NightFigure.MASK_PRESSURE_95)!!.toPlainString())
        assertEquals("25.2", absurd.value(NightFigure.LEAK_95)!!.toPlainString())

        val short = plan {
            message(2) { varint(1, 1); varint(2, noon); varint(3, noon + 23 * 3_600_000L); zigzag(4, -420); varint(5, 200); varint(7, 90); varint(39, 0) }
        }.single()!!
        assertEquals(23, short.hours)
        assertEquals("2026-11-15T11:00-07:00", short.end.toString())
    }

    @Test
    fun `a record that cannot stand as a night is not made into one`() {
        // The list of sessions is not whole.
        assertNull(plan { night(listed = 3) }.single())
        // A day that does not begin at anyone's noon.
        assertNull(plan { night(start = noon + 7 * 60_000) }.single())
        // Software the layout was not confirmed on.
        val other = SummaryDecoder.decode(proto { night() }, Firmware.reportedByMachine("SW04600.20.0.0.0.abc"))
        assertTrue(other.decoded.all { NightPlan.night(it, MachineTime(noon + 2 * day)) == null })
    }
}
