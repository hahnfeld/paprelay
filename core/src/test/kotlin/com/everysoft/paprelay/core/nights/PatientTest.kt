// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.core.nights

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PatientTest {
    private val tag = "m-13f8d92ec4854105"

    /** Plainly not a real person. */
    private val details = PatientDetails(listOf("Testy", "Example"), "McTestface", LocalDate.of(1970, 1, 2))

    private val night = Night(LocalDate.of(2026, 11, 14), -420, 432, 2, mapOf(NightFigure.AHI to 230L, NightFigure.LEAK_95 to 1440L), true)

    @Test
    fun `the Patient record is exactly the name and the date of birth, under its fixed id`() {
        val patient = FhirObservations.patient(details)
        assertEquals("paprelay-patient", patient.id)
        assertEquals(
            """{"resourceType":"Patient","id":"paprelay-patient","name":[{"use":"official","family":"McTestface","given":["Testy","Example"],"text":"Testy Example McTestface"}],"birthDate":"1970-01-02"}""",
            patient.json,
        )
        // A name with a quote or a backslash in it stays one JSON string.
        val odd = FhirObservations.patient(PatientDetails(listOf("A\"B"), "C\\D", LocalDate.of(1980, 5, 6)))
        assertTrue(odd.json.contains(""""family":"C\\D","given":["A\"B"]"""))
        assertEquals("FhirResource(paprelay-patient)", "$patient")
    }

    @Test
    fun `a night's records name the Patient only while asked to`() {
        val without = FhirObservations.forNight(night, tag)
        val with = FhirObservations.forNight(night, tag, withSubject = true)
        assertTrue(without.none { it.json.contains("subject") })
        assertTrue(with.all { it.json.contains(""","subject":{"reference":"Patient/paprelay-patient"}""") })
        // The same records otherwise, with the same ids.
        assertEquals(without.map { it.id }, with.map { it.id })
        assertEquals(without.map { it.json }, with.map { it.json.replace(""","subject":{"reference":"Patient/paprelay-patient"}""", "") })
        assertEquals("Patient/paprelay-patient", FhirObservations.PATIENT_REFERENCE)
        assertEquals(5, FhirObservations.FORMAT)
    }

    @Test
    fun `the details are checked, and read out as one name`() {
        assertEquals("Testy Example McTestface", details.text)
        assertEquals(listOf("Testy", "Example"), PatientDetails.givenFrom("  Testy   Example "))
        assertEquals(details, PatientDetails(listOf(" Testy ", "Example"), " McTestface ", LocalDate.of(1970, 1, 2)))
        assertEquals("PatientDetails", "$details")
        assertNotEquals(details, PatientDetails(listOf("Testy", "Example"), "McTestface", LocalDate.of(1970, 1, 3)))
        assertFailsWith<IllegalArgumentException> { PatientDetails(emptyList(), "McTestface", LocalDate.of(1970, 1, 2)) }
        assertFailsWith<IllegalArgumentException> { PatientDetails(listOf("Testy"), " ", LocalDate.of(1970, 1, 2)) }
        assertFailsWith<IllegalArgumentException> { PatientDetails(listOf("Testy"), "Mc\nTestface", LocalDate.of(1970, 1, 2)) }
        assertFailsWith<IllegalArgumentException> { PatientDetails(listOf("Testy"), "x".repeat(101), LocalDate.of(1970, 1, 2)) }
        assertFailsWith<IllegalArgumentException> { PatientDetails(listOf("Testy"), "McTestface", LocalDate.of(1899, 12, 31)) }
        PatientDetails(listOf("Testy"), "McTestface", LocalDate.of(1900, 1, 1))
        // Not born in the future: today is the latest date of birth.
        val today = LocalDate.of(2026, 11, 22)
        PatientDetails(listOf("Testy"), "McTestface", today, today = today)
        assertFailsWith<IllegalArgumentException> { PatientDetails(listOf("Testy"), "McTestface", today.plusDays(1), today = today) }
        assertFailsWith<IllegalArgumentException> { PatientDetails(listOf("Testy"), "McTestface", LocalDate.now().plusDays(1)) }
    }
}
