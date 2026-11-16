// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.as11.decode

import com.everysoft.paprelay.core.PapUnit
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SummaryDecoderTest {
    private val noon = 1_794_000_000_000L
    private val day = 86_400_000L

    /** A night with usage; [extra] adds to or overrides nothing, it only appends fields. */
    private fun night(extra: Proto.() -> Unit = {}): ByteArray = proto {
        message(2) {
            varint(1, 1)
            varint(2, noon)
            varint(3, noon + day)
            zigzag(4, -300)
            varint(5, 431)
            message(6) {
                message(1) { varint(1, noon + 36_000_000); varint(2, 400) }
                message(1) { varint(1, noon + 61_000_000); varint(2, 31) }
            }
            varint(7, 123) // AHI 1.23
            varint(9, 23)
            varint(10, 100)
            message(14) { varint(2, 4); varint(3, 10); varint(4, 31); varint(5, 67) }
            message(15) { varint(2, 1040); varint(3, 1180); varint(4, 1260) }
            message(25) { varint(2, 1450); varint(3, 1800); varint(4, 2400) }
            message(36) { varint(1, 420); varint(3, 1310) }
            message(29) { varint(2, 950) }
            varint(39, 2)
            varint(40, noon + day + 5_000)
            extra()
        }
    }

    @Test
    fun `a night decodes to the machine's own figures`() {
        val record = SummaryDecoder.decode(night()).records.single()
        assertEquals(noon, record.periodStartMs)
        assertEquals(noon + day, record.periodEndMs)
        assertEquals(-300, record.zoneOffsetMinutes)
        assertEquals(431, record.durationMinutes)
        assertEquals(listOf(400, 31), record.sessions.map { it.durationMinutes })
        assertEquals(noon + 36_000_000, record.sessions[0].startMs)
        assertEquals(noon + day + 5_000, record.recordTimestampMs)

        val ahi = record.indices.getValue(SummaryIndex.AHI)
        assertEquals(BigDecimal("1.23"), ahi.exact())
        assertEquals(PapUnit.EVENTS_PER_HOUR, ahi.unit)
        assertEquals(BigDecimal("0.23"), record.indices.getValue(SummaryIndex.HYPOPNEA).exact())
        assertEquals(BigDecimal("1"), record.indices.getValue(SummaryIndex.OBSTRUCTIVE_APNEA).exact())
        assertEquals(setOf(SummaryIndex.AHI, SummaryIndex.HYPOPNEA, SummaryIndex.OBSTRUCTIVE_APNEA), record.indices.keys)
        assertEquals(0, record.unknownFields)
    }

    @Test
    fun `the documented pressure example is 10_40 cmH2O`() {
        val pressure = SummaryDecoder.decode(night()).records.single().metrics.getValue(SummaryMetric.INSPIRATORY_PRESSURE)
        assertEquals(BigDecimal("10.4"), pressure.getValue(Percentile.P50).exact())
        assertEquals(10.4, pressure.getValue(Percentile.P50).value)
        assertEquals(PapUnit.CM_H2O, pressure.getValue(Percentile.P50).unit)
        assertEquals(BigDecimal("11.8"), pressure.getValue(Percentile.P95).exact())
        assertEquals(BigDecimal("12.6"), pressure.getValue(Percentile.P100).exact())
    }

    @Test
    fun `sub-field 3 is the 70th percentile for leak and the 95th elsewhere`() {
        val record = SummaryDecoder.decode(night()).records.single()
        val leak = record.metrics.getValue(SummaryMetric.LEAK)
        assertEquals(BigDecimal("0.04"), leak.getValue(Percentile.P50).exact())
        assertEquals(BigDecimal("0.1"), leak.getValue(Percentile.P70).exact())
        assertEquals(BigDecimal("0.31"), leak.getValue(Percentile.P95).exact())
        assertEquals(BigDecimal("0.67"), leak.getValue(Percentile.P100).exact())
        assertEquals(PapUnit.LITRES_PER_SECOND, leak.getValue(Percentile.P95).unit)

        val rate = record.metrics.getValue(SummaryMetric.RESPIRATORY_RATE)
        assertEquals(BigDecimal("18"), rate.getValue(Percentile.P95).exact())
        assertNull(rate[Percentile.P70])

        val blower = record.metrics.getValue(SummaryMetric.BLOWER_PRESSURE)
        assertEquals(setOf(Percentile.P5, Percentile.P95), blower.keys)
        assertEquals(BigDecimal("4.2"), blower.getValue(Percentile.P5).exact())
        assertEquals(setOf(Percentile.P50), record.metrics.getValue(SummaryMetric.AMBIENT_HUMIDITY).keys)
    }

    @Test
    fun `a value the record leaves out stays absent`() {
        val record = SummaryDecoder.decode(night()).records.single()
        assertNull(record.indices[SummaryIndex.CENTRAL_APNEA])
        assertNull(record.metrics[SummaryMetric.SPO2])
        assertNull(record.spontaneousTriggered)
        assertNull(record.minutesBelowSpo2Threshold)
    }

    @Test
    fun `a day without usage decodes with nothing in it`() {
        val payload = proto {
            message(2) { varint(1, 1); varint(2, noon); varint(3, noon + day) }
            message(2) { varint(1, 1); varint(2, noon + day); varint(3, noon + 2 * day) }
        }
        val records = SummaryDecoder.decode(payload).records
        assertEquals(2, records.size)
        assertNull(records[0].durationMinutes)
        assertTrue(records[0].sessions.isEmpty() && records[0].indices.isEmpty() && records[0].metrics.isEmpty())
        assertEquals(noon + day, records[1].periodStartMs)
    }

    @Test
    fun `an empty download holds no records`() {
        assertTrue(SummaryDecoder.decode(ByteArray(0)).records.isEmpty())
    }

    @Test
    fun `a session with no stored duration lasted zero minutes`() {
        val payload = proto {
            message(2) {
                varint(2, noon); varint(3, noon + day)
                message(6) { message(1) { varint(1, noon + 1000) } }
                varint(39, 1)
            }
        }
        assertEquals(0, SummaryDecoder.decode(payload).records.single().sessions.single().durationMinutes)
    }

    @Test
    fun `fields this version does not know are counted and skipped`() {
        val record = SummaryDecoder.decode(
            night {
                varint(60, 7)
                bytes(61, byteArrayOf(1, 2, 3))
                fixed32(62, 9)
                message(22) { varint(2, 45); varint(9, 1) } // tidal volume with an unknown sub-field
            },
        ).records.single()
        assertEquals(4, record.unknownFields)
        assertEquals(7, record.storedIntegers[60])
        assertEquals(BigDecimal("0.45"), record.metrics.getValue(SummaryMetric.TIDAL_VOLUME).getValue(Percentile.P50).exact())
        assertEquals(BigDecimal("1.23"), record.indices.getValue(SummaryIndex.AHI).exact())
    }

    @Test
    fun `anything that contradicts the layout is refused, not guessed at`() {
        // Refused outright, or reported as a record that could not be decoded; never passed off as a night.
        fun refused(payload: ByteArray) {
            val log = try {
                SummaryDecoder.decode(payload)
            } catch (e: SpoolDecodeException) {
                return
            }
            assertTrue(log.failures.isNotEmpty() && log.records.isEmpty(), "decoded to ${log.records.size} records")
        }
        // Another log's envelope.
        refused(proto { message(5) { varint(2, noon); varint(3, noon + day) } })
        refused(proto { message(2) { varint(2, noon); varint(3, noon + day) }; varint(3, 1) })
        // No period.
        refused(proto { message(2) { varint(1, 1); varint(3, noon + day) } })
        refused(proto { message(2) { varint(1, 1); varint(2, noon) } })
        refused(proto { message(2) { varint(2, noon + day); varint(3, noon) } })
        // A value given twice.
        refused(night { varint(7, 200) })
        refused(night { message(15) { varint(2, 900) } })
        refused(proto { message(2) { varint(2, noon); varint(3, noon + day); message(15) { varint(2, 1); varint(2, 2) } } })
        // The wrong kind of field.
        refused(night { varint(21, 5) })
        refused(night { bytes(8, byteArrayOf(1)) })
        refused(night { fixed32(11, 5) })
        refused(proto { message(2) { varint(2, noon); varint(3, noon + day); message(15) { bytes(2, byteArrayOf(1)) } } })
        // A negative number where none belongs (ten-byte varint).
        refused(night { varint(11, -1) })
        refused(night { message(20) { varint(2, -5) } })
        // A session with no start time.
        refused(proto { message(2) { varint(2, noon); varint(3, noon + day); message(6) { message(1) { varint(2, 5) } }; varint(39, 1) } })
        // Times in seconds where milliseconds belong.
        refused(proto { message(2) { varint(2, noon / 1000); varint(3, (noon + day) / 1000) } })
        // Out of range.
        refused(proto { message(2) { varint(2, noon); varint(3, noon + day); varint(5, 1441) } })
        refused(proto { message(2) { varint(2, noon); varint(3, noon + day); zigzag(4, 2000) } })
        refused(proto { message(2) { varint(2, 0); varint(3, noon + day) } })
        // Cut short.
        assertFailsWith<SpoolDecodeException> { SummaryDecoder.decode(night().copyOf(night().size - 3)) }
    }

    @Test
    fun `a session list that does not match its count is reported, not refused`() {
        val agrees = SummaryDecoder.decode(night()).records.single()
        assertEquals(2, agrees.storedSessionCount)
        assertTrue(agrees.sessionCountMatches)
        // One more session than the record counts.
        val extra = SummaryDecoder.decode(night { message(6) { message(1) { varint(1, noon + 5); varint(2, 1) } } }).records.single()
        assertEquals(3, extra.sessions.size)
        assertEquals(2, extra.storedSessionCount)
        assertTrue(!extra.sessionCountMatches)
        // A count with no list, and a list with no count.
        val counted = SummaryDecoder.decode(proto { message(2) { varint(2, noon); varint(3, noon + day); varint(39, 3) } }).records.single()
        assertTrue(counted.sessions.isEmpty() && !counted.sessionCountMatches)
        val uncounted = SummaryDecoder.decode(
            proto { message(2) { varint(2, noon); varint(3, noon + day); message(6) { message(1) { varint(1, noon + 5); varint(2, 9) } } } },
        ).records.single()
        assertNull(uncounted.storedSessionCount)
        assertTrue(!uncounted.sessionCountMatches)
        // No sessions and no count is what an unused day looks like, and is consistent.
        assertTrue(SummaryDecoder.decode(proto { message(2) { varint(2, noon); varint(3, noon + day) } }).records.single().sessionCountMatches)
    }

    @Test
    fun `one night that is not understood does not take the others with it`() {
        val log = SummaryDecoder.decode(
            proto {
                message(2) { varint(2, noon); varint(3, noon + day); varint(7, 150) }
                message(2) { varint(2, noon + day); varint(3, noon + 2 * day); varint(7, 100); varint(7, 200) }
                message(2) { varint(2, noon + 2 * day); varint(3, noon + 3 * day); varint(7, 50) }
            },
        )
        assertEquals(listOf(noon, noon + 2 * day), log.records.map { it.periodStartMs })
        assertEquals(listOf(1), log.failures.map { it.index })
        assertTrue(!log.complete)
        assertTrue(SummaryDecoder.decode(night()).complete)
    }

    @Test
    fun `the remaining whole-number fields carry their own meaning`() {
        val record = SummaryDecoder.decode(night { varint(17, 12); varint(18, 9_550); varint(19, 8_025) }).records.single()
        assertEquals(12, record.minutesBelowSpo2Threshold)
        val triggered = record.spontaneousTriggered!!
        assertEquals(BigDecimal("95.5"), triggered.exact())
        assertEquals(PapUnit.PERCENT, triggered.unit)
        assertEquals(BigDecimal("80.25"), record.spontaneousCycled!!.exact())
        // Kept as stored for export: the zone offset is still in its wire encoding there.
        assertEquals(599, record.storedIntegers[4])
        assertEquals(-300, record.zoneOffsetMinutes)
    }

    @Test
    fun `percentiles that fall where they should rise mark the metric as misread`() {
        assertTrue(SummaryDecoder.decode(night()).records.single().disorderedMetrics.isEmpty())
        val record = SummaryDecoder.decode(night { message(20) { varint(2, 900); varint(3, 600); varint(4, 950) } }).records.single()
        assertEquals(setOf(SummaryMetric.EXPIRATORY_PRESSURE), record.disorderedMetrics)
    }

    @Test
    fun `respiratory flow is left alone until its sign convention is known`() {
        // Field 37's low percentile is negative in life; neither way of storing that is assumed.
        val record = SummaryDecoder.decode(night { message(37) { varint(1, 59); varint(3, 80) } }).records.single()
        assertEquals(1, record.unknownFields)
        assertTrue(SummaryMetric.entries.none { it.field == 37 })
    }

    @Test
    fun `every scale, unit and layout matches the published table`() {
        // Field, divisor, unit, sub-field layout: rpc_spools.md, "Summary records".
        val expected = mapOf(
            SummaryMetric.LEAK to Row(14, 100, PapUnit.LITRES_PER_SECOND, "2=P50 3=P70 4=P95 5=P100"),
            SummaryMetric.INSPIRATORY_PRESSURE to Row(15, 100, PapUnit.CM_H2O, STANDARD),
            SummaryMetric.EXPIRATORY_PRESSURE to Row(20, 100, PapUnit.CM_H2O, STANDARD),
            SummaryMetric.MEAN_MASK_PRESSURE to Row(21, 100, PapUnit.CM_H2O, STANDARD),
            SummaryMetric.TIDAL_VOLUME to Row(22, 100, PapUnit.LITRES, STANDARD),
            SummaryMetric.MINUTE_VENTILATION to Row(23, 100, PapUnit.LITRES_PER_MINUTE, STANDARD),
            SummaryMetric.TARGET_MINUTE_VENTILATION to Row(24, 100, PapUnit.LITRES_PER_MINUTE, STANDARD),
            SummaryMetric.RESPIRATORY_RATE to Row(25, 100, PapUnit.BREATHS_PER_MINUTE, STANDARD),
            SummaryMetric.INSPIRATORY_DURATION to Row(26, 1000, PapUnit.SECONDS, STANDARD),
            SummaryMetric.IE_RATIO to Row(27, 100, PapUnit.PERCENT, STANDARD),
            SummaryMetric.SPO2 to Row(28, 100, PapUnit.PERCENT, STANDARD),
            SummaryMetric.AMBIENT_HUMIDITY to Row(29, 100, PapUnit.MILLIGRAMS_PER_LITRE, MEDIAN),
            SummaryMetric.HUMIDIFIER_TEMPERATURE to Row(30, 100, PapUnit.CELSIUS, MEDIAN),
            SummaryMetric.HEATED_TUBE_TEMPERATURE to Row(31, 100, PapUnit.CELSIUS, MEDIAN),
            SummaryMetric.HUMIDIFIER_POWER to Row(32, 100, PapUnit.PERCENT, MEDIAN),
            SummaryMetric.HEATED_TUBE_POWER to Row(33, 100, PapUnit.PERCENT, MEDIAN),
            SummaryMetric.BLOWER_PRESSURE to Row(36, 100, PapUnit.CM_H2O, RANGE),
            SummaryMetric.BLOWER_FLOW to Row(38, 100, PapUnit.LITRES_PER_SECOND, MEDIAN),
            SummaryMetric.HEART_RATE to Row(41, 100, PapUnit.BEATS_PER_MINUTE, STANDARD),
            SummaryMetric.ALVEOLAR_MINUTE_VENTILATION to Row(42, 100, PapUnit.LITRES_PER_MINUTE, STANDARD),
        )
        assertEquals(expected.keys, SummaryMetric.entries.toSet())
        for ((metric, row) in expected) {
            assertEquals(row.field, metric.field, "$metric field")
            assertEquals(1, metric.scale.numerator, "$metric")
            assertEquals(row.divisor, metric.scale.denominator, "$metric divisor")
            assertEquals(row.unit, metric.unit, "$metric unit")
            assertEquals(row.layout, metric.layout.bySubField.entries.joinToString(" ") { "${it.key}=${it.value}" }, "$metric layout")
        }
        assertEquals(
            listOf("AHI=7", "APNEA=8", "HYPOPNEA=9", "OBSTRUCTIVE_APNEA=10", "CENTRAL_APNEA=11", "UNKNOWN_APNEA=12", "RERA=13"),
            SummaryIndex.entries.map { "${it.name}=${it.field}" },
        )
        for (index in SummaryIndex.entries) {
            assertEquals(100, index.scale.denominator)
            assertEquals(PapUnit.EVENTS_PER_HOUR, index.unit)
        }
    }

    private class Row(val field: Int, val divisor: Int, val unit: PapUnit, val layout: String)

    private companion object {
        const val STANDARD = "2=P50 3=P95 4=P100"
        const val MEDIAN = "2=P50"
        const val RANGE = "1=P5 3=P95"
    }
}
