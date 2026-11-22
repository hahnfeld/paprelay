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
        val csv = NightReport.csv(listOf(unused, night(), running), NightFigure.entries)
        val lines = csv.split("\r\n")
        // The header is the first line, and every column says what it is measured in.
        val header = lines[0].split(',')
        assertEquals(listOf("night_of", "usage_minutes", "usage_hours", "sessions"), header.take(4))
        assertEquals(NightFigure.entries.map { it.column } + listOf("session_starts_corrected", "session_starts_machine_clock", "session_lengths_minutes"), header.drop(4))
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
        // Each figure about the person once; none about the machine.
        for (figure in NightFigure.entries) assertEquals(if (figure.healthRecord) 1 else 0, Regex(""""code":"${figure.key}"""").findAll(all).count(), figure.key)
        assertTrue(records.all { it.json.length < 12_000 })
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

    private val start = java.time.OffsetDateTime.parse("2026-11-14T12:00:00-07:00").toInstant().toEpochMilli()

    private fun session(afterNoonMinutes: Long, minutes: Int) = NightSession(com.everysoft.paprelay.core.MachineTime(start + afterNoonMinutes * 60_000), minutes)

    @Test
    fun `a night keeps its sessions one by one when the machine listed them, and the table lists them`() {
        val sessions = listOf(session(11 * 60 + 5, 203), session(16 * 60 + 15, 102))
        val listed = Night(LocalDate.of(2026, 11, 14), -420, 305, 2, mapOf(NightFigure.AHI to 230L), true, sessionList = sessions)
        assertEquals(sessions, listed.sessionList)
        // Not the count the night gives, or a session outside its day: refused.
        assertFailsWith<IllegalArgumentException> { Night(LocalDate.of(2026, 11, 14), -420, 305, 3, emptyMap(), true, sessionList = sessions) }
        assertFailsWith<IllegalArgumentException> { Night(LocalDate.of(2026, 11, 14), -420, 5, 1, emptyMap(), true, sessionList = listOf(session(-1, 5))) }
        assertFailsWith<IllegalArgumentException> { Night(LocalDate.of(2026, 11, 14), -420, 5, 1, emptyMap(), true, sessionList = listOf(session(24 * 60 + 1, 5))) }
        // Both ends of the day are the day's: the machine files a session that starts on either.
        Night(LocalDate.of(2026, 11, 14), -420, 5, 2, emptyMap(), true, sessionList = listOf(session(0, 5), session(24 * 60, 0)))
        assertFailsWith<IllegalArgumentException> { session(0, 24 * 60 + 1) }
        assertEquals("NightSession", "${sessions[0]}")

        // The fingerprint follows the sessions; a night without the list keeps the fingerprint it always had.
        val plain = Night(LocalDate.of(2026, 11, 14), -420, 305, 2, mapOf(NightFigure.AHI to 230L), true)
        assertNotEquals(plain.fingerprint(), listed.fingerprint())
        val moved = Night(LocalDate.of(2026, 11, 14), -420, 305, 2, mapOf(NightFigure.AHI to 230L), true, sessionList = listOf(sessions[0], session(16 * 60 + 15, 101)))
        assertNotEquals(listed.fingerprint(), moved.fingerprint())

        // A corrected start is part of what the night says.
        val corrected = Night(LocalDate.of(2026, 11, 14), -420, 305, 2, mapOf(NightFigure.AHI to 230L), true, sessionList = sessions.map { it.withCorrected(it.start.epochMs - 361_600) })
        assertNotEquals(listed.fingerprint(), corrected.fingerprint())
        val half = Night(LocalDate.of(2026, 11, 14), -420, 305, 2, emptyMap(), true, sessionList = listOf(sessions[0].withCorrected(1_794_000_000_000L), sessions[1]))

        val csv = NightReport.csv(listOf(listed, plain, corrected, half), listOf(NightFigure.AHI))
        val lines = csv.split("\r\n")
        assertEquals("night_of,usage_minutes,usage_hours,sessions,ahi_events_per_hour,session_starts_corrected,session_starts_machine_clock,session_lengths_minutes", lines[0])
        // By the machine's clock, at the night's own offset; in its order; separated by spaces, never by commas.
        assertEquals("2026-11-14,305,5.08,2,2.3,,2026-11-14T23:05:00-07:00 2026-11-15T04:15:00-07:00,203 102", lines[1])
        // Not known one by one: empty, not guessed.
        assertEquals("2026-11-14,305,5.08,2,2.3,,,", lines[2])
        // Corrected: six minutes earlier in true time, beside the machine's own.
        assertEquals("2026-11-14,305,5.08,2,2.3,2026-11-14T22:58:58.400-07:00 2026-11-15T04:08:58.400-07:00,2026-11-14T23:05:00-07:00 2026-11-15T04:15:00-07:00,203 102", lines[3])
        // Corrected for some sessions only: the corrected list is not given, rather than given with gaps.
        assertEquals("2026-11-14,305,5.08,2,,,2026-11-14T23:05:00-07:00 2026-11-15T04:15:00-07:00,203 102", lines[4])
        // Without the sessions, as shared in a message.
        assertEquals("night_of,usage_minutes,usage_hours,sessions,ahi_events_per_hour", NightReport.csv(listOf(listed), listOf(NightFigure.AHI), withSessions = false).lines()[0])
        assertFailsWith<IllegalArgumentException> { NightReport.csv(listOf(listed), listOf(NightFigure.AHI, NightFigure.AHI)) }
    }

    @Test
    fun `times in the tables are exact, with their offset`() {
        assertEquals("2026-11-14T23:05:00-07:00", CsvTime.format(start + 665 * 60_000L, -420))
        assertEquals("2026-11-15T06:05:00.250Z", CsvTime.format(start + 665 * 60_000L + 250, 0))
        assertEquals("2026-11-15T11:35:00.001+05:30", CsvTime.format(start + 665 * 60_000L + 1, 330))
        // Whatever the phone's language: the digits are the plain ones.
        val before = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("ar-EG"))
            assertEquals("2026-11-15T06:05:00.250Z", CsvTime.format(start + 665 * 60_000L + 250, 0))
        } finally {
            java.util.Locale.setDefault(before)
        }
    }

    @Test
    fun `the new figures go to the leak and breathing records, and the machine's own only to the table`() {
        assertEquals(3, FhirObservations.FORMAT)
        val figures = mapOf(
            NightFigure.LEAK_95 to 1440L, NightFigure.LEAK_70 to 480L, NightFigure.RESPIRATORY_RATE_MEDIAN to 1320L,
            NightFigure.RESPIRATORY_RATE_95 to 1520L, NightFigure.RESPIRATORY_FLOW_5 to 16L, NightFigure.TIDAL_VOLUME_MAX to 90L,
            NightFigure.HUMIDIFIER_TEMPERATURE_MEDIAN to 2950L, NightFigure.BLOWER_FLOW_MEDIAN to 48L, NightFigure.AMBIENT_HUMIDITY_MEDIAN to 1120L,
        )
        val records = FhirObservations.forNight(night(figures = figures), tag).associateBy { it.id.substringAfterLast('-') }
        assertEquals(setOf("usage", "leak", "breathing"), records.keys)
        assertTrue(records.getValue("leak").json.contains(""""code":"leak-70th","display":"Mask leak, 70th percentile"}],"text":"Mask leak, 70th percentile"},"valueQuantity":{"value":4.8,"unit":"L/min""""))
        assertTrue(records.getValue("breathing").json.contains(""""code":"respiratory-rate-95th""""))
        assertTrue(records.getValue("breathing").json.contains("""{"value":0.16,"unit":"L/min","system":"http://unitsofmeasure.org","code":"L/min"}"""))
        assertTrue(records.getValue("breathing").json.contains("""{"value":0.9,"unit":"mL","system":"http://unitsofmeasure.org","code":"mL"}"""))
        assertTrue(records.getValue("breathing").json.contains(""""code":"tidal-volume-max""""))
        val all = records.values.joinToString("") { it.json }
        for (machine in listOf("humidifier", "blower", "humidity", "Cel", "mg/L")) assertFalse(all.contains(machine), machine)
        // In the table they are there, with their units.
        val header = NightReport.csv(listOf(night(figures = figures)), NightFigure.entries).lines()[0].split(',')
        for (column in listOf("leak_70th_l_per_min", "respiratory_flow_5th_l_per_min", "tidal_volume_max_ml", "blower_flow_median_l_per_min", "humidifier_temperature_median_celsius", "ambient_humidity_median_mg_per_l", "heated_tube_power_median_percent", "blower_pressure_95th_cmh2o")) {
            assertTrue(column in header, column)
        }
        assertEquals(setOf(NightFigure.BLOWER_PRESSURE_5, NightFigure.BLOWER_PRESSURE_95, NightFigure.BLOWER_FLOW_MEDIAN, NightFigure.AMBIENT_HUMIDITY_MEDIAN, NightFigure.HUMIDIFIER_TEMPERATURE_MEDIAN, NightFigure.HUMIDIFIER_POWER_MEDIAN, NightFigure.HEATED_TUBE_TEMPERATURE_MEDIAN, NightFigure.HEATED_TUBE_POWER_MEDIAN), NightFigure.entries.filter { !it.healthRecord }.toSet())
    }

    @Test
    fun `the columns of the table before 0_3_0 keep their places, and the shared text keeps to them`() {
        assertEquals(
            listOf(
                "ahi", "apnea-index", "hypopnea-index", "obstructive-apnea-index", "central-apnea-index", "unclassified-apnea-index", "rera-index",
                "leak-median", "leak-95th", "leak-max", "therapy-pressure-median", "therapy-pressure-95th", "therapy-pressure-max",
                "expiratory-pressure-median", "expiratory-pressure-95th", "expiratory-pressure-max", "mask-pressure-median", "mask-pressure-95th",
                "mask-pressure-max", "respiratory-rate-median", "tidal-volume-median", "minute-ventilation-median",
            ),
            NightReport.SHARED.map { it.key },
        )
        assertEquals(NightReport.SHARED, NightFigure.entries.take(NightReport.SHARED.size))
    }

    @Test
    fun `what goes to the health records has a fingerprint of its own, which the machine's figures and the sessions do not move`() {
        val base = mapOf(NightFigure.AHI to 230L, NightFigure.TIDAL_VOLUME_MEDIAN to 48_000L)
        val a = Night(LocalDate.of(2026, 11, 14), -420, 305, 1, base, true)
        val humidifier = Night(LocalDate.of(2026, 11, 14), -420, 305, 1, base + (NightFigure.HUMIDIFIER_POWER_MEDIAN to 3300L), true)
        val listed = Night(LocalDate.of(2026, 11, 14), -420, 305, 1, base, true, sessionList = listOf(session(11 * 60, 305)))
        assertEquals(a.healthFingerprint(), humidifier.healthFingerprint())
        assertEquals(a.healthFingerprint(), listed.healthFingerprint())
        assertNotEquals(a.fingerprint(), humidifier.fingerprint())
        assertNotEquals(a.fingerprint(), listed.fingerprint())
        assertNotEquals(a.healthFingerprint(), Night(LocalDate.of(2026, 11, 14), -420, 305, 1, base + (NightFigure.AHI to 231L), true).healthFingerprint())
        // Tidal volume is in millilitres, flows in litres a minute.
        assertEquals(NightUnit.MILLILITRES, NightFigure.TIDAL_VOLUME_MEDIAN.unit)
        assertEquals("480", a.value(NightFigure.TIDAL_VOLUME_MEDIAN)!!.toPlainString())
        assertEquals(setOf(NightUnit.LITRES_PER_MINUTE), listOf(NightFigure.RESPIRATORY_FLOW_5, NightFigure.RESPIRATORY_FLOW_95, NightFigure.BLOWER_FLOW_MEDIAN, NightFigure.LEAK_95).map { it.unit }.toSet())
    }
}
