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
internal enum class PercentileLayout(val bySubField: Map<Int, Percentile>) {
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
 * @property confirmed whether field, layout and divisor have been checked
 *   against the same machine's SD card (AirSense 11 AutoSet, firmware 19.8.8.0:
 *   twelve nights, every value equal at the stored resolution). The others
 *   were not present in that capture and are as the reference gives them.
 * @property zeroIsImpossible true for the oximeter metrics, where a stored zero
 *   cannot be a measurement and means no sensor was attached.
 */
public enum class SummaryMetric(
    public val field: Int,
    public val scale: Scale,
    public val unit: PapUnit,
    internal val layout: PercentileLayout,
    public val confirmed: Boolean,
    public val zeroIsImpossible: Boolean = false,
) {
    LEAK(14, HUNDREDTHS, PapUnit.LITRES_PER_SECOND, PercentileLayout.LEAK, confirmed = true),
    INSPIRATORY_PRESSURE(15, HUNDREDTHS, PapUnit.CM_H2O, PercentileLayout.STANDARD, confirmed = true),
    EXPIRATORY_PRESSURE(20, HUNDREDTHS, PapUnit.CM_H2O, PercentileLayout.STANDARD, confirmed = true),
    MEAN_MASK_PRESSURE(21, HUNDREDTHS, PapUnit.CM_H2O, PercentileLayout.STANDARD, confirmed = true),
    TIDAL_VOLUME(22, HUNDREDTHS, PapUnit.LITRES, PercentileLayout.STANDARD, confirmed = true),
    MINUTE_VENTILATION(23, HUNDREDTHS, PapUnit.LITRES_PER_MINUTE, PercentileLayout.STANDARD, confirmed = true),
    TARGET_MINUTE_VENTILATION(24, HUNDREDTHS, PapUnit.LITRES_PER_MINUTE, PercentileLayout.STANDARD, confirmed = false),
    RESPIRATORY_RATE(25, HUNDREDTHS, PapUnit.BREATHS_PER_MINUTE, PercentileLayout.STANDARD, confirmed = true),
    INSPIRATORY_DURATION(26, Scale(1, 1000), PapUnit.SECONDS, PercentileLayout.STANDARD, confirmed = false),
    IE_RATIO(27, HUNDREDTHS, PapUnit.PERCENT, PercentileLayout.STANDARD, confirmed = false),
    SPO2(28, HUNDREDTHS, PapUnit.PERCENT, PercentileLayout.STANDARD, confirmed = false, zeroIsImpossible = true),
    AMBIENT_HUMIDITY(29, HUNDREDTHS, PapUnit.MILLIGRAMS_PER_LITRE, PercentileLayout.MEDIAN, confirmed = true),
    HUMIDIFIER_TEMPERATURE(30, HUNDREDTHS, PapUnit.CELSIUS, PercentileLayout.MEDIAN, confirmed = true),
    HEATED_TUBE_TEMPERATURE(31, HUNDREDTHS, PapUnit.CELSIUS, PercentileLayout.MEDIAN, confirmed = true),
    HUMIDIFIER_POWER(32, HUNDREDTHS, PapUnit.PERCENT, PercentileLayout.MEDIAN, confirmed = true),
    HEATED_TUBE_POWER(33, HUNDREDTHS, PapUnit.PERCENT, PercentileLayout.MEDIAN, confirmed = true),
    BLOWER_PRESSURE(36, HUNDREDTHS, PapUnit.CM_H2O, PercentileLayout.RANGE, confirmed = true),

    /**
     * Stored as a plain value: on the nights examined both percentiles were positive and
     * equal to the SD card's. The card allows this figure to be negative; how the machine
     * would send a negative one is not known, and such a value is reported as misread.
     */
    RESPIRATORY_FLOW(37, HUNDREDTHS, PapUnit.LITRES_PER_SECOND, PercentileLayout.RANGE, confirmed = true),
    BLOWER_FLOW(38, HUNDREDTHS, PapUnit.LITRES_PER_SECOND, PercentileLayout.MEDIAN, confirmed = true),
    HEART_RATE(41, HUNDREDTHS, PapUnit.BEATS_PER_MINUTE, PercentileLayout.STANDARD, confirmed = false, zeroIsImpossible = true),
    ALVEOLAR_MINUTE_VENTILATION(42, HUNDREDTHS, PapUnit.LITRES_PER_MINUTE, PercentileLayout.STANDARD, confirmed = false),
}

/**
 * The software a machine reports itself to be running, as the thing that
 * decides how far its data is believed.
 *
 * The layouts, moduli and factors in this file were checked against one real
 * machine and its SD card: an AirSense 11 AutoSet on software 19.8.8.0. Data
 * is also read from every other version whose identifier has the form of that
 * machine's (`SW04600`, a version, a build), on the assumption that it is laid
 * out the same. The assumption is not relied on alone:
 *
 *  - The daily summaries and the event logs are tagged fields. Each summary
 *    value is checked for sense, and a metric whose percentiles are not exactly
 *    the ones known is left out.
 *  - The per-minute log is compressed with a modulus for each signal, and with
 *    a wrong modulus it can decode to values that are wrong but plausible,
 *    mostly in short records. Whoever uses it for a version that was not
 *    compared ([perMinuteCompared] is false) must first see a long record of
 *    that version decode with every known signal in agreement, and must not
 *    use what an earlier version may have recorded. The synchronising engine
 *    does both.
 *
 * Software whose identifier has another form is not read at all.
 */
