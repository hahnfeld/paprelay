// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.core.nights

import com.everysoft.paprelay.core.MachineTime
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
    MILLILITRES("mL", "mL", "ml"),
    HOURS("h", "h", "hours"),
    MINUTES("min", "min", "minutes"),
    MILLIGRAMS_PER_LITRE("mg/L", "mg/L", "mg_per_l"),
    CELSIUS("°C", "Cel", "celsius"),
    PERCENT("%", "%", "percent"),
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
 * @property healthRecord whether it describes the person's therapy and goes to the health records.
 *   The others (humidifier, heated tube, blower) describe the machine, and go to the table only.
 *
 * New figures are added at the end, so that the columns of the table keep their places.
 *
 * Some figures here are named for a value the machine stores whose layout and factor have not
 * been compared with an SD card (see `SummaryMetric.confirmed` in the `as11` module). A night
 * never has one of them until that is done: the plan that makes nights reads only confirmed
 * values, and the table has a column only for what the plan can give. They are named here so
 * that confirming one is a change of one line in one place.
 */
public enum class NightFigure(
    public val key: String,
    public val label: String,
    public val unit: NightUnit,
    public val maxHundredths: Long,
    public val healthRecord: Boolean = true,
) {
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
    TIDAL_VOLUME_MEDIAN("tidal-volume-median", "Tidal volume during therapy, median", NightUnit.MILLILITRES, 500_000),
    MINUTE_VENTILATION_MEDIAN("minute-ventilation-median", "Minute ventilation during therapy, median", NightUnit.LITRES_PER_MINUTE, 6_000),

    // Added in 0.3.0.
    LEAK_70("leak-70th", "Mask leak, 70th percentile", NightUnit.LITRES_PER_MINUTE, 20_000),
    RESPIRATORY_RATE_95("respiratory-rate-95th", "Respiratory rate during therapy, 95th percentile", NightUnit.BREATHS_PER_MINUTE, 9_000),
    RESPIRATORY_RATE_MAX("respiratory-rate-max", "Respiratory rate during therapy, maximum", NightUnit.BREATHS_PER_MINUTE, 9_000),
    TIDAL_VOLUME_95("tidal-volume-95th", "Tidal volume during therapy, 95th percentile", NightUnit.MILLILITRES, 500_000),
    TIDAL_VOLUME_MAX("tidal-volume-max", "Tidal volume during therapy, maximum", NightUnit.MILLILITRES, 500_000),
    MINUTE_VENTILATION_95("minute-ventilation-95th", "Minute ventilation during therapy, 95th percentile", NightUnit.LITRES_PER_MINUTE, 6_000),
    MINUTE_VENTILATION_MAX("minute-ventilation-max", "Minute ventilation during therapy, maximum", NightUnit.LITRES_PER_MINUTE, 6_000),
    RESPIRATORY_FLOW_5("respiratory-flow-5th", "Respiratory flow during therapy, 5th percentile", NightUnit.LITRES_PER_MINUTE, 60_000),
    RESPIRATORY_FLOW_95("respiratory-flow-95th", "Respiratory flow during therapy, 95th percentile", NightUnit.LITRES_PER_MINUTE, 60_000),
    BLOWER_PRESSURE_5("blower-pressure-5th", "Pressure at the machine's blower, 5th percentile", NightUnit.CM_H2O, 5_000, healthRecord = false),
    BLOWER_PRESSURE_95("blower-pressure-95th", "Pressure at the machine's blower, 95th percentile", NightUnit.CM_H2O, 5_000, healthRecord = false),
    BLOWER_FLOW_MEDIAN("blower-flow-median", "Flow at the machine's blower, median", NightUnit.LITRES_PER_MINUTE, 60_000, healthRecord = false),
    AMBIENT_HUMIDITY_MEDIAN("ambient-humidity-median", "Humidity of the room air the machine takes in, median", NightUnit.MILLIGRAMS_PER_LITRE, 10_000, healthRecord = false),
    HUMIDIFIER_TEMPERATURE_MEDIAN("humidifier-temperature-median", "Humidifier temperature, median", NightUnit.CELSIUS, 10_000, healthRecord = false),
    HUMIDIFIER_POWER_MEDIAN("humidifier-power-median", "Humidifier power, median", NightUnit.PERCENT, 10_000, healthRecord = false),
    HEATED_TUBE_TEMPERATURE_MEDIAN("heated-tube-temperature-median", "Heated tube temperature, median", NightUnit.CELSIUS, 10_000, healthRecord = false),
    HEATED_TUBE_POWER_MEDIAN("heated-tube-power-median", "Heated tube power, median", NightUnit.PERCENT, 10_000, healthRecord = false),

    // Named, and not yet given by any night: the machine's values for these are not yet confirmed.
    TARGET_VENTILATION_MEDIAN("target-ventilation-median", "Target minute ventilation, median", NightUnit.LITRES_PER_MINUTE, 6_000),
    TARGET_VENTILATION_95("target-ventilation-95th", "Target minute ventilation, 95th percentile", NightUnit.LITRES_PER_MINUTE, 6_000),
    TARGET_VENTILATION_MAX("target-ventilation-max", "Target minute ventilation, maximum", NightUnit.LITRES_PER_MINUTE, 6_000),
    ALVEOLAR_VENTILATION_MEDIAN("alveolar-ventilation-median", "Alveolar minute ventilation during therapy, median", NightUnit.LITRES_PER_MINUTE, 6_000),
    ALVEOLAR_VENTILATION_95("alveolar-ventilation-95th", "Alveolar minute ventilation during therapy, 95th percentile", NightUnit.LITRES_PER_MINUTE, 6_000),
    ALVEOLAR_VENTILATION_MAX("alveolar-ventilation-max", "Alveolar minute ventilation during therapy, maximum", NightUnit.LITRES_PER_MINUTE, 6_000),
    IE_RATIO_MEDIAN("ie-ratio-median", "Ratio of breathing in to breathing out, median", NightUnit.PERCENT, 50_000),
    IE_RATIO_95("ie-ratio-95th", "Ratio of breathing in to breathing out, 95th percentile", NightUnit.PERCENT, 50_000),
    IE_RATIO_MAX("ie-ratio-max", "Ratio of breathing in to breathing out, maximum", NightUnit.PERCENT, 50_000),
    ;

    /** The figure's name as a table column, with its unit: `leak_95th_l_per_min`. */
    public val column: String get() = key.replace('-', '_') + "_" + unit.columnSuffix

    public companion object {
        private val byKey = entries.associateBy { it.key }

        public fun ofKey(key: String): NightFigure? = byKey[key]
    }
}

