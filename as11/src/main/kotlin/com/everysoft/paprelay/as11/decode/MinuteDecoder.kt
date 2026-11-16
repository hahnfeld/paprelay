// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
//
// Record layout from the airbreak-plus protocol notes (MIT), facts only.
package com.everysoft.paprelay.as11.decode

import com.everysoft.paprelay.core.MachineTime
import com.everysoft.paprelay.core.Quantity
import java.util.Collections

/**
 * One stretch of the per-minute log: several signals sampled at the same times.
 *
 * What is known, from a capture compared with the same machine's SD card
 * (AirSense 11 AutoSet, firmware 19.8.8.0): the machine writes one record per
 * mask-on period and one sample per completed minute. Each sample is the
 * machine's own figure for the minute that **ends** at its time stamp: the
 * first is stamped sixty seconds after mask-on and equals the card's first
 * minute. A last part-minute before mask-off has no sample. Three sessions
 * were examined.
 *
 * The record stores no sample count, and a compressed series cannot always
 * show where it ends. The signals of a record share their times, so each one
 * narrows the answer for the others. What is left is:
 *
 *  - [sampleCount] samples that certainly exist, and
 *  - [uncertainTail] further positions that may be real minutes or may be
 *    padding. Nothing returns them unless the caller names a length it has
 *    settled from elsewhere (the mask-off time, the next record). With the
 *    padding this machine uses, the tail is empty unless every series of the
 *    record happens to end exactly on a byte.
 */
