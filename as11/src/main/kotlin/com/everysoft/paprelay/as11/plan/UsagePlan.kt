// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.as11.plan

import com.everysoft.paprelay.as11.decode.DecodedLog
import com.everysoft.paprelay.as11.decode.EventRecord
import com.everysoft.paprelay.as11.decode.UsageEvent
import com.everysoft.paprelay.core.nights.UsageMark

/**
 * Turns the usage log's mask-on and mask-off records into the marks the morning preview is made
 * of. Nothing else of the log is used, and nothing at all until both kinds are confirmed
 * ([UsageEvent.confirmed]).
 */
public object UsagePlan {
    /** The kinds confirmed in this version. */
    public val CONFIRMED: Set<UsageEvent> = UsageEvent.entries.filter { it.confirmed }.toSet()

    /** Whether the preview can be made with [kinds]: it needs both mask on and mask off. */
    public fun ready(kinds: Set<UsageEvent>): Boolean = UsageEvent.MASK_ON in kinds && UsageEvent.MASK_OFF in kinds

    /**
     * @param kinds the kinds that may be used: the confirmed ones, unless a test says otherwise.
     * @return the mark, or null: another kind, one not confirmed, or software whose data is not read.
     */
    public fun mark(
        log: DecodedLog<EventRecord<UsageEvent>>,
        record: EventRecord<UsageEvent>,
        correction: ClockCorrection,
        kinds: Set<UsageEvent> = CONFIRMED,
    ): UsageMark? {
        if (!log.layoutAccepted || !ready(kinds)) return null
        val on = when (record.kind) {
            UsageEvent.MASK_ON -> true
            UsageEvent.MASK_OFF -> false
            else -> return null
        }
        return UsageMark(on, record.time, correction.of(record.time))
    }
}
