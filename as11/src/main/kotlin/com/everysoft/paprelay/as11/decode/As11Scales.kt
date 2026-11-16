// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
//
// Field numbers, percentile layouts, Rice moduli and scale factors are facts
// taken from the airbreak-plus protocol notes and reference decoder (MIT):
// docs/as11/rpc_spools.md and python/lib/as11_spool.py. This file is the only
// place in PAPRelay where they are written down.
package com.everysoft.paprelay.as11.decode

import com.everysoft.paprelay.core.PapUnit
import com.everysoft.paprelay.core.Scale

/** The points of a distribution the machine reports for a night. */
public enum class Percentile { P5, P50, P70, P95, P100 }

/**
 * Which sub-field of a Summary metric holds which percentile. The numbering is
 * not uniform: sub-field 3 is the 70th percentile for leak and the 95th for
 * everything else.
 */
public enum class PercentileLayout(internal val bySubField: Map<Int, Percentile>) {
    LEAK(mapOf(2 to Percentile.P50, 3 to Percentile.P70, 4 to Percentile.P95, 5 to Percentile.P100)),
    STANDARD(mapOf(2 to Percentile.P50, 3 to Percentile.P95, 4 to Percentile.P100)),
    MEDIAN(mapOf(2 to Percentile.P50)),
    RANGE(mapOf(1 to Percentile.P5, 3 to Percentile.P95)),
}

private val HUNDREDTHS = Scale(1, 100)

/** Event rates in a Summary record: stored in hundredths of events per hour. */
public enum class SummaryIndex(public val field: Int) {
    AHI(7),
    APNEA(8),
    HYPOPNEA(9),
    OBSTRUCTIVE_APNEA(10),
    CENTRAL_APNEA(11),
    UNKNOWN_APNEA(12),
    RERA(13),
    ;

    public val scale: Scale get() = HUNDREDTHS
    public val unit: PapUnit get() = PapUnit.EVENTS_PER_HOUR
}

/**
 * Percentile metrics in a Summary record.
 *
 * Leak and the flows are in litres per **second** here; the per-minute log
 * ([MinuteSignal.LEAK]) uses litres per minute.
 *
 * Field 37 (respiratory flow, 5th and 95th percentile) is deliberately not
 * listed. Its low percentile is negative on any used night, and the references
 * do not say how a negative number is stored there; guessing would turn -0.30
 * into +0.59. It is skipped as an unknown field until a capture settles it.
 * The two references also differ on the flow divisors, so [BLOWER_FLOW] is to
 * be confirmed against the SD card before anything depends on it.
 */
public enum class SummaryMetric(
    public val field: Int,
    public val scale: Scale,
    public val unit: PapUnit,
    public val layout: PercentileLayout,
) {
    LEAK(14, HUNDREDTHS, PapUnit.LITRES_PER_SECOND, PercentileLayout.LEAK),
    INSPIRATORY_PRESSURE(15, HUNDREDTHS, PapUnit.CM_H2O, PercentileLayout.STANDARD),
    EXPIRATORY_PRESSURE(20, HUNDREDTHS, PapUnit.CM_H2O, PercentileLayout.STANDARD),
    MEAN_MASK_PRESSURE(21, HUNDREDTHS, PapUnit.CM_H2O, PercentileLayout.STANDARD),
    TIDAL_VOLUME(22, HUNDREDTHS, PapUnit.LITRES, PercentileLayout.STANDARD),
    MINUTE_VENTILATION(23, HUNDREDTHS, PapUnit.LITRES_PER_MINUTE, PercentileLayout.STANDARD),
    TARGET_MINUTE_VENTILATION(24, HUNDREDTHS, PapUnit.LITRES_PER_MINUTE, PercentileLayout.STANDARD),
    RESPIRATORY_RATE(25, HUNDREDTHS, PapUnit.BREATHS_PER_MINUTE, PercentileLayout.STANDARD),
    INSPIRATORY_DURATION(26, Scale(1, 1000), PapUnit.SECONDS, PercentileLayout.STANDARD),
    IE_RATIO(27, HUNDREDTHS, PapUnit.PERCENT, PercentileLayout.STANDARD),
    SPO2(28, HUNDREDTHS, PapUnit.PERCENT, PercentileLayout.STANDARD),
    AMBIENT_HUMIDITY(29, HUNDREDTHS, PapUnit.MILLIGRAMS_PER_LITRE, PercentileLayout.MEDIAN),
    HUMIDIFIER_TEMPERATURE(30, HUNDREDTHS, PapUnit.CELSIUS, PercentileLayout.MEDIAN),
    HEATED_TUBE_TEMPERATURE(31, HUNDREDTHS, PapUnit.CELSIUS, PercentileLayout.MEDIAN),
    HUMIDIFIER_POWER(32, HUNDREDTHS, PapUnit.PERCENT, PercentileLayout.MEDIAN),
    HEATED_TUBE_POWER(33, HUNDREDTHS, PapUnit.PERCENT, PercentileLayout.MEDIAN),
    BLOWER_PRESSURE(36, HUNDREDTHS, PapUnit.CM_H2O, PercentileLayout.RANGE),
    BLOWER_FLOW(38, HUNDREDTHS, PapUnit.LITRES_PER_SECOND, PercentileLayout.MEDIAN),
    HEART_RATE(41, HUNDREDTHS, PapUnit.BEATS_PER_MINUTE, PercentileLayout.STANDARD),
    ALVEOLAR_MINUTE_VENTILATION(42, HUNDREDTHS, PapUnit.LITRES_PER_MINUTE, PercentileLayout.STANDARD),
}

