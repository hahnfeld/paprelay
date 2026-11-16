// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
//
// Record layout from the airbreak-plus protocol notes (MIT), facts only.
package com.everysoft.paprelay.as11.decode

import com.everysoft.paprelay.core.MachineTime

/**
 * One entry of an event log, as stored.
 *
 * What is known, from one capture compared with the same machine's SD card
 * (AirSense 11 AutoSet, firmware 19.8.8.0; eleven breathing events and twelve
 * usage events):
 *
 *  - The record holds two time stamps, and in every event seen they were the
 *    same instant. A record in which they differ is refused, because what the
 *    difference would mean is not known.
 *  - For an obstructive or central apnoea (eight and one were seen) that
 *    instant is when it **ended**: the machine reports an apnoea once it is
 *    over, with its length in [durationMs]. The SD card carries the same
 *    instant and the same length, and its own format documents that instant
 *    as the end.
 *  - A hypopnoea carries no duration; its stamp matched the SD card's, and
 *    whether that is its start or its end cannot be told.
 *  - Usage events (mask on, therapy stop and so on) are moments.
 *
 * @param K the vocabulary of the log it came from. The two logs number their
 *   events independently (3 is "mask on" in one and "central apnoea" in the
 *   other), so an event cannot be read without knowing its log.
 * @property kind what the event is, or null for a [code] this version does not know.
 * @property time the event's time stamp, as described above.
 * @property durationMs the stored duration, or null when the record has none.
 * @property onset when the event began, for the kinds where that is known:
 *   an obstructive or central apnoea with a stored duration. Null otherwise.
 * @property unknownFields fields of the record this version does not understand.
 */
public class EventRecord<K : Any> internal constructor(
    public val kind: K?,
    public val code: Int,
    public val time: MachineTime,
    public val durationMs: Long?,
    public val onset: MachineTime?,
    public val unknownFields: Int,
)

/** Decodes the event logs. */
public object EventDecoder {
    /** How deep to look for events hidden inside an event. Beyond this, it is taken to hide some. */
    private const val MAX_NESTING = 16
    private const val MAX_CODE = 1_000_000
    /** Generous: it guards against a mis-scaled value, and says nothing about how long an event can last. */
    private const val MAX_DURATION_MS = 24L * 60 * 60 * 1000

    /** The kinds whose stamp is known to be their end, so that an onset can be worked out. */
    private val ENDS_AT_ITS_STAMP: Set<Any> = setOf(RespiratoryEvent.OBSTRUCTIVE_APNEA, RespiratoryEvent.CENTRAL_APNEA)

    /**
     * @param payload one verified download of `UsageEvents-TherapyStatusEvents`.
     * @param firmware the software of the machine the download came from.
     * @throws SpoolDecodeException if the download as a whole is not this log.
     */
    public fun usage(payload: ByteArray, firmware: Firmware): DecodedLog<EventRecord<UsageEvent>> =
        decode(payload, firmware, SpoolEnvelope.USAGE_EVENTS, "usage events", UsageEvent::fromCode)

    /**
     * @param payload one verified download of `TherapyEvents-RespiratoryEvents`.
     * @param firmware the software of the machine the download came from.
     * @throws SpoolDecodeException if the download as a whole is not this log.
     */
    public fun respiratory(payload: ByteArray, firmware: Firmware): DecodedLog<EventRecord<RespiratoryEvent>> =
        decode(payload, firmware, SpoolEnvelope.RESPIRATORY_EVENTS, "breathing events", RespiratoryEvent::fromCode)

