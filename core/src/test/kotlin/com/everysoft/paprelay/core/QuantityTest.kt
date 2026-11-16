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
        // Whole numbers stay in plain notation: an index of 10.00 is "10", not "1E+1".
        assertEquals("10", Scale(1, 100).of(1000, PapUnit.EVENTS_PER_HOUR).exact().toString())
        assertEquals("30", Scale(6, 5).of(25, PapUnit.LITRES_PER_MINUTE).exact().toString())
        assertEquals("0", Scale(1, 100).of(0, PapUnit.EVENTS_PER_HOUR).exact().toString())
        assertEquals("1100", Scale(4, 1).of(275, PapUnit.PERCENT).exact().toString())
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
    fun `a value is given in another unit only when the two measure the same thing`() {
        val leak = Scale(1, 100).of(42, PapUnit.LITRES_PER_SECOND)
        assertEquals(25.2, leak.valueIn(PapUnit.LITRES_PER_MINUTE))
        assertEquals(0.42, leak.valueIn(PapUnit.LITRES_PER_SECOND))
        assertEquals(0.4, Scale(6, 5).of(20, PapUnit.LITRES_PER_MINUTE).valueIn(PapUnit.LITRES_PER_SECOND))
        assertEquals(120.0, Scale.ONE.of(2, PapUnit.MINUTES).valueIn(PapUnit.SECONDS))
        assertEquals(1.5, Scale.ONE.of(90, PapUnit.SECONDS).valueIn(PapUnit.MINUTES))
        // Each conversion is the nearest double to the true value, in both directions.
        for (raw in 0L..5_000L) {
            assertEquals(BigDecimal(raw * 6).divide(BigDecimal(5 * 60), java.math.MathContext.DECIMAL128).toDouble(), Scale(6, 5).of(raw, PapUnit.LITRES_PER_MINUTE).valueIn(PapUnit.LITRES_PER_SECOND))
            assertEquals(BigDecimal(raw * 60).divide(BigDecimal(100)).toDouble(), Scale(1, 100).of(raw, PapUnit.LITRES_PER_SECOND).valueIn(PapUnit.LITRES_PER_MINUTE))
        }
        assertFailsWith<IllegalArgumentException> { Scale.ONE.of(5, PapUnit.LITRES).valueIn(PapUnit.LITRES_PER_MINUTE) }
        assertFailsWith<IllegalArgumentException> { leak.valueIn(PapUnit.CM_H2O) }
        assertFailsWith<IllegalArgumentException> { leak.valueIn(PapUnit.LITRES) }
        assertFailsWith<IllegalArgumentException> { Scale.ONE.of(5, PapUnit.PERCENT).valueIn(PapUnit.SECONDS) }
    }

    @Test
    fun `the same amount is recognised however it is stored, and never across units`() {
        assertEquals(true, Scale(1, 100).of(120, PapUnit.CM_H2O).sameAmountAs(Scale(1, 10).of(12, PapUnit.CM_H2O)))
        assertEquals(false, Scale(1, 100).of(120, PapUnit.CM_H2O).sameAmountAs(Scale(1, 10).of(13, PapUnit.CM_H2O)))
        // Twenty-four a minute is not twenty-four a second; but one a second is sixty a minute.
        assertEquals(false, Scale.ONE.of(24, PapUnit.LITRES_PER_MINUTE).sameAmountAs(Scale.ONE.of(24, PapUnit.LITRES_PER_SECOND)))
        assertEquals(true, Scale.ONE.of(1, PapUnit.LITRES_PER_SECOND).sameAmountAs(Scale.ONE.of(60, PapUnit.LITRES_PER_MINUTE)))
        assertEquals(true, Scale(6, 5).of(20, PapUnit.LITRES_PER_MINUTE).sameAmountAs(Scale(1, 100).of(40, PapUnit.LITRES_PER_SECOND)))
        assertEquals(true, Scale.ONE.of(2, PapUnit.MINUTES).sameAmountAs(Scale.ONE.of(120, PapUnit.SECONDS)))
        assertEquals(false, Scale.ONE.of(24, PapUnit.PERCENT).sameAmountAs(Scale.ONE.of(24, PapUnit.CELSIUS)))
    }

    @Test
    fun `machine time becomes real time only through a measured offset`() {
        val stamp = MachineTime(1_794_556_805_988)
        // The machine's clock was 361.6 s fast, so true time is earlier.
        assertEquals(java.time.Instant.ofEpochMilli(1_794_556_444_388), stamp.toInstant(clockOffsetMs = -361_600))
        assertEquals(MachineTime(1_794_556_865_988), stamp + 60_000)
        assertEquals(MachineTime(1_794_556_795_988), stamp - 10_000)
        assertEquals(60_000, (stamp + 60_000).since(stamp))
        assertEquals(true, stamp < stamp + 1)
        assertEquals(true, stamp + 1 > stamp)
        // Neither a time nor a measurement prints its value: both are health data.
        assertEquals("MachineTime", stamp.toString())
        assertEquals("Quantity(BREATHS_PER_MINUTE)", Scale(1, 4).of(60, PapUnit.BREATHS_PER_MINUTE).toString())
        assertEquals("a reading of Quantity(CM_H2O) at MachineTime", "a reading of ${Scale(1, 5).of(52, PapUnit.CM_H2O)} at $stamp")
        // Overflow cannot pass silently.
        assertFailsWith<ArithmeticException> { MachineTime(Long.MIN_VALUE) - 1 }
        assertFailsWith<ArithmeticException> { MachineTime(Long.MAX_VALUE).since(MachineTime(-1)) }
        assertFailsWith<ArithmeticException> { MachineTime(Long.MAX_VALUE).toInstant(1) }
        assertFailsWith<ArithmeticException> { MachineTime(Long.MAX_VALUE) + 1 }
    }

    @Test
    fun `nonsense is refused`() {
        assertFailsWith<IllegalArgumentException> { Scale(0, 5) }
        assertFailsWith<IllegalArgumentException> { Scale(1, -5) }
        assertFailsWith<IllegalArgumentException> { Scale.ONE.of(Long.MAX_VALUE, PapUnit.PERCENT) }
        assertFailsWith<IllegalArgumentException> { Scale.ONE.of(Quantity.MAX_RAW + 1, PapUnit.PERCENT) }
        // A third has no exact decimal form, and a huge factor would overflow.
        assertFailsWith<IllegalArgumentException> { Scale(1, 3) }
        assertFailsWith<IllegalArgumentException> { Scale(Int.MAX_VALUE, 1) }
        // The largest value allowed is still exact.
        val largest = Scale(Scale.MAX_NUMERATOR, 1).of(Quantity.MAX_RAW, PapUnit.PERCENT)
        assertEquals(BigDecimal(Quantity.MAX_RAW).multiply(BigDecimal(Scale.MAX_NUMERATOR)), largest.exact())
        assertEquals(Quantity.MAX_RAW.toDouble() * Scale.MAX_NUMERATOR, largest.value)
    }
}
