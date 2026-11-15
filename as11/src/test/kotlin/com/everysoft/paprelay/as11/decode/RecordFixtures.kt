// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.as11.decode

import java.io.ByteArrayOutputStream

/** Writes protobuf by hand, independently of the reader under test. */
internal class Proto {
    private val out = ByteArrayOutputStream()

    private fun raw(value: Long) {
        var v = value
        while (v and 0x7F.inv().toLong() != 0L) {
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        out.write(v.toInt())
    }

    fun varint(field: Int, value: Long): Proto = apply {
        raw((field.toLong() shl 3) or 0)
        raw(value)
    }

    fun zigzag(field: Int, value: Long): Proto = varint(field, (value shl 1) xor (value shr 63))

    fun bytes(field: Int, value: ByteArray): Proto = apply {
        raw((field.toLong() shl 3) or 2)
        raw(value.size.toLong())
        out.write(value)
    }

    fun message(field: Int, build: Proto.() -> Unit): Proto = bytes(field, Proto().apply(build).toByteArray())

    fun fixed32(field: Int, value: Int): Proto = apply {
        raw((field.toLong() shl 3) or 5)
        repeat(4) { out.write(value ushr (8 * it)) }
    }

    fun toByteArray(): ByteArray = out.toByteArray()
}

internal fun proto(build: Proto.() -> Unit): ByteArray = Proto().apply(build).toByteArray()

/**
 * Compresses a series the way the machine does; written separately from the
 * decoder. [padWith] fills the spare bits of the last byte.
 */
internal fun riceBlock(samples: IntArray, modulus: Int, padWith: Int = 1): ByteArray {
    require(samples.size >= 2)
    val bits = ArrayList<Int>()
    val k = Integer.numberOfTrailingZeros(modulus)
    for (n in 2 until samples.size) {
        val d = samples[n] - 2 * samples[n - 1] + samples[n - 2]
        val encoded = if (d >= 0) 2L * d else -2L * d - 1
        repeat((encoded / modulus).toInt()) { bits += 1 }
        bits += 0
        for (i in k - 1 downTo 0) bits += ((encoded % modulus) shr i).toInt() and 1
    }
    while (bits.size % 8 != 0) bits += padWith
    val out = ByteArray(4 + bits.size / 8)
    for (i in 0 until 2) {
        out[i * 2] = samples[i].toByte()
        out[i * 2 + 1] = (samples[i] shr 8).toByte()
    }
    for (i in bits.indices) if (bits[i] == 1) out[4 + i / 8] = (out[4 + i / 8].toInt() or (0x80 ushr (i % 8))).toByte()
    return out
}

internal fun int16Block(samples: IntArray): ByteArray =
    ByteArray(samples.size * 2) { i -> (samples[i / 2] shr (8 * (i % 2))).toByte() }
