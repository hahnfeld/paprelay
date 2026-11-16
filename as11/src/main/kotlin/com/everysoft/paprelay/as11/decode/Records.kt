// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.as11.decode

import com.everysoft.paprelay.core.PapUnit
import com.everysoft.paprelay.core.Quantity
import com.everysoft.paprelay.core.Scale

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

/** One record of a log that could not be decoded. */
public class RecordFailure(
    /** Position of the record in the download, counting from zero. */
    public val index: Int,
    public val reason: String,
)

/**
 * What one download of a log decoded to.
 *
 * A record that is not understood is reported in [failures] and leaves the
 * others standing: it stays on the machine, so refusing the whole log would
 * hide every night until the app is fixed. A caller must treat the period a
 * failed record covers as unknown, never as empty.
 */
public class DecodedLog<T> internal constructor(
    public val records: List<T>,
    public val failures: List<RecordFailure>,
) {
    public val complete: Boolean get() = failures.isEmpty()
}

/**
 * Decodes each record of a log on its own. Damage to the envelope itself still
 * fails the whole download: then nothing can be told apart.
 */
internal fun <T> decodeRecords(
    payload: ByteArray,
    envelopeField: Int,
    what: String,
    decode: (body: ByteArray, what: String) -> List<T>,
): DecodedLog<T> {
    val records = ArrayList<T>()
    val failures = ArrayList<RecordFailure>()
    unwrap(payload, envelopeField, what).forEachIndexed { index, body ->
        try {
            records += decode(body, "$what record $index")
        } catch (e: SpoolDecodeException) {
            failures += RecordFailure(index, e.message ?: "not understood")
        }
    }
    return DecodedLog(java.util.Collections.unmodifiableList(records), java.util.Collections.unmodifiableList(failures))
}

/**
 * Splits a downloaded log into its records. Every top-level field must be the
 * log's own envelope field; anything else means this is not the log that was
 * asked for, or a layout this version does not know.
 */
internal fun unwrap(payload: ByteArray, envelopeField: Int, what: String): List<ByteArray> =
    ProtoReader.read(payload).map { field ->
        if (field.number != envelopeField) {
            throw SpoolDecodeException("$what: field ${field.number} where only field $envelopeField belongs")
        }
        field.bytes ?: wrongType(what, field)
    }

internal fun timestamp(value: Long, what: String): Long {
    if (value !in MIN_TIMESTAMP_MS..MAX_TIMESTAMP_MS) throw SpoolDecodeException("$what: a time is out of range")
    return value
}

internal fun count(value: Long, max: Int, what: String, name: String): Int {
    if (value !in 0..max) throw SpoolDecodeException("$what: $name is out of range")
    return value.toInt()
}

internal fun quantity(raw: Long, scale: Scale, unit: PapUnit, what: String): Quantity {
    // A varint above this is a negative number or damage; neither is expected in these records.
    if (raw !in 0..MAX_STORED_VALUE) throw SpoolDecodeException("$what: a stored value is out of range")
    return Quantity(raw, scale, unit)
}

internal const val MAX_STORED_VALUE: Long = 100_000_000L

internal fun missing(what: String, name: String): Nothing = throw SpoolDecodeException("$what: $name is missing")

internal fun repeated(what: String, number: Int): Nothing = throw SpoolDecodeException("$what: field $number appears twice")

internal fun wrongType(what: String, field: ProtoField): Nothing =
    throw SpoolDecodeException("$what: field ${field.number} has unexpected wire type ${field.wireType}")
