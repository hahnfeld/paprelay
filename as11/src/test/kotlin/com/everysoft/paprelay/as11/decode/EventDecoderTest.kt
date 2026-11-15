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

    @Test
    fun `events decode in stored order with their own times`() {
        val payload = proto {
            message(4) { event(4, t, t + 14_000, 14_000) }
            message(4) { event(2, t + 60_000, t + 60_000) }
            message(4) { event(8, t + 90_000, t + 90_000) }
        }
        val events = EventDecoder.decode(payload, SpoolEnvelope.RESPIRATORY_EVENTS)
        assertEquals(listOf(4, 2, 8), events.map { it.code })
        assertEquals(RespiratoryEvent.OBSTRUCTIVE_APNEA, RespiratoryEvent.fromCode(events[0].code))
        assertEquals(t, events[0].startMs)
        assertEquals(t + 14_000, events[0].endMs)
        assertEquals(14_000, events[0].durationMs)
        // A duration the record does not store is not made up from the times.
        assertNull(events[1].durationMs)
        assertEquals(RespiratoryEvent.CSR_START, RespiratoryEvent.fromCode(events[2].code))
    }

    @Test
    fun `events are found under zero, one or two wrappers`() {
        val direct = proto { message(6) { event(3, t, t) } }
        val once = proto { message(6) { message(1) { event(3, t, t) }; message(1) { event(2, t + 5, t + 5) } } }
        val twice = proto { message(6) { message(2) { message(1) { event(3, t, t) } }; message(2) { message(1) { event(2, t + 5, t + 5) } } } }
        assertEquals(listOf(3), EventDecoder.decode(direct, SpoolEnvelope.USAGE_EVENTS).map { it.code })
        assertEquals(listOf(3, 2), EventDecoder.decode(once, SpoolEnvelope.USAGE_EVENTS).map { it.code })
        assertEquals(listOf(3, 2), EventDecoder.decode(twice, SpoolEnvelope.USAGE_EVENTS).map { it.code })
        // One layer more than has ever been seen is not waved through.
        val thrice = proto { message(6) { message(2) { message(1) { message(1) { event(3, t, t) } } } } }
        assertFailsWith<SpoolDecodeException> { EventDecoder.decode(thrice, SpoolEnvelope.USAGE_EVENTS) }
    }

    @Test
    fun `an empty log, or an empty wrapper, holds no events`() {
        assertTrue(EventDecoder.decode(ByteArray(0), SpoolEnvelope.USAGE_EVENTS).isEmpty())
        assertTrue(EventDecoder.decode(proto { message(6) {} }, SpoolEnvelope.USAGE_EVENTS).isEmpty())
    }

    @Test
    fun `a kind of event this version does not know is kept by number`() {
        val event = EventDecoder.decode(proto { message(6) { event(42, t, t); varint(9, 3) } }, SpoolEnvelope.USAGE_EVENTS).single()
        assertEquals(42, event.code)
        assertNull(UsageEvent.fromCode(event.code))
        assertEquals(1, event.extraFields)
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
        assertEquals(UsageEvent.MASK_ON, UsageEvent.fromCode(3))
        assertEquals(RespiratoryEvent.CSR_END, RespiratoryEvent.fromCode(9))
    }

    @Test
    fun `anything that is neither an event nor a list of events is refused`() {
        fun refused(payload: ByteArray, envelope: Int = SpoolEnvelope.USAGE_EVENTS) {
            assertFailsWith<SpoolDecodeException> { EventDecoder.decode(payload, envelope) }
        }
        // The other log's envelope.
        refused(proto { message(4) { event(3, t, t) } })
        refused(proto { message(6) { event(3, t, t) } }, SpoolEnvelope.RESPIRATORY_EVENTS)
        // No end time, no start time, no code.
        refused(proto { message(6) { varint(1, 3); varint(2, t) } })
        refused(proto { message(6) { varint(1, 3); varint(3, t) } })
        refused(proto { message(6) { varint(2, t); varint(3, t) } })
        // A wrapper that carries something other than records.
        refused(proto { message(6) { message(1) { event(3, t, t) }; varint(2, 7) } })
        // Times that cannot be right.
        refused(proto { message(6) { event(3, t + 1, t) } })
        refused(proto { message(6) { event(3, 0, t) } })
        refused(proto { message(6) { event(3, t, Long.MAX_VALUE) } })
        refused(proto { message(6) { event(3, t, t, duration = -1) } })
        // A value given twice.
        refused(proto { message(6) { event(3, t, t); varint(1, 2) } })
        // Cut short.
        val whole = proto { message(6) { event(3, t, t) } }
        refused(whole.copyOf(whole.size - 1))
    }
}
