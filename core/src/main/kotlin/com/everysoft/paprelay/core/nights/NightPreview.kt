// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.core.nights

import com.everysoft.paprelay.core.MachineTime
import java.time.LocalDate

/**
 * The mask going on or off, as the machine stamped it in its usage log.
 *
 * @property correctedMs the same moment in true time, where the clock's error is known well
 *   enough (the same rule as a breathing event's), or null. Fixed once given.
 */
public class UsageMark(public val on: Boolean, public val machineTime: MachineTime, public val correctedMs: Long?) {
    /** Its kind and stamp, as stored: read twice, the same mark. */
    public val identity: String get() = (if (on) "on@" else "off@") + machineTime.epochMs

    /** Shows nothing: when someone put the mask on is health data. */
    override fun toString(): String = "UsageMark"
}

/**
 * One time the mask was on in a night that the machine has not summed up yet.
 *
 * @property end when the mask came off; for a session still running ([running]), the last moment
 *   it is known to have been on, which is when the machine was last read; and null for a running
 *   session whose time beyond its start is not known (the log was not read whole, or never to its
 *   end): it has a start and no length, never a length of zero.
 */
public class PreviewSession(
    public val start: MachineTime,
    public val end: MachineTime?,
    public val running: Boolean,
    public val correctedStartMs: Long?,
) {
    init {
        require(end == null || end >= start) { "a session that ends before it starts" }
        require(end != null || running) { "an ended session with no end" }
    }

    /** Its whole minutes, or null when its length is not known. */
    public val minutes: Int? get() = end?.let { (it.since(start) / 60_000L).toInt() }

    override fun toString(): String = "PreviewSession"
}

/**
 * A stretch of the usage log where a record could not be read: after the last record read before
 * it, and before the first read after it (either end null where there is none). A night that this
 * stretch reaches may be missing a mark, and is given no total.
 */
public class UsageGap(public val afterMs: Long?, public val beforeMs: Long?) {
    public fun reaches(startMs: Long, endMs: Long): Boolean = (afterMs ?: Long.MIN_VALUE) < endMs && (beforeMs ?: Long.MAX_VALUE) >= startMs

    override fun toString(): String = "UsageGap"
}

/**
 * The night now in progress, or just ended, before the machine has summed it up (it does that
 * after the next midday): only what the machine's own logs say of it so far, and nothing worked
 * out from it. It is shown in the app and never passed on: not to the files, not to the health
 * records, not in the text shared. When the machine's own night for this date arrives, it takes
 * the preview's place.
 *
 * @property date the night's date, the day its midday began, by the machine's clock.
 * @property startMs the machine's local midday that begins it, and [endMs] the next.
 * @property zoneOffsetMinutes the offset of the night before it, the nearest known.
 * @property sessions the mask-on times whose marks are known, in order.
 * @property usageMinutes the whole minutes the mask was on so far, or null where a sum would be a
 *   guess: the marks do not pair up, or a record of the log that may belong to this night could
 *   not be read.
 * @property lastReadMs when the machine's usage log was last read whole, by its clock, and
 *   [lastReadCorrectedMs] the same in true time where that is known: a session still running had
 *   not ended then. It is not the present.
 */
public class NightPreview(
    public val date: LocalDate,
    public val startMs: Long,
    public val endMs: Long,
    public val zoneOffsetMinutes: Int,
    public val sessions: List<PreviewSession>,
    public val usageMinutes: Int?,
    public val lastReadMs: Long? = null,
    public val lastReadCorrectedMs: Long? = null,
) {
    /** Whether the mask was on when the machine was last read. */
    public val running: Boolean get() = sessions.lastOrNull()?.running == true

    override fun toString(): String = "NightPreview"

    public companion object {
        private const val DAY_MS = 24L * 60 * 60 * 1000

        /**
         * The night after the newest finished one, from the mask-on and mask-off marks, or null:
         * no finished night to start from, or no mask-on in that night yet.
         *
         * @param knownUntilMs the machine's clock when the usage log was last read whole: a session
         *   with no mask-off yet is counted up to it. Also, when it lies more than a day after the
         *   newest finished night, the night it falls in is the one previewed.
         * @param gaps where the log could not be read in the latest run. A night one of them reaches
         *   is given no total, and a session still running there no time beyond its start.
         */
        public fun of(
            nights: Collection<Night>,
            marks: Collection<UsageMark>,
            knownUntilMs: Long?,
            knownUntilCorrectedMs: Long? = null,
            gaps: Collection<UsageGap> = emptyList(),
        ): NightPreview? {
            val newest = nights.filter { it.complete }.maxByOrNull { it.date } ?: return null
            val after = newest.end.toInstant().toEpochMilli()
            // Days after the newest finished night: its offset and 24 hours each, as near as is known
            // before the machine has said (a day the clocks change in is an hour off at its far end).
            val skipped = if (knownUntilMs != null && knownUntilMs >= after) (knownUntilMs - after) / DAY_MS else 0L
            val start = after + skipped * DAY_MS
            val end = start + DAY_MS
            val sorted = marks.sortedBy { it.machineTime.epochMs }
            val inside = sorted.filter { it.machineTime.epochMs in start until end }
            if (inside.none { it.on }) return null
            val incomplete = gaps.any { it.reaches(start, end) }
            // A mask-off before any mask-on ends a session of the night before, if the mark before midday
            // is a mask-on that it closes. Otherwise it is a mark without its pair: no sum.
            val before = sorted.lastOrNull { it.machineTime.epochMs < start }
            var whole = !incomplete
            val marked = if (inside.first().on) {
                inside
            } else {
                if (before?.on != true || inside.drop(1).firstOrNull()?.on == false) whole = false
                inside.dropWhile { !it.on }
            }
            val sessions = ArrayList<PreviewSession>()
            var index = 0
            while (index < marked.size) {
                val on = marked[index]
                if (!on.on) {
                    // Two mask-offs in a row: the log is not whole, and no sum is given.
                    whole = false
                    index += 1
                    continue
                }
                val off = marked.getOrNull(index + 1)
                when {
                    off == null -> {
                        // Still on when the machine was last read; counted up to then, and said to be
                        // running. Where the log is not whole, or was never read to its end, no time
                        // beyond its start is known: it has no end, and no length.
                        // A last read before this mask-on says nothing about how long it has been on.
                        val until = if (incomplete || knownUntilMs == null || knownUntilMs < on.machineTime.epochMs) null else minOf(knownUntilMs, end)
                        if (until == null) whole = false
                        sessions += PreviewSession(on.machineTime, until?.let(::MachineTime), running = true, on.correctedMs)
                        index += 1
                    }
                    off.on -> {
                        // Two mask-ons in a row: when the first ended is not known. It is not drawn.
                        whole = false
                        index += 1
                    }
                    else -> {
                        sessions += PreviewSession(on.machineTime, off.machineTime, running = false, on.correctedMs)
                        index += 2
                    }
                }
            }
            val date = newest.date.plusDays(1 + skipped)
            val minutes = if (whole) (sessions.sumOf { it.end!!.since(it.start) } / 60_000L).toInt() else null
            return NightPreview(date, start, end, newest.zoneOffsetMinutes, sessions, minutes, knownUntilMs, knownUntilCorrectedMs)
        }
    }
}
