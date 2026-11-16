// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.as11.decode

import com.everysoft.paprelay.core.MachineTime
import com.everysoft.paprelay.core.PapUnit
import com.everysoft.paprelay.core.Quantity
import com.everysoft.paprelay.core.Scale
import java.util.Collections

// Shared checks for the record decoders. Each one fails loudly: a record that
// is not understood must never turn into a plausible-looking number.

/** 2100-01-01. A later time cannot come from a working clock. */
internal const val MAX_TIMESTAMP_MS: Long = 4_102_444_800_000L

/**
 * 2015-01-01, years before the machine existed. An earlier time is a clock that
 * was never set, or a value in seconds mistaken for milliseconds; either way it
 * must not be filed under 1970.
 */
internal const val MIN_TIMESTAMP_MS: Long = 1_420_070_400_000L

/** One entry of a downloaded log, in the order the machine stored it. */
public sealed interface RecordResult<out T> {
    /** An entry that was understood. */
    public class Decoded<T> internal constructor(public val value: T) : RecordResult<T>

    /**
     * An entry that was not understood. Its content is unknown: the stretch of time it
     * covers must be treated as unknown too, never as empty. Its neighbours in
     * [DecodedLog.entries] show roughly where it belongs.
     *
     * @property ordinal position among the log's entries, counting from zero.
     */
    public class Failed internal constructor(public val ordinal: Int, public val reason: String) : RecordResult<Nothing>
}

/**
 * What one download of a log decoded to.
 *
 * An entry that is not understood is kept in its place as a [RecordResult.Failed]
 * and leaves the others standing: it stays on the machine, so refusing the whole
 * log would hide every night until the app is fixed. [records] alone is therefore
 * not the whole story; a caller must look at [complete] or [failures] before
 * treating a gap as a gap.
 */
public class DecodedLog<T> internal constructor(entries: List<RecordResult<T>>) {
    public val entries: List<RecordResult<T>> = Collections.unmodifiableList(ArrayList(entries))

    /** The entries that were understood, in order. */
    public val records: List<T> = Collections.unmodifiableList(this.entries.filterIsInstance<RecordResult.Decoded<T>>().map { it.value })

    public val failures: List<RecordResult.Failed> = Collections.unmodifiableList(this.entries.filterIsInstance<RecordResult.Failed>())

    public val complete: Boolean get() = failures.isEmpty()
}

/** Collects results in order, giving each failure its place. */
internal class ResultSink<T> {
    private val entries = ArrayList<RecordResult<T>>()

    fun add(value: T) {
        entries += RecordResult.Decoded(value)
    }

    fun fail(reason: String) {
        entries += RecordResult.Failed(entries.size, reason)
    }

    /** Runs [decode] for one entry; a decoding failure becomes that entry's result. */
    inline fun attempt(decode: () -> T) {
        try {
            add(decode())
        } catch (e: SpoolDecodeException) {
            fail(e.message ?: "not understood")
        }
    }

    fun finish(): DecodedLog<T> = DecodedLog(entries)
}

/**
 * Splits a downloaded log into its records. Every top-level field must be the
 * log's own envelope field; anything else means this is not the log that was
 * asked for, or a layout this version does not know, and then nothing in the
 * download can be told apart.
 */
internal fun unwrap(payload: ByteArray, envelopeField: Int, what: String): List<ByteArray> =
    ProtoReader.read(payload).map { field ->
        if (field.number != envelopeField) {
            throw SpoolDecodeException("$what: field ${field.number} where only field $envelopeField belongs")
        }
        field.bytes ?: wrongType(what, field)
    }

internal fun timestamp(value: Long, what: String): MachineTime {
    if (value !in MIN_TIMESTAMP_MS..MAX_TIMESTAMP_MS) throw SpoolDecodeException("$what: a time is out of range")
    return MachineTime(value)
}

internal fun count(value: Long, max: Int, what: String, name: String): Int {
    if (value !in 0..max) throw SpoolDecodeException("$what: $name is out of range")
    return value.toInt()
}

/** The quantity, or null if the stored value cannot be one: negative (a ten-byte varint) or absurdly large. */
internal fun quantityOrNull(raw: Long, scale: Scale, unit: PapUnit): Quantity? =
    if (raw in 0..MAX_STORED_VALUE) Quantity(raw, scale, unit) else null

internal fun quantity(raw: Long, scale: Scale, unit: PapUnit, what: String): Quantity =
    quantityOrNull(raw, scale, unit) ?: throw SpoolDecodeException("$what: a stored value is out of range")

/** Ten thousand of any unit, in hundredths. No figure the machine reports comes near it. */
internal const val MAX_STORED_VALUE: Long = 1_000_000L

internal fun missing(what: String, name: String): Nothing = throw SpoolDecodeException("$what: $name is missing")

internal fun repeated(what: String, number: Int): Nothing = throw SpoolDecodeException("$what: field $number appears twice")

internal fun wrongType(what: String, field: ProtoField): Nothing =
    throw SpoolDecodeException("$what: field ${field.number} has unexpected wire type ${field.wireType}")
