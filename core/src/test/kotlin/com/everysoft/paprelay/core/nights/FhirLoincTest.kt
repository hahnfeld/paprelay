// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.core.nights

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The LOINC codes beside PAPRelay's own: only the PAP device panel's, only on the figures they
 * measure, never a sleep-study or a vital-signs code.
 */
class FhirLoincTest {
    private val night = Night(
        LocalDate.of(2026, 11, 14), -420, 432, 2,
        mapOf(
            NightFigure.AHI to 230, NightFigure.OBSTRUCTIVE_APNEA_INDEX to 110, NightFigure.CENTRAL_APNEA_INDEX to 0,
            NightFigure.HYPOPNEA_INDEX to 120, NightFigure.LEAK_MEDIAN to 600, NightFigure.LEAK_70 to 900, NightFigure.LEAK_95 to 1440,
            NightFigure.LEAK_MAX to 3000, NightFigure.THERAPY_PRESSURE_MEDIAN to 900, NightFigure.THERAPY_PRESSURE_95 to 1042,
            NightFigure.THERAPY_PRESSURE_MAX to 1100, NightFigure.MASK_PRESSURE_95 to 960, NightFigure.RESPIRATORY_RATE_MEDIAN to 1500,
        ),
        true,
    )

    private val expected = mapOf(
        "usage" to (
            """{"resourceType":"Observation","id":"m-1-20261114-usage","status":"final","category":[{"coding":[{"system":"http://terminology.hl7.org/CodeSystem/observation-category","code":"laboratory","display":"Laboratory"}]}],"code":{"coding":[{"system":"https://github.com/hahnfeld/paprelay/fhir/CodeSystem/cpap-night","code":"usage","display":"CPAP therapy usage in the night (hours)"}""" +
            """,{"system":"http://loinc.org","code":"69988-4","display":"Positive airway pressure device usage 24 hour"}],"text":"CPAP therapy usage in the night (hours)"}""" +
            ""","effectivePeriod":{"start":"2026-11-14T12:00:00-07:00","end":"2026-11-15T12:00:00-07:00"},"valueQuantity":{"value":7.2,"unit":"h","system":"http://unitsofmeasure.org","code":"h"}""" +
            ""","device":{"display":"ResMed AirSense 11 (copied by PAPRelay)"}""" +
            ""","component":[{"code":{"coding":[{"system":"https://github.com/hahnfeld/paprelay/fhir/CodeSystem/cpap-night","code":"usage-minutes","display":"CPAP therapy usage in the night (minutes)"}],"text":"CPAP therapy usage in the night (minutes)"},"valueQuantity":{"value":432,"unit":"min","system":"http://unitsofmeasure.org","code":"min"}}""" +
            """,{"code":{"coding":[{"system":"https://github.com/hahnfeld/paprelay/fhir/CodeSystem/cpap-night","code":"sessions","display":"Times the mask was put on in the night"}],"text":"Times the mask was put on in the night"},"valueQuantity":{"value":2,"unit":"times","system":"http://unitsofmeasure.org","code":"{times}"}}]}"""
        ),
        "events" to (
            """{"resourceType":"Observation","id":"m-1-20261114-events","status":"final","category":[{"coding":[{"system":"http://terminology.hl7.org/CodeSystem/observation-category","code":"laboratory","display":"Laboratory"}]}],"code":{"coding":[{"system":"https://github.com/hahnfeld/paprelay/fhir/CodeSystem/cpap-night","code":"ahi","display":"Residual apnea-hypopnea index on CPAP therapy, as the machine reports it (events per hour of use)"}""" +
            """,{"system":"http://loinc.org","code":"69990-0","display":"Apnea hypopnea index 24 hour"}],"text":"Residual apnea-hypopnea index on CPAP therapy, as the machine reports it (events per hour of use)"}""" +
            ""","effectivePeriod":{"start":"2026-11-14T12:00:00-07:00","end":"2026-11-15T12:00:00-07:00"},"valueQuantity":{"value":2.3,"unit":"events/h","system":"http://unitsofmeasure.org","code":"{events}/h"}""" +
            ""","device":{"display":"ResMed AirSense 11 (copied by PAPRelay)"}""" +
            ""","component":[{"code":{"coding":[{"system":"https://github.com/hahnfeld/paprelay/fhir/CodeSystem/cpap-night","code":"obstructive-apnea-index","display":"Residual obstructive apnea index on CPAP therapy (events per hour of use)"}""" +
            """,{"system":"http://loinc.org","code":"70002-1","display":"Obstructive apnea index 24 hour"}],"text":"Residual obstructive apnea index on CPAP therapy (events per hour of use)"},"valueQuantity":{"value":1.1,"unit":"events/h","system":"http://unitsofmeasure.org","code":"{events}/h"}}""" +
            """,{"code":{"coding":[{"system":"https://github.com/hahnfeld/paprelay/fhir/CodeSystem/cpap-night","code":"central-apnea-index","display":"Residual central (clear-airway) apnea index on CPAP therapy (events per hour of use)"}""" +
            """,{"system":"http://loinc.org","code":"70003-9","display":"Central apnea index 24 hour"}],"text":"Residual central (clear-airway) apnea index on CPAP therapy (events per hour of use)"},"valueQuantity":{"value":0,"unit":"events/h","system":"http://unitsofmeasure.org","code":"{events}/h"}}""" +
            """,{"code":{"coding":[{"system":"https://github.com/hahnfeld/paprelay/fhir/CodeSystem/cpap-night","code":"hypopnea-index","display":"Residual hypopnea index on CPAP therapy (events per hour of use)"}],"text":"Residual hypopnea index on CPAP therapy (events per hour of use)"},"valueQuantity":{"value":1.2,"unit":"events/h","system":"http://unitsofmeasure.org","code":"{events}/h"}}]}"""
        ),
        "leak" to (
            """{"resourceType":"Observation","id":"m-1-20261114-leak","status":"final","category":[{"coding":[{"system":"http://terminology.hl7.org/CodeSystem/observation-category","code":"laboratory","display":"Laboratory"}]}],"code":{"coding":[{"system":"https://github.com/hahnfeld/paprelay/fhir/CodeSystem/cpap-night","code":"leak-95th","display":"CPAP mask leak in the night, 95th percentile"}""" +
            """,{"system":"http://loinc.org","code":"69997-5","display":"Leak.95% 24 hour maximum Positive airway pressure device"}],"text":"CPAP mask leak in the night, 95th percentile"}""" +
            ""","effectivePeriod":{"start":"2026-11-14T12:00:00-07:00","end":"2026-11-15T12:00:00-07:00"},"valueQuantity":{"value":14.4,"unit":"L/min","system":"http://unitsofmeasure.org","code":"L/min"}""" +
            ""","device":{"display":"ResMed AirSense 11 (copied by PAPRelay)"}""" +
            ""","component":[{"code":{"coding":[{"system":"https://github.com/hahnfeld/paprelay/fhir/CodeSystem/cpap-night","code":"leak-median","display":"Mask leak, median"}""" +
            """,{"system":"http://loinc.org","code":"70001-3","display":"Leak 24 hour median Positive airway pressure device"}],"text":"Mask leak, median"},"valueQuantity":{"value":6,"unit":"L/min","system":"http://unitsofmeasure.org","code":"L/min"}}""" +
            """,{"code":{"coding":[{"system":"https://github.com/hahnfeld/paprelay/fhir/CodeSystem/cpap-night","code":"leak-70th","display":"Mask leak, 70th percentile"}],"text":"Mask leak, 70th percentile"},"valueQuantity":{"value":9,"unit":"L/min","system":"http://unitsofmeasure.org","code":"L/min"}}""" +
            """,{"code":{"coding":[{"system":"https://github.com/hahnfeld/paprelay/fhir/CodeSystem/cpap-night","code":"leak-max","display":"Mask leak, maximum"}""" +
            """,{"system":"http://loinc.org","code":"69998-3","display":"Positive airway pressure device Leak"}],"text":"Mask leak, maximum"},"valueQuantity":{"value":30,"unit":"L/min","system":"http://unitsofmeasure.org","code":"L/min"}}]}"""
        ),
        "pressure" to (
            """{"resourceType":"Observation","id":"m-1-20261114-pressure","status":"final","category":[{"coding":[{"system":"http://terminology.hl7.org/CodeSystem/observation-category","code":"laboratory","display":"Laboratory"}]}],"code":{"coding":[{"system":"https://github.com/hahnfeld/paprelay/fhir/CodeSystem/cpap-night","code":"therapy-pressure-95th","display":"CPAP therapy pressure in the night, 95th percentile"}""" +
            """,{"system":"http://loinc.org","code":"69986-8","display":"Pressure.continuous positive airway.95% 24 hour maximum Respiratory system"}],"text":"CPAP therapy pressure in the night, 95th percentile"}""" +
            ""","effectivePeriod":{"start":"2026-11-14T12:00:00-07:00","end":"2026-11-15T12:00:00-07:00"},"valueQuantity":{"value":10.42,"unit":"cmH2O","system":"http://unitsofmeasure.org","code":"cm[H2O]"}""" +
            ""","device":{"display":"ResMed AirSense 11 (copied by PAPRelay)"}""" +
            ""","component":[{"code":{"coding":[{"system":"https://github.com/hahnfeld/paprelay/fhir/CodeSystem/cpap-night","code":"therapy-pressure-median","display":"Therapy pressure (inspiratory), median"}""" +
            """,{"system":"http://loinc.org","code":"70000-5","display":"Continuous positive airway pressure 24 hour median Respiratory system"}],"text":"Therapy pressure (inspiratory), median"},"valueQuantity":{"value":9,"unit":"cmH2O","system":"http://unitsofmeasure.org","code":"cm[H2O]"}}""" +
            """,{"code":{"coding":[{"system":"https://github.com/hahnfeld/paprelay/fhir/CodeSystem/cpap-night","code":"therapy-pressure-max","display":"Therapy pressure (inspiratory), maximum"}""" +
            """,{"system":"http://loinc.org","code":"69987-6","display":"Continuous positive airway pressure 24 hour maximum Respiratory system"}],"text":"Therapy pressure (inspiratory), maximum"},"valueQuantity":{"value":11,"unit":"cmH2O","system":"http://unitsofmeasure.org","code":"cm[H2O]"}}""" +
            """,{"code":{"coding":[{"system":"https://github.com/hahnfeld/paprelay/fhir/CodeSystem/cpap-night","code":"mask-pressure-95th","display":"Mean mask pressure, 95th percentile"}],"text":"Mean mask pressure, 95th percentile"},"valueQuantity":{"value":9.6,"unit":"cmH2O","system":"http://unitsofmeasure.org","code":"cm[H2O]"}}]}"""
        ),
        "breathing" to (
            """{"resourceType":"Observation","id":"m-1-20261114-breathing","status":"final","category":[{"coding":[{"system":"http://terminology.hl7.org/CodeSystem/observation-category","code":"laboratory","display":"Laboratory"}]}],"code":{"coding":[{"system":"https://github.com/hahnfeld/paprelay/fhir/CodeSystem/cpap-night","code":"respiratory-rate-median","display":"Respiratory rate during CPAP therapy in the night, median"}],"text":"Respiratory rate during CPAP therapy in the night, median"}""" +
            ""","effectivePeriod":{"start":"2026-11-14T12:00:00-07:00","end":"2026-11-15T12:00:00-07:00"},"valueQuantity":{"value":15,"unit":"breaths/min","system":"http://unitsofmeasure.org","code":"{breaths}/min"}""" +
            ""","device":{"display":"ResMed AirSense 11 (copied by PAPRelay)"}}"""
        ),
    )

