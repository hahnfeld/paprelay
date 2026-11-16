// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
//
// Record layout from the airbreak-plus protocol notes (MIT), facts only.
package com.everysoft.paprelay.as11.decode

import com.everysoft.paprelay.core.PapUnit
import com.everysoft.paprelay.core.Quantity
import com.everysoft.paprelay.core.Scale
import java.util.Collections

/**
 * One mask-on period listed in a day's Summary.
 *
 * @property startMs when it began, by the machine's clock, in UTC milliseconds.
 * @property durationMinutes its length; zero when the record stores none, which is
 *   how the machine writes a session shorter than a minute.
 */
public class SummarySession(public val startMs: Long, public val durationMinutes: Int)

/**
 * One day of the machine's own totals, exactly as stored.
 *
 * A day runs from the machine's local noon to the next. Nothing here is
 * computed by PAPRelay: indices and percentiles are the machine's figures.
 *
 * A value the record does not carry is absent (null, or missing from a map),
 * never zero. The machine may leave a field out *because* it is zero, so what
 * an absent index means on a night with usage is decided by the caller, not
 * guessed here.
 */
public class SummaryRecord internal constructor(
    /** The machine's local noon that starts the day, by its clock, in UTC milliseconds. */
    public val periodStartMs: Long,
    /** The following noon, in UTC milliseconds. */
    public val periodEndMs: Long,
    /** The machine's zone offset when the record was sent, not when the night happened. */
    public val zoneOffsetMinutes: Int?,
    public val durationMinutes: Int?,
    public val sessions: List<SummarySession>,
    /** How many sessions the record says it lists, when it says. See [sessionCountMatches]. */
    public val storedSessionCount: Int?,
    /** When the machine last wrote this record, by its own clock, in UTC milliseconds. */
    public val recordTimestampMs: Long?,
    public val indices: Map<SummaryIndex, Quantity>,
    public val metrics: Map<SummaryMetric, Map<Percentile, Quantity>>,
    public val spontaneousTriggered: Quantity?,
    public val spontaneousCycled: Quantity?,
    public val minutesBelowSpo2Threshold: Int?,
    /**
     * Metrics whose percentiles are not in rising order. A higher percentile below a lower
     * one cannot be a real distribution, so the metric was probably misread; do not use it.
     */
    public val disorderedMetrics: Set<SummaryMetric>,
    /**
     * Every whole-number field of the record by field number, exactly as stored, for
     * export. Not interpreted: the zone offset, for one, is still in its wire encoding.
     */
    public val storedIntegers: Map<Int, Long>,
    /** Fields and sub-fields this version does not understand. They are skipped, not errors. */
    public val unknownFields: Int,
) {
    /**
     * Whether the session list has as many entries as the record says. When it does not,
     * the list may be incomplete, and a duration summed from it is not to be trusted.
     */
    public val sessionCountMatches: Boolean get() = (storedSessionCount ?: 0) == sessions.size
}

/** Decodes the `Summary` log. */
public object SummaryDecoder {
    private const val MARKER = 1
    private const val PERIOD_START = 2
    private const val PERIOD_END = 3
    private const val ZONE_OFFSET = 4
    private const val DURATION = 5
    private const val SESSIONS = 6
    private const val CSR = 16
    private const val SPO2_THRESHOLD = 17
    private const val SPONT_TRIGGER = 18
    private const val SPONT_CYCLE = 19
    private const val HUMIDIFIER_CONNECTED = 34
    private const val TUBE_CONNECTED = 35
    private const val SESSION_COUNT = 39
    private const val RECORD_TIMESTAMP = 40

    private const val MINUTES_PER_DAY = 1440
    private const val MAX_ZONE_OFFSET = 18 * 60
    private val PERCENT_HUNDREDTHS = Scale(1, 100)

    private val indexByField = SummaryIndex.entries.associateBy { it.field }
    private val metricByField = SummaryMetric.entries.associateBy { it.field }

    /**
     * @param payload one verified download of the `Summary` log.
     * @throws SpoolDecodeException if the download as a whole is not this log.
     */
    public fun decode(payload: ByteArray): DecodedLog<SummaryRecord> =
        decodeRecords(payload, SpoolEnvelope.SUMMARY, "Summary") { body, what -> listOf(record(body, what)) }

