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
     * Signals that were decoded but hold, among the samples that certainly exist, a value
     * that cannot be a measurement: below zero, or above anything the machine can report.
     * They are not served. What such a value means is not known.
     */
    public val misreadSignals: Set<MinuteSignal>,
    /** Fields of the record this version does not understand. */
    public val unknownFields: Int,
) {
    /** The signals whose values can be read. */
    public val signals: Set<MinuteSignal> = Collections.unmodifiableSet(LinkedHashSet(series.keys))

    /** The time stamp of the last sample that certainly exists. */
    public val lastMinuteEnd: MachineTime get() = firstMinuteEnd + (sampleCount - 1).coerceAtLeast(0) * intervalMs

    /**
     * What the record says about [signal]. The four answers call for different handling, so
     * they are not folded into one "nothing":
     *
     *  - [SignalReadings.Values]: the physical values, one per minute.
     *  - [SignalReadings.NotRecorded]: the machine stored no such signal in this record.
     *    Nothing is missing.
     *  - [SignalReadings.Withheld] and [SignalReadings.Misread]: the signal is there and
     *    could not be trusted. Its minutes are **unknown**, and whoever is working through
     *    the log in order must not move its place past this record on the strength of it.
     *
     * @param length how many samples to return: [sampleCount] unless the caller has settled
     *   the record's true length, which lies between [sampleCount] and `sampleCount + uncertainTail`.
     * @param allowUnconfirmed must be true to read a signal whose factor has not been
     *   confirmed against the SD card ([MinuteSignal.factorConfirmed]); such values may be wrong.
     * @throws IllegalArgumentException if [length] is not possible for this record, or reaches
     *   into a tail whose values cannot be measurements (which shows the tail to be padding).
     * @throws IllegalStateException if the signal's factor is unconfirmed and [allowUnconfirmed] is false.
     */
    public fun readings(signal: MinuteSignal, length: Int = sampleCount, allowUnconfirmed: Boolean = false): SignalReadings {
        checkLength(length)
        if (signal in withheldSignals) return SignalReadings.Withheld
        if (signal in misreadSignals) return SignalReadings.Misread
        val values = series[signal] ?: return SignalReadings.NotRecorded
        check(signal.factorConfirmed || allowUnconfirmed) { "the factor for $signal has not been confirmed" }
        require((sampleCount until length).all { values[it] in 0..signal.maxStored }) { "that length takes in values that cannot be measurements" }
        return SignalReadings.Values(
            List(length) { index ->
                val stored = values[index]
                if (stored == 0 && signal.zeroIsUnknown) null else signal.scale.of(stored.toLong(), signal.unit)
            },
        )
    }

    /**
     * The integers the machine stored for [signal], for export, or null if the record does
     * not serve that signal. **These are not physical values**: a stored respiratory rate of
     * 60 means 15 breaths a minute. Use [readings] for anything that is shown or passed on.
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
     * @throws IllegalArgumentException if [length] is not possible for this record.
     * @throws IndexOutOfBoundsException if [index] is not within [length].
     */
    public fun minuteEnd(index: Int, length: Int = sampleCount): MachineTime {
        checkLength(length)
        if (index !in 0 until length) throw IndexOutOfBoundsException("no such sample")
        return firstMinuteEnd + index * intervalMs
    }

    /**
     * When the minute that sample [index] describes began. For the first sample this is mask-on.
     *
     * @throws IllegalArgumentException if [length] is not possible for this record.
     * @throws IndexOutOfBoundsException if [index] is not within [length].
     */
    public fun minuteStart(index: Int, length: Int = sampleCount): MachineTime = minuteEnd(index, length) - intervalMs

    private fun checkLength(length: Int) {
        // No numbers in the message: how long someone wore a mask is not for a log.
        require(length in sampleCount..sampleCount + uncertainTail) { "that length is not possible for this record" }
    }
}

