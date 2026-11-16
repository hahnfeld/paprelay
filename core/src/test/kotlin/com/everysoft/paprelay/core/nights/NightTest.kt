// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.core.nights

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NightTest {
    private val tag = "m-13f8d92ec4854105"

    private fun night(
        date: LocalDate = LocalDate.of(2026, 11, 14),
        usage: Int = 432,
        figures: Map<NightFigure, Long> = mapOf(
            NightFigure.AHI to 230, NightFigure.OBSTRUCTIVE_APNEA_INDEX to 110, NightFigure.CENTRAL_APNEA_INDEX to 0,
            NightFigure.HYPOPNEA_INDEX to 120, NightFigure.LEAK_95 to 1440, NightFigure.PRESSURE_95 to 1042,
        ),
        complete: Boolean = true,
    ) = Night(date, -420, usage, 2, figures, complete)

    @Test
    fun `a night says when it ran and what it holds, exactly`() {
        val night = night()
        assertEquals("2026-11-14T12:00-07:00", night.start.toString())
        assertEquals("2026-11-15T12:00-07:00", night.end.toString())
        assertEquals("2.3", night.value(NightFigure.AHI)!!.toPlainString())
        assertEquals("0", night.value(NightFigure.CENTRAL_APNEA_INDEX)!!.toPlainString())
        assertEquals("14.4", night.value(NightFigure.LEAK_95)!!.toPlainString())
        assertEquals("10.42", night.value(NightFigure.PRESSURE_95)!!.toPlainString())
        // Absent is absent, never zero.
        assertNull(night.value(NightFigure.RERA_INDEX))
        assertEquals("7.2", night.usageHours.toPlainString())
        assertEquals("0", night(usage = 0, figures = emptyMap()).usageHours.toPlainString())
        assertEquals("0.02", night(usage = 1).usageHours.toPlainString())
        assertEquals("10", Night.decimal(1000).toPlainString())
        // Printing one gives nothing away.
        assertEquals("Night", "$night")
    }

    @Test
    fun `a night that cannot be one is refused`() {
        assertFailsWith<IllegalArgumentException> { night(usage = -1) }
        assertFailsWith<IllegalArgumentException> { night(usage = 26 * 60) }
        assertFailsWith<IllegalArgumentException> { night(figures = mapOf(NightFigure.AHI to -1L)) }
        assertFailsWith<IllegalArgumentException> { Night(LocalDate.of(2026, 1, 1), 19 * 60, 0, 0, emptyMap(), true) }
    }

    @Test
    fun `the fingerprint changes with anything the night says and with nothing else`() {
        val a = night().fingerprint()
        assertEquals(a, night().fingerprint())
        assertTrue(Regex("[0-9a-f]{64}").matches(a))
        assertNotEquals(a, night(usage = 433).fingerprint())
        assertNotEquals(a, night(complete = false).fingerprint())
        assertNotEquals(a, night(date = LocalDate.of(2026, 11, 13)).fingerprint())
        assertNotEquals(a, night(figures = night().hundredths + (NightFigure.AHI to 231L)).fingerprint())
        assertNotEquals(a, night(figures = night().hundredths - NightFigure.CENTRAL_APNEA_INDEX).fingerprint())
        // The order the figures were given in does not matter.
        assertEquals(a, night(figures = night().hundredths.entries.reversed().associate { it.key to it.value }).fingerprint())
    }

    @Test
    fun `the table explains itself, leaves gaps as gaps, and leaves out a day still running`() {
        val unused = night(date = LocalDate.of(2026, 11, 13), usage = 0, figures = emptyMap())
        val running = night(date = LocalDate.of(2026, 11, 15), complete = false)
        val csv = NightReport.csv(listOf(unused, night(), running))
        val lines = csv.split("\r\n")
        assertTrue(lines.take(4).all { it.startsWith("# ") })
        assertEquals(
            "night_of,usage_hours,sessions,ahi,obstructive_apnea_index,central_apnea_index,unclassified_apnea_index,hypopnea_index,rera_index," +
                "leak_median,leak_95th,leak_max,pressure_median,pressure_95th,pressure_max,respiratory_rate_median,tidal_volume_median,minute_ventilation_median",
            lines[4],
        )
        // Newest first; an absent figure is an empty cell; a day without therapy is a line of its own.
        assertEquals("2026-11-14,7.2,2,2.3,1.1,0,,1.2,,,14.4,,,10.42,,,,", lines[5])
        assertEquals("2026-11-13,0,2,,,,,,,,,,,,,,,", lines[6])
        assertEquals("", lines[7])
        assertEquals(8, lines.size)
        assertFalse(csv.contains("2026-11-15"))
        // Every line has the same number of cells.
        assertEquals(1, lines.drop(4).dropLast(1).map { it.count { c -> c == ',' } }.toSet().size)

        val text = NightReport.text(listOf(unused, night(), running))
        assertTrue(text.contains("2026-11-14: used 7 h 12 min, AHI 2.3, obstructive 1.1, central 0, hypopnea 1.2, leak 95th 14.4 L/min, pressure 95th 10.42 cmH2O\n"))
        assertTrue(text.contains("2026-11-13: no therapy\n"))
        assertFalse(text.contains("2026-11-15"))
        assertEquals(2, NightReport.text(listOf(unused, night()), limit = 1).trim().lines().size)
    }

    @Test
    fun `a night becomes one record per figure, each with a fixed name`() {
        val records = FhirObservations.forNight(night(), tag)
        // Usage, and the six figures the night has.
        assertEquals(7, records.size)
        assertEquals(records.size, records.map { it.id }.toSet().size)
        assertTrue(records.all { Regex("[A-Za-z0-9\\-.]{1,64}").matches(it.id) })
        assertEquals("$tag-20261114-usage-hours", records[0].id)
        // The same night gives the same records, to the letter: storing it again replaces, never adds.
        assertEquals(records.map { it.json }, FhirObservations.forNight(night(), tag).map { it.json })
        // Every id a night could ever have had is known, for removing them.
        assertTrue(FhirObservations.idsFor(night(), tag).containsAll(records.map { it.id }))
        assertEquals(1 + NightFigure.entries.size, FhirObservations.idsFor(night(), tag).size)

        val ahi = records.single { it.id.endsWith("-ahi") }.json
        assertEquals(
            """{"resourceType":"Observation","id":"m-13f8d92ec4854105-20261114-ahi","status":"final",""" +
                """"category":[{"coding":[{"system":"http://terminology.hl7.org/CodeSystem/observation-category","code":"laboratory","display":"Laboratory"}]}],""" +
                """"code":{"coding":[{"system":"https://github.com/hahnfeld/paprelay/fhir/CodeSystem/cpap-night","code":"ahi","display":"Apnea-hypopnea index"},""" +
                """{"system":"http://loinc.org","code":"69990-0"}],"text":"Apnea-hypopnea index (CPAP machine, nightly)"},""" +
                """"effectivePeriod":{"start":"2026-11-14T12:00:00-07:00","end":"2026-11-15T12:00:00-07:00"},""" +
                """"valueQuantity":{"value":2.3,"unit":"events/h","system":"http://unitsofmeasure.org","code":"{events}/h"},""" +
                """"device":{"display":"ResMed AirSense 11 (copied by PAPRelay)"}}""",
            ahi,
        )
        // A figure LOINC has no exact code for carries PAPRelay's own code only.
        val obstructive = records.single { it.id.endsWith("-obstructive-apnea-index") }.json
        assertFalse(obstructive.contains("loinc.org"))
        assertTrue(records[0].json.contains(""""valueQuantity":{"value":7.2,"unit":"h""""))
        // A record's printed form does not carry its contents.
        assertEquals("FhirResource($tag-20261114-usage-hours)", "${records[0]}")
    }

    @Test
    fun `a day without therapy is one record saying so, and a day still running is none`() {
        val unused = FhirObservations.forNight(night(usage = 0, figures = emptyMap()), tag)
        assertEquals(1, unused.size)
        assertTrue(unused.single().json.contains(""""value":0,"""))
        assertFailsWith<IllegalArgumentException> { FhirObservations.forNight(night(complete = false), tag) }
        assertFailsWith<IllegalArgumentException> { FhirObservations.forNight(night(), "has space") }
        assertFailsWith<IllegalArgumentException> { FhirObservations.forNight(night(), "x".repeat(25)) }
    }

    @Test
    fun `every figure has a name of its own that fits in a record id`() {
        assertEquals(NightFigure.entries.size, NightFigure.entries.map { it.key }.toSet().size)
        assertTrue(NightFigure.entries.all { Regex("[a-z0-9-]{2,26}").matches(it.key) })
        assertTrue(NightFigure.entries.all { NightFigure.ofKey(it.key) == it })
        assertNull(NightFigure.ofKey("nothing"))
        // The longest id still fits the 64 characters a record id may have.
        val longest = FhirObservations.idsFor(night(), "m-" + "f".repeat(22)).maxOf { it.length }
        assertTrue(longest <= 64, "$longest")
    }
}
