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
 *    padding. They are **not** included in [samples]. A caller that can settle
 *    the true length from elsewhere (the mask-off time, the next record) may
 *    read them with [samplesWithTail].
 */
public class MinuteRecord internal constructor(
    public val startMs: Long,
    public val intervalMs: Long,
    public val sampleCount: Int,
    public val uncertainTail: Int,
    private val series: Map<MinuteSignal, IntArray>,
    /** Fields of the record this version does not understand. */
    public val unknownFields: Int,
) {
    public val signals: Set<MinuteSignal> get() = series.keys

    /** The stored integers of [signal] for the samples that certainly exist, or null if the record has no such signal. */
    public fun samples(signal: MinuteSignal): IntArray? = series[signal]?.copyOf(sampleCount)

    /** As [samples], followed by the [uncertainTail] positions that may be padding. */
    public fun samplesWithTail(signal: MinuteSignal): IntArray? = series[signal]?.copyOf(sampleCount + uncertainTail)

    /** The physical value of one sample, or null if the record has no such signal. */
    public fun quantity(signal: MinuteSignal, index: Int): Quantity? {
        val values = series[signal] ?: return null
        if (index !in 0 until sampleCount) throw IndexOutOfBoundsException("sample $index of $sampleCount")
        return signal.scale.of(values[index].toLong(), signal.unit)
    }

    /** When sample [index] was taken, by the machine's clock. */
    public fun timeOf(index: Int): Long = startMs + index * intervalMs
}

/** Decodes the `TherapyOneMinutePeriodic` log. */
public object MinuteDecoder {
    private const val STATUS = 1
    private const val START = 2
    private const val BLOCK = 3
    private const val INTERVAL = 15
    private const val EXPECTED_STATUS = 1L
    private const val MINUTE_MS = 60_000L
    private const val MAX_INTERVAL_MS = 24 * 60 * MINUTE_MS

    private val signalByField = MinuteSignal.entries.associateBy { it.field }

    /**
     * @param payload one verified download of the per-minute log.
     * @throws SpoolDecodeException if the payload is not what this version understands,
     *   or the signals of a record contradict each other.
     */
    public fun decode(payload: ByteArray): List<MinuteRecord> =
        unwrap(payload, SpoolEnvelope.MINUTE_LOG, "per-minute log").mapIndexed { index, body -> record(body, "per-minute record $index") }

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
        // Only one kind of series has ever been seen. Another may mean the samples are not what is assumed here.
        if (status != null && status != EXPECTED_STATUS) throw SpoolDecodeException("$what: unknown kind of series ($status)")
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

    /** The interval field holds minutes when small and milliseconds otherwise; absent means one minute. */
    private fun intervalMs(token: Long?, what: String): Long {
        val ms = when {
            token == null -> MINUTE_MS
            token in 1 until 1000 -> token * MINUTE_MS
            else -> token
        }
        if (ms !in 1000..MAX_INTERVAL_MS) throw SpoolDecodeException("$what: sample interval out of range")
        return ms
    }
}
