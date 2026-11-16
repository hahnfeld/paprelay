// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.as11.decode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EventDecoderTest {
    private val t = 1_794_000_000_000L

    private fun Proto.event(code: Long, start: Long, end: Long, duration: Long? = null) {
        varint(1, code)
        varint(2, start)
        varint(3, end)
        if (duration != null) varint(4, duration)
    }

    /** Refused outright, or reported as a record that could not be decoded, with nothing passed off as an event. */
    private fun refused(payload: ByteArray) {
        val log = try {
            EventDecoder.usage(payload)
        } catch (e: SpoolDecodeException) {
            return
        }
        assertTrue(log.failures.isNotEmpty() && log.records.isEmpty(), "decoded to ${log.records.size} events")
    }

    @Test
    fun `events decode in stored order with their own times`() {
        val payload = proto {
            message(4) { event(4, t, t + 14_000, 14_000) }
            message(4) { event(2, t + 60_000, t + 60_000) }
            message(4) { event(8, t + 90_000, t + 90_000) }
        }
        val log = EventDecoder.respiratory(payload)
        assertTrue(log.complete)
        val events = log.records
        assertEquals(listOf(4, 2, 8), events.map { it.code })
        assertEquals(
            listOf(RespiratoryEvent.OBSTRUCTIVE_APNEA, RespiratoryEvent.HYPOPNEA, RespiratoryEvent.CSR_START),
            events.map { it.kind },
        )
        assertEquals(t, events[0].startMs)
        assertEquals(t + 14_000, events[0].endMs)
        assertEquals(14_000, events[0].durationMs)
        // A duration the record does not store is not made up from the times.
        assertNull(events[1].durationMs)
    }

    @Test
    fun `the same number means different things in the two logs`() {
        val usage = EventDecoder.usage(proto { message(6) { event(3, t, t) } }).records.single()
        val breathing = EventDecoder.respiratory(proto { message(4) { event(3, t, t) } }).records.single()
        assertEquals(UsageEvent.MASK_ON, usage.kind)
        assertEquals(RespiratoryEvent.CENTRAL_APNEA, breathing.kind)
        // And a download is only ever read as the log it is.
        assertFailsWith<SpoolDecodeException> { EventDecoder.usage(proto { message(4) { event(3, t, t) } }) }
        assertFailsWith<SpoolDecodeException> { EventDecoder.respiratory(proto { message(6) { event(3, t, t) } }) }
    }

    @Test
    fun `events are found under zero, one or two wrappers`() {
        val direct = proto { message(6) { event(3, t, t) } }
        val once = proto { message(6) { message(1) { event(3, t, t) }; message(1) { event(2, t + 5, t + 5) } } }
        val twice = proto { message(6) { message(2) { message(1) { event(3, t, t) } }; message(2) { message(1) { event(2, t + 5, t + 5) } } } }
        assertEquals(listOf(3), EventDecoder.usage(direct).records.map { it.code })
        assertEquals(listOf(3, 2), EventDecoder.usage(once).records.map { it.code })
        assertEquals(listOf(3, 2), EventDecoder.usage(twice).records.map { it.code })
        // One layer more than has ever been seen is not waved through.
        refused(proto { message(6) { message(2) { message(1) { message(1) { event(3, t, t) } } } } })
    }

    @Test
    fun `an empty log, or an empty wrapper, holds no events`() {
        assertTrue(EventDecoder.usage(ByteArray(0)).records.isEmpty())
        val empty = EventDecoder.usage(proto { message(6) {} })
        assertTrue(empty.records.isEmpty() && empty.complete)
    }

    @Test
    fun `a kind of event this version does not know is kept by number`() {
        val event = EventDecoder.usage(proto { message(6) { event(42, t, t); varint(9, 3); bytes(17, "note".toByteArray()) } }).records.single()
        assertEquals(42, event.code)
        assertNull(event.kind)
        assertEquals(2, event.extraFields)
        // The gaps in the numbering are not filled in.
        assertNull(EventDecoder.usage(proto { message(6) { event(9, t, t) } }).records.single().kind)
        assertNull(EventDecoder.respiratory(proto { message(4) { event(7, t, t) } }).records.single().kind)
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
        assertEquals(RespiratoryEvent.CSR_END, RespiratoryEvent.fromCode(9))
    }

    @Test
    fun `unusual times are kept as stored, not judged`() {
        // A clock corrected between the two stamps, and a machine left switched off for a month.
        val backwards = EventDecoder.usage(proto { message(6) { event(3, t + 1_000, t) } }).records.single()
        assertEquals(t + 1_000, backwards.startMs)
        assertEquals(t, backwards.endMs)
        val month = 31L * 86_400_000
        assertEquals(month, EventDecoder.usage(proto { message(6) { event(4, t, t + month, month) } }).records.single().durationMs)
    }

    @Test
    fun `one record that is not understood does not take the others with it`() {
        val log = EventDecoder.usage(
            proto {
                message(6) { event(3, t, t) }
                message(6) { varint(1, 3); varint(2, t) } // no end time
                message(6) { event(2, t + 9, t + 9) }
            },
        )
        assertEquals(listOf(3, 2), log.records.map { it.code })
        assertEquals(listOf(1), log.failures.map { it.index })
        assertTrue(!log.complete)
    }

    @Test
    fun `anything that is neither an event nor a list of events is refused`() {
        // No end time, no start time, no code.
        refused(proto { message(6) { varint(1, 3); varint(2, t) } })
        refused(proto { message(6) { varint(1, 3); varint(3, t) } })
        refused(proto { message(6) { varint(2, t); varint(3, t) } })
        // A wrapper that carries something other than records.
        refused(proto { message(6) { message(1) { event(3, t, t) }; varint(2, 7) } })
        // Something shaped like an event that carries events inside: reading it as one would drop them.
        refused(proto { message(6) { event(3, t, t); message(5) { event(2, t, t) } } })
        // Times that cannot be right: none, far future, or seconds where milliseconds belong.
        refused(proto { message(6) { event(3, 0, t) } })
        refused(proto { message(6) { event(3, t, Long.MAX_VALUE) } })
        refused(proto { message(6) { event(3, t / 1000, t / 1000) } })
        refused(proto { message(6) { event(3, t, t, duration = -1) } })
        // A value given twice, or of the wrong kind.
        refused(proto { message(6) { event(3, t, t); varint(1, 2) } })
        refused(proto { message(6) { event(3, t, t); varint(3, t) } })
        refused(proto { message(6) { event(3, t, t, 5); varint(4, 5) } })
        refused(proto { message(6) { event(3, t, t); bytes(4, byteArrayOf(1)) } })
        // Cut short, or with a stray field beside the records.
        val whole = proto { message(6) { event(3, t, t) } }
        assertFailsWith<SpoolDecodeException> { EventDecoder.usage(whole.copyOf(whole.size - 1)) }
        assertFailsWith<SpoolDecodeException> { EventDecoder.usage(whole + proto { varint(6, 1) }) }
    }
}