/**
 * One time the mask went on, as the machine listed it in the day's summary.
 *
 * @property start when it began, by the machine's own clock, uncorrected: a night's figures are
 *   all by the machine's clock (see [Night]).
 * @property minutes how long it lasted, as stored.
 * @property correctedMs the same start in true time, milliseconds since the epoch: [start] plus how
 *   far the machine's clock was measured to be off when the session was first copied. Null when
 *   that cannot be said with confidence (see the synchronising engine). Fixed once given.
 */
public class NightSession(public val start: MachineTime, public val minutes: Int, public val correctedMs: Long? = null) {
    init {
        require(minutes in 0..MAX_MINUTES) { "not a session's length" }
    }

    /** The same session with its corrected start, as it was first given. */
    public fun withCorrected(ms: Long?): NightSession = NightSession(start, minutes, ms)

    override fun equals(other: Any?): Boolean = other is NightSession && start == other.start && minutes == other.minutes && correctedMs == other.correctedMs

    override fun hashCode(): Int = (start.hashCode() * 31 + minutes) * 31 + correctedMs.hashCode()

    /** Shows nothing: when someone put the mask on is health data. */
    override fun toString(): String = "NightSession"

    public companion object {
        public const val MAX_MINUTES: Int = 24 * 60
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
 * @property sessionList each session, in the machine's order, when the record listed every one with
 *   its start and length. Null when that is not known (a session with no stored length). When
 *   known, there are [sessions] of them.
 */
public class Night(
    public val date: LocalDate,
    public val zoneOffsetMinutes: Int,
    public val usageMinutes: Int,
    public val sessions: Int,
    hundredths: Map<NightFigure, Long>,
    public val complete: Boolean,
    public val hours: Int = 24,
    sessionList: List<NightSession>? = null,
) {
    public val sessionList: List<NightSession>? = sessionList?.let { java.util.Collections.unmodifiableList(ArrayList(it)) }

    public val hundredths: Map<NightFigure, Long> = java.util.Collections.unmodifiableMap(java.util.EnumMap<NightFigure, Long>(NightFigure::class.java).apply { putAll(hundredths) })

    init {
        require(zoneOffsetMinutes in -MAX_ZONE_OFFSET..MAX_ZONE_OFFSET) { "not a zone offset" }
        require(hours in 23..25) { "not a day's length" }
        require(usageMinutes in 0..hours * 60) { "not a day's usage" }
        require(sessions in 0..MAX_SESSIONS) { "not a day's sessions" }
        require(this.hundredths.all { (figure, value) -> value in 0..figure.maxHundredths }) { "a figure out of range" }
        this.sessionList?.let { list ->
            require(list.size == sessions) { "not the day's sessions" }
            // A session belongs to the day it starts in; the machine lets one start on either end of it.
            val from = start.toInstant().toEpochMilli()
            val to = end.toInstant().toEpochMilli()
            require(list.all { it.start.epochMs in from..to }) { "a session outside its day" }
        }
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
     * unchanged night is not kept again. Holds no health value in readable form.
     */
    public fun fingerprint(): String = digest(forHealthRecords = false)

    /**
     * The same, of what goes to the health records only: a change in a figure that describes the
     * machine, or in the list of sessions, does not make the night's records be written again.
     */
    public fun healthFingerprint(): String = digest(forHealthRecords = true)

    private fun digest(forHealthRecords: Boolean): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val text = buildString {
            append(date).append(';').append(zoneOffsetMinutes).append(';').append(hours).append(';')
            append(usageMinutes).append(';').append(sessions).append(';').append(complete)
            for ((figure, value) in hundredths) if (figure.healthRecord || !forHealthRecords) append(';').append(figure.key).append('=').append(value)
            if (!forHealthRecords) sessionList?.let { list -> append(";sessions=").append(list.joinToString("/") { "${it.start.epochMs}+${it.minutes}=${it.correctedMs ?: "-"}" }) }
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
