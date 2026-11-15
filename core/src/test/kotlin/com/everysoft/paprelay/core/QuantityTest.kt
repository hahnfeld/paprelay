// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.core

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class QuantityTest {
    @Test
    fun `factors are applied without rounding`() {
        // 1.2 has no exact binary form; as 6/5 it never needs one.
        assertEquals(24.0, Scale(6, 5).of(20, PapUnit.LITRES_PER_MINUTE).value)
        assertEquals(BigDecimal("24"), Scale(6, 5).of(20, PapUnit.LITRES_PER_MINUTE).exact())
        assertEquals(BigDecimal("1.2"), Scale(6, 5).of(1, PapUnit.LITRES_PER_MINUTE).exact())
        assertEquals(1.2, Scale(6, 5).of(1, PapUnit.LITRES_PER_MINUTE).value)
        assertEquals(BigDecimal("10.4"), Scale(1, 100).of(1040, PapUnit.CM_H2O).exact())
        assertEquals(10.4, Scale(1, 100).of(1040, PapUnit.CM_H2O).value)
        assertEquals(BigDecimal("0.007"), Scale(1, 1000).of(7, PapUnit.SECONDS).exact())
        assertEquals(BigDecimal("-3.75"), Scale(1, 4).of(-15, PapUnit.BREATHS_PER_MINUTE).exact())
        assertEquals(BigDecimal("0"), Scale(1, 100).of(0, PapUnit.EVENTS_PER_HOUR).exact().stripTrailingZeros())
    }

    @Test
    fun `every stored value up to a day of hundredths converts to the nearest double`() {
        for (raw in 0L..20_000L) {
            assertEquals(BigDecimal(raw).divide(BigDecimal(100)).toDouble(), Scale(1, 100).of(raw, PapUnit.CM_H2O).value)
            assertEquals(BigDecimal(raw * 6).divide(BigDecimal(5)).toDouble(), Scale(6, 5).of(raw, PapUnit.LITRES_PER_MINUTE).value)
        }
    }

    @Test
    fun `equal measurements are equal, and a unit is part of the measurement`() {
        assertEquals(Scale(1, 100).of(5, PapUnit.CM_H2O), Scale(1, 100).of(5, PapUnit.CM_H2O))
        assertEquals(false, Scale(1, 100).of(5, PapUnit.CM_H2O) == Scale(1, 100).of(5, PapUnit.PERCENT))
    }

    @Test
    fun `nonsense is refused`() {
        assertFailsWith<IllegalArgumentException> { Scale(0, 5) }
        assertFailsWith<IllegalArgumentException> { Scale(1, -5) }
        assertFailsWith<IllegalArgumentException> { Scale.ONE.of(Long.MAX_VALUE, PapUnit.PERCENT) }
    }
}
