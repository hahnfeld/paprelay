// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.core.nights

import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** What a night's figure is measured in. [ucum] is its code in the Unified Code for Units of Measure. */
public enum class NightUnit(public val symbol: String, public val ucum: String, public val columnSuffix: String) {
    EVENTS_PER_HOUR("events/h", "{events}/h", "events_per_hour"),
    LITRES_PER_MINUTE("L/min", "L/min", "l_per_min"),
    CM_H2O("cmH2O", "cm[H2O]", "cmh2o"),
    BREATHS_PER_MINUTE("breaths/min", "{breaths}/min", "breaths_per_min"),
    LITRES("L", "L", "l"),
    HOURS("h", "h", "hours"),
    MINUTES("min", "min", "minutes"),
}

/**
 * One of the machine's own figures for a night.
 *
 * Every one is the machine's figure, passed on: nothing here is worked out by PAPRelay
 * except a change of unit (leak from litres per second to litres per minute, which is exact).
 *
 * The indices are what a therapy machine can know: events it detected from airflow, per hour
 * that it was in use. They are not the indices of a sleep study, which are scored by other
 * rules and counted per hour of sleep; the labels say so, and no sleep-study code is attached.
 *
 * @property key a short stable name, used in files and in records. Never changed once released.
 * @property label what it is, in plain English, for files and records read by other programs.
 * @property maxHundredths the most it can plausibly be, in hundredths of its unit. A stored value
 *   beyond it is a misreading, not a measurement, and is left out.
 */
public enum class NightFigure(public val key: String, public val label: String, public val unit: NightUnit, public val maxHundredths: Long) {
    AHI("ahi", "Residual apnea-hypopnea index on CPAP therapy, as the machine reports it (events per hour of use)", NightUnit.EVENTS_PER_HOUR, 20_000),
    APNEA_INDEX("apnea-index", "Residual apnea index on CPAP therapy (events per hour of use)", NightUnit.EVENTS_PER_HOUR, 20_000),
    HYPOPNEA_INDEX("hypopnea-index", "Residual hypopnea index on CPAP therapy (events per hour of use)", NightUnit.EVENTS_PER_HOUR, 20_000),
    OBSTRUCTIVE_APNEA_INDEX("obstructive-apnea-index", "Residual obstructive apnea index on CPAP therapy (events per hour of use)", NightUnit.EVENTS_PER_HOUR, 20_000),
    CENTRAL_APNEA_INDEX("central-apnea-index", "Residual central (clear-airway) apnea index on CPAP therapy (events per hour of use)", NightUnit.EVENTS_PER_HOUR, 20_000),
    UNCLASSIFIED_APNEA_INDEX("unclassified-apnea-index", "Residual unclassified apnea index on CPAP therapy (events per hour of use)", NightUnit.EVENTS_PER_HOUR, 20_000),
    RERA_INDEX("rera-index", "Respiratory effort-related arousal index as the machine estimates it (events per hour of use)", NightUnit.EVENTS_PER_HOUR, 20_000),
    LEAK_MEDIAN("leak-median", "Mask leak, median", NightUnit.LITRES_PER_MINUTE, 20_000),
    LEAK_95("leak-95th", "Mask leak, 95th percentile", NightUnit.LITRES_PER_MINUTE, 20_000),
    LEAK_MAX("leak-max", "Mask leak, maximum", NightUnit.LITRES_PER_MINUTE, 20_000),
    THERAPY_PRESSURE_MEDIAN("therapy-pressure-median", "Therapy pressure (inspiratory), median", NightUnit.CM_H2O, 4_000),
    THERAPY_PRESSURE_95("therapy-pressure-95th", "Therapy pressure (inspiratory), 95th percentile", NightUnit.CM_H2O, 4_000),
    THERAPY_PRESSURE_MAX("therapy-pressure-max", "Therapy pressure (inspiratory), maximum", NightUnit.CM_H2O, 4_000),
    EXPIRATORY_PRESSURE_MEDIAN("expiratory-pressure-median", "Expiratory pressure, median", NightUnit.CM_H2O, 4_000),
    EXPIRATORY_PRESSURE_95("expiratory-pressure-95th", "Expiratory pressure, 95th percentile", NightUnit.CM_H2O, 4_000),
    EXPIRATORY_PRESSURE_MAX("expiratory-pressure-max", "Expiratory pressure, maximum", NightUnit.CM_H2O, 4_000),
    MASK_PRESSURE_MEDIAN("mask-pressure-median", "Mean mask pressure, median", NightUnit.CM_H2O, 4_000),
    MASK_PRESSURE_95("mask-pressure-95th", "Mean mask pressure, 95th percentile", NightUnit.CM_H2O, 4_000),
    MASK_PRESSURE_MAX("mask-pressure-max", "Mean mask pressure, maximum", NightUnit.CM_H2O, 4_000),
    RESPIRATORY_RATE_MEDIAN("respiratory-rate-median", "Respiratory rate during therapy, median", NightUnit.BREATHS_PER_MINUTE, 9_000),
    TIDAL_VOLUME_MEDIAN("tidal-volume-median", "Tidal volume during therapy, median", NightUnit.LITRES, 500),
    MINUTE_VENTILATION_MEDIAN("minute-ventilation-median", "Minute ventilation during therapy, median", NightUnit.LITRES_PER_MINUTE, 6_000),
    ;

