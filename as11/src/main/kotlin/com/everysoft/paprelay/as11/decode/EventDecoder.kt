// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
//
// Record layout from the airbreak-plus protocol notes (MIT), facts only.
package com.everysoft.paprelay.as11.decode

/**
 * One entry of an event log, as stored.
 *
 * @property code the machine's number for the kind of event; see [UsageEvent]
 *   and [RespiratoryEvent]. Kept as a number so an unknown kind is not lost.
 * @property durationMs the stored duration, or null when the record has none.
 *   It is never derived here from the two times.
 * @property extraFields fields of the record this version does not understand.
 */
public class EventRecord internal constructor(
    public val code: Int,
    public val startMs: Long,
    public val endMs: Long,
    public val durationMs: Long?,
    public val extraFields: Int,
)

/** Decodes the event logs (`UsageEvents-TherapyStatusEvents`, `TherapyEvents-RespiratoryEvents`). */
public object EventDecoder {
    /** How many wrapper layers may sit between the envelope and an event. The depth differs between logs. */
    private const val MAX_WRAPPERS = 2
    private const val MAX_DURATION_MS = 7L * 24 * 60 * 60 * 1000
    private const val MAX_CODE = 1_000_000

    /**
     * @param payload one verified download of an event log.
     * @param envelopeField the log's field in the delivery envelope: [SpoolEnvelope.USAGE_EVENTS]
     *   or [SpoolEnvelope.RESPIRATORY_EVENTS].
     * @throws SpoolDecodeException if anything in the payload is neither an event nor a wrapper around events.
     */
    public fun decode(payload: ByteArray, envelopeField: Int): List<EventRecord> {
        val events = ArrayList<EventRecord>()
        unwrap(payload, envelopeField, "event log").forEach { collect(it, MAX_WRAPPERS, events) }
        return events
    }

    private fun collect(body: ByteArray, wrappersLeft: Int, into: MutableList<EventRecord>) {
        val fields = ProtoReader.read(body)
        event(fields)?.let {
            into += it
            return
        }
        // Not an event, so it must be a wrapper: nothing but nested messages.
        for (field in fields) {
            val inner = field.bytes
            if (inner == null || wrappersLeft == 0) {
                throw SpoolDecodeException("event log: record ${into.size} is neither an event nor a list of events")
            }
            collect(inner, wrappersLeft - 1, into)
        }
    }

    /** The event these fields describe, or null if they are not an event record. */
    private fun event(fields: List<ProtoField>): EventRecord? {
        var code: Long? = null
        var start: Long? = null
        var end: Long? = null
        var duration: Long? = null
        var extra = 0
        val what = "event log"
        for (field in fields) {
            if (field.wireType != ProtoReader.VARINT || field.number !in 1..4) {
                extra++
                continue
            }
            when (field.number) {
                1 -> if (code == null) code = field.value else repeated(what, 1)
                2 -> if (start == null) start = field.value else repeated(what, 2)
                3 -> if (end == null) end = field.value else repeated(what, 3)
                else -> if (duration == null) duration = field.value else repeated(what, 4)
            }
        }
        if (code == null || start == null || end == null) return null
        val startMs = timestamp(start, what)
        val endMs = timestamp(end, what)
        if (endMs < startMs) throw SpoolDecodeException("$what: an event ends before it starts")
        if (duration != null && duration !in 0..MAX_DURATION_MS) throw SpoolDecodeException("$what: a duration is out of range")
        return EventRecord(count(code, MAX_CODE, what, "an event code"), startMs, endMs, duration, extra)
    }
}