    private fun record(body: ByteArray, what: String): SummaryRecord {
        val integers = LinkedHashMap<Int, Long>()
        val metrics = LinkedHashMap<SummaryMetric, Map<Percentile, Quantity>>()
        val sessions = ArrayList<SummarySession>()
        var unknown = 0

        for (field in ProtoReader.read(body)) {
            val metric = metricByField[field.number]
            val bytes = field.bytes
            when {
                field.number == SESSIONS ->
                    unknown += sessions(bytes ?: wrongType(what, field), what, sessions)
                metric != null -> {
                    val values = LinkedHashMap<Percentile, Quantity>()
                    unknown += percentiles(metric, bytes ?: wrongType(what, field), what, values)
                    if (metrics.put(metric, Collections.unmodifiableMap(values)) != null) repeated(what, field.number)
                }
                field.wireType == ProtoReader.VARINT ->
                    if (integers.put(field.number, field.value) != null) repeated(what, field.number)
                field.number in KNOWN_INTEGER_FIELDS -> wrongType(what, field)
                else -> unknown++
            }
        }

        val start = timestamp(integers[PERIOD_START] ?: missing(what, "the period start"), what)
        val end = timestamp(integers[PERIOD_END] ?: missing(what, "the period end"), what)
        if (end <= start) throw SpoolDecodeException("$what: the period ends before it starts")

        val indices = LinkedHashMap<SummaryIndex, Quantity>()
        for ((number, index) in indexByField) {
            integers[number]?.let { indices[index] = quantity(it, index.scale, index.unit, what) }
        }
        unknown += integers.keys.count { it !in KNOWN_INTEGER_FIELDS }

        return SummaryRecord(
            periodStartMs = start,
            periodEndMs = end,
            zoneOffsetMinutes = integers[ZONE_OFFSET]?.let { stored ->
                val minutes = (stored ushr 1) xor -(stored and 1)
                if (minutes !in -MAX_ZONE_OFFSET..MAX_ZONE_OFFSET) throw SpoolDecodeException("$what: zone offset out of range")
                minutes.toInt()
            },
            durationMinutes = integers[DURATION]?.let { count(it, MINUTES_PER_DAY, what, "duration") },
            sessions = Collections.unmodifiableList(sessions),
            storedSessionCount = integers[SESSION_COUNT]?.let { count(it, MAX_SESSIONS, what, "the session count") },
            recordTimestampMs = integers[RECORD_TIMESTAMP]?.let { timestamp(it, what) },
            indices = Collections.unmodifiableMap(indices),
            metrics = Collections.unmodifiableMap(metrics),
            spontaneousTriggered = integers[SPONT_TRIGGER]?.let { quantity(it, PERCENT_HUNDREDTHS, PapUnit.PERCENT, what) },
            spontaneousCycled = integers[SPONT_CYCLE]?.let { quantity(it, PERCENT_HUNDREDTHS, PapUnit.PERCENT, what) },
            minutesBelowSpo2Threshold = integers[SPO2_THRESHOLD]?.let { count(it, MINUTES_PER_DAY, what, "time below the SpO2 threshold") },
            disorderedMetrics = metrics.filterValues { values ->
                // Percentile is declared lowest first, so the values must not fall along it.
                val inOrder = Percentile.entries.mapNotNull { values[it]?.raw }
                inOrder.zipWithNext().any { (lower, higher) -> higher < lower }
            }.keys,
            storedIntegers = Collections.unmodifiableMap(integers),
            unknownFields = unknown,
        )
    }

    /** Appends the sessions of one field-6 body; returns how many fields in it were not understood. */
    private fun sessions(body: ByteArray, what: String, into: MutableList<SummarySession>): Int {
        var unknown = 0
        for (wrapper in ProtoReader.read(body)) {
            if (wrapper.number != 1 || wrapper.wireType != ProtoReader.BYTES) {
                unknown++
                continue
            }
            var start: Long? = null
            var minutes: Long? = null
            for (field in ProtoReader.read(wrapper.bytes!!)) {
                when {
                    field.number == 1 && field.wireType == ProtoReader.VARINT ->
                        if (start == null) start = field.value else repeated(what, field.number)
                    field.number == 2 && field.wireType == ProtoReader.VARINT ->
                        if (minutes == null) minutes = field.value else repeated(what, field.number)
                    field.number == 1 || field.number == 2 -> wrongType(what, field)
                    else -> unknown++
                }
            }
            if (into.size >= MAX_SESSIONS) throw SpoolDecodeException("$what: too many sessions")
            into += SummarySession(
                startMs = timestamp(start ?: missing(what, "a session start"), what),
                // The machine leaves the duration out when it is zero.
                durationMinutes = count(minutes ?: 0, MINUTES_PER_DAY, what, "session duration"),
            )
        }
        return unknown
    }

    /** Fills [into] from one metric body; returns how many sub-fields were not understood. */
    private fun percentiles(metric: SummaryMetric, body: ByteArray, what: String, into: MutableMap<Percentile, Quantity>): Int {
        var unknown = 0
        for (field in ProtoReader.read(body)) {
            val percentile = metric.layout.bySubField[field.number]
            when {
                percentile == null -> unknown++
                field.wireType != ProtoReader.VARINT -> wrongType("$what, $metric", field)
                into.put(percentile, quantity(field.value, metric.scale, metric.unit, what)) != null ->
                    repeated("$what, $metric", field.number)
            }
        }
        return unknown
    }

    private const val MAX_SESSIONS = 500

    private val KNOWN_INTEGER_FIELDS: Set<Int> =
        setOf(MARKER, PERIOD_START, PERIOD_END, ZONE_OFFSET, DURATION, CSR, SPO2_THRESHOLD, SPONT_TRIGGER, SPONT_CYCLE, HUMIDIFIER_CONNECTED, TUBE_CONNECTED, SESSION_COUNT, RECORD_TIMESTAMP) +
            SummaryIndex.entries.map { it.field }
}