/** What a [MinuteRecord] says about one signal. See [MinuteRecord.readings]. */
public sealed interface SignalReadings {
    /**
     * The values, one per minute. Null where the machine stored a zero for a signal in which
     * zero is not a measurement ([MinuteSignal.zeroIsUnknown]): that minute has no known value.
     */
    public class Values internal constructor(values: List<Quantity?>) : SignalReadings {
        public val values: List<Quantity?> = Collections.unmodifiableList(values)
    }

    /** The record holds no such signal. */
    public data object NotRecorded : SignalReadings

    /** The signal is there but has not been confirmed on a real machine, so it was not decoded. */
    public data object Withheld : SignalReadings

    /** The signal was decoded and holds values that cannot be measurements. */
    public data object Misread : SignalReadings
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

    /**
     * A record's series could not be decoded, or contradict each other. Either is what a wrong
     * modulus looks like, and that casts doubt on the short records of the same download.
     */
    private class LengthDisagreement(message: String) : Exception(message)

    /**
     * @param payload one verified download of the per-minute log.
     * @param firmware the software of the machine the download came from.
     * @throws SpoolDecodeException if the download as a whole is not this log, or comes from
     *   software on which this log's layout has not been confirmed ([Firmware.layoutsConfirmed]).
     */
    public fun decode(payload: ByteArray, firmware: Firmware): DecodedLog<MinuteRecord> {
        if (!firmware.layoutsConfirmed) {
            throw SpoolDecodeException("per-minute log: this machine's software has not been confirmed, so its data is not decoded")
        }
        val sink = ResultSink<MinuteRecord>()
        var doubted = false
        var previous: MinuteRecord? = null
        unwrap(payload, SpoolEnvelope.MINUTE_LOG, "per-minute log").forEachIndexed { index, body ->
            val what = "per-minute record $index"
            try {
                sink.attempt {
                    val decoded = record(body, what)
                    // The machine stores sessions in time order and they cannot overlap. One that
                    // does not start after the one before it would collide with it minute for minute.
                    val before = previous
                    if (before != null && decoded.firstMinuteEnd <= before.lastMinuteEnd) {
                        throw SpoolDecodeException("$what: not after the record before it")
                    }
                    previous = decoded
                    decoded
                }
            } catch (e: LengthDisagreement) {
                sink.fail(e.message!!)
                doubted = true
            }
        }
        // A series that will not decode, or signals that disagree about a record's length, may mean
        // a modulus is wrong, and a wrong modulus can pass in a short record. It may equally be
        // one record cut short by a power failure. So such a record is skipped, and the doubt
        // is passed on with the result: a long record that decoded cleanly beside it stands on
        // its own evidence, and what to make of the short ones is for the caller. (A signal that
        // decodes but holds an impossible value is different again: on confirmed software that
        // is a marker this version does not know, and it costs only that signal.)
        return sink.finish(layoutConfirmed = true, doubted = doubted)
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
        if (interval != ONE_MINUTE) throw SpoolDecodeException("$what: unexpected sample interval")

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
            // Only among the samples that certainly exist: a doubtful tail is padding run on,
            // and may wander anywhere without saying anything about the real ones.
            (0 until atLeast).any { series.block.samples[it] !in 0..signal.maxStored }
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
        if (status != EXPECTED_STATUS) throw SpoolDecodeException("$what: unknown kind of series")
        val bytes = block ?: missing(what, "the sample block")
        val modulus = signal.modulus
        val decoded = if (modulus != null) {
            try {
                SampleSeries.decode(bytes, modulus)
            } catch (e: SpoolDecodeException) {
                throw LengthDisagreement("$what: ${e.message}")
            }
        } else {
            if (bytes.size % 2 != 0) throw SpoolDecodeException("$what: an uncompressed series has an odd length")
            val raw = SampleSeries.decodeRaw(bytes)
            if (raw.size > SampleSeries.MAX_SAMPLES) throw SpoolDecodeException("$what: too many samples")
            SampleBlock(raw, raw.size, unreadBits = 0)
        }
        return Series(timestamp(start ?: missing(what, "the start time"), what), decoded) to unknown
    }
}