    @Test
    fun `each record is exactly as written, with LOINC's coding second where it has the same measure`() {
        val records = FhirObservations.forNight(night, "m-1")
        assertEquals(expected.keys.map { "m-1-20261114-$it" }, records.map { it.id })
        for (record in records) assertEquals(expected.getValue(record.id.substringAfterLast('-')), record.json, record.id)
    }

    /** Every code given in a coding of the given system, in all the records of a night with every figure. */
    private fun codes(system: String): List<String> {
        val full = Night(LocalDate.of(2026, 11, 14), -420, 432, 2, NightFigure.entries.associateWith { 100L }, true)
        val all = FhirObservations.forNight(full, "m-1") + FhirObservations.forNight(full, "m-1", withSubject = true)
        return all.flatMap { record -> Regex("""\{"system":"${Regex.escape(system)}","code":"([^"]+)"""").findAll(record.json).map { it.groupValues[1] }.toList() }
    }

    @Test
    fun `no record or component carries a vital-signs code, which would move it out of lab results`() {
        val loinc = codes("http://loinc.org")
        assertEquals(10, loinc.size / 2)
        for (code in FhirObservations.VITAL_SIGNS) assertTrue(code !in loinc, code)
        assertEquals(
            setOf("85353-1", "9279-1", "8867-4", "2708-6", "8310-5", "8302-2", "9843-4", "29463-7", "39156-5", "85354-9", "8480-6", "8462-4"),
            FhirObservations.VITAL_SIGNS,
        )
    }

    @Test
    fun `every LOINC code given is one of the ten allowed, each given once a night`() {
        assertEquals(
            setOf("69988-4", "69990-0", "69997-5", "69986-8", "70002-1", "70003-9", "70001-3", "69998-3", "70000-5", "69987-6"),
            FhirObservations.LOINC_CODES,
        )
        val loinc = codes("http://loinc.org")
        assertEquals(FhirObservations.LOINC_CODES, loinc.toSet())
        // Twice: the night written without a subject and with one.
        assertTrue(loinc.groupingBy { it }.eachCount().values.all { it == 2 })
        // The own code is always there, and first.
        val own = codes(FhirObservations.CODE_SYSTEM)
        assertEquals(own.size, own.toSet().size * 2)
    }

    @Test
    fun `a record without its headline figure is named for its group, with no LOINC code`() {
        val leakless = Night(LocalDate.of(2026, 11, 14), -420, 432, 2, mapOf(NightFigure.LEAK_70 to 900L, NightFigure.THERAPY_PRESSURE_MEDIAN to 900L), true)
        val records = FhirObservations.forNight(leakless, "m-1").associateBy { it.id.substringAfterLast('-') }
        assertTrue("loinc" !in records.getValue("leak").json)
        // The component that LOINC has a code for keeps it; the top level, without its figure, has none.
        val pressure = records.getValue("pressure").json
        assertTrue(""""code":"pressure","display":"CPAP pressure in the night"}],""" in pressure)
        assertTrue(""""code":"70000-5"""" in pressure)
    }
}
