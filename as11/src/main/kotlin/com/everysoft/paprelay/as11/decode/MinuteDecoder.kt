// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
//
// Record layout from the airbreak-plus protocol notes (MIT), facts only.
package com.everysoft.paprelay.as11.decode

import com.everysoft.paprelay.core.Quantity

/**
 * One stretch of the per-minute log: several signals sampled at the same times.
 *
 * The record stores no sample count, and a compressed series cannot always
 * show where it ends (see [SampleBlock]). The signals of a record share their
 * times, so each one narrows the answer for the others. What is left is:
 *
 *  - [sampleCount] samples that certainly exist, and
 *  - [uncertainTail] further positions that may be real minutes or may be
 *    padding. Nothing returns them unless the caller names a length it has
 *    settled from elsewhere (the mask-off time, the next record).
 *
 * Values come out as [Quantity], which carries the factor and the unit. The
 * stored integers are available too, but they are not physical values: a
 * stored respiratory rate of 60 means 15 breaths a minute.
 */
public class MinuteRecord internal constructor(
    /** When the first sample was taken, by the machine's clock, in UTC milliseconds. */
    public val startMs: Long,
    public val intervalMs: Long,
    public val sampleCount: Int,
    public val uncertainTail: Int,
    private val series: Map<MinuteSignal, IntArray>,
    /** Fields of the record this version does not understand. */
    public val unknownFields: Int,
) {
    public val signals: Set<MinuteSignal> get() = series.keys

    /**
     * The integers the machine stored for [signal], or null if the record has no such signal.
     * These are not physical values; use [quantity] for those.
     *
     * @param length how many samples to return: [sampleCount] unless the caller has settled
     *   the record's true length, which lies between [sampleCount] and `sampleCount + uncertainTail`.
     */
    public fun storedSamples(signal: MinuteSignal, length: Int = sampleCount): IntArray? {
        checkLength(length)
        return series[signal]?.copyOf(length)
    }

    /**
     * The physical value of one sample, or null if the record has no such signal.
     *
     * @param length the record's settled length, as for [storedSamples].
     */
    public fun quantity(signal: MinuteSignal, index: Int, length: Int = sampleCount): Quantity? {
        checkLength(length)
        if (index !in 0 until length) throw IndexOutOfBoundsException("sample $index of $length")
        val values = series[signal] ?: return null
        return signal.scale.of(values[index].toLong(), signal.unit)
    }

    /** When sample [index] was taken, by the machine's clock, in UTC milliseconds. */
    public fun timeOf(index: Int): Long {
        if (index !in 0 until sampleCount + uncertainTail) throw IndexOutOfBoundsException("sample $index")
        return startMs + index * intervalMs
    }

    private fun checkLength(length: Int) {
        require(length in sampleCount..sampleCount + uncertainTail) {
            "a length of $length is not possible for this record ($sampleCount to ${sampleCount + uncertainTail})"
        }
    }
}

/** Decodes the `TherapyOneMinutePeriodic` log. */
public object MinuteDecoder {
    private const val STATUS = 1
    private const val START = 2
    private const val BLOCK = 3
    private const val INTERVAL = 15
    private const val EXPECTED_STATUS = 1L
    private const val MINUTE_MS = 60_000L

    private val signalByField = MinuteSignal.entries.associateBy { it.field }

    /**
     * @param payload one verified download of the per-minute log.
     * @throws SpoolDecodeException if the download as a whole is not this log.
     */
    public fun decode(payload: ByteArray): DecodedLog<MinuteRecord> =
        decodeRecords(payload, SpoolEnvelope.MINUTE_LOG, "per-minute log") { body, what -> listOf(record(body, what)) }

    private class Series(val start: Long, val block: SampleBlock)

    private fun record(body: ByteArray, what: String): MinuteRecord {
        val found = LinkedHashMap<MinuteSignal, Series>()
        var interval: Long? = null
        var unknown = 0
        for (field in ProtoReader.read(body)) {
            val signal = signalByField[field.number]
            when {
                signal != null -> {
                    val (series, extra) = series(signal, field.bytes ?: wrongType(what, field), "$what, $signal")
                    unknown += extra
                    if (found.put(signal, series) != null) repeated(what, field.number)
                }
                field.number == INTERVAL -> {
                    if (field.wireType != ProtoReader.VARINT) wrongType(what, field)
                    if (interval == null) interval = field.value else repeated(what, INTERVAL)
                }
                else -> unknown++
            }
        }
        if (found.isEmpty()) throw SpoolDecodeException("$what: no signals")

        val start = found.values.first().start
        if (found.values.any { it.start != start }) throw SpoolDecodeException("$what: the signals start at different times")

        // Every signal has the same true length, so it is at least the longest
        // certain run and at most the shortest series.
        val atLeast = found.values.maxOf { it.block.certain }
        val atMost = found.values.minOf { it.block.samples.size }
        if (atLeast > atMost) throw SpoolDecodeException("$what: the signals disagree about how many samples there are")

        return MinuteRecord(
            startMs = start,
            intervalMs = intervalMs(interval, what),
            sampleCount = atLeast,
            uncertainTail = atMost - atLeast,
            series = found.mapValues { it.value.block.samples.copyOf(atMost) },
            unknownFields = unknown,
        )
    }

    private fun series(signal: MinuteSignal, body: ByteArray, what: String): Pair<Series, Int> {
        var status: Long? = null
        var start: Long? = null
        var block: ByteArray? = null
        var unknown = 0
        for (field in ProtoReader.read(body)) {
            when (field.number) {
                STATUS -> {
                    if (field.wireType != ProtoReader.VARINT) wrongType(what, field)
                    if (status == null) status = field.value else repeated(what, STATUS)
                }
                START -> {
                    if (field.wireType != ProtoReader.VARINT) wrongType(what, field)
                    if (start == null) start = field.value else repeated(what, START)
                }
                BLOCK -> if (block == null) block = field.bytes ?: wrongType(what, field) else repeated(what, BLOCK)
                else -> unknown++
            }
        }
        // Only one kind of series has ever been seen. Any other, a missing marker included
        // (which reads as kind zero), may mean the samples are not what is assumed here.
        if (status != EXPECTED_STATUS) throw SpoolDecodeException("$what: unknown kind of series (${status ?: "none"})")
        val bytes = block ?: missing(what, "the sample block")
        val modulus = signal.modulus
        val decoded = if (modulus != null) {
            SampleSeries.decode(bytes, modulus)
        } else {
            if (bytes.size % 2 != 0) throw SpoolDecodeException("$what: an uncompressed series has an odd length")
            val raw = SampleSeries.decodeRaw(bytes)
            if (raw.size > SampleSeries.MAX_SAMPLES) throw SpoolDecodeException("$what: too many samples")
            SampleBlock(raw, raw.size, unreadBits = 0)
        }
        return Series(timestamp(start ?: missing(what, "the start time"), what), decoded) to unknown
    }

    /**
     * One minute, however it is written: left out, as 1 (minutes) or as 60000
     * (milliseconds). The format allows other intervals, but none has been
     * seen, and a misread interval would misplace every sample after the first.
     */
    private fun intervalMs(token: Long?, what: String): Long {
        if (token != null && token != 1L && token != MINUTE_MS) throw SpoolDecodeException("$what: unexpected sample interval ($token)")
        return MINUTE_MS
    }
}
