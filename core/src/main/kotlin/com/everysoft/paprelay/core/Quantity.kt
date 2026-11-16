// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.core

import java.math.BigDecimal

/** The physical units PAPRelay stores. A value never travels without one. */
public enum class PapUnit {
    EVENTS_PER_HOUR,
    PERCENT,
    LITRES_PER_SECOND,
    LITRES_PER_MINUTE,
    LITRES,
    CM_H2O,
    BREATHS_PER_MINUTE,
    BEATS_PER_MINUTE,
    SECONDS,
    MINUTES,
    MILLIGRAMS_PER_LITRE,
    CELSIUS,
}

/**
 * How a stored integer becomes a physical value: `raw * numerator / denominator`.
 *
 * Kept as a ratio of whole numbers so that no factor is ever rounded: 1.2 is
 * `6/5` here, not the nearest binary fraction. The denominator may contain only
 * the factors 2 and 5, so every value has an exact decimal form.
 */
public data class Scale(val numerator: Int, val denominator: Int) {
    init {
        require(numerator in 1..MAX_NUMERATOR) { "scale numerator out of range" }
        require(denominator in 1..MAX_DENOMINATOR) { "scale denominator out of range" }
        var rest = denominator
        while (rest % 2 == 0) rest /= 2
        while (rest % 5 == 0) rest /= 5
        require(rest == 1) { "scale denominator must have an exact decimal form" }
    }

    public fun of(raw: Long, unit: PapUnit): Quantity = Quantity(raw, this, unit)

    public companion object {
        public val ONE: Scale = Scale(1, 1)
        public const val MAX_NUMERATOR: Int = 4096
        public const val MAX_DENOMINATOR: Int = 1_000_000
    }
}

/**
 * A measurement exactly as the machine stored it, with the rule that gives it meaning.
 *
 * The stored integer is kept so that nothing is lost to conversion; [value] and
 * [exact] are derived on demand. Two quantities are equal only when the stored
 * integer, the scale and the unit are all the same: `120 x 1/100` and
 * `12 x 1/10` are the same amount but not the same record. Compare amounts
 * with [exact].
 *
 * The [unit] is part of the value. Leak, for one, is stored in litres per
 * second in one log and litres per minute in another; code that needs a
 * particular unit must check it, not assume it.
 */
public data class Quantity(val raw: Long, val scale: Scale, val unit: PapUnit) {
    init {
        // With the limits on a scale, `raw * numerator` stays below 2^53: exact in a Long and in a Double.
        require(raw in -MAX_RAW..MAX_RAW) { "stored value out of range" }
    }

    /** The physical value, correctly rounded to the nearest double. */
    public val value: Double get() = (raw * scale.numerator).toDouble() / scale.denominator

    /** The physical value with no rounding at all, in plain notation, for display and export. */
    public fun exact(): BigDecimal {
        val exact = BigDecimal(raw * scale.numerator).divide(BigDecimal(scale.denominator)).stripTrailingZeros()
        // stripTrailingZeros turns 10 into 1E+1; whole numbers are kept whole.
        return if (exact.scale() < 0) exact.setScale(0) else exact
    }

    public companion object {
        public const val MAX_RAW: Long = 1L shl 40
    }
}
