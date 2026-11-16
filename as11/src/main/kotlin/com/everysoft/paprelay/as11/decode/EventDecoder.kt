// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
//
// Record layout from the airbreak-plus protocol notes (MIT), facts only.
package com.everysoft.paprelay.as11.decode

/**
 * One entry of an event log, as stored.
 *
 * @param K the vocabulary of the log it came from. The two logs number their
 *   events independently (3 is "mask on" in one and "central apnoea" in the
 *   other), so an event cannot be read without knowing its log.
 * @property kind what the event is, or null for a [code] this version does not know.
 * @property startMs when it began, by the machine's clock, in UTC milliseconds.
 * @property endMs when it ended, by the machine's clock, in UTC milliseconds.
 *   Stored as found: it can precede [startMs] if the machine's clock was changed in between.
 * @property durationMs the stored duration, or null when the record has none.
 *   It is never derived here from the two times.
 * @property extraFields fields of the record this version does not understand.
 */
public class EventRecord<K : Any> internal constructor(
    public val kind: K?,
    public val code: Int,
    public val startMs: Long,
    public val endMs: Long,
    public val durationMs: Long?,
    public val extraFields: Int,
)

/** Decodes the event logs. */
public object EventDecoder {
    /** How many wrapper layers may sit between the envelope and an event. The depth differs between logs. */
    private const val MAX_WRAPPERS = 2
    private const val MAX_CODE = 1_000_000

    /** @param payload one verified download of `UsageEvents-TherapyStatusEvents`. */
    public fun usage(payload: ByteArray): DecodedLog<EventRecord<UsageEvent>> =
        decode(payload, SpoolEnvelope.USAGE_EVENTS, "usage events", UsageEvent::fromCode)

    /** @param payload one verified download of `TherapyEvents-RespiratoryEvents`. */
    public fun respiratory(payload: ByteArray): DecodedLog<EventRecord<RespiratoryEvent>> =
        decode(payload, SpoolEnvelope.RESPIRATORY_EVENTS, "breathing events", RespiratoryEvent::fromCode)

    /** @throws SpoolDecodeException if the download as a whole is not this log. */
    private fun <K : Any> decode(payload: ByteArray, envelopeField: Int, what: String, kindOf: (Int) -> K?): DecodedLog<EventRecord<K>> =
        decodeRecords(payload, envelopeField, what) { body, where ->
            ArrayList<EventRecord<K>>().also { collect(body, MAX_WRAPPERS, where, kindOf, it) }
        }

    private fun <K : Any> collect(body: ByteArray, wrappersLeft: Int, what: String, kindOf: (Int) -> K?, into: MutableList<EventRecord<K>>) {
        val fields = ProtoReader.read(body)
        if (isEvent(fields)) {
            into += event(fields, what, kindOf)
            return
        }
        // Not an event, so it must be a wrapper: nothing but nested messages.
        for (field in fields) {
            val inner = field.bytes
            if (inner == null || wrappersLeft == 0) throw SpoolDecodeException("$what: neither an event nor a list of events")
            collect(inner, wrappersLeft - 1, what, kindOf, into)
        }
    }

    /** An event is recognised by its code and both times; nothing else in these logs has all three. */
    private fun isEvent(fields: List<ProtoField>): Boolean =
        (1..3).all { number -> fields.any { it.number == number && it.wireType == ProtoReader.VARINT } }

    private fun <K : Any> event(fields: List<ProtoField>, what: String, kindOf: (Int) -> K?): EventRecord<K> {
        var code: Long? = null
        var start: Long? = null
        var end: Long? = null
        var duration: Long? = null
        var extra = 0
        for (field in fields) {
            if (field.number in 1..4 && field.wireType != ProtoReader.VARINT) wrongType(what, field)
            when (field.number) {
                1 -> if (code == null) code = field.value else repeated(what, 1)
                2 -> if (start == null) start = field.value else repeated(what, 2)
                3 -> if (end == null) end = field.value else repeated(what, 3)
                4 -> if (duration == null) duration = field.value else repeated(what, 4)
                else -> {
                    // A record that looks like an event but carries events inside is a wrapper
                    // of a shape not seen before. Reading it as one event would drop the rest.
                    val inner = field.bytes
                    if (inner != null && holdsEvent(inner)) throw SpoolDecodeException("$what: an event that contains events")
                    extra++
                }
            }
        }
        // A negative duration can only be damage. A long one, or an end before the start, can be
        // a machine left off for a month or a clock that was corrected: unusual, but as stored.
        if (duration != null && duration < 0) throw SpoolDecodeException("$what: a duration is out of range")
        val number = count(code!!, MAX_CODE, what, "an event code")
        return EventRecord(kindOf(number), number, timestamp(start!!, what), timestamp(end!!, what), duration, extra)
    }

    private fun holdsEvent(bytes: ByteArray): Boolean = try {
        isEvent(ProtoReader.read(bytes))
    } catch (e: SpoolDecodeException) {
        false // text or some other payload, not a message
    }
}