public class MinuteRecord internal constructor(
    /** The time stamp of the first sample: the **end** of the first minute, sixty seconds after mask-on. */
    public val firstMinuteEnd: MachineTime,
    public val intervalMs: Long,
    public val sampleCount: Int,
    public val uncertainTail: Int,
    private val series: Map<MinuteSignal, IntArray>,
    /**
     * Signals the record carries but which were not decoded, because they have not been
     * seen and confirmed on a real machine. See [MinuteSignal.layoutConfirmed].
     */
    public val withheldSignals: Set<MinuteSignal>,
    /**
     * Signals that were decoded but hold a value that cannot be a measurement: below zero,
     * or above anything the machine can report. They are not served. What such a value
     * means (a marker for a minute without data, perhaps) is not known.
     */
    public val misreadSignals: Set<MinuteSignal>,
    /** Fields of the record this version does not understand. */
    public val unknownFields: Int,
) {
    /** The signals whose values can be read. */
    public val signals: Set<MinuteSignal> = Collections.unmodifiableSet(LinkedHashSet(series.keys))

    /**
     * The physical values of [signal], or null if the record does not serve that signal.
     * A stored zero is passed on as zero; whether the machine uses it for "no breath
     * detected" is not known, and is for the caller to weigh.
     *
     * @param length how many samples to return: [sampleCount] unless the caller has settled
     *   the record's true length, which lies between [sampleCount] and `sampleCount + uncertainTail`.
     * @param allowUnconfirmed must be true to read a signal whose factor has not been
     *   confirmed against the SD card ([MinuteSignal.factorConfirmed]); such values may be wrong.
     * @throws IllegalArgumentException if [length] is not possible for this record.
     * @throws IllegalStateException if the signal's factor is unconfirmed and [allowUnconfirmed] is false.
     */
    public fun quantities(signal: MinuteSignal, length: Int = sampleCount, allowUnconfirmed: Boolean = false): List<Quantity>? {
        checkLength(length)
        val values = series[signal] ?: return null
        check(signal.factorConfirmed || allowUnconfirmed) { "the factor for $signal has not been confirmed" }
        return List(length) { signal.scale.of(values[it].toLong(), signal.unit) }
    }

    /**
     * One value of [quantities].
     *
     * @throws IndexOutOfBoundsException if [index] is not within [length].
     */
    public fun quantity(signal: MinuteSignal, index: Int, length: Int = sampleCount, allowUnconfirmed: Boolean = false): Quantity? {
        checkIndex(index, length)
        return quantities(signal, length, allowUnconfirmed)?.get(index)
    }

    /**
     * The integers the machine stored for [signal], for export, or null if the record does
     * not serve that signal. **These are not physical values**: a stored respiratory rate of
     * 60 means 15 breaths a minute. Use [quantities] for anything that is shown or passed on.
     *
     * @throws IllegalArgumentException if [length] is not possible for this record.
     */
    public fun storedSamples(signal: MinuteSignal, length: Int = sampleCount): IntArray? {
        checkLength(length)
        return series[signal]?.copyOf(length)
    }

    /**
     * When the minute that sample [index] describes ended: the sample's own time stamp.
     *
     * @throws IndexOutOfBoundsException if [index] is not within [length].
     */
    public fun minuteEnd(index: Int, length: Int = sampleCount): MachineTime {
        checkIndex(index, length)
        return firstMinuteEnd + index * intervalMs
    }

    /** When the minute that sample [index] describes began. For the first sample this is mask-on. */
    public fun minuteStart(index: Int, length: Int = sampleCount): MachineTime = minuteEnd(index, length) - intervalMs

    private fun checkIndex(index: Int, length: Int) {
        checkLength(length)
        if (index !in 0 until length) throw IndexOutOfBoundsException("sample $index of $length")
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
    private const val ONE_MINUTE = 1L
    private const val MINUTE_MS = 60_000L

    private val signalByField = MinuteSignal.entries.associateBy { it.field }

    /** The signals of a record contradict each other, which casts doubt on how every record of the download was read. */
    private class LengthDisagreement(message: String) : Exception(message)

    /**
     * @param payload one verified download of the per-minute log.
     * @param applicationIdentifier the `ApplicationIdentifier` of the machine the download came from.
     * @throws SpoolDecodeException if the download as a whole is not this log, or comes from
     *   firmware on which this log's layout has not been confirmed ([ConfirmedFirmware]).
     */
    public fun decode(payload: ByteArray, applicationIdentifier: String): DecodedLog<MinuteRecord> {
        if (!ConfirmedFirmware.covers(applicationIdentifier)) {
            throw SpoolDecodeException("per-minute log: this machine's firmware has not been confirmed, so its data is not decoded")
        }
        val sink = ResultSink<MinuteRecord>()
        var disagreement: String? = null
        unwrap(payload, SpoolEnvelope.MINUTE_LOG, "per-minute log").forEachIndexed { index, body ->
            try {
                sink.attempt { record(body, "per-minute record $index") }
            } catch (e: LengthDisagreement) {
                sink.fail(e.message!!)
                disagreement = disagreement ?: e.message
            }
        }
        // Signals that disagree about a record's length mean a modulus is wrong, and a wrong
        // modulus can pass in a short record. So one such record condemns the whole download.
        disagreement?.let { sink.failAll("per-minute log: not trusted, because $it") }
        return sink.finish()
    }

    private class Series(val start: MachineTime, val block: SampleBlock)

    private fun record(body: ByteArray, what: String): MinuteRecord {
        val found = LinkedHashMap<MinuteSignal, Series>()
        val withheld = LinkedHashSet<MinuteSignal>()
        var interval: Long? = null
        var unknown = 0
        for (field in ProtoReader.read(body)) {
            val signal = signalByField[field.number]
            when {
                signal != null -> {
                    val bytes = field.bytes ?: wrongType(what, field)
                    if (signal in found || signal in withheld) repeated(what, field.number)
                    if (signal.layoutConfirmed) {
                        val (series, extra) = series(signal, bytes, "$what, $signal")
                        unknown += extra
                        found[signal] = series
                    } else {
                        withheld += signal
                    }
                }
                field.number == INTERVAL -> {
                    if (field.wireType != ProtoReader.VARINT) wrongType(what, field)
                    if (interval == null) interval = field.value else repeated(what, INTERVAL)
                }
                else -> unknown++
            }
        }
        if (found.isEmpty()) throw SpoolDecodeException("$what: no signals that can be decoded")
        // One minute, written as 1, is all that has been seen. The format allows other intervals,
        // and a misread interval would misplace every sample after the first.
        if (interval != ONE_MINUTE) throw SpoolDecodeException("$what: unexpected sample interval (${interval ?: "none"})")

        val start = found.values.first().start
        if (found.values.any { it.start != start }) throw SpoolDecodeException("$what: the signals start at different times")

        // Every signal has the same true length, so it is at least the longest
        // certain run and at most the shortest series.
        val atLeast = found.values.maxOf { it.block.certain }
        val atMost = found.values.minOf { it.block.samples.size }
        if (atLeast > atMost) throw LengthDisagreement("$what: the signals disagree about how many samples there are")

        // A series still counts towards the record's length when its values cannot be right:
        // where it ends does not depend on what it holds.
        val misread = found.filter { (signal, series) ->
            (0 until atMost).any { series.block.samples[it] !in 0..signal.maxStored }
        }.keys
        return MinuteRecord(
            firstMinuteEnd = start,
            intervalMs = MINUTE_MS,
            sampleCount = atLeast,
            uncertainTail = atMost - atLeast,
            series = found.filterKeys { it !in misread }.mapValues { it.value.block.samples.copyOf(atMost) },
            withheldSignals = Collections.unmodifiableSet(withheld),
            misreadSignals = Collections.unmodifiableSet(LinkedHashSet(misread)),
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
}
