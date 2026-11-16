// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.as11.decode

/** Stored data from the machine could not be decoded. The raw bytes are kept, so it can be retried after a fix. */
public class SpoolDecodeException(message: String) : Exception(message)

/** One field of a protobuf message, as found on the wire. */
internal class ProtoField(
    val number: Int,
    val wireType: Int,
    /** The value for varint and fixed-width fields; zero for length-delimited ones. */
    val value: Long,
    /** The content of a length-delimited field; null otherwise. */
    val bytes: ByteArray?,
) {
    /** The value read as a signed, ZigZag-encoded integer. */
    val signed: Long get() = (value ushr 1) xor -(value and 1)
}

/**
 * Reads protobuf messages without a schema.
 *
 * The machine's record layouts are known only from observation, so nothing is
 * generated from a schema: each decoder picks out the field numbers it
 * understands and leaves the rest alone. Unknown fields are therefore not an
 * error, and a firmware that adds fields does not break decoding.
 *
 * Every length is checked against what is actually there before it is used.
 */
internal object ProtoReader {
    const val VARINT: Int = 0
    const val FIXED64: Int = 1
    const val BYTES: Int = 2
    const val FIXED32: Int = 5
    private const val MAX_FIELD_NUMBER = (1L shl 29) - 1

    /** A month of per-minute records is a few tens of thousands of fields. */
    const val DEFAULT_MAX_FIELDS: Int = 250_000

    /** @throws SpoolDecodeException if [data] is not a well-formed message. */
    fun read(data: ByteArray, maxFields: Int = DEFAULT_MAX_FIELDS): List<ProtoField> {
        val fields = ArrayList<ProtoField>()
        var at = 0
        while (at < data.size) {
            if (fields.size >= maxFields) throw SpoolDecodeException("message has too many fields")
            val key = varint(data, at)
            at = key.next
            val number = (key.value ushr 3).toInt()
            // Field numbers are 1 to 2^29 - 1 in the wire format.
            if (key.value ushr 3 !in 1..MAX_FIELD_NUMBER) throw SpoolDecodeException("invalid field number")
            when (val wireType = (key.value and 7).toInt()) {
                VARINT -> {
                    val v = varint(data, at)
                    at = v.next
                    fields += ProtoField(number, wireType, v.value, null)
                }
                FIXED64 -> {
                    need(data, at, 8)
                    fields += ProtoField(number, wireType, little(data, at, 8), null)
                    at += 8
                }
                BYTES -> {
                    val length = varint(data, at)
                    at = length.next
                    if (length.value < 0 || length.value > data.size - at) throw SpoolDecodeException("field runs past the end of the message")
                    fields += ProtoField(number, wireType, 0, data.copyOfRange(at, at + length.value.toInt()))
                    at += length.value.toInt()
                }
                FIXED32 -> {
                    need(data, at, 4)
                    fields += ProtoField(number, wireType, little(data, at, 4), null)
                    at += 4
                }
                else -> throw SpoolDecodeException("unsupported wire type $wireType")
            }
        }
        return fields
    }

    private class Varint(val value: Long, val next: Int)

    private fun varint(data: ByteArray, from: Int): Varint {
        var value = 0L
        var shift = 0
        var at = from
        while (true) {
            if (at >= data.size) throw SpoolDecodeException("number runs past the end of the message")
            if (shift > 63) throw SpoolDecodeException("number is too long")
            val b = data[at++].toInt()
            // The tenth byte carries the last bit of a 64-bit number; anything more does not fit.
            if (shift == 63 && (b and 0x7E) != 0) throw SpoolDecodeException("number is too large")
            value = value or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return Varint(value, at)
            shift += 7
        }
    }

    private fun need(data: ByteArray, at: Int, count: Int) {
        if (count > data.size - at) throw SpoolDecodeException("field runs past the end of the message")
    }

    private fun little(data: ByteArray, at: Int, count: Int): Long {
        var v = 0L
        for (i in 0 until count) v = v or ((data[at + i].toLong() and 0xFF) shl (8 * i))
        return v
    }
}
