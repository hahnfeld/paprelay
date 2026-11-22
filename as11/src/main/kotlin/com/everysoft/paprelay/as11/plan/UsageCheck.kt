// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.as11.plan

import com.everysoft.paprelay.as11.decode.EventRecord
import com.everysoft.paprelay.as11.decode.SummarySession
import com.everysoft.paprelay.as11.decode.UsageEvent
import kotlin.math.abs

/**
 * Compares the mask-on and mask-off stamps of the usage log with the sessions of the daily
 * summaries for the same stretch of time: the comparison that has to pass before the usage log
 * is used for anything. It gives counts and the largest differences only, never a time.
 */
public object UsageCheck {
    /** How far a mask-on may be from a session's start and still be taken as that session's. */
    public const val MATCH_WINDOW_MS: Long = 2 * 60_000L

    public class Result(
        /** Summary sessions that start within the span of the usage log (or up to [MATCH_WINDOW_MS] before it), and so can be compared. */
        public val sessions: Int,
        /** Of those, the ones with a mask-on within [MATCH_WINDOW_MS] of their start. */
        public val withMaskOn: Int,
        /** Of those, the ones whose mask-on is followed by a mask-off before the next mask-on. */
        public val withMaskOff: Int,
        /** Of those, the ones whose length from mask-on to mask-off is the summary's, to the minute (as the summary stores it). */
        public val lengthAgrees: Int,
        /** The largest difference between a session's start and its mask-on, in seconds. */
        public val maxStartDifferenceS: Long,
        /** The largest difference between a session's stored length and mask-on to mask-off, in seconds. */
        public val maxLengthDifferenceS: Long,
        /** Mask-ons with no mask-off before the next mask-on or the end of the log (a session still running, or one lost). */
        public val maskOnWithoutOff: Int,
        /** Mask-offs with no mask-on before them since the last mask-off. */
        public val maskOffWithoutOn: Int,
        /** Mask-ons that match no summary session. */
        public val maskOnWithoutSession: Int,
        /** How many of each other kind, by name or code. */
        public val otherKinds: Map<String, Int>,
    )

    public fun compare(sessions: List<SummarySession>, usage: List<EventRecord<UsageEvent>>): Result {
        val sorted = usage.sortedBy { it.time.epochMs }
        val ons = sorted.filter { it.kind == UsageEvent.MASK_ON }.map { it.time.epochMs }
        val offs = sorted.filter { it.kind == UsageEvent.MASK_OFF }.map { it.time.epochMs }
        val first = sorted.firstOrNull()?.time?.epochMs
        val last = sorted.lastOrNull()?.time?.epochMs
        // A session's start is stored in whole minutes and comes a little before the log's first record
        // (therapy start, then mask on, a few seconds later): sessions that start up to the match window
        // before the log are in its span.
        val inSpan = if (first == null || last == null) emptyList() else sessions.filter { it.start.epochMs in (first - MATCH_WINDOW_MS)..last }

        // Pairs: each mask-on with the first mask-off after it and before the next mask-on.
        val pairs = ons.mapIndexed { index, on ->
            val nextOn = ons.getOrNull(index + 1) ?: Long.MAX_VALUE
            on to offs.firstOrNull { it in on until nextOn }
        }
        val pairedOffs = pairs.mapNotNull { it.second }.toSet()
        val maskOffWithoutOn = offs.count { it !in pairedOffs }

        var withOn = 0
        var withOff = 0
        var agrees = 0
        var maxStart = 0L
        var maxLength = 0L
        val used = HashSet<Long>()
        for (session in inSpan) {
            val start = session.start.epochMs
            val pair = pairs.filter { abs(it.first - start) <= MATCH_WINDOW_MS }.minByOrNull { abs(it.first - start) } ?: continue
            withOn++
            used += pair.first
            maxStart = maxOf(maxStart, abs(pair.first - start) / 1000)
            val off = pair.second ?: continue
            withOff++
            val minutes = session.durationMinutes ?: continue
            val lengthMs = off - pair.first
            maxLength = maxOf(maxLength, abs(lengthMs - minutes * 60_000L) / 1000)
            // The summary stores whole minutes; a length within a minute of them agrees.
            if (abs(lengthMs - minutes * 60_000L) < 60_000L) agrees++
        }
        val others = sorted.filter { it.kind != UsageEvent.MASK_ON && it.kind != UsageEvent.MASK_OFF }
            .groupingBy { it.kind?.name ?: "code ${it.code}" }.eachCount()
        return Result(
            sessions = inSpan.size,
            withMaskOn = withOn,
            withMaskOff = withOff,
            lengthAgrees = agrees,
            maxStartDifferenceS = maxStart,
            maxLengthDifferenceS = maxLength,
            maskOnWithoutOff = pairs.count { it.second == null },
            maskOffWithoutOn = maskOffWithoutOn,
            maskOnWithoutSession = ons.count { it !in used },
            otherKinds = others,
        )
    }
}
