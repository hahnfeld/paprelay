// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.as11.decode

import com.everysoft.paprelay.core.MachineTime
import com.everysoft.paprelay.core.PapUnit
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SummaryDecoderTest {
    private val noon = 1_794_000_000_000L
    private val confirmed = Firmware.reportedByMachine("SW04600.19.8.8.0.dced143ae")

    private fun summaries(payload: ByteArray) = SummaryDecoder.decode(payload, confirmed)
    private val day = 86_400_000L

    /** A night with therapy, laid out as the machine sends one; [extra] appends fields. */
    private fun Proto.night(start: Long = noon, extra: Proto.() -> Unit = {}) {
        message(2) {
            varint(1, 1)
            varint(2, start)
            varint(3, start + day)
            zigzag(4, -420)
            varint(5, 305)
            message(6) {
                message(1) { varint(1, start + 48_300_000); varint(2, 203) }
                message(1) { varint(1, start + 66_900_000); varint(2, 102) }
            }
            varint(7, 170); varint(8, 130); varint(9, 30); varint(10, 110); varint(11, 10); varint(12, 0); varint(13, 0)
            message(14) { varint(2, 6); varint(3, 8); varint(4, 42); varint(5, 86) }
            message(15) { varint(2, 800); varint(3, 960); varint(4, 1020) }
            varint(16, 0)
            message(20) { varint(2, 612); varint(3, 768); varint(4, 828) }
            message(25) { varint(2, 1320); varint(3, 1520); varint(4, 1920) }
            message(29) { varint(2, 1120) }
            varint(34, 2); varint(35, 1)
            message(36) { varint(1, 474); varint(3, 958) }
            message(37) { varint(1, 16); varint(3, 36) }
            message(38) { varint(2, 48) }
            varint(39, 2)
            varint(40, start + 73_000_000)
            varint(43, start - 90_000_000)
            extra()
        }
    }

    /** A day without therapy, as the machine sends one. */
    private fun Proto.unusedDay(start: Long) {
        message(2) { varint(1, 1); varint(2, start); varint(3, start + day); zigzag(4, -420); varint(5, 0); varint(40, start) }
    }

    private fun one(payload: ByteArray): SummaryRecord {
        val log = summaries(payload)
        assertTrue(log.complete, "failures: ${log.failures.map { it.reason }}")
        return log.decoded.single()
    }

    private fun night(extra: Proto.() -> Unit = {}): SummaryRecord = one(proto { night(extra = extra) })

    /** One record that must be refused, with nothing passed off as a night. */
    private fun refused(why: String, build: Proto.() -> Unit) {
        val log = summaries(proto(build))
        assertTrue(log.decoded.isEmpty(), "$why: decoded")
        assertEquals(1, log.failures.size, why)
    }

    private fun refusedNight(why: String, extra: Proto.() -> Unit) = refused(why) { night(extra = extra) }

    private fun bare(build: Proto.() -> Unit): Proto.() -> Unit = { message(2) { varint(2, noon); varint(3, noon + day); build() } }

    @Test
    fun `a night decodes to the machine's own figures`() {
        val record = night()
        assertEquals(MachineTime(noon), record.periodStart)
        assertEquals(MachineTime(noon + day), record.periodEnd)
        assertEquals(-420, record.zoneOffsetMinutesWhenSent)
        assertEquals(305, record.durationMinutes)
        assertEquals(listOf(203, 102), record.sessions.map { it.durationMinutes })
        assertEquals(MachineTime(noon + 48_300_000), record.sessions[0].start)
        assertEquals(2, record.storedSessionCount)
        assertTrue(record.sessionCountMatches)
        assertEquals(MachineTime(noon + 73_000_000), record.recordTimestamp)
        assertEquals(
            listOf("AHI=1.7", "APNEA=1.3", "HYPOPNEA=0.3", "OBSTRUCTIVE_APNEA=1.1", "CENTRAL_APNEA=0.1", "UNKNOWN_APNEA=0", "RERA=0"),
            record.indices.map { "${it.key}=${it.value.exact().toPlainString()}" },
        )
        assertEquals(PapUnit.EVENTS_PER_HOUR, record.indices.getValue(SummaryIndex.AHI).unit)
        // Field 43, of unknown meaning, is the only thing not understood.
        assertEquals(1, record.unknownFields)
        assertTrue(record.misreadMetrics.isEmpty())
    }

    @Test
    fun `a stored zero is a zero, and a value that is not stored stays absent`() {
        val record = night()
        // The machine writes its zeros out on a night with therapy.
        assertEquals(0L, record.indices.getValue(SummaryIndex.RERA).raw)
        assertEquals(0L, record.indices.getValue(SummaryIndex.UNKNOWN_APNEA).raw)
        // What it does not write is not turned into one.
        assertNull(record.metrics[SummaryMetric.SPO2])
        assertNull(record.metrics[SummaryMetric.TIDAL_VOLUME])
        assertTrue(record.unconfirmedMetrics.isEmpty())
        // A metric that is present but says nothing is not a reading either.
        val hollow = night { message(22) {} }
        assertNull(hollow.metrics[SummaryMetric.TIDAL_VOLUME])
        assertTrue(hollow.misreadMetrics.isEmpty())
        val noIndices = one(proto(bare { varint(5, 12) }))
        assertTrue(noIndices.indices.isEmpty())
    }

    @Test
    fun `a day without therapy has a duration of zero and nothing else`() {
        val log = summaries(proto { unusedDay(noon); night(start = noon + day); unusedDay(noon + 2 * day) })
        assertTrue(log.complete)
        val (before, used, after) = log.decoded
        assertEquals(0, before.durationMinutes)
        assertTrue(before.sessions.isEmpty() && before.indices.isEmpty() && before.metrics.isEmpty())
        assertNull(before.storedSessionCount)
        assertTrue(before.sessionCountMatches)
        assertEquals(0, before.unknownFields)
        assertEquals(305, used.durationMinutes)
        assertEquals(MachineTime(noon + 2 * day), after.periodStart)
        assertTrue(summaries(ByteArray(0)).entries.isEmpty())
    }

    @Test
    fun `percentiles are read by each metric's own layout`() {
        val record = night()
        val leak = record.metrics.getValue(SummaryMetric.LEAK)
        assertEquals(
            listOf("P50=0.06", "P70=0.08", "P95=0.42", "P100=0.86"),
            leak.map { "${it.key}=${it.value.exact().toPlainString()}" },
        )
        assertEquals(PapUnit.LITRES_PER_SECOND, leak.getValue(Percentile.P95).unit)
        // The same sub-field is the 95th percentile everywhere else.
        val pressure = record.metrics.getValue(SummaryMetric.INSPIRATORY_PRESSURE)
        assertEquals(BigDecimal("8"), pressure.getValue(Percentile.P50).exact())
        assertEquals(BigDecimal("9.6"), pressure.getValue(Percentile.P95).exact())
        assertEquals(BigDecimal("10.2"), pressure.getValue(Percentile.P100).exact())
        assertNull(pressure[Percentile.P70])
        assertEquals(BigDecimal("15.2"), record.metrics.getValue(SummaryMetric.RESPIRATORY_RATE).getValue(Percentile.P95).exact())
        val blower = record.metrics.getValue(SummaryMetric.BLOWER_PRESSURE)
        assertEquals(setOf(Percentile.P5, Percentile.P95), blower.keys)
        assertEquals(BigDecimal("4.74"), blower.getValue(Percentile.P5).exact())
        // Respiratory flow, as the SD card confirmed it: plain hundredths of a litre per second.
        val flow = record.metrics.getValue(SummaryMetric.RESPIRATORY_FLOW)
        assertEquals(BigDecimal("0.16"), flow.getValue(Percentile.P5).exact())
        assertEquals(BigDecimal("0.36"), flow.getValue(Percentile.P95).exact())
        assertEquals(setOf(Percentile.P50), record.metrics.getValue(SummaryMetric.AMBIENT_HUMIDITY).keys)
    }

    @Test
    fun `whole-number fields are kept as stored for export, and metrics never checked on a machine are kept apart`() {
        val record = night {
            varint(17, 12); varint(18, 9_550)
            message(27) { varint(2, 6_800); varint(3, 8_000); varint(4, 9_200) } // I:E ratio, never seen on a real machine
        }
        assertEquals(12, record.storedIntegers[17])
        assertEquals(9_550, record.storedIntegers[18])
        // The zone offset is still in its wire encoding there.
        assertEquals(839, record.storedIntegers[4])
        assertNull(record.metrics[SummaryMetric.IE_RATIO])
        assertEquals(BigDecimal("68"), record.unconfirmedMetrics.getValue(SummaryMetric.IE_RATIO).getValue(Percentile.P50).exact())
        assertTrue(record.metrics.keys.all { it.confirmed })
    }

    @Test
    fun `a metric that cannot be right is set aside, and the night still decodes`() {
        // Percentiles that fall, a value below zero, an oximeter figure of zero.
        val record = night {
            message(21) { varint(2, 900); varint(3, 600); varint(4, 950) }
            message(30) { varint(2, -5) } // one value, so only its sign can be wrong
            message(28) { varint(2, 0); varint(3, 0); varint(4, 0) }
            message(41) { varint(2, 0) }
        }
        assertEquals(
            setOf(SummaryMetric.MEAN_MASK_PRESSURE, SummaryMetric.HUMIDIFIER_TEMPERATURE, SummaryMetric.SPO2, SummaryMetric.HEART_RATE),
            record.misreadMetrics,
        )
        // None of them can be read by mistake, and the rest of the night is untouched.
        for (metric in record.misreadMetrics) {
            assertNull(record.metrics[metric])
            assertNull(record.unconfirmedMetrics[metric])
        }
        // One impossible zero is enough, even beside values that look fine.
        assertEquals(setOf(SummaryMetric.SPO2), night { message(28) { varint(2, 0); varint(3, 9_600); varint(4, 9_900) } }.misreadMetrics)
        // An index that cannot be right is set aside in the same way.
        val wild = one(proto(bare { varint(5, 60); varint(7, 36_001); varint(8, 150) }))
        assertEquals(setOf(SummaryIndex.AHI), wild.misreadIndices)
        assertNull(wild.indices[SummaryIndex.AHI])
        assertEquals(BigDecimal("1.5"), wild.indices.getValue(SummaryIndex.APNEA).exact())
        assertEquals(setOf(SummaryIndex.CENTRAL_APNEA), one(proto(bare { varint(11, -1) })).misreadIndices)
        assertEquals(BigDecimal("1.7"), record.indices.getValue(SummaryIndex.AHI).exact())
        assertEquals(BigDecimal("8"), record.metrics.getValue(SummaryMetric.INSPIRATORY_PRESSURE).getValue(Percentile.P50).exact())
        // Equal percentiles are a flat night, not a misreading; and zero is a fair value where it is possible.
        val flat = night { message(21) { varint(2, 500); varint(3, 500); varint(4, 500) }; message(33) { varint(2, 0) } }
        assertTrue(flat.misreadMetrics.isEmpty())
        assertEquals(0L, flat.metrics.getValue(SummaryMetric.HEATED_TUBE_POWER).getValue(Percentile.P50).raw)
        // Disorder anywhere along the row counts, not only at its start.
        assertEquals(setOf(SummaryMetric.MEAN_MASK_PRESSURE), night { message(21) { varint(2, 500); varint(3, 700); varint(4, 600) } }.misreadMetrics)
    }

    @Test
    fun `on software the layout was not confirmed on, nothing is served as confirmed`() {
        val log = SummaryDecoder.decode(proto { night() }, Firmware.reportedByMachine("SW04600.20.1.0.0.abc"))
        assertTrue(!log.layoutConfirmed)
        val record = log.decoded.single()
        assertTrue(!record.layoutConfirmed)
        assertTrue(record.metrics.isEmpty())
        // Everything read is still there, marked for what it is.
        assertEquals(BigDecimal("8"), record.unconfirmedMetrics.getValue(SummaryMetric.INSPIRATORY_PRESSURE).getValue(Percentile.P50).exact())
        assertEquals(305, record.durationMinutes)
        assertTrue(night().layoutConfirmed)
        assertTrue(summaries(proto { night() }).layoutConfirmed)
    }

    @Test
    fun `a session list that does not match its count is reported, not refused`() {
        val extra = night { message(6) { message(1) { varint(1, noon + 5_000); varint(2, 1) } } }
        assertEquals(3, extra.sessions.size)
        assertEquals(2, extra.storedSessionCount)
        assertTrue(!extra.sessionCountMatches)
        val counted = one(proto(bare { varint(39, 3) }))
        assertTrue(counted.sessions.isEmpty() && !counted.sessionCountMatches)
        val uncounted = one(proto(bare { message(6) { message(1) { varint(1, noon + 5_000) } } }))
        assertNull(uncounted.storedSessionCount)
        assertTrue(!uncounted.sessionCountMatches)
        // A session with no stored length has none; it is not given one.
        assertNull(uncounted.sessions.single().durationMinutes)
    }

    @Test
    fun `one night that is not understood keeps its place and leaves the others standing`() {
        val log = summaries(
            proto {
                night(start = noon)
                night(start = noon + day) { varint(7, 200) } // the index twice
                night(start = noon + 2 * day)
            },
        )
        assertEquals(listOf(MachineTime(noon), MachineTime(noon + 2 * day)), log.decoded.map { it.periodStart })
        assertTrue(!log.complete)
        assertEquals(3, log.entries.size)
        assertEquals(1, assertIs<RecordResult.Failed>(log.entries[1]).ordinal)
    }

    @Test
    fun `fields this version does not know are counted and skipped`() {
        val record = night {
            varint(60, 7)
            varint(60, 8) // a field of unknown meaning may repeat
            bytes(61, byteArrayOf(1, 2, 3))
            fixed32(62, 9)
            message(23) { varint(2, 675); varint(9, 1) } // minute ventilation with an unknown sub-field
        }
        assertEquals(1 + 5, record.unknownFields)
        assertEquals(7, record.storedIntegers[60])
        assertEquals(BigDecimal("6.75"), record.metrics.getValue(SummaryMetric.MINUTE_VENTILATION).getValue(Percentile.P50).exact())
    }

    @Test
    fun `a record that contradicts the layout is refused, not guessed at`() {
        refused("no period start") { message(2) { varint(1, 1); varint(3, noon + day) } }
        refused("no period end") { message(2) { varint(1, 1); varint(2, noon) } }
        refused("a period that runs backwards") { message(2) { varint(2, noon + day); varint(3, noon) } }
        refused("a period of a millisecond") { message(2) { varint(2, noon); varint(3, noon + 1) } }
        refused("a period of thirty days") { message(2) { varint(2, noon); varint(3, noon + 30 * day) } }
        refused("a period under twenty-three hours") { message(2) { varint(2, noon); varint(3, noon + day - 3_600_001) } }
        refused("a period over twenty-five hours") { message(2) { varint(2, noon); varint(3, noon + day + 3_600_001) } }
        refused("times in seconds") { message(2) { varint(2, noon / 1000); varint(3, (noon + day) / 1000) } }
        refusedNight("an index twice") { varint(7, 200) }
        refusedNight("a metric twice") { message(15) { varint(2, 900) } }
        refused("a percentile twice", bare { message(15) { varint(2, 1); varint(2, 2) } })
        refusedNight("a metric as a number") { varint(21, 5) }
        refusedNight("an index as bytes") { bytes(8, byteArrayOf(1)) }
        refusedNight("an index as a fixed number") { fixed32(11, 5) }
        refused("a percentile as bytes", bare { message(15) { bytes(2, byteArrayOf(1)) } })
        refused("a session with no start", bare { message(6) { message(1) { varint(2, 5) } } })
        refused("a session before its day", bare { message(6) { message(1) { varint(1, noon - 1); varint(2, 5) } } })
        refused("a session after its day", bare { message(6) { message(1) { varint(1, noon + day + 1); varint(2, 5) } } })
        refused("a session longer than a day", bare { message(6) { message(1) { varint(1, noon + 5); varint(2, 1441) } } })
        refused("a session start twice", bare { message(6) { message(1) { varint(1, noon + 5); varint(1, noon + 6) } } })
        refused("more than a day of therapy", bare { varint(5, 1441) })
        refused("a zone beyond the map", bare { zigzag(4, 1081) })
        refused("a zone beyond the map, westwards", bare { zigzag(4, -1081) })
        refused("a record stamp from 1970", bare { varint(40, 1000) })
        refused("a session count beyond belief", bare { varint(39, 501) })
        // The limits themselves are accepted.
        assertEquals(1, summaries(proto(bare { varint(7, 36_000); varint(5, 1440); zigzag(4, -1080) })).decoded.size)
        assertEquals(1, summaries(proto(bare { message(6) { message(1) { varint(1, noon); varint(2, 1440) } } })).decoded.size)
        assertEquals(1, summaries(proto(bare { message(6) { message(1) { varint(1, noon + day) } } })).decoded.size)
        // A day an hour short or long, as a clock change might make it.
        assertEquals(1, summaries(proto { message(2) { varint(2, noon); varint(3, noon + day - 3_600_000) } }).decoded.size)
        assertEquals(1, summaries(proto { message(2) { varint(2, noon); varint(3, noon + day + 3_600_000) } }).decoded.size)
    }

    @Test
    fun `a download that is not this log at all is refused whole`() {
        val whole = proto { night() }
        assertFailsWith<SpoolDecodeException> { summaries(whole.copyOf(whole.size - 3)) }
        assertFailsWith<SpoolDecodeException> { summaries(proto { message(5) { varint(2, noon); varint(3, noon + day) } }) }
        assertFailsWith<SpoolDecodeException> { summaries(whole + proto { varint(3, 1) }) }
        assertFailsWith<SpoolDecodeException> { summaries(proto { varint(2, 1) }) }
    }

    @Test
    fun `every scale, unit and layout matches the published table, and says whether it has been confirmed`() {
        // Field, divisor, unit, sub-field layout: rpc_spools.md, "Summary records". The last column is
        // whether the same machine's SD card has confirmed it.
        val expected = listOf(
            "LEAK 14 /100 LITRES_PER_SECOND 2=P50 3=P70 4=P95 5=P100 confirmed",
            "INSPIRATORY_PRESSURE 15 /100 CM_H2O $STANDARD confirmed",
            "EXPIRATORY_PRESSURE 20 /100 CM_H2O $STANDARD confirmed",
            "MEAN_MASK_PRESSURE 21 /100 CM_H2O $STANDARD confirmed",
            "TIDAL_VOLUME 22 /100 LITRES $STANDARD confirmed",
            "MINUTE_VENTILATION 23 /100 LITRES_PER_MINUTE $STANDARD confirmed",
            "TARGET_MINUTE_VENTILATION 24 /100 LITRES_PER_MINUTE $STANDARD unconfirmed",
            "RESPIRATORY_RATE 25 /100 BREATHS_PER_MINUTE $STANDARD confirmed",
            "INSPIRATORY_DURATION 26 /1000 SECONDS $STANDARD unconfirmed",
            "IE_RATIO 27 /100 PERCENT $STANDARD unconfirmed",
            "SPO2 28 /100 PERCENT $STANDARD unconfirmed",
            "AMBIENT_HUMIDITY 29 /100 MILLIGRAMS_PER_LITRE 2=P50 confirmed",
            "HUMIDIFIER_TEMPERATURE 30 /100 CELSIUS 2=P50 confirmed",
            "HEATED_TUBE_TEMPERATURE 31 /100 CELSIUS 2=P50 confirmed",
            "HUMIDIFIER_POWER 32 /100 PERCENT 2=P50 confirmed",
            "HEATED_TUBE_POWER 33 /100 PERCENT 2=P50 confirmed",
            "BLOWER_PRESSURE 36 /100 CM_H2O 1=P5 3=P95 confirmed",
            "RESPIRATORY_FLOW 37 /100 LITRES_PER_SECOND 1=P5 3=P95 confirmed",
            "BLOWER_FLOW 38 /100 LITRES_PER_SECOND 2=P50 confirmed",
            "HEART_RATE 41 /100 BEATS_PER_MINUTE $STANDARD unconfirmed",
            "ALVEOLAR_MINUTE_VENTILATION 42 /100 LITRES_PER_MINUTE $STANDARD unconfirmed",
        )
        assertEquals(
            expected,
            SummaryMetric.entries.map { metric ->
                check(metric.scale.numerator == 1)
                "${metric.name} ${metric.field} /${metric.scale.denominator} ${metric.unit} " +
                    metric.layout.bySubField.entries.joinToString(" ") { "${it.key}=${it.value}" } +
                    if (metric.confirmed) " confirmed" else " unconfirmed"
            },
        )
        assertEquals(setOf(SummaryMetric.SPO2, SummaryMetric.HEART_RATE), SummaryMetric.entries.filter { it.zeroIsImpossible }.toSet())
        assertEquals(
            listOf("AHI=7", "APNEA=8", "HYPOPNEA=9", "OBSTRUCTIVE_APNEA=10", "CENTRAL_APNEA=11", "UNKNOWN_APNEA=12", "RERA=13"),
            SummaryIndex.entries.map { "${it.name}=${it.field}" },
        )
        for (index in SummaryIndex.entries) {
            assertEquals(100, index.scale.denominator)
            assertEquals(PapUnit.EVENTS_PER_HOUR, index.unit)
        }
        // Lowest first: the order check for misread metrics depends on it.
        assertEquals(listOf(Percentile.P5, Percentile.P50, Percentile.P70, Percentile.P95, Percentile.P100), Percentile.entries.toList())
    }

    private companion object {
        const val STANDARD = "2=P50 3=P95 4=P100"
    }
}
