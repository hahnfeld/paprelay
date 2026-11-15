// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.core

import java.math.BigDecimal
import java.math.MathContext

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
 * `6/5` here, not the nearest binary fraction.
 */
public data class Scale(val numerator: Int, val denominator: Int) {
    init {
        require(numerator > 0 && denominator > 0) { "a scale must be positive" }
    }

    public fun of(raw: Long, unit: PapUnit): Quantity = Quantity(raw, this, unit)

    public companion object {
        public val ONE: Scale = Scale(1, 1)
    }
}

/**
 * A measurement exactly as the machine stored it, with the rule that gives it meaning.
 *
 * The stored integer is kept so that nothing is lost to conversion; [value] and
 * [exact] are derived on demand.
 */
public data class Quantity(val raw: Long, val scale: Scale, val unit: PapUnit) {
    init {
        // Keeps `raw * numerator` far inside what a Long, and a Double, holds exactly.
        require(raw in -MAX_RAW..MAX_RAW) { "stored value out of range" }
    }

    /** The physical value, correctly rounded to the nearest double. */
    public val value: Double get() = (raw * scale.numerator).toDouble() / scale.denominator

    /** The physical value with no binary rounding, for display and export. */
    public fun exact(): BigDecimal =
        BigDecimal(raw * scale.numerator).divide(BigDecimal(scale.denominator), MathContext.DECIMAL64).stripTrailingZeros()

    public companion object {
        public const val MAX_RAW: Long = 1L shl 40
    }
}
