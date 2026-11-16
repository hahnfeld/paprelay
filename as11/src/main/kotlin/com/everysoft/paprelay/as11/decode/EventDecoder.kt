// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
//
// Record layout from the airbreak-plus protocol notes (MIT), facts only.
package com.everysoft.paprelay.as11.decode

import com.everysoft.paprelay.core.MachineTime

/**
 * One entry of an event log, as stored.
 *
 * What is known, from a capture compared with the same machine's SD card
 * (AirSense 11 AutoSet, firmware 19.8.8.0):
 *
 *  - The record holds two time stamps, and in every event seen they are the
 *    same instant.
 *  - For a breathing event that instant is when the event **ended**: the
 *    machine reports an apnoea once it is over. The SD card carries the same
 *    instant.
 *  - Apnoeas carry their length in [durationMs]; hypopnoeas carry none.
 *  - Usage events (mask on, therapy stop and so on) are moments, with no duration.
 *
 * @param K the vocabulary of the log it came from. The two logs number their
 *   events independently (3 is "mask on" in one and "central apnoea" in the
 *   other), so an event cannot be read without knowing its log.
 * @property kind what the event is, or null for a [code] this version does not know.
 * @property time the event's time stamp: for a breathing event, when it ended.
 * @property storedStart the record's other stamp, kept as stored. Equal to [time]
 *   in everything seen; what it would mean if it differed is not known, so
 *   nothing here is computed from it.
 * @property durationMs the stored duration, or null when the record has none.
 * @property extraFields fields of the record this version does not understand.
 */
public class EventRecord<K : Any> internal constructor(
    public val kind: K?,
    public val code: Int,
    public val time: MachineTime,
    public val storedStart: MachineTime,
    public val durationMs: Long?,
    public val extraFields: Int,
) {
    /** When the event began: [time] less its duration. Null for an event with no stored duration. */
    public val onset: MachineTime? get() = durationMs?.let { time - it }

    override fun toString(): String = "EventRecord(${kind ?: code} at $time${durationMs?.let { " lasting $it ms" } ?: ""})"
}

/** Decodes the event logs. */
public object EventDecoder {
    /** How many wrapper layers may sit between an envelope entry and an event. One has been seen. */
    private const val MAX_WRAPPERS = 2
    private const val MAX_CODE = 1_000_000
    private const val MAX_DURATION_MS = 24L * 60 * 60 * 1000

    /**
     * @param payload one verified download of `UsageEvents-TherapyStatusEvents`.
     * @throws SpoolDecodeException if the download as a whole is not this log.
     */
    public fun usage(payload: ByteArray): DecodedLog<EventRecord<UsageEvent>> =
        decode(payload, SpoolEnvelope.USAGE_EVENTS, "usage events", UsageEvent::fromCode)

    /**
     * @param payload one verified download of `TherapyEvents-RespiratoryEvents`.
     * @throws SpoolDecodeException if the download as a whole is not this log.
     */
    public fun respiratory(payload: ByteArray): DecodedLog<EventRecord<RespiratoryEvent>> =
        decode(payload, SpoolEnvelope.RESPIRATORY_EVENTS, "breathing events", RespiratoryEvent::fromCode)

    private fun <K : Any> decode(payload: ByteArray, envelopeField: Int, what: String, kindOf: (Int) -> K?): DecodedLog<EventRecord<K>> {
        val sink = ResultSink<EventRecord<K>>()
        for (body in unwrap(payload, envelopeField, what)) {
            // The machine puts every event of a download inside one entry, so a wrapper that
            // cannot be walked is one failure, while an event that cannot be read is its own.
            try {
                collect(body, MAX_WRAPPERS, what, kindOf, sink)
            } catch (e: SpoolDecodeException) {
                sink.fail(e.message ?: "not understood")
            }
        }
        return sink.finish()
    }

    private fun <K : Any> collect(body: ByteArray, wrappersLeft: Int, what: String, kindOf: (Int) -> K?, into: ResultSink<EventRecord<K>>) {
        val fields = ProtoReader.read(body)
        if (isEvent(fields)) {
            into.attempt { event(fields, what, kindOf) }
            return
        }
        // Not an event, so it must be a wrapper: nothing but nested messages.
        for (field in fields) {
            val inner = field.bytes
            if (inner == null || wrappersLeft == 0) throw SpoolDecodeException("$what: neither an event nor a list of events")
            collect(inner, wrappersLeft - 1, what, kindOf, into)
        }
    }

    /**
     * An event is recognised by having any of its own whole-number fields. A wrapper has
     * none: it holds only nested messages. An event with a field missing is then still seen
     * as an event, and fails as one, instead of being mistaken for an empty wrapper.
     */
    private fun isEvent(fields: List<ProtoField>): Boolean = fields.any { it.wireType == ProtoReader.VARINT && it.number in 1..4 }

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
                    if (inner != null && holdsEvent(inner, MAX_WRAPPERS)) throw SpoolDecodeException("$what: an event that contains events")
                    extra++
                }
            }
        }
        if (duration != null && duration !in 0..MAX_DURATION_MS) throw SpoolDecodeException("$what: a duration is out of range")
        val number = count(code ?: missing(what, "an event's code"), MAX_CODE, what, "an event code")
        if (number == 0) throw SpoolDecodeException("$what: an event code of zero")
        return EventRecord(
            kind = kindOf(number),
            code = number,
            time = timestamp(end ?: missing(what, "an event's time"), what),
            storedStart = timestamp(start ?: missing(what, "an event's first time stamp"), what),
            durationMs = duration,
            extraFields = extra,
        )
    }

    private fun holdsEvent(bytes: ByteArray, depth: Int): Boolean {
        val fields = try {
            ProtoReader.read(bytes)
        } catch (e: SpoolDecodeException) {
            return false // text or some other payload, not a message
        }
        if (fields.isEmpty()) return false
        if ((1..3).all { number -> fields.any { it.number == number && it.wireType == ProtoReader.VARINT } }) return true
        return depth > 0 && fields.any { field -> field.bytes?.let { holdsEvent(it, depth - 1) } == true }
    }
}
