// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.core.nights

import com.everysoft.paprelay.core.MachineTime
import java.math.BigDecimal
import java.util.TreeMap

/**
 * The kinds of breathing event that are passed on: those seen in a real capture and compared
 * with the same machine's SD card. Which of the machine's codes each one is, is the `as11`
 * module's affair.
 *
 * @property key its name in files. Never changed once released.
 * @property stampIsEnd true where the machine's time stamp is known to be when the event
 *   **ended**, for an event that carries its length (an apnoea: it is reported once it is over,
 *   with its length). False where it is not known whether the stamp is its start or its end (a
 *   hypopnoea, which has no stored length). The stamp is passed on as stored either way;
 *   nothing is worked out from it. See [BreathingEvent.stampIsEnd].
 */
public enum class BreathingEventKind(public val key: String, public val stampIsEnd: Boolean) {
    OBSTRUCTIVE_APNEA("obstructive_apnea", true),
    CENTRAL_APNEA("central_apnea", true),
    UNCLASSIFIED_APNEA("unclassified_apnea", true),
    HYPOPNEA("hypopnea", false),
    ;

    public companion object {
        private val byKey = entries.associateBy { it.key }

        public fun ofKey(key: String): BreathingEventKind? = byKey[key]
    }
}

/**
 * One breathing event the machine recorded, as stored.
 *
 * @property machineTime the machine's time stamp, by its own clock: exactly as stored.
 * @property durationMs the stored length, or null where the machine stores none.
 * @property correctedMs the same moment in true time, milliseconds since the epoch: the machine's
 *   stamp plus how far its clock was measured to be off when the event was first copied. Null when
 *   that cannot be said with confidence (see the synchronising engine). Fixed once given, so the
 *   event says the same in every run.
 */
public class BreathingEvent(
    public val kind: BreathingEventKind,
    public val machineTime: MachineTime,
    public val durationMs: Long?,
    public val correctedMs: Long?,
) {
    init {
        require(durationMs == null || durationMs in 0..MAX_DURATION_MS) { "not an event's length" }
    }

    /**
     * What makes this event the one it is: its kind, its stamp and its length, all as stored. Read
     * twice (the next run reads the last day again), it is the same event, and kept once.
     */
    public val identity: String get() = "${kind.key}@${machineTime.epochMs}+${durationMs ?: "-"}"

    /**
     * Whether the stamp is known to be the event's end: an apnoea, with the length it was seen
     * with. One stored without a length is not the event that was compared, and is not claimed.
     */
    public val stampIsEnd: Boolean get() = kind.stampIsEnd && durationMs != null

    /** The stored length in seconds, exactly: 10500 ms is "10.5". */
    public val durationSeconds: BigDecimal? get() = durationMs?.let { BigDecimal.valueOf(it, 3).stripTrailingZeros().let { s -> if (s.scale() < 0) s.setScale(0) else s } }

    /** Shows nothing: when someone stopped breathing is health data. */
    override fun toString(): String = "BreathingEvent"

    public companion object {
        /** A day: the bound the decoder holds a stored length to. */
        public const val MAX_DURATION_MS: Long = 24L * 60 * 60 * 1000
    }
}

/**
 * The breathing events as a table: one line for each event, all nights in one file.
 *
 * Plain comma-separated values with one header line, like [NightReport]'s table, and in the
 * same style: units in the column names, an empty cell where something is not known, never a
 * guess, newest first.
 */
public object EventReport {
    public val HEADER: List<String> = listOf("night_of", "event", "time_corrected", "time_machine_clock", "time_is", "duration_seconds")

    private const val NEWLINE = "\r\n"

    /** What the time stamp is, in the table's words: `end`, or `start_or_end` where that is not known. */
    public fun timeIs(event: BreathingEvent): String = if (event.stampIsEnd) "end" else "start_or_end"

    /**
     * The events that are shown and written: those before the end of the newest finished night. The
     * events of a day still running (or not yet summed up by the machine) wait, kept, until that
     * night is in the table of nights too.
     */
    public fun shown(events: Collection<BreathingEvent>, nights: Collection<Night>): List<BreathingEvent> {
        val until = nights.filter { it.complete }.maxOfOrNull { it.end.toInstant().toEpochMilli() } ?: return emptyList()
        return events.filter { it.machineTime.epochMs < until }
    }

    /**
     * @param nights the nights kept. Each event is given the finished night whose day (machine's
     *   local noon to noon, as the machine summed it up) holds its stamp; with none, the cell is
     *   empty. Times are written at the offset of that night, or of the nearest night.
     */
    public fun csv(events: Collection<BreathingEvent>, nights: Collection<Night>): String = buildString {
        append(HEADER.joinToString(",")).append(NEWLINE)
        val finished = Nights(nights.filter { it.complete })
        val sorted = shown(events, nights).sortedWith(compareByDescending<BreathingEvent> { it.machineTime.epochMs }.thenBy { it.kind.key }.thenBy { it.durationMs ?: -1 })
        for (event in sorted) {
            val ms = event.machineTime.epochMs
            val night = finished.holding(ms)
            val offset = finished.offsetNear(ms)
            append(night?.date?.toString().orEmpty()).append(',')
            append(event.kind.key).append(',')
            append(event.correctedMs?.let { CsvTime.format(it, offset) }.orEmpty()).append(',')
            append(CsvTime.format(ms, offset)).append(',')
            append(timeIs(event)).append(',')
            append(event.durationSeconds?.toPlainString().orEmpty())
            append(NEWLINE)
        }
    }

    /** The night whose day holds [machineMs]: from its start, up to but not including its end. */
    public fun nightOf(machineMs: Long, nights: Collection<Night>): Night? = Nights(nights).holding(machineMs)

    /**
     * The offset to write [machineMs] at: that of the night holding it, or else of the nearest night
     * (the machine's own local time, as near as it is known); UTC with no night at all.
     */
    public fun offsetNear(machineMs: Long, nights: Collection<Night>): Int = Nights(nights).offsetNear(machineMs)

    /** Nights by start, for finding the one that holds a moment. */
    public class Nights(nights: Collection<Night>) {
        private val byStart = TreeMap<Long, Night>().apply { for (night in nights) put(night.start.toInstant().toEpochMilli(), night) }

        public fun holding(machineMs: Long): Night? {
            val night = byStart.floorEntry(machineMs)?.value ?: return null
            return night.takeIf { machineMs < it.end.toInstant().toEpochMilli() }
        }

        public fun offsetNear(machineMs: Long): Int {
            holding(machineMs)?.let { return it.zoneOffsetMinutes }
            val before = byStart.floorEntry(machineMs)?.value
            val after = byStart.ceilingEntry(machineMs)?.value
            val nearest = listOfNotNull(before, after).minByOrNull { night ->
                val start = night.start.toInstant().toEpochMilli()
                val end = night.end.toInstant().toEpochMilli()
                if (machineMs < start) start - machineMs else machineMs - end
            }
            return nearest?.zoneOffsetMinutes ?: 0
        }
    }
}