public class Firmware private constructor(public val applicationIdentifier: String) {
    /** Whether data from this software is read. */
    public val layoutsAccepted: Boolean get() = applicationIdentifier.length <= MAX_LENGTH && ACCEPTED.matches(applicationIdentifier)

    /** Whether this is the version the per-minute log was compared on, so that its moduli are known and not assumed. */
    public val perMinuteCompared: Boolean get() = COMPARED.matches(applicationIdentifier)

    public companion object {
        /** Far longer than any identifier seen, and well within what is kept of it between synchronisations. */
        private const val MAX_LENGTH = 64

        /** `SW04600`, then the parts of a version and a build. */
        private val ACCEPTED = Regex("SW04600(\\.[0-9A-Za-z]{1,40}){2,8}")

        /** Software 19.8.8.0, any build. */
        private val COMPARED = Regex("SW04600\\.19\\.8\\.8\\.0\\.[0-9a-f]{1,40}")

        /**
         * @param applicationIdentifier the `ApplicationIdentifier` the machine gave **in the
         *   connection the data came from**. Never a remembered or assumed value: a machine's
         *   software can be updated between one connection and the next.
         */
        public fun reportedByMachine(applicationIdentifier: String): Firmware = Firmware(applicationIdentifier)
    }
}

/**
 * Signals in the per-minute log.
 *
 * @property modulus the Rice modulus of the compressed series, or null where
 *   the machine stores plain int16 samples (the oximeter signals).
 * @property layoutConfirmed whether the signal has been seen in a real capture
 *   and found to decode as described here. One that has not is neither decoded
 *   nor allowed a say in how long the record is: decoded with a wrong modulus
 *   a short series can come out the right length with wrong values, and a
 *   series of an unexpected length would take the whole record down with it.
 * @property factorConfirmed whether the factor and unit have been checked
 *   against the same machine's SD card. Unconfirmed values are given out only
 *   on request.
 * @property maxStored the largest stored value that can be a measurement. For
 *   the confirmed signals this is the top of the range the SD card declares
 *   for the same signal; anything above it, or below zero, means the series
 *   was misread or holds a marker this version does not know.
 * @property zeroIsUnknown true where a stored zero is not a measurement the
 *   signal can have (nobody breathes zero times a minute), so that what the
 *   machine means by it is not known. No zero occurred in the capture. Such
 *   a minute is reported as having no value. Leak is the one signal where
 *   zero is an ordinary reading.
 *
 * Evidence, from one AirSense 11 AutoSet on firmware 19.8.8.0 (2026-11-16):
 * three sessions, signals 1 to 7 present. With
 * respiratory rate at modulus 8 and minute ventilation at 4 (the reference
 * decoder has them the other way round) every signal of every record has the
 * session's length in minutes. Leak, both pressures, respiratory rate and
 * minute ventilation agree with the SD card's two-second signals averaged per
 * minute. Inspiratory duration and I:E ratio have nothing to be compared with,
 * and the first gives values near half a second, which is short for a breath in.
 */
public enum class MinuteSignal(
    public val field: Int,
    public val modulus: Int?,
    public val scale: Scale,
    public val unit: PapUnit,
    public val layoutConfirmed: Boolean,
    public val factorConfirmed: Boolean,
    public val maxStored: Int = Short.MAX_VALUE.toInt(),
    public val zeroIsUnknown: Boolean = true,
) {
    LEAK(1, 4, Scale(6, 5), PapUnit.LITRES_PER_MINUTE, true, true, maxStored = 100, zeroIsUnknown = false), // 120 L/min
    INSPIRATORY_PRESSURE(2, 4, Scale(1, 5), PapUnit.CM_H2O, true, true, maxStored = 250), // 50 cmH2O
    EXPIRATORY_PRESSURE(3, 2, Scale(1, 5), PapUnit.CM_H2O, true, true, maxStored = 250),
    RESPIRATORY_RATE(4, 8, Scale(1, 4), PapUnit.BREATHS_PER_MINUTE, true, true, maxStored = 360), // 90 breaths/min
    INSPIRATORY_DURATION(5, 4, Scale(1, 25), PapUnit.SECONDS, true, false),
    MINUTE_VENTILATION(6, 4, Scale(2, 5), PapUnit.LITRES_PER_MINUTE, true, true, maxStored = 75), // 30 L/min
    IE_RATIO(7, 4, Scale(4, 1), PapUnit.PERCENT, true, false),
    SPO2(8, null, Scale.ONE, PapUnit.PERCENT, false, false),
    HEART_RATE(9, null, Scale.ONE, PapUnit.BEATS_PER_MINUTE, false, false),
    ALVEOLAR_MINUTE_VENTILATION(18, 8, Scale(2, 5), PapUnit.LITRES_PER_MINUTE, false, false),
    MEAN_INSPIRATORY_TIME(21, 4, Scale(1, 10), PapUnit.SECONDS, false, false),
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
 * Seen in a real capture: obstructive apnoeas, central apnoeas and hypopnoeas.
 * An apnoea is written when it ends and stamped with that moment;
 * for a hypopnoea, which has no stored length, whether the stamp is its start
 * or its end cannot be told. See [EventRecord]. The other codes are as the
 * reference gives them and have not been seen.
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
internal object SpoolEnvelope {
    const val SUMMARY: Int = 2
    const val RESPIRATORY_EVENTS: Int = 4
    const val MINUTE_LOG: Int = 5
    const val USAGE_EVENTS: Int = 6
}
