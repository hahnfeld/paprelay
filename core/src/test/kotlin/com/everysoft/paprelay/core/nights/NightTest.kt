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
            NightFigure.HYPOPNEA_INDEX to 120, NightFigure.LEAK_95 to 1440, NightFigure.THERAPY_PRESSURE_95 to 1042,
            NightFigure.MASK_PRESSURE_95 to 960,
        ),
        complete: Boolean = true,
        hours: Int = 24,
    ) = Night(date, -420, usage, 2, figures, complete, hours)

    @Test
    fun `a night says when it ran and what it holds, exactly`() {
        val night = night()
        assertEquals("2026-11-14T12:00-07:00", night.start.toString())
        assertEquals("2026-11-15T12:00-07:00", night.end.toString())
        // Where the clocks changed during the day, it ends when it really did.
        assertEquals("2026-11-15T11:00-07:00", night(hours = 23).end.toString())
        assertEquals("2026-11-15T13:00-07:00", night(hours = 25).end.toString())
        assertEquals("2.3", night.value(NightFigure.AHI)!!.toPlainString())
        assertEquals("0", night.value(NightFigure.CENTRAL_APNEA_INDEX)!!.toPlainString())
        assertEquals("14.4", night.value(NightFigure.LEAK_95)!!.toPlainString())
        assertEquals("10.42", night.value(NightFigure.THERAPY_PRESSURE_95)!!.toPlainString())
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
        assertFailsWith<IllegalArgumentException> { night(usage = 24 * 60 + 1) }
        night(usage = 25 * 60, hours = 25)
        assertFailsWith<IllegalArgumentException> { night(hours = 26) }
        assertFailsWith<IllegalArgumentException> { night(figures = mapOf(NightFigure.AHI to -1L)) }
        assertFailsWith<IllegalArgumentException> { Night(LocalDate.of(2026, 1, 1), 19 * 60, 0, 0, emptyMap(), true) }
        // No figure may be more than it can plausibly be: a pressure of a hundred is a misreading.
        assertFailsWith<IllegalArgumentException> { night(figures = mapOf(NightFigure.THERAPY_PRESSURE_MAX to 10_000L)) }
        for (figure in NightFigure.entries) {
            night(figures = mapOf(figure to figure.maxHundredths))
            assertFailsWith<IllegalArgumentException>(figure.key) { night(figures = mapOf(figure to figure.maxHundredths + 1)) }
        }
    }

    @Test
    fun `the fingerprint changes with anything the night says and with nothing else`() {
        val a = night().fingerprint()
        assertEquals(a, night().fingerprint())
        assertTrue(Regex("[0-9a-f]{64}").matches(a))
        assertNotEquals(a, night(usage = 433).fingerprint())
        assertNotEquals(a, night(complete = false).fingerprint())
        assertNotEquals(a, night(hours = 25).fingerprint())
        assertNotEquals(a, night(date = LocalDate.of(2026, 11, 13)).fingerprint())
        assertNotEquals(a, night(figures = night().hundredths + (NightFigure.AHI to 231L)).fingerprint())
        assertNotEquals(a, night(figures = night().hundredths - NightFigure.CENTRAL_APNEA_INDEX).fingerprint())
        // The order the figures were given in does not matter.
        assertEquals(a, night(figures = night().hundredths.entries.reversed().associate { it.key to it.value }).fingerprint())
    }

    @Test
    fun `the table is one any program reads, names its units, leaves gaps as gaps, and leaves out a day still running`() {
        val unused = night(date = LocalDate.of(2026, 11, 13), usage = 0, figures = emptyMap())
        val running = night(date = LocalDate.of(2026, 11, 15), complete = false)
        val csv = NightReport.csv(listOf(unused, night(), running))
        val lines = csv.split("\r\n")
        // The header is the first line, and every column says what it is measured in.
        val header = lines[0].split(',')
        assertEquals(listOf("night_of", "usage_minutes", "usage_hours", "sessions"), header.take(4))
        assertEquals(NightFigure.entries.map { it.column }, header.drop(4))
        assertTrue("ahi_events_per_hour" in header && "leak_95th_l_per_min" in header && "therapy_pressure_95th_cmh2o" in header && "mask_pressure_95th_cmh2o" in header)
        assertTrue(header.all { Regex("[a-z0-9_]+").matches(it) })
        // Newest first; a day without therapy is a line of its own; then nothing but the final line end.
        assertEquals(4, lines.size)
        assertEquals("", lines[3])
        val newest = header.zip(lines[1].split(',')).toMap()
        assertEquals("2026-11-14", newest["night_of"])
        assertEquals("432", newest["usage_minutes"])
        assertEquals("7.2", newest["usage_hours"])
        assertEquals("2.3", newest["ahi_events_per_hour"])
        assertEquals("0", newest["central_apnea_index_events_per_hour"])
        assertEquals("14.4", newest["leak_95th_l_per_min"])
        assertEquals("10.42", newest["therapy_pressure_95th_cmh2o"])
        assertEquals("9.6", newest["mask_pressure_95th_cmh2o"])
        // An absent figure is an empty cell.
        assertEquals("", newest["rera_index_events_per_hour"])
        assertTrue(lines[2].startsWith("2026-11-13,0,0,2,"))
        assertTrue(lines[2].split(',').drop(4).all { it.isEmpty() })
        assertFalse(csv.contains("2026-11-15"))
        // Every line has the same number of cells, and no cell needs quoting.
        assertEquals(setOf(header.size), lines.dropLast(1).map { it.split(',').size }.toSet())
        assertFalse(csv.contains('"'))

        val text = NightReport.text(listOf(unused, night(), running))
        assertTrue(text.startsWith(NightReport.EXPLANATION))
        assertTrue(text.contains("2026-11-14: used 7 h 12 min, AHI 2.3, obstructive 1.1, central 0, hypopnea 1.2, leak 95th 14.4 L/min, therapy pressure 95th 10.42 cmH2O\n"))
        assertTrue(text.contains("2026-11-13: no therapy\n"))
        assertFalse(text.contains("2026-11-15"))
        assertEquals(1, NightReport.text(listOf(unused, night()), limit = 1).substringAfter("\n\n").trim().lines().size)
        // What the table is says the things a reader would otherwise get wrong.
        for (said in listOf("not sleep-study indices", "noon to noon", "one decimal place", "per hour of use")) assertTrue(NightReport.EXPLANATION.contains(said), said)
    }

    @Test
    fun `a night becomes a few records, each with a fixed name, a headline value and the rest beside it`() {
        val records = FhirObservations.forNight(night(), tag)
        // Usage, events, leak and pressure. The night has nothing of breathing, so there is no such record.
        assertEquals(listOf("usage", "events", "leak", "pressure").map { "$tag-20261114-$it" }, records.map { it.id })
        assertTrue(records.all { Regex("[A-Za-z0-9\\-.]{1,64}").matches(it.id) })
        // The same night gives the same records, to the letter: storing it again replaces, never adds.
        assertEquals(records.map { it.json }, FhirObservations.forNight(night(), tag).map { it.json })
        // Every id a night could ever have is known, for removing those it no longer has.
        assertEquals(listOf("usage", "events", "leak", "pressure", "breathing").map { "$tag-20261114-$it" }, FhirObservations.idsFor(night(), tag))

        val events = records[1].json
        assertEquals(
            """{"resourceType":"Observation","id":"m-13f8d92ec4854105-20261114-events","status":"final",""" +
                """"category":[{"coding":[{"system":"http://terminology.hl7.org/CodeSystem/observation-category","code":"laboratory","display":"Laboratory"}]}],""" +
                """"code":{"coding":[{"system":"https://github.com/hahnfeld/paprelay/fhir/CodeSystem/cpap-night","code":"ahi",""" +
                """"display":"Residual apnea-hypopnea index on CPAP therapy, as the machine reports it (events per hour of use)"}],""" +
                """"text":"Residual apnea-hypopnea index on CPAP therapy, as the machine reports it (events per hour of use)"},""" +
                """"effectivePeriod":{"start":"2026-11-14T12:00:00-07:00","end":"2026-11-15T12:00:00-07:00"},""" +
                """"valueQuantity":{"value":2.3,"unit":"events/h","system":"http://unitsofmeasure.org","code":"{events}/h"},""" +
                """"device":{"display":"ResMed AirSense 11 (copied by PAPRelay)"},""" +
                """"component":[""" +
                """{"code":{"coding":[{"system":"https://github.com/hahnfeld/paprelay/fhir/CodeSystem/cpap-night","code":"obstructive-apnea-index",""" +
                """"display":"Residual obstructive apnea index on CPAP therapy (events per hour of use)"}],"text":"Residual obstructive apnea index on CPAP therapy (events per hour of use)"},""" +
                """"valueQuantity":{"value":1.1,"unit":"events/h","system":"http://unitsofmeasure.org","code":"{events}/h"}},""" +
                """{"code":{"coding":[{"system":"https://github.com/hahnfeld/paprelay/fhir/CodeSystem/cpap-night","code":"central-apnea-index",""" +
                """"display":"Residual central (clear-airway) apnea index on CPAP therapy (events per hour of use)"}],"text":"Residual central (clear-airway) apnea index on CPAP therapy (events per hour of use)"},""" +
                """"valueQuantity":{"value":0,"unit":"events/h","system":"http://unitsofmeasure.org","code":"{events}/h"}},""" +
                """{"code":{"coding":[{"system":"https://github.com/hahnfeld/paprelay/fhir/CodeSystem/cpap-night","code":"hypopnea-index",""" +
                """"display":"Residual hypopnea index on CPAP therapy (events per hour of use)"}],"text":"Residual hypopnea index on CPAP therapy (events per hour of use)"},""" +
                """"valueQuantity":{"value":1.2,"unit":"events/h","system":"http://unitsofmeasure.org","code":"{events}/h"}}]}""",
            events,
        )
        // No sleep-study code is claimed for a therapy machine's figures.
        assertTrue(records.none { it.json.contains("loinc") })
        // Usage is given in hours for reading, and exactly in minutes beside it.
        assertTrue(records[0].json.contains(""""valueQuantity":{"value":7.2,"unit":"h""""))
        assertTrue(records[0].json.contains(""""code":"usage-minutes""""))
        assertTrue(records[0].json.contains("""{"value":432,"unit":"min""""))
        // The pressure record's headline is the therapy pressure; the mask pressure is beside it under its own name.
        assertTrue(records[3].json.contains(""""valueQuantity":{"value":10.42,"unit":"cmH2O""""))
        assertTrue(records[3].json.contains(""""code":"mask-pressure-95th""""))
        // A record's printed form does not carry its contents.
        assertEquals("FhirResource($tag-20261114-usage)", "${records[0]}")
    }

    @Test
    fun `a record whose headline figure is missing still carries the rest, and says no more than it has`() {
        val only = FhirObservations.forNight(night(figures = mapOf(NightFigure.LEAK_MAX to 5160L)), tag).single { it.id.endsWith("-leak") }.json
        assertFalse(only.contains(""""valueQuantity":{"value":51.6,"unit":"L/min","system":"http://unitsofmeasure.org","code":"L/min"},"device""""))
        assertTrue(only.contains(""""component":[{"code":{"coding":[{"system":"https://github.com/hahnfeld/paprelay/fhir/CodeSystem/cpap-night","code":"leak-max""""))
        // The top level has no value of its own, and is named for the group, not for the figure it lacks.
        assertFalse(only.substringBefore(""""component"""").contains("valueQuantity"))
        assertTrue(only.contains(""""code":"leak","display":"CPAP mask leak in the night""""))
        assertFalse(only.substringBefore(""""component"""").contains("95th"))
    }

    @Test
    fun `a night with everything gives five records that together hold every figure once`() {
        val full = night(figures = NightFigure.entries.associateWith { 100L })
        val records = FhirObservations.forNight(full, tag)
        assertEquals(5, records.size)
        val all = records.joinToString("") { it.json }
        for (figure in NightFigure.entries) assertEquals(1, Regex(""""code":"${figure.key}"""").findAll(all).count(), figure.key)
        assertTrue(records.all { it.json.length < 8_000 })
    }

    @Test
    fun `a day without therapy is one record saying so, and a day still running is none`() {
        val unused = FhirObservations.forNight(night(usage = 0, figures = emptyMap()), tag)
        assertEquals(1, unused.size)
        assertTrue(unused.single().json.contains(""""valueQuantity":{"value":0,"unit":"h""""))
        assertFailsWith<IllegalArgumentException> { FhirObservations.forNight(night(complete = false), tag) }
        assertFailsWith<IllegalArgumentException> { FhirObservations.forNight(night(), "has space") }
        assertFailsWith<IllegalArgumentException> { FhirObservations.forNight(night(), "x".repeat(25)) }
    }

    @Test
    fun `every figure has a name of its own`() {
        assertEquals(NightFigure.entries.size, NightFigure.entries.map { it.key }.toSet().size)
        assertEquals(NightFigure.entries.size, NightFigure.entries.map { it.column }.toSet().size)
        assertTrue(NightFigure.entries.all { Regex("[a-z0-9-]{2,30}").matches(it.key) })
        assertTrue(NightFigure.entries.all { NightFigure.ofKey(it.key) == it })
        assertNull(NightFigure.ofKey("nothing"))
        // The longest id still fits the 64 characters a record id may have.
        val longest = FhirObservations.idsFor(night(), "m-" + "f".repeat(22)).maxOf { it.length }
        assertTrue(longest <= 64, "$longest")
    }
}
