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
    private val confirmed = Firmware.reportedByMachine("SW04600.19.8.8.0.dced143ae")

    private fun usage(payload: ByteArray) = EventDecoder.usage(payload, confirmed)

    private fun respiratory(payload: ByteArray) = EventDecoder.respiratory(payload, confirmed)

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
        val log = usage(
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
            log.decoded.map { it.kind },
        )
        val on = log.decoded[1]
        assertEquals(MachineTime(maskOn), on.time)
        assertNull(on.durationMs)
        assertNull(on.onset)
    }

    @Test
    fun `a breathing event is stamped when it ended, and its onset is that less its duration`() {
        val log = respiratory(
            asCaptured(4) {
                message(1) { event(4, t, duration = 10_000) }
                message(1) { event(3, t + 60_000, duration = 12_000) }
                message(1) { event(2, t + 90_000) }
            },
        )
        val (obstructive, central, hypopnea) = log.decoded
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
        val usage = usage(asCaptured(6) { message(1) { event(3, t) } }).decoded.single()
        val breathing = respiratory(asCaptured(4) { message(1) { event(3, t) } }).decoded.single()
        assertEquals(UsageEvent.MASK_ON, usage.kind)
        assertEquals(RespiratoryEvent.CENTRAL_APNEA, breathing.kind)
        // And a download is only ever read as the log it is.
        assertFailsWith<SpoolDecodeException> { usage(asCaptured(4) { message(1) { event(3, t) } }) }
        assertFailsWith<SpoolDecodeException> { respiratory(asCaptured(6) { message(1) { event(3, t) } }) }
    }

    @Test
    fun `only the nesting the machine uses is read as events`() {
        // Events directly in the entry, or a layer deeper than seen, are not waved through.
        val direct = usage(proto { message(6) { event(3, t) } })
        assertTrue(direct.decoded.isEmpty() && !direct.complete)
        val deeper = usage(proto { message(6) { message(1) { message(1) { message(1) { event(3, t) } } } } })
        assertTrue(deeper.decoded.isEmpty())
        // What failed there held records, so more than one may be missing.
        assertTrue(deeper.failures.single().severalRecords)
        // Several lists in one entry, and several entries, are the same nesting.
        // Lists and events sit in field 1; the same shapes under another number are not read as them.
        val elsewhere = usage(proto { message(6) { message(2) { message(1) { event(3, t) } } } })
        assertTrue(elsewhere.decoded.isEmpty() && elsewhere.failures.single().severalRecords)
        val eventElsewhere = usage(proto { message(6) { message(1) { message(1) { event(3, t) }; message(2) { event(2, t + 5) } } } })
        assertEquals(listOf(3), eventElsewhere.decoded.map { it.code })
        assertEquals(1, eventElsewhere.failures.size)
        val lists = usage(proto { message(6) { message(1) { message(1) { event(3, t) } }; message(1) { message(1) { event(2, t + 5) } } } })
        assertEquals(listOf(3, 2), lists.decoded.map { it.code })
        val entries = usage(proto { message(6) { message(1) { message(1) { event(3, t) } } }; message(6) { message(1) { message(1) { event(2, t + 5) } } } })
        assertEquals(listOf(3, 2), entries.decoded.map { it.code })
        assertTrue(entries.complete)
    }

    @Test
    fun `an empty log, or an empty wrapper, holds no events`() {
        assertTrue(usage(ByteArray(0)).entries.isEmpty())
        val empty = usage(asCaptured(6) {})
        assertTrue(empty.entries.isEmpty() && empty.complete)
    }

    @Test
    fun `a kind of event this version does not know is kept by number`() {
        val event = usage(asCaptured(6) { message(1) { event(42, t); varint(9, 3); bytes(17, "note".toByteArray()) } }).decoded.single()
        assertEquals(42, event.code)
        assertNull(event.kind)
        assertEquals(2, event.unknownFields)
        // The gaps in the numbering are not filled in.
        assertNull(usage(asCaptured(6) { message(1) { event(9, t) } }).decoded.single().kind)
        assertNull(respiratory(asCaptured(4) { message(1) { event(7, t) } }).decoded.single().kind)
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
        val log = usage(
            asCaptured(6) {
                message(1) { event(3, t) }
                message(1) { varint(1, 3); varint(2, t) } // no second stamp
                message(1) { event(2, t + 9) }
            },
        )
        assertEquals(listOf(3, 2), log.decoded.map { it.code })
        assertTrue(!log.complete)
        // The failure sits between its neighbours, so a caller can see where the gap is.
        assertEquals(3, log.entries.size)
        assertIs<RecordResult.Decoded<*>>(log.entries[0])
        val failed = assertIs<RecordResult.Failed>(log.entries[1])
        assertEquals(1, failed.ordinal)
        assertIs<RecordResult.Decoded<*>>(log.entries[2])
    }

    /** One event that must be refused for the stated reason, between two that must survive it. */
    private fun refusedEvent(reason: String, several: Boolean = false, build: Proto.() -> Unit) {
        val log = usage(asCaptured(6) { message(1) { event(7, t) }; message(1, build); message(1) { event(8, t + 1) } })
        assertEquals(listOf(7, 8), log.decoded.map { it.code }, reason)
        val failure = log.failures.single()
        assertEquals(1, failure.ordinal, reason)
        assertTrue(reason in failure.reason, "expected \"$reason\" in \"${failure.reason}\"")
        assertEquals(several, failure.severalRecords, reason)
    }

    @Test
    fun `an event that cannot be right is refused, and its neighbours are not`() {
        refusedEvent("code is missing") { varint(2, t); varint(3, t) }
        refusedEvent("first time stamp is missing") { varint(1, 3); varint(3, t) }
        refusedEvent("second time stamp is missing") { varint(1, 3); varint(2, t) }
        refusedEvent("code of zero") { event(0, t) }
        refusedEvent("time is out of range") { event(3, 0, t) }
        refusedEvent("time is out of range") { event(3, t, Long.MAX_VALUE) }
        refusedEvent("time is out of range") { event(3, t / 1000) }
        refusedEvent("time is out of range") { event(3, 1_420_070_399_999) }
        refusedEvent("duration is out of range") { event(3, t, duration = -1) }
        refusedEvent("duration is out of range") { event(3, t, duration = 86_400_001) }
        refusedEvent("field 1 appears twice") { event(3, t); varint(1, 2) }
        refusedEvent("field 2 appears twice") { event(3, t); varint(2, t) }
        refusedEvent("field 3 appears twice") { event(3, t); varint(3, t) }
        refusedEvent("field 4 appears twice") { event(3, t, duration = 5); varint(4, 5) }
        // A record that fails with nested content in it may have held more than one event.
        refusedEvent("unexpected wire type", several = true) { event(3, t); bytes(4, byteArrayOf(1)) }
        refusedEvent("unexpected wire type", several = true) { varint(1, 3); varint(2, t); bytes(3, byteArrayOf(1)) }
        refusedEvent("contains events", several = true) { event(3, t); message(5) { event(2, t) } }
        refusedEvent("contains events", several = true) { event(3, t); message(5) { message(1) { event(2, t) } } }
        refusedEvent("contains events", several = true) { event(3, t); message(5) { message(1) { message(1) { message(1) { message(1) { event(2, t) } } } } } }
        // A list that carries a value of its own beside its events is not read as one event.
        refusedEvent("unexpected wire type", several = true) { varint(1, 3); message(1) { event(2, t) }; message(1) { event(4, t) } }
        refusedEvent("contains events", several = true) { varint(1, 3); varint(2, t); varint(3, t); message(7) { event(2, t) }; message(7) { event(4, t) } }
        // The range of fields that make a record an event: 1 to 4, no more.
        refusedEvent("neither an event") { varint(5, 3) }
        refusedEvent("code is missing") { varint(4, 5_000) }
        // Two stamps that differ have never been seen, and what the difference would mean is not known.
        refusedEvent("two time stamps differ") { event(3, t, t + 5_000, duration = 5_000) }
        refusedEvent("two time stamps differ") { event(3, t + 5_000, t, duration = 5_000) }
        // An empty record where an event belongs is a missing event, not nothing.
        refusedEvent("neither an event") { }
        refusedEvent("neither an event") { varint(9, 1) }
        refusedEvent("neither an event", several = true) { message(1) { event(3, t) } }
        // The limits themselves are accepted.
        assertEquals(1, usage(asCaptured(6) { message(1) { event(3, 1_420_070_400_000) } }).decoded.size)
        assertEquals(1, usage(asCaptured(6) { message(1) { event(3, t, duration = 86_400_000) } }).decoded.size)
    }

    @Test
    fun `an onset is worked out only for the kinds whose stamp is known to be their end`() {
        fun onsetOf(code: Long) = respiratory(asCaptured(4) { message(1) { event(code, t, duration = 10_000) } }).decoded.single().onset
        assertEquals(MachineTime(t - 10_000), onsetOf(3))
        assertEquals(MachineTime(t - 10_000), onsetOf(4))
        // Never seen with a duration, so nothing is assumed about them, nor about kinds not known at all.
        for (code in listOf(2L, 5L, 6L, 8L, 9L, 42L)) assertNull(onsetOf(code), "code $code")
        // And a usage event is a moment, whatever number it carries.
        assertNull(usage(asCaptured(6) { message(1) { event(3, t, duration = 10_000) } }).decoded.single().onset)
    }

    @Test
    fun `a log says whether its layout was confirmed for the machine's software, and what can be acted on`() {
        val payload = asCaptured(6) { message(1) { event(3, t) }; message(1) { event(2, t + 1) }; message(1) { varint(1, 3) }; message(1) { event(7, t + 2) } }
        assertTrue(usage(payload).layoutConfirmed)
        val other = EventDecoder.usage(payload, Firmware.reportedByMachine("SW04600.20.0.0.0.abc"))
        assertTrue(!other.layoutConfirmed)
        // Decoded all the same, for keeping and showing as unconfirmed.
        assertEquals(listOf(3, 2, 7), other.decoded.map { it.code })
        // Only what comes before the first failure can be acted on with nothing earlier missing.
        assertEquals(listOf(3, 2), usage(payload).beforeFirstFailure().map { it.code })
        assertNull(usage(payload).allOrNull())
        assertEquals(2, usage(asCaptured(6) { message(1) { event(3, t) }; message(1) { event(2, t + 1) } }).allOrNull()!!.size)
    }

    @Test
    fun `a stray value among the records costs only itself`() {
        val log = usage(proto { message(6) { message(1) { message(1) { event(3, t) }; fixed32(2, 7); message(1) { event(2, t + 1) } } } })
        assertEquals(listOf(3, 2), log.decoded.map { it.code })
        assertEquals(1, log.failures.size)
    }

    @Test
    fun `a download that is not this log at all is refused whole`() {
        val whole = asCaptured(6) { message(1) { event(3, t) } }
        assertFailsWith<SpoolDecodeException> { usage(whole.copyOf(whole.size - 1)) }
        assertFailsWith<SpoolDecodeException> { usage(whole + proto { varint(6, 1) }) }
        assertFailsWith<SpoolDecodeException> { usage(whole + proto { message(5) { event(3, t) } }) }
    }
}
