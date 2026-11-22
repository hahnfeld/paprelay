// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.core.nights

import java.time.LocalDate

/**
 * The person's name and date of birth, given by them so that a reader of health records that
 * insists on knowing whose records these are (Google Health) shows them. Optional, and never
 * passed anywhere but into the one Patient record in the phone's health records.
 *
 * @property given the given names, in order.
 * @property family the family name.
 * @property birthDate the date of birth: not before [MIN_YEAR], and not after [today].
 */
public class PatientDetails(
    given: List<String>,
    family: String,
    public val birthDate: LocalDate,
    /** The date it is now, where the date of birth is checked. */
    today: LocalDate = LocalDate.now(),
) {
    public val given: List<String> = given.map { it.trim() }.filter { it.isNotEmpty() }
    public val family: String = family.trim()

    init {
        require(this.given.isNotEmpty() && this.family.isNotEmpty()) { "a name is missing" }
        require((this.given + this.family).all { it.length <= MAX_PART && it.none { char -> char < ' ' } }) { "not a name" }
        require(this.given.size <= MAX_GIVEN) { "too many given names" }
        require(birthDate.year >= MIN_YEAR && !birthDate.isAfter(today)) { "not a date of birth" }
    }

    /** The whole name as it is read: the given names, then the family name. */
    public val text: String get() = (given + family).joinToString(" ")

    override fun equals(other: Any?): Boolean = other is PatientDetails && given == other.given && family == other.family && birthDate == other.birthDate

    override fun hashCode(): Int = (given.hashCode() * 31 + family.hashCode()) * 31 + birthDate.hashCode()

    /** Shows nothing: a name and a date of birth must not reach a log by way of a string template. */
    override fun toString(): String = "PatientDetails"

    public companion object {
        public const val MAX_PART: Int = 100
        public const val MAX_GIVEN: Int = 10
        public const val MIN_YEAR: Int = 1900

        /** The given names as typed in one field: separated by spaces. */
        public fun givenFrom(typed: String): List<String> = typed.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    }
}
