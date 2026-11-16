// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.as11.plan

import com.everysoft.paprelay.as11.decode.MinuteRecord
import com.everysoft.paprelay.as11.decode.MinuteSignal
import com.everysoft.paprelay.as11.decode.SignalReadings
import com.everysoft.paprelay.core.PapUnit
import com.everysoft.paprelay.core.sync.RateReading
import com.everysoft.paprelay.core.sync.SessionReadings
import java.math.BigDecimal

/**
 * Which minutes of a session did not become a reading, and why. Nothing is
 * dropped without being counted here.
 */
public class Omitted(
    /** The machine stored a rate of zero. What that means is not known; it is not a breathing rate. */
    public val zeroRate: Int,
    /** The leak in that minute was above the limit, where the machine's own rate is unreliable. */
    public val highLeak: Int,
    /**
     * At the very end of the session: minutes that may exist, or may be padding in the
     * machine's storage. They cannot be told apart, so they are not passed on. Rare: it
     * needs every signal of the session to end exactly on a byte.
     */
    public val uncertain: Int,
)

/** What became of one session. */
public sealed interface SessionPlan {
    /** The readings of the session, with what was left out. */
    public class Planned internal constructor(public val readings: SessionReadings, public val omitted: Omitted) : SessionPlan

    /**
     * The session could not be planned; nothing of it is to be written.
     *
     * @property minutesUnknown true when the machine did record the signal and it could not be
     *   trusted, so that the session's minutes are unknown and synchronisation must not
     *   move its place past this session. False when the machine simply recorded no such signal.
     */
    public class Unplanned internal constructor(public val reason: Reason, public val minutesUnknown: Boolean) : SessionPlan

    public enum class Reason {
        /** The record has no respiratory-rate signal that can be used. */
        NO_USABLE_RATE,

        /** A leak limit is set, and the record has no leak signal that can be used to apply it. */
        NO_USABLE_LEAK,
    }
}

/**
 * Decides which per-minute respiratory rates go to Health Connect.
 *
 * This is the whole of PAPRelay's filtering, and it is fixed rules: a minute
 * is passed on exactly as the machine recorded it, or left out for one of the
 * reasons in [Omitted]. By default the only minutes left out are those with no
 * rate to pass on (a stored zero) and those that cannot be shown to exist.
 * Nothing is averaged, smoothed, interpolated or corrected.
 */
public object RespiratoryRatePlan {
    /**
     * The limit offered when the leak rule is switched on, in litres per minute: the figure
     * commonly used for a large leak on these machines. The rule is **off** unless the user
     * turns it on: leaving a minute out because of its leak is a judgement about the data,
     * and PAPRelay's own default is to pass on what the machine recorded.
     */
    public const val SUGGESTED_LEAK_LIMIT_LITRES_PER_MINUTE: Int = 24

    /**
     * @param record one decoded session. Only the minutes that certainly exist are used.
     * @param machineTag a stable, non-identifying name for the machine.
     * @param leakLimitLitresPerMinute minutes with more leak than this are left out; null for no limit.
     * @throws IllegalArgumentException if [machineTag] is not a valid tag or the limit is negative.
     * @throws IllegalStateException if the decoder served a value it should have set aside: a defect.
     */
    public fun session(record: MinuteRecord, machineTag: String, leakLimitLitresPerMinute: Int?): SessionPlan {
        require(leakLimitLitresPerMinute == null || leakLimitLitresPerMinute >= 0) { "a leak limit is not negative" }
        val rates = when (val found = record.readings(MinuteSignal.RESPIRATORY_RATE)) {
            is SignalReadings.Values -> found.values
            SignalReadings.NotRecorded -> return SessionPlan.Unplanned(SessionPlan.Reason.NO_USABLE_RATE, minutesUnknown = false)
            SignalReadings.Withheld, SignalReadings.Misread -> return SessionPlan.Unplanned(SessionPlan.Reason.NO_USABLE_RATE, minutesUnknown = true)
        }
        val leaks = if (leakLimitLitresPerMinute == null) {
            null
        } else {
            when (val found = record.readings(MinuteSignal.LEAK)) {
                is SignalReadings.Values -> found.values
                // With a limit set and nothing to apply it with, the rates cannot be vouched for.
                else -> return SessionPlan.Unplanned(SessionPlan.Reason.NO_USABLE_LEAK, minutesUnknown = true)
            }
        }
        val limit = leakLimitLitresPerMinute?.let(::BigDecimal)
        val hundred = BigDecimal(100)

        val readings = ArrayList<RateReading>()
        var zero = 0
        var leaky = 0
        for (index in rates.indices) {
            val rate = rates[index]
            if (rate == null) {
                zero++
                continue
            }
            check(rate.unit == PapUnit.BREATHS_PER_MINUTE)
            // Exact: the stored value is in quarters, so hundredths are whole.
            val hundredths = rate.exact().multiply(hundred).intValueExact()
            val leak = leaks?.get(index)
            // The decoder has already set aside any series holding a value beyond what the machine
            // can report, so one here would be a defect in this program, not something in the data.
            check(hundredths in 1..RateReading.MAX_HUNDREDTHS) { "a rate the decoder should not have served" }
            // Leak is the one signal in which zero is a reading, so it is never missing here.
            if (leak != null && litresPerMinute(leak) > limit) leaky++ else readings += RateReading(record.minuteEnd(index), hundredths)
        }
        return SessionPlan.Planned(SessionReadings(machineTag, record.firstMinuteEnd, readings), Omitted(zero, leaky, record.uncertainTail))
    }

    /** The per-minute log stores leak in litres per minute; anything else here is a defect, not data. */
    private fun litresPerMinute(leak: com.everysoft.paprelay.core.Quantity): BigDecimal {
        check(leak.unit == PapUnit.LITRES_PER_MINUTE) { "per-minute leak in an unexpected unit" }
        return leak.exact()
    }
}
