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
    if (value !in 1..MAX_TIMESTAMP_MS) throw SpoolDecodeException("$what: a time is out of range")
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