    /** The figure's name as a table column, with its unit: `leak_95th_l_per_min`. */
    public val column: String get() = key.replace('-', '_') + "_" + unit.columnSuffix

    public companion object {
        private val byKey = entries.associateBy { it.key }

        public fun ofKey(key: String): NightFigure? = byKey[key]
    }
}

/**
 * One day of therapy as the machine summed it up: from its local noon to the next.
 *
 * @property date the calendar date, by the machine's clock, on which the day began: the
 *   evening of the night. A night from the 14th into the 15th is the 14th.
 * @property zoneOffsetMinutes how far the machine's local time was from UTC when the day began.
 * @property hours how long the day was: 24, or 23 or 25 where the clocks changed during it.
 * @property usageMinutes minutes of therapy in the day. Zero for a day the machine was not used,
 *   which is kept: that there was no therapy is itself worth knowing.
 * @property sessions how many times the mask went on.
 * @property hundredths each figure the machine recorded, in hundredths of its unit. A figure
 *   the record did not carry, or carried with a value that cannot be right, is absent, never zero.
 * @property complete false while the day is still running by the machine's clock: its figures
 *   may yet change, and it is shown as such and not passed on.
 */
public class Night(
    public val date: LocalDate,
    public val zoneOffsetMinutes: Int,
    public val usageMinutes: Int,
    public val sessions: Int,
    hundredths: Map<NightFigure, Long>,
    public val complete: Boolean,
    public val hours: Int = 24,
) {
    public val hundredths: Map<NightFigure, Long> = java.util.Collections.unmodifiableMap(java.util.EnumMap<NightFigure, Long>(NightFigure::class.java).apply { putAll(hundredths) })

    init {
        require(zoneOffsetMinutes in -MAX_ZONE_OFFSET..MAX_ZONE_OFFSET) { "not a zone offset" }
        require(hours in 23..25) { "not a day's length" }
        require(usageMinutes in 0..hours * 60) { "not a day's usage" }
        require(sessions in 0..MAX_SESSIONS) { "not a day's sessions" }
        require(this.hundredths.all { (figure, value) -> value in 0..figure.maxHundredths }) { "a figure out of range" }
    }

    /** The machine's local noon that begins the day, and the moment it ends. */
    public val start: OffsetDateTime get() = LocalDateTime.of(date, NOON).atOffset(ZoneOffset.ofTotalSeconds(zoneOffsetMinutes * 60))
    public val end: OffsetDateTime get() = start.plusHours(hours.toLong())

    /** A figure as an exact decimal in its unit, or null if the night does not have it. */
    public fun value(figure: NightFigure): BigDecimal? = hundredths[figure]?.let(::decimal)

    /** Hours of therapy, rounded to two places, for reading. [usageMinutes] is the exact figure. */
    public val usageHours: BigDecimal get() = BigDecimal(usageMinutes).divide(BigDecimal(60), 2, java.math.RoundingMode.HALF_UP).let(::plain)

    /**
     * Identifies exactly what this night says, so that a change in any figure is noticed and an
     * unchanged night is not passed on again. Holds no health value in readable form.
     */
    public fun fingerprint(): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val text = buildString {
            append(date).append(';').append(zoneOffsetMinutes).append(';').append(hours).append(';')
            append(usageMinutes).append(';').append(sessions).append(';').append(complete)
            for ((figure, value) in hundredths) append(';').append(figure.key).append('=').append(value)
        }
        return digest.digest(text.toByteArray(Charsets.US_ASCII)).joinToString("") { "%02x".format(it) }
    }

    /** Shows nothing: a night's figures are health data, and must not reach a log by way of a string template. */
    override fun toString(): String = "Night"

    public companion object {
        public const val MAX_ZONE_OFFSET: Int = 18 * 60
        public const val MAX_SESSIONS: Int = 500
        private val NOON = java.time.LocalTime.NOON
        private val HUNDRED = BigDecimal(100)

        /** Hundredths as a plain decimal with no needless zeros: 230 is "2.3", 1000 is "10". */
        public fun decimal(hundredths: Long): BigDecimal = plain(BigDecimal(hundredths).divide(HUNDRED))

        private fun plain(value: BigDecimal): BigDecimal {
            val stripped = value.stripTrailingZeros()
            return if (stripped.scale() < 0) stripped.setScale(0) else stripped
        }
    }
}
