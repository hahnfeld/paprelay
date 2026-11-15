// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.as11

import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FigTest {
    @Test
    fun `encoding matches independently computed frames`() {
        for (case in Vectors.list("fig")) {
            val frame = Fig.encode(case["channel"]!!.jsonPrimitive.int, case.bytes("payload"))
            assertContentEquals(case.bytes("frame"), frame)
        }
    }

    @Test
    fun `frames survive every way of chunking the stream`() {
        val payloads = listOf(ByteArray(0), "hello".toByteArray(), ByteArray(700) { it.toByte() })
        val stream = payloads.mapIndexed { i, p -> Fig.encode(0x0390 + i, p) }.reduce { a, b -> a + b }
        for (chunk in listOf(1, 2, 3, 7, 16, 17, 20, 244, stream.size)) {
            val deframer = FigDeframer()
            val out = stream.asList().chunked(chunk).flatMap { deframer.push(it.toByteArray()) }
            assertEquals(payloads.size, out.size, "chunk=$chunk")
            out.forEachIndexed { i, frame ->
                assertEquals(0x0390 + i, frame.channel)
                assertContentEquals(payloads[i], frame.payload)
            }
            assertEquals(0, deframer.discardedBytes)
        }
    }

    @Test
    fun `a corrupted payload is dropped and the following frame is still delivered`() {
        val bad = Fig.encode(0x0396, "first".toByteArray()).also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        val good = Fig.encode(0x0396, "second".toByteArray())
        val deframer = FigDeframer()
        val out = deframer.push(bad + good)
        assertEquals(1, out.size)
        assertContentEquals("second".toByteArray(), out[0].payload)
        assertEquals(1, deframer.payloadCrcFailures)
    }

    @Test
    fun `a frame cut short by a lost chunk does not swallow the frames after it`() {
        val truncated = Fig.encode(0x0396, ByteArray(200) { 7 }).copyOf(60)
        val following = (1..3).map { Fig.encode(0x0396, "frame-$it".toByteArray()) }.reduce { a, b -> a + b }
        // Enough later data arrives to satisfy the truncated frame's declared length.
        val out = FigDeframer().push(truncated + following + Fig.encode(0x0396, ByteArray(300)))
        assertTrue(out.map { String(it.payload) }.containsAll(listOf("frame-1", "frame-2", "frame-3")))
    }

    @Test
    fun `frames hidden behind a truncated frame are recovered`() {
        val deframer = FigDeframer()
        val truncated = Fig.encode(0x0396, ByteArray(4000) { 7 }).copyOf(300)
        val following = (1..3).map { Fig.encode(0x0396, "frame-$it".toByteArray()) }.reduce { a, b -> a + b }
        // Nothing can be delivered: the truncated frame still claims 3.7 kB that will never come.
        assertEquals(0, deframer.push(truncated + following).size)
        assertTrue(deframer.hasPartialFrame)

        val recovered = deframer.recoverHiddenFrames()
        assertEquals(listOf("frame-1", "frame-2", "frame-3"), recovered.map { String(it.payload) })
        assertEquals(1, deframer.abandonedFrames)
        assertEquals(0, deframer.recoverHiddenFrames().size)
    }

    @Test
    fun `a frame that is only arriving slowly is never dropped`() {
        val deframer = FigDeframer()
        val frame = Fig.encode(0x0396, ByteArray(900) { it.toByte() })
        assertEquals(0, deframer.push(frame.copyOf(300)).size)
        // Asked to recover while nothing complete lies behind it: it must keep waiting.
        repeat(5) { assertEquals(0, deframer.recoverHiddenFrames().size) }
        assertEquals(0, deframer.abandonedFrames)
        val delivered = deframer.push(frame.copyOfRange(300, frame.size))
        assertEquals(1, delivered.size)
        assertContentEquals(ByteArray(900) { it.toByte() }, delivered[0].payload)
        assertEquals(0, deframer.discardedBytes)
    }

    @Test
    fun `a payload that happens to contain a whole valid frame is not split by recovery`() {
        // The inner frame is complete and CRC-valid, so recovery would deliver it if the
        // outer frame were pending. It is only reachable when the outer frame is incomplete.
        val inner = Fig.encode(0x0396, "inner".toByteArray())
        val outer = Fig.encode(0x0396, inner + ByteArray(40))
        val deframer = FigDeframer()
        val delivered = deframer.push(outer)
        assertEquals(1, delivered.size)
        assertEquals(0, deframer.recoverHiddenFrames().size)
    }

    @Test
    fun `a corrupted header and leading noise are skipped`() {
        val noise = byteArrayOf(1, 2, 0xBE.toByte(), 0xBA.toByte(), 3)
        val badHeader = Fig.encode(0x0396, "x".toByteArray()).also { it[5] = 0x7F }
        val good = Fig.encode(0x0393, "ok".toByteArray())
        val deframer = FigDeframer()
        val out = deframer.push(noise + badHeader + good)
        assertEquals(listOf("ok"), out.map { String(it.payload) })
        assertTrue(deframer.discardedBytes > 0)
    }

    @Test
    fun `an oversized declared length is treated as corruption, not buffered`() {
        val huge = Fig.encode(0x0396, ByteArray(40_000))
        val good = Fig.encode(0x0396, "ok".toByteArray())
        val out = FigDeframer(maxPayload = 32 * 1024).push(huge.copyOf(64) + good)
        assertEquals(listOf("ok"), out.map { String(it.payload) })
    }

    @Test
    fun `random garbage never throws and never yields a frame by accident`() {
        val random = Random(42)
        val deframer = FigDeframer()
        var frames = 0
        repeat(2_000) {
            val junk = ByteArray(random.nextInt(64)).also(random::nextBytes)
            frames += deframer.push(junk).size
        }
        assertEquals(0, frames)
    }
}
