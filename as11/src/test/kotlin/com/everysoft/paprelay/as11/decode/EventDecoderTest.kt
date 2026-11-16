// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.as11.decode

import com.everysoft.paprelay.core.MachineTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EventDecoderTest {
    private val t = 1_794_000_000_000L

    private fun Proto.event(code: Long, start: Long, end: Long = start, duration: Long? = null) {
        varint(1, code)
        varint(2, start)
        varint(3, end)
        if (duration != null) varint(4, duration)
    }

    /** The layout a real machine sends: one envelope entry, one wrapper, the events inside it. */
    private fun asCaptured(envelope: Int, events: Proto.() -> Unit): ByteArray = proto { message(envelope) { message(1, events) } }

    @Test
    fun `a usage log as the machine sends it`() {
        val maskOn = t + 5_543
        val log = EventDecoder.usage(
            asCaptured(6) {
                message(1) { event(7, t) }
                message(1) { event(3, maskOn) }
                message(1) { event(8, t + 7_433_383) }
                message(1) { event(2, t + 7_433_384) }
            },
        )
        assertTrue(log.complete)
        assertEquals(
            listOf(UsageEvent.THERAPY_START, UsageEvent.MASK_ON, UsageEvent.THERAPY_STOP, UsageEvent.MASK_OFF),
            log.records.map { it.kind },
        )
        val on = log.records[1]
        assertEquals(MachineTime(maskOn), on.time)
        assertEquals(on.time, on.storedStart)
        assertNull(on.durationMs)
        assertNull(on.onset)
    }

    @Test
    fun `a breathing event is stamped when it ended, and its onset is that less its duration`() {
        val log = EventDecoder.respiratory(
            asCaptured(4) {
                message(1) { event(4, t, duration = 10_000) }
                message(1) { event(3, t + 60_000, duration = 12_000) }
                message(1) { event(2, t + 90_000) }
            },
        )
        val (obstructive, central, hypopnea) = log.records
        assertEquals(RespiratoryEvent.OBSTRUCTIVE_APNEA, obstructive.kind)
        assertEquals(MachineTime(t), obstructive.time)
        assertEquals(10_000, obstructive.durationMs)
        assertEquals(MachineTime(t - 10_000), obstructive.onset)
        assertEquals(RespiratoryEvent.CENTRAL_APNEA, central.kind)
        assertEquals(MachineTime(t + 48_000), central.onset)
        // A hypopnoea carries no duration, and none is invented for it.
        assertEquals(RespiratoryEvent.HYPOPNEA, hypopnea.kind)
        assertNull(hypopnea.durationMs)
        assertNull(hypopnea.onset)
    }

    @Test
    fun `the same number means different things in the two logs`() {
        val usage = EventDecoder.usage(asCaptured(6) { message(1) { event(3, t) } }).records.single()
        val breathing = EventDecoder.respiratory(asCaptured(4) { message(1) { event(3, t) } }).records.single()
        assertEquals(UsageEvent.MASK_ON, usage.kind)
        assertEquals(RespiratoryEvent.CENTRAL_APNEA, breathing.kind)
        // And a download is only ever read as the log it is.
        assertFailsWith<SpoolDecodeException> { EventDecoder.usage(asCaptured(4) { message(1) { event(3, t) } }) }
        assertFailsWith<SpoolDecodeException> { EventDecoder.respiratory(asCaptured(6) { message(1) { event(3, t) } }) }
    }

    @Test
    fun `events are found under zero, one or two wrappers, and no deeper`() {
        val direct = proto { message(6) { event(3, t) } }
        val twice = proto { message(6) { message(2) { message(1) { event(3, t) } }; message(2) { message(1) { event(2, t + 5) } } } }
        assertEquals(listOf(3), EventDecoder.usage(direct).records.map { it.code })
        assertEquals(listOf(3, 2), EventDecoder.usage(twice).records.map { it.code })
        val thrice = EventDecoder.usage(proto { message(6) { message(2) { message(1) { message(1) { event(3, t) } } } } })
        assertTrue(thrice.records.isEmpty())
        assertEquals(1, thrice.failures.size)
    }

    @Test
    fun `an empty log, or an empty wrapper, holds no events`() {
        assertTrue(EventDecoder.usage(ByteArray(0)).entries.isEmpty())
        val empty = EventDecoder.usage(asCaptured(6) {})
        assertTrue(empty.entries.isEmpty() && empty.complete)
    }

    @Test
    fun `a kind of event this version does not know is kept by number`() {
        val event = EventDecoder.usage(asCaptured(6) { message(1) { event(42, t); varint(9, 3); bytes(17, "note".toByteArray()) } }).records.single()
        assertEquals(42, event.code)
        assertNull(event.kind)
        assertEquals(2, event.extraFields)
        // The gaps in the numbering are not filled in.
        assertNull(EventDecoder.usage(asCaptured(6) { message(1) { event(9, t) } }).records.single().kind)
        assertNull(EventDecoder.respiratory(asCaptured(4) { message(1) { event(7, t) } }).records.single().kind)
    }

    @Test
    fun `the code tables match the published numbering, gaps included`() {
        assertEquals(
            "NO_USAGE=1 MASK_OFF=2 MASK_ON=3 POWER_OFF=4 MASK_FIT_START=5 MASK_FIT_STOP=6 THERAPY_START=7 THERAPY_STOP=8 " +
                "LEARN_TARGETS_START=10 LEARN_TARGETS_STOP=11",
            UsageEvent.entries.joinToString(" ") { "${it.name}=${it.code}" },
        )
        assertEquals(
            "NO_EVENT=1 HYPOPNEA=2 CENTRAL_APNEA=3 OBSTRUCTIVE_APNEA=4 APNEA=5 RERA=6 CSR_START=8 CSR_END=9",
            RespiratoryEvent.entries.joinToString(" ") { "${it.name}=${it.code}" },
        )
        assertNull(UsageEvent.fromCode(9))
        assertNull(RespiratoryEvent.fromCode(7))
        assertNull(RespiratoryEvent.fromCode(0))
    }

    @Test
    fun `one event that is not understood is reported in its place and leaves the others standing`() {
        val log = EventDecoder.usage(
            asCaptured(6) {
                message(1) { event(3, t) }
                message(1) { varint(1, 3); varint(2, t) } // no second stamp
                message(1) { event(2, t + 9) }
            },
        )
        assertEquals(listOf(3, 2), log.records.map { it.code })
        assertTrue(!log.complete)
        // The failure sits between its neighbours, so a caller can see where the gap is.
        assertEquals(3, log.entries.size)
        assertIs<RecordResult.Decoded<*>>(log.entries[0])
        val failed = assertIs<RecordResult.Failed>(log.entries[1])
        assertEquals(1, failed.ordinal)
        assertIs<RecordResult.Decoded<*>>(log.entries[2])
    }

    /** Each of these is one event that must be refused, with no event made out of it. */
    private fun refusedEvent(why: String, build: Proto.() -> Unit) {
        val log = EventDecoder.usage(asCaptured(6) { message(1, build) })
        assertTrue(log.records.isEmpty(), "$why: decoded to ${log.records}")
        assertEquals(1, log.failures.size, why)
    }

    @Test
    fun `an event that cannot be right is refused`() {
        refusedEvent("no code") { varint(2, t); varint(3, t) }
        refusedEvent("no first stamp") { varint(1, 3); varint(3, t) }
        refusedEvent("no second stamp") { varint(1, 3); varint(2, t) }
        refusedEvent("code zero") { event(0, t) }
        refusedEvent("no time") { event(3, 0, t) }
        refusedEvent("far future") { event(3, t, Long.MAX_VALUE) }
        refusedEvent("seconds for milliseconds") { event(3, t / 1000) }
        refusedEvent("just before 2015") { event(3, 1_420_070_399_999) }
        refusedEvent("negative duration") { event(3, t, duration = -1) }
        refusedEvent("a duration of days") { event(3, t, duration = 86_400_001) }
        refusedEvent("code twice") { event(3, t); varint(1, 2) }
        refusedEvent("first stamp twice") { event(3, t); varint(2, t) }
        refusedEvent("second stamp twice") { event(3, t); varint(3, t) }
        refusedEvent("duration twice") { event(3, t, duration = 5); varint(4, 5) }
        refusedEvent("duration of the wrong kind") { event(3, t); bytes(4, byteArrayOf(1)) }
        refusedEvent("events inside an event") { event(3, t); message(5) { event(2, t) } }
        refusedEvent("events two levels inside an event") { event(3, t); message(5) { message(1) { event(2, t) } } }
        // The limits themselves are accepted.
        assertEquals(1, EventDecoder.usage(asCaptured(6) { message(1) { event(3, 1_420_070_400_000) } }).records.size)
        assertEquals(1, EventDecoder.usage(asCaptured(6) { message(1) { event(3, t, duration = 86_400_000) } }).records.size)
    }

    @Test
    fun `a download that is not this log at all is refused whole`() {
        val whole = asCaptured(6) { message(1) { event(3, t) } }
        assertFailsWith<SpoolDecodeException> { EventDecoder.usage(whole.copyOf(whole.size - 1)) }
        assertFailsWith<SpoolDecodeException> { EventDecoder.usage(whole + proto { varint(6, 1) }) }
        assertFailsWith<SpoolDecodeException> { EventDecoder.usage(whole + proto { message(5) { event(3, t) } }) }
        // A wrapper that carries something other than records fails as one entry.
        val mixed = EventDecoder.usage(proto { message(6) { message(1) { message(1) { event(3, t) } }; fixed32(2, 7) } })
        assertEquals(1, mixed.failures.size)
    }
}
