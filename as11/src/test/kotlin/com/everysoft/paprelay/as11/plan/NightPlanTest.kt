// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.as11.plan

import com.everysoft.paprelay.as11.decode.Firmware
import com.everysoft.paprelay.as11.decode.Proto
import com.everysoft.paprelay.as11.decode.SummaryDecoder
import com.everysoft.paprelay.as11.decode.SummaryMetric
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
        // Tidal volume in millilitres: 0.48 litres, exactly.
        assertEquals("480", night.value(NightFigure.TIDAL_VOLUME_MEDIAN)!!.toPlainString())
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
        // Hawaii, sent from California, from Arizona or from New York: the machine's zone is hours off, and
        // still points plainly at Hawaii and not at the other side of the date line.
        val hawaii = LocalDate.of(2026, 11, 14).atTime(22, 0).toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
        for (zone in listOf(-480L, -420L, -300L)) {
            val there = plan { night(start = hawaii, zone = zone) }.single()!!
            assertEquals(LocalDate.of(2026, 11, 14), there.date, "zone $zone")
            assertEquals(-600, there.zoneOffsetMinutes)
        }
        // A zone field about midway between the two possibilities decides nothing: the machine was carried half-way round the world and set there.
        assertNull(plan { night(start = winter, zone = 0) }.single())
        assertNull(plan { night(start = winter, zone = 60) }.single())
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
        // Software of another line.
        val other = SummaryDecoder.decode(proto { night() }, Firmware.reportedByMachine("SW04700.20.0.0.0.abc"))
        assertTrue(other.decoded.all { NightPlan.night(it, MachineTime(noon + 2 * day)) == null })
    }

    @Test
    fun `another version of the same software line gives the same night`() {
        val newer = SummaryDecoder.decode(proto { night() }, Firmware.reportedByMachine("SW04600.20.0.0.0.abc"))
        val checked = SummaryDecoder.decode(proto { night() }, confirmed)
        val now = MachineTime(noon + 2 * day)
        val nights = newer.decoded.map { NightPlan.night(it, now) }
        assertTrue(nights.isNotEmpty() && nights.all { it != null })
        assertEquals(checked.decoded.map { NightPlan.night(it, now)?.fingerprint() }, nights.map { it?.fingerprint() })
    }

    @Test
    fun `every confirmed summary value is a figure, in its own unit and exactly`() {
        val night = plan {
            night {
                message(29) { varint(2, 1120) }
                message(30) { varint(2, 2950) }
                message(31) { varint(2, 2675) }
                message(32) { varint(2, 3300) }
                message(33) { varint(2, 0) }
                message(36) { varint(1, 474); varint(3, 958) }
                message(37) { varint(1, 16); varint(3, 36) }
                message(38) { varint(2, 48) }
            }
        }.single()!!
        val expected = mapOf(
            NightFigure.LEAK_70 to "4.8", // 0.08 litres a second
            NightFigure.RESPIRATORY_RATE_95 to "15.2", NightFigure.RESPIRATORY_RATE_MAX to "19.2",
            NightFigure.TIDAL_VOLUME_95 to "600", NightFigure.TIDAL_VOLUME_MAX to "900",
            NightFigure.MINUTE_VENTILATION_95 to "8", NightFigure.MINUTE_VENTILATION_MAX to "11",
            // Every flow in litres a minute: 0.16 and 0.36 litres a second.
            NightFigure.RESPIRATORY_FLOW_5 to "9.6", NightFigure.RESPIRATORY_FLOW_95 to "21.6",
            NightFigure.BLOWER_PRESSURE_5 to "4.74", NightFigure.BLOWER_PRESSURE_95 to "9.58",
            NightFigure.BLOWER_FLOW_MEDIAN to "28.8",
            NightFigure.AMBIENT_HUMIDITY_MEDIAN to "11.2",
            NightFigure.HUMIDIFIER_TEMPERATURE_MEDIAN to "29.5", NightFigure.HEATED_TUBE_TEMPERATURE_MEDIAN to "26.75",
            NightFigure.HUMIDIFIER_POWER_MEDIAN to "33", NightFigure.HEATED_TUBE_POWER_MEDIAN to "0",
        )
        for ((figure, value) in expected) assertEquals(value, night.value(figure)?.toPlainString(), figure.key)
        // Every figure the plan can give is in the night, and nothing else.
        assertEquals(NightPlan.FIGURES.toSet(), night.hundredths.keys)
    }

    @Test
    fun `a value whose layout or factor is not confirmed is not given, even when the record carries it`() {
        val night = plan {
            night {
                message(24) { varint(2, 600); varint(3, 700); varint(4, 800) } // target minute ventilation
                message(26) { varint(2, 1_200); varint(3, 1_500); varint(4, 1_900) } // inspiratory duration
                message(27) { varint(2, 6_800); varint(3, 8_000); varint(4, 9_200) } // I:E ratio
                message(28) { varint(2, 9_500); varint(3, 9_700); varint(4, 9_900) } // SpO2
                message(41) { varint(2, 6_000); varint(3, 7_000); varint(4, 9_000) } // heart rate
                message(42) { varint(2, 400); varint(3, 500); varint(4, 600) } // alveolar minute ventilation
                varint(16, 12); varint(18, 50); varint(19, 30); varint(34, 2); varint(35, 1)
            }
        }.single()!!
        val pending = NightFigure.entries - NightPlan.FIGURES.toSet()
        assertEquals(
            setOf("target-ventilation", "alveolar-ventilation", "ie-ratio"),
            pending.map { it.key.substringBeforeLast('-') }.toSet(),
        )
        for (figure in pending) assertNull(night.value(figure), figure.key)
        assertEquals(night.hundredths.keys, night.hundredths.keys.intersect(NightPlan.FIGURES.toSet()))
    }

    @Test
    fun `confirming a summary value is its flag alone, and the plan's version follows`() {
        // Every percentile of every confirmed metric is a figure the plan gives.
        for (metric in SummaryMetric.entries.filter { it.confirmed }) {
            for (percentile in metric.layout.bySubField.values) {
                assertTrue(NightPlan.metrics.values.any { it.metric == metric && it.percentile == percentile }, "$metric $percentile")
            }
        }
        // Each figure named and not yet given waits on exactly one unconfirmed metric's flag.
        for (figure in NightFigure.entries - NightPlan.FIGURES.toSet()) {
            val source = NightPlan.metrics[figure]
            assertTrue(source != null && !source.metric.confirmed, figure.key)
        }
        // Each source's unit is the figure's, and each figure has one source.
        for ((figure, source) in NightPlan.metrics) assertEquals(figure.unit.name, source.unit.name, figure.key)
        assertEquals(NightPlan.metrics.size, NightPlan.metrics.values.map { it.metric to it.percentile }.toSet().size)
        // The indices and the confirmed metrics, in the order of the figures.
        assertEquals(NightFigure.entries.filter { it in NightPlan.FIGURES }, NightPlan.FIGURES)
        assertTrue(Regex("2-[0-9a-f]{16}").matches(NightPlan.VERSION), NightPlan.VERSION)
    }

    @Test
    fun `a night lists its sessions one by one, by the machine's clock, when each has its length`() {
        val night = plan { night() }.single()!!
        assertEquals(listOf(noon + 36_000_000, noon + 54_000_000), night.sessionList!!.map { it.start.epochMs })
        assertEquals(listOf(203, 102), night.sessionList!!.map { it.minutes })
        // A session without a stored length: the list is not given at all, and the count still is.
        val gap = plan {
            message(2) {
                varint(1, 1); varint(2, noon); varint(3, noon + day); zigzag(4, -420); varint(5, 200)
                message(6) { message(1) { varint(1, noon + 36_000_000); varint(2, 100) }; message(1) { varint(1, noon + 50_000_000) } }
                varint(39, 2)
            }
        }.single()!!
        assertNull(gap.sessionList)
        assertEquals(2, gap.sessions)
        val unused = plan { message(2) { varint(1, 1); varint(2, noon); varint(3, noon + day); zigzag(4, -420); varint(5, 0) } }.single()!!
        assertEquals(emptyList(), unused.sessionList)
        // Without a measured clock, no start is corrected.
        assertTrue(night.sessionList!!.all { it.correctedMs == null })
    }

    @Test
    fun `a session's start is corrected to true time by the same rule as an event's`() {
        // The machine six minutes fast, measured two days after the night's noon: within the week.
        val machineNow = MachineTime(noon + 2 * day)
        val trueNow = noon + 2 * day - 361_600
        val correction = ClockCorrection(com.everysoft.paprelay.core.ClockOffset.measured(machineNow, trueNow), machineNow, trueNow)
        val night = SummaryDecoder.decode(proto { night() }, confirmed).decoded.map { NightPlan.night(it, machineNow, correction) }.single()!!
        assertEquals(listOf(noon + 36_000_000 - 361_600, noon + 54_000_000 - 361_600), night.sessionList!!.map { it.correctedMs })
        assertEquals(listOf(noon + 36_000_000, noon + 54_000_000), night.sessionList!!.map { it.start.epochMs })
        // Measured more than a week later: not corrected.
        val late = MachineTime(noon + 9 * day)
        val far = ClockCorrection(com.everysoft.paprelay.core.ClockOffset.measured(late, late.epochMs), late, late.epochMs)
        val old = SummaryDecoder.decode(proto { night() }, confirmed).decoded.map { NightPlan.night(it, late, far) }.single()!!
        assertTrue(old.sessionList!!.all { it.correctedMs == null })
    }

    @Test
    fun `no figure shown or exported is in litres a second or in litres`() {
        // Every flow in litres a minute, tidal volume in millilitres: wherever a person reads them.
        for (figure in NightPlan.FIGURES) {
            assertTrue(figure.unit.symbol !in setOf("L/s", "L"), figure.key)
            assertTrue(figure.unit.ucum !in setOf("L/s", "L"), figure.key)
            assertTrue(figure.unit.columnSuffix !in setOf("l_per_s", "l"), figure.column)
        }
        for (figure in listOf(NightFigure.RESPIRATORY_FLOW_5, NightFigure.RESPIRATORY_FLOW_95, NightFigure.BLOWER_FLOW_MEDIAN, NightFigure.LEAK_95, NightFigure.MINUTE_VENTILATION_MEDIAN)) {
            assertEquals("L/min", figure.unit.symbol, figure.key)
            assertTrue(figure.column.endsWith("_l_per_min"), figure.column)
        }
        for (figure in listOf(NightFigure.TIDAL_VOLUME_MEDIAN, NightFigure.TIDAL_VOLUME_95, NightFigure.TIDAL_VOLUME_MAX)) {
            assertEquals("mL", figure.unit.symbol, figure.key)
            assertTrue(figure.column.endsWith("_ml"), figure.column)
        }
    }

    @Test
    fun `the plan's version follows each figure's unit, not only its name`() {
        // Worked out as the plan does: the same figures in another unit give another version.
        fun version(units: (NightFigure) -> String) = java.security.MessageDigest.getInstance("SHA-256")
            .digest(NightPlan.FIGURES.joinToString(",") { "${it.key}:${units(it)}" }.toByteArray(Charsets.US_ASCII))
            .take(8).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        assertEquals(NightPlan.VERSION.substringAfter('-'), version { it.unit.name })
        val litres = version { if (it == NightFigure.TIDAL_VOLUME_MEDIAN) "LITRES" else it.unit.name }
        assertTrue(litres != version { it.unit.name })
    }
}
