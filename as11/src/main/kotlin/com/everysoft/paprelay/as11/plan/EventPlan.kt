// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.as11.plan

import com.everysoft.paprelay.as11.decode.DecodedLog
import com.everysoft.paprelay.as11.decode.EventRecord
import com.everysoft.paprelay.as11.decode.RespiratoryEvent
import com.everysoft.paprelay.core.nights.BreathingEvent
import com.everysoft.paprelay.core.nights.BreathingEventKind

/**
 * Turns the machine's breathing-event records into the events that are passed on.
 *
 * Only kinds seen in a real capture and compared with an SD card are passed on
 * ([RespiratoryEvent.confirmed]); only from software whose data is read; each with its stamp and
 * length exactly as stored. Nothing is worked out but the corrected time, and that only where the
 * clock's error is known well enough.
 */
public object EventPlan {
    /** The kinds passed on, and what each is called. A test holds it to [RespiratoryEvent.confirmed]. */
    internal val kinds: Map<RespiratoryEvent, BreathingEventKind> = mapOf(
        RespiratoryEvent.OBSTRUCTIVE_APNEA to BreathingEventKind.OBSTRUCTIVE_APNEA,
        RespiratoryEvent.CENTRAL_APNEA to BreathingEventKind.CENTRAL_APNEA,
        RespiratoryEvent.HYPOPNEA to BreathingEventKind.HYPOPNEA,
        RespiratoryEvent.APNEA to BreathingEventKind.UNCLASSIFIED_APNEA,
    )

    /**
     * Which kinds are passed on. Events kept under another set are read again from as far back as
     * the machine is asked for, once, so that a kind confirmed later is found in the nights before.
     */
    public val VERSION: String = kinds.values.map { it.key }.sorted().joinToString(",")

    /**
     * @param log the decoded download, for whether its software's data is read at all.
     * @param correction how the event's stamp is put into true time.
     * @return the event to keep, or null: a kind that is not passed on, or software whose data is not read.
     */
    public fun event(log: DecodedLog<EventRecord<RespiratoryEvent>>, record: EventRecord<RespiratoryEvent>, correction: ClockCorrection): BreathingEvent? {
        if (!log.layoutAccepted) return null
        val source = record.kind ?: return null
        if (!source.confirmed) return null
        val kind = kinds[source] ?: return null
        return BreathingEvent(kind, record.time, record.durationMs, correction.of(record.time))
    }
}