    private fun <K : Any> decode(
        payload: ByteArray,
        firmware: Firmware,
        envelopeField: Int,
        what: String,
        kindOf: (Int) -> K?,
    ): DecodedLog<EventRecord<K>> {
        val sink = ResultSink<EventRecord<K>>()
        // The one nesting ever seen: an envelope entry holds lists, and a list holds events.
        // Whatever goes wrong is recorded where it happened and the walk goes on: the machine
        // puts every event of a download inside one entry, so stopping at the first oddity
        // would lose everything after it.
        for (entry in unwrap(payload, envelopeField, what)) {
            for (list in children(entry, what, sink)) {
                for (record in children(list, what, sink)) {
                    val fields = try {
                        ProtoReader.read(record)
                    } catch (e: SpoolDecodeException) {
                        sink.fail(e.message ?: "not understood")
                        continue
                    }
                    // A record with nested content that fails may be a list of a shape not seen
                    // before, in which case more than one event is missing.
                    val nested = fields.any { it.bytes != null }
                    if (!isEvent(fields)) {
                        sink.fail("$what: neither an event nor anything known", severalRecords = nested)
                        continue
                    }
                    try {
                        sink.add(event(fields, what, kindOf))
                    } catch (e: SpoolDecodeException) {
                        sink.fail(e.message ?: "not understood", severalRecords = nested)
                    }
                }
            }
        }
        return sink.finish(firmware.layoutsConfirmed)
    }

    /** The nested messages of a container. Anything in it that is not one is recorded as a failure. */
    private fun children(container: ByteArray, what: String, into: ResultSink<*>): List<ByteArray> {
        val fields = try {
            ProtoReader.read(container)
        } catch (e: SpoolDecodeException) {
            into.fail(e.message ?: "not understood", severalRecords = true)
            return emptyList()
        }
        val messages = ArrayList<ByteArray>()
        for (field in fields) {
            val inner = field.bytes
            if (inner == null) into.fail("$what: a stray value among the records", severalRecords = true) else messages += inner
        }
        return messages
    }

    /**
     * An event is recognised by having any of its own whole-number fields. A wrapper has
     * none: it holds only nested messages. An event with a field missing is then still seen
     * as an event, and fails as one, instead of being mistaken for an empty wrapper.
     */
    private fun isEvent(fields: List<ProtoField>): Boolean = fields.any { it.wireType == ProtoReader.VARINT && it.number in 1..4 }

    private fun <K : Any> event(fields: List<ProtoField>, what: String, kindOf: (Int) -> K?): EventRecord<K> {
        var code: Long? = null
        var first: Long? = null
        var second: Long? = null
        var duration: Long? = null
        var unknown = 0
        for (field in fields) {
            if (field.number in 1..4 && field.wireType != ProtoReader.VARINT) wrongType(what, field)
            when (field.number) {
                1 -> if (code == null) code = field.value else repeated(what, 1)
                2 -> if (first == null) first = field.value else repeated(what, 2)
                3 -> if (second == null) second = field.value else repeated(what, 3)
                4 -> if (duration == null) duration = field.value else repeated(what, 4)
                else -> {
                    // A record that looks like an event but carries events inside is a wrapper
                    // of a shape not seen before. Reading it as one event would drop the rest.
                    val inner = field.bytes
                    if (inner != null && holdsEvent(inner, MAX_NESTING)) throw SpoolDecodeException("$what: an event that contains events")
                    unknown++
                }
            }
        }
        if (duration != null && duration !in 0..MAX_DURATION_MS) throw SpoolDecodeException("$what: a duration is out of range")
        val number = count(code ?: missing(what, "an event's code"), MAX_CODE, what, "an event code")
        if (number == 0) throw SpoolDecodeException("$what: an event code of zero")
        val time = timestamp(second ?: missing(what, "an event's second time stamp"), what)
        if (timestamp(first ?: missing(what, "an event's first time stamp"), what) != time) {
            throw SpoolDecodeException("$what: an event whose two time stamps differ")
        }
        val kind = kindOf(number)
        return EventRecord(
            kind = kind,
            code = number,
            time = time,
            durationMs = duration,
            onset = if (duration != null && kind != null && ENDS_AT_ITS_STAMP.contains(kind as Any)) time - duration else null,
            unknownFields = unknown,
        )
    }

    private fun holdsEvent(bytes: ByteArray, depth: Int): Boolean {
        val fields = try {
            ProtoReader.read(bytes)
        } catch (e: SpoolDecodeException) {
            return false // text or some other payload, not a message
        }
        if ((1..3).all { number -> fields.any { it.number == number && it.wireType == ProtoReader.VARINT } }) return true
        val nested = fields.mapNotNull { it.bytes }
        // Too deep to follow: assume the worst, so that nothing can hide by nesting.
        if (depth == 0) return nested.isNotEmpty()
        return nested.any { holdsEvent(it, depth - 1) }
    }
}
