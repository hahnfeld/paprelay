// SPDX-License-Identifier: GPL-3.0-or-later
//
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
// Based on SomnoTrace, originally created by Ilya Kruchinin (https://github.com/ilyakruchinin).
//
// Derived from SomnoTrace main/as11_ble.c (fig_encode, fig_take_packet),
// Copyright (C) 2026 Ilya Kruchinin, GPL-3.0-or-later with the Section 7(b)
// attribution term reproduced in NOTICE.
// Ported to Kotlin and modified 2026-11-15: payload CRC is verified on receive,
// declared lengths are bounded, and resynchronisation advances one byte at a time.
package com.everysoft.paprelay.as11

import java.util.zip.CRC32

/** One framed message on the machine's serial-over-GATT stream. */
public class FigFrame(public val channel: Int, public val payload: ByteArray)

/**
 * "FIG" framing used on the machine's GATT stream.
 *
 * Layout (all little-endian): sync `BE BA FE CA`, channel u16, payload length u16,
 * payload CRC-32, header CRC-32 (over the preceding 8 header bytes), payload.
 */
public object Fig {
    /** Plaintext requests: pairing and session setup only. */
    public const val CHANNEL_TX_PLAIN: Int = 0x0393

    /** Encrypted requests. */
    public const val CHANNEL_TX_ENCRYPTED: Int = 0x0397

    /** Encrypted responses and notifications. */
    public const val CHANNEL_RX_ENCRYPTED: Int = 0x0396

    public const val OVERHEAD: Int = 16
    public const val MAX_PAYLOAD: Int = 0xFFFF

    internal val SYNC = byteArrayOf(0xBE.toByte(), 0xBA.toByte(), 0xFE.toByte(), 0xCA.toByte())

    public fun encode(channel: Int, payload: ByteArray): ByteArray {
        require(channel in 0..0xFFFF) { "channel out of range" }
        require(payload.size <= MAX_PAYLOAD) { "payload too large: ${payload.size}" }
        val out = ByteArray(OVERHEAD + payload.size)
        SYNC.copyInto(out, 0)
        putU16(out, 4, channel)
        putU16(out, 6, payload.size)
        putU32(out, 8, crc32(payload, 0, payload.size))
        putU32(out, 12, crc32(out, 4, 8))
        payload.copyInto(out, OVERHEAD)
        return out
    }

    internal fun crc32(data: ByteArray, offset: Int, length: Int): Long {
        val crc = CRC32()
        crc.update(data, offset, length)
        return crc.value
    }

