// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
//
// Record layout from the airbreak-plus protocol notes (MIT), facts only.
package com.everysoft.paprelay.as11.decode

import com.everysoft.paprelay.core.MachineTime
import com.everysoft.paprelay.core.Quantity
import java.util.Collections

/**
 * One session listed in a day's Summary.
 *
 * On the machine examined each entry matched one mask-on period on the SD
 * card: its start to the minute, and its length.
 *
 * @property durationMinutes its length, or null if the record stores none.
 */
public class SummarySession internal constructor(public val start: MachineTime, public val durationMinutes: Int?)

/**
 * One day of the machine's own totals, exactly as stored.
 *
 * A day runs from the machine's local noon to the next. Nothing here is
 * computed by PAPRelay: indices and percentiles are the machine's figures (it
 * cuts indices off at one decimal place).
 *
 * A value the record does not carry is absent (null, or missing from a map),
 * never zero. On the machine examined (firmware 19.8.8.0) a day with therapy
 * writes its zeros out, so there a stored zero is a real zero, and a day
 * without therapy carries a duration of zero and nothing else. Other firmware
 * may leave zeros out; what an absent index means on a day with usage is for
 * the caller to decide, not guessed here.
 */
public class SummaryRecord internal constructor(
    /**
     * Whether the machine's software is the one this record's layout was confirmed on. When
     * false, [metrics] is empty, every metric read is in [unconfirmedMetrics], and the
     * indices, durations and sessions are likewise as the published format describes them,
     * unchecked: fit to keep and to show as such, not to write into a health record.
     */
    public val layoutConfirmed: Boolean,
    /** The machine's local noon that starts the day. */
    public val periodStart: MachineTime,
    /** The following noon. */
    public val periodEnd: MachineTime,
    /**
     * The machine's zone setting **when the record was sent**, not when the night happened:
     * every record of a download carries the same value. Local time is UTC plus this.
     */
    public val zoneOffsetMinutesWhenSent: Int?,
    /** Minutes of therapy in the day. */
    public val durationMinutes: Int?,
    public val sessions: List<SummarySession>,
    /** How many sessions the record says it lists, when it says. See [sessionCountMatches]. */
    public val storedSessionCount: Int?,
    /** When the machine last wrote this record. */
    public val recordTimestamp: MachineTime?,
    public val indices: Map<SummaryIndex, Quantity>,
    /** Indices the record carries with a value that cannot be right; they are not in [indices]. */
    public val misreadIndices: Set<SummaryIndex>,
    /**
     * The metrics that were read and whose layout and factor the SD card has confirmed
     * ([SummaryMetric.confirmed]). See [misreadMetrics] for those that were present but left out.
     */
    public val metrics: Map<SummaryMetric, Map<Percentile, Quantity>>,
    /**
     * Metrics read as the reference describes them, but never checked against a real
     * machine. They may be wrong; they are kept apart so that using one is a decision.
     */
    public val unconfirmedMetrics: Map<SummaryMetric, Map<Percentile, Quantity>>,
    /**
     * Metrics the record carries whose values cannot be right, and which are therefore not in
     * [metrics]: percentiles that fall where they should rise, a value that is negative or
     * absurdly large, or a zero where zero is impossible (an oximeter figure with no oximeter).
     */
    public val misreadMetrics: Set<SummaryMetric>,
    /**
     * Every whole-number field of the record by field number, exactly as stored, for
     * export. Not interpreted: the zone offset, for one, is still in its wire encoding.
     * A field of unknown meaning that appears more than once is listed with its first value.
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

    private const val HOUR_MS = 3_600_000L
    private const val RECORD_MARKER = 1L
    private const val MINUTES_PER_DAY = 1440
    private const val MAX_ZONE_OFFSET = 18 * 60
    private const val MAX_SESSIONS = 500

    /** 360 an hour is an event every ten seconds, the shortest an apnoea can be. */
    private const val MAX_INDEX_HUNDREDTHS = 36_000L

    private val indexByField = SummaryIndex.entries.associateBy { it.field }
    private val metricByField = SummaryMetric.entries.associateBy { it.field }

    private val KNOWN_INTEGER_FIELDS: Set<Int> =
        setOf(MARKER, PERIOD_START, PERIOD_END, ZONE_OFFSET, DURATION, CSR, SPO2_THRESHOLD, SPONT_TRIGGER, SPONT_CYCLE, HUMIDIFIER_CONNECTED, TUBE_CONNECTED, SESSION_COUNT, RECORD_TIMESTAMP) +
            SummaryIndex.entries.map { it.field }

    /**
     * @param payload one verified download of the `Summary` log.
     * @param firmware the software of the machine the download came from.
     * @throws SpoolDecodeException if the download as a whole is not this log.
     */
    public fun decode(payload: ByteArray, firmware: Firmware): DecodedLog<SummaryRecord> {
        val sink = ResultSink<SummaryRecord>()
        unwrap(payload, SpoolEnvelope.SUMMARY, "Summary").forEachIndexed { index, body ->
            sink.attempt { record(body, "Summary record $index", firmware.layoutsConfirmed) }
        }
        return sink.finish(firmware.layoutsConfirmed)
    }

    private fun record(body: ByteArray, what: String, layoutConfirmed: Boolean): SummaryRecord {
        val integers = LinkedHashMap<Int, Long>()
        val metrics = LinkedHashMap<SummaryMetric, Map<Percentile, Quantity>>()
        val misread = LinkedHashSet<SummaryMetric>()
        val emptied = HashSet<SummaryMetric>()
        val sessionBodies = ArrayList<ByteArray>()
        var unknown = 0

        for (field in ProtoReader.read(body)) {
            val metric = metricByField[field.number]
            val bytes = field.bytes
            when {
                field.number == SESSIONS -> sessionBodies += bytes ?: wrongType(what, field)
                metric != null -> {
                    if (metric in metrics || metric in misread || metric in emptied) repeated(what, field.number)
                    val values = LinkedHashMap<Percentile, Quantity>()
                    val result = percentiles(metric, bytes ?: wrongType(what, field), what, values)
                    unknown += result.unknown
                    when {
                        !result.readable || !inOrder(values) || (metric.zeroIsImpossible && values.values.any { it.raw == 0L }) -> misread += metric
                        // Present but empty: the record says nothing about it, which is not a reading.
                        values.isEmpty() -> emptied += metric
                        else -> metrics[metric] = Collections.unmodifiableMap(values)
                    }
                }
                field.wireType == ProtoReader.VARINT ->
                    if (field.number in KNOWN_INTEGER_FIELDS) {
                        if (integers.put(field.number, field.value) != null) repeated(what, field.number)
                    } else {
                        // A field of unknown meaning may repeat; the first value is what is kept for export.
                        integers.putIfAbsent(field.number, field.value)
                        unknown++
                    }
                field.number in KNOWN_INTEGER_FIELDS -> wrongType(what, field)
                else -> unknown++
            }
        }

        val start = timestamp(integers[PERIOD_START] ?: missing(what, "the period start"), what)
        val end = timestamp(integers[PERIOD_END] ?: missing(what, "the period end"), what)
        // Twenty-four hours in everything seen. Exactly an hour either way is allowed for, since no
        // capture yet spans a clock change and what the machine does then is not known.
        if (end.since(start) !in setOf(23 * HOUR_MS, 24 * HOUR_MS, 25 * HOUR_MS)) throw SpoolDecodeException("$what: the period is not one day long")
        // Every record seen opens with this marker. One without it is some other kind of record.
        if (integers[MARKER] != RECORD_MARKER) throw SpoolDecodeException("$what: not marked as a summary record")

        val sessions = ArrayList<SummarySession>()
        for (sessionBody in sessionBodies) unknown += sessions(sessionBody, what, start, end, sessions)

        val indices = LinkedHashMap<SummaryIndex, Quantity>()
        val misreadIndices = LinkedHashSet<SummaryIndex>()
        for ((number, index) in indexByField) {
            val stored = integers[number] ?: continue
            if (stored in 0..MAX_INDEX_HUNDREDTHS) indices[index] = Quantity(stored, index.scale, index.unit) else misreadIndices += index
        }

        return SummaryRecord(
            layoutConfirmed = layoutConfirmed,
            periodStart = start,
            periodEnd = end,
            zoneOffsetMinutesWhenSent = integers[ZONE_OFFSET]?.let { stored ->
                val minutes = (stored ushr 1) xor -(stored and 1)
                if (minutes !in -MAX_ZONE_OFFSET..MAX_ZONE_OFFSET) throw SpoolDecodeException("$what: zone offset out of range")
                minutes.toInt()
            },
            durationMinutes = integers[DURATION]?.let { count(it, MINUTES_PER_DAY, what, "duration") },
            sessions = Collections.unmodifiableList(sessions),
            storedSessionCount = integers[SESSION_COUNT]?.let { count(it, MAX_SESSIONS, what, "the session count") },
            recordTimestamp = integers[RECORD_TIMESTAMP]?.let { timestamp(it, what) },
            indices = Collections.unmodifiableMap(indices),
            misreadIndices = Collections.unmodifiableSet(misreadIndices),
            metrics = Collections.unmodifiableMap(metrics.filterKeys { layoutConfirmed && it.confirmed }),
            unconfirmedMetrics = Collections.unmodifiableMap(metrics.filterKeys { !(layoutConfirmed && it.confirmed) }),
            misreadMetrics = Collections.unmodifiableSet(misread),
            storedIntegers = Collections.unmodifiableMap(integers),
            unknownFields = unknown,
        )
    }

    /** Percentile is declared lowest first, so the values must not fall along it. Equal values are in order. */
    private fun inOrder(values: Map<Percentile, Quantity>): Boolean =
        Percentile.entries.mapNotNull { values[it]?.raw }.zipWithNext().all { (lower, higher) -> higher >= lower }

    /** Appends the sessions of one field-6 body; returns how many fields in it were not understood. */
    private fun sessions(body: ByteArray, what: String, periodStart: MachineTime, periodEnd: MachineTime, into: MutableList<SummarySession>): Int {
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
            val began = timestamp(start ?: missing(what, "a session start"), what)
            // A session belongs to the day it starts in. Whether one that starts exactly at noon
            // is filed under the day ending or the day beginning is not known, so both ends are let in.
            if (began < periodStart || began > periodEnd) throw SpoolDecodeException("$what: a session starts outside its day")
            into += SummarySession(began, minutes?.let { count(it, MINUTES_PER_DAY, what, "session duration") })
        }
        return unknown
    }

    private class Read(val unknown: Int, val readable: Boolean)

    /** Fills [into] from one metric body. Not [Read.readable] if a value cannot be a measurement. */
    private fun percentiles(metric: SummaryMetric, body: ByteArray, what: String, into: MutableMap<Percentile, Quantity>): Read {
        var unknown = 0
        var readable = true
        for (field in ProtoReader.read(body)) {
            val percentile = metric.layout.bySubField[field.number]
            when {
                percentile == null -> unknown++
                field.wireType != ProtoReader.VARINT -> wrongType("$what, $metric", field)
                percentile in into -> repeated("$what, $metric", field.number)
                else -> {
                    val value = quantityOrNull(field.value, metric.scale, metric.unit)
                    if (value == null) readable = false else into[percentile] = value
                }
            }
        }
        return Read(unknown, readable)
    }
}