/**
 * Signals in the per-minute log.
 *
 * @property modulus the Rice modulus of the compressed series, or null where
 *   the machine stores plain int16 samples (the oximeter signals).
 */
public enum class MinuteSignal(
    public val field: Int,
    public val modulus: Int?,
    public val scale: Scale,
    public val unit: PapUnit,
) {
    LEAK(1, 4, Scale(6, 5), PapUnit.LITRES_PER_MINUTE),
    INSPIRATORY_PRESSURE(2, 4, Scale(1, 5), PapUnit.CM_H2O),
    EXPIRATORY_PRESSURE(3, 2, Scale(1, 5), PapUnit.CM_H2O),
    RESPIRATORY_RATE(4, 4, Scale(1, 4), PapUnit.BREATHS_PER_MINUTE),
    INSPIRATORY_DURATION(5, 4, Scale(1, 25), PapUnit.SECONDS),
    MINUTE_VENTILATION(6, 8, Scale(2, 5), PapUnit.LITRES_PER_MINUTE),
    IE_RATIO(7, 4, Scale(4, 1), PapUnit.PERCENT),
    SPO2(8, null, Scale.ONE, PapUnit.PERCENT),
    HEART_RATE(9, null, Scale.ONE, PapUnit.BEATS_PER_MINUTE),
    ALVEOLAR_MINUTE_VENTILATION(18, 8, Scale(2, 5), PapUnit.LITRES_PER_MINUTE),
    MEAN_INSPIRATORY_TIME(21, 4, Scale(1, 10), PapUnit.SECONDS),
}

/** Codes in the `UsageEvents-TherapyStatusEvents` log. The numbering has gaps. */
public enum class UsageEvent(public val code: Int) {
    NO_USAGE(1),
    MASK_OFF(2),
    MASK_ON(3),
    POWER_OFF(4),
    MASK_FIT_START(5),
    MASK_FIT_STOP(6),
    THERAPY_START(7),
    THERAPY_STOP(8),
    LEARN_TARGETS_START(10),
    LEARN_TARGETS_STOP(11),
    ;

    public companion object {
        /** Null for a code this version does not know; such events are kept by number. */
        public fun fromCode(code: Int): UsageEvent? = entries.firstOrNull { it.code == code }
    }
}

/**
 * Codes in the `TherapyEvents-RespiratoryEvents` log. The numbering has gaps.
 *
 * Apnoea, hypopnoea and RERA entries are written when the event **ends**.
 */
public enum class RespiratoryEvent(public val code: Int) {
    NO_EVENT(1),
    HYPOPNEA(2),
    CENTRAL_APNEA(3),
    OBSTRUCTIVE_APNEA(4),
    APNEA(5),
    RERA(6),
    CSR_START(8),
    CSR_END(9),
    ;

    public companion object {
        /** Null for a code this version does not know; such events are kept by number. */
        public fun fromCode(code: Int): RespiratoryEvent? = entries.firstOrNull { it.code == code }
    }
}

/** Where each stored log sits in the machine's delivery envelope. */
public object SpoolEnvelope {
    public const val SUMMARY: Int = 2
    public const val RESPIRATORY_EVENTS: Int = 4
    public const val MINUTE_LOG: Int = 5
    public const val USAGE_EVENTS: Int = 6
}