    internal fun putU16(out: ByteArray, at: Int, value: Int) {
        out[at] = (value and 0xFF).toByte()
        out[at + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    internal fun putU32(out: ByteArray, at: Int, value: Long) {
        for (i in 0 until 4) out[at + i] = ((value ushr (8 * i)) and 0xFF).toByte()
    }

    internal fun getU16(data: ByteArray, at: Int): Int =
        (data[at].toInt() and 0xFF) or ((data[at + 1].toInt() and 0xFF) shl 8)

    internal fun getU32(data: ByteArray, at: Int): Long {
        var v = 0L
        for (i in 0 until 4) v = v or ((data[at + i].toLong() and 0xFF) shl (8 * i))
        return v
    }
}

/**
 * Reassembles [FigFrame]s from an arbitrarily chunked byte stream.
 *
 * A frame is delivered only when both its header CRC and its payload CRC match.
 * Corrupt input never throws; it is skipped and counted so the caller can decide
 * whether the link is still trustworthy.
 *
 * Not thread-safe: callers serialise [push], [abandonPartialFrame] and [reset].
 * The counters may be read from any thread.
 *
 * @param maxPayload largest payload length accepted from a header; larger values
 *   are treated as corruption. The machine's messages are a few kilobytes at most.
 */
public class FigDeframer(private val maxPayload: Int = DEFAULT_MAX_PAYLOAD) {
    private var buffer = ByteArray(4096)
    private var length = 0

    /** Bytes thrown away while hunting for a valid frame. */
    @Volatile
    public var discardedBytes: Long = 0
        private set

    /** Frames with a valid header whose payload failed its CRC. */
    @Volatile
    public var payloadCrcFailures: Long = 0
        private set

    /** Frames given up on because the rest of them never arrived. */
    @Volatile
    public var abandonedFrames: Long = 0
        private set

    /** True while bytes of an unfinished frame are being held. */
    public val hasPartialFrame: Boolean get() = length > 0

    init {
        require(maxPayload in 1..Fig.MAX_PAYLOAD) { "maxPayload out of range" }
    }

    /** Feeds received bytes and returns every frame completed by them, in order. */
    public fun push(bytes: ByteArray): List<FigFrame> {
        append(bytes)
        return scan(0)
    }

    /**
     * Gives up on the frame currently being waited for and rescans what is held.
     *
     * A frame whose middle was lost declares a length that the stream will only
     * satisfy with bytes belonging to later frames, so those frames would stay
     * hidden behind it. Call this when the link has been quiet for long enough
     * that the rest of the frame is clearly not coming.
     */
    public fun abandonPartialFrame(): List<FigFrame> {
        if (length == 0) return emptyList()
        abandonedFrames += 1
        discardedBytes += 1
        return scan(1)
    }

    private fun scan(from: Int): List<FigFrame> {
        var frames: MutableList<FigFrame>? = null
        var pos = from
        while (true) {
            val sync = indexOfSync(pos)
            if (sync < 0) {
                // Keep a possible partial sync word at the tail.
                val keepFrom = maxOf(pos, length - (Fig.SYNC.size - 1))
                discardedBytes += (keepFrom - pos).toLong()
                pos = keepFrom
                break
            }
            discardedBytes += (sync - pos).toLong()
            pos = sync
            if (length - pos < Fig.OVERHEAD) break

            val headerCrc = Fig.getU32(buffer, pos + 12)
            val payloadLength = Fig.getU16(buffer, pos + 6)
            if (Fig.crc32(buffer, pos + 4, 8) != headerCrc || payloadLength > maxPayload) {
                // Not a real frame start: skip this sync byte and keep hunting.
                pos += 1
                discardedBytes += 1
                continue
            }
            val total = Fig.OVERHEAD + payloadLength
            if (length - pos < total) break

            if (Fig.crc32(buffer, pos + Fig.OVERHEAD, payloadLength) != Fig.getU32(buffer, pos + 8)) {
                // Usually a lost chunk: the bytes after this header belong to later
                // frames. Skip only the sync byte so those frames are still found.
                payloadCrcFailures += 1
                pos += 1
                discardedBytes += 1
                continue
            }
            val payload = buffer.copyOfRange(pos + Fig.OVERHEAD, pos + total)
            if (frames == null) frames = ArrayList(2)
            frames.add(FigFrame(Fig.getU16(buffer, pos + 4), payload))
            pos += total
        }
        compact(pos)
        return frames ?: emptyList()
    }

    /** Drops any partial frame, for use when the link is re-established. */
    public fun reset() {
        length = 0
    }

    private fun append(bytes: ByteArray) {
        val needed = length + bytes.size
        if (needed > buffer.size) {
            buffer = buffer.copyOf(maxOf(needed, buffer.size * 2))
        }
        bytes.copyInto(buffer, length)
        length = needed
    }

    private fun compact(consumed: Int) {
        if (consumed == 0) return
        buffer.copyInto(buffer, 0, consumed, length)
        length -= consumed
    }

    private fun indexOfSync(from: Int): Int {
        var i = from
        val last = length - Fig.SYNC.size
        while (i <= last) {
            if (buffer[i] == Fig.SYNC[0] && buffer[i + 1] == Fig.SYNC[1] &&
                buffer[i + 2] == Fig.SYNC[2] && buffer[i + 3] == Fig.SYNC[3]
            ) {
                return i
            }
            i++
        }
        return -1
    }

    public companion object {
        public const val DEFAULT_MAX_PAYLOAD: Int = 32 * 1024
    }
}
