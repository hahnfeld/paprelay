// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.core.nights

/**
 * The nights as a table that a person, a spreadsheet or an assistant can read.
 *
 * The table is plain comma-separated values with one header line, so that every program
 * that reads such files reads this one. It explains itself through its column names, each
 * of which carries its unit.
 */
public object NightReport {
    /**
     * The figures of the table as it was before 0.3.0, in their order: what the text shared from
     * the app carries, so that it stays short.
     */
    public val SHARED: List<NightFigure> = NightFigure.entries.takeWhile { it != NightFigure.LEAK_70 }

    /** The columns that list the sessions, after the figures. */
    public const val SESSION_STARTS_CORRECTED_COLUMN: String = "session_starts_corrected"
    public const val SESSION_STARTS_COLUMN: String = "session_starts_machine_clock"
    public const val SESSION_LENGTHS_COLUMN: String = "session_lengths_minutes"

    private const val NEWLINE = "\r\n"

    /**
     * Comma-separated values, newest night first. Days still running are left out.
     *
     * A cell is empty when the machine recorded no such figure for that night; it is never a
     * guessed zero. No cell holds a comma, a quote or a line break, so none needs quoting.
     *
     * @param figures the figures given a column each, in this order: those a night can have
     *   (the plan that makes nights says which).
     * @param withSessions whether to end each line with its sessions: their starts corrected to
     *   true time, their starts by the machine's clock, and their lengths, each list separated by
     *   spaces in the machine's order. Empty where the night's sessions are not known one by one;
     *   the corrected starts are also empty unless every session of the night has one.
     */
    public fun csv(nights: Collection<Night>, figures: List<NightFigure>, withSessions: Boolean = true): String = buildString {
        require(figures.toSet().size == figures.size) { "a figure twice" }
        append("night_of,usage_minutes,usage_hours,sessions")
        for (figure in figures) append(',').append(figure.column)
        if (withSessions) append(',').append(SESSION_STARTS_CORRECTED_COLUMN).append(',').append(SESSION_STARTS_COLUMN).append(',').append(SESSION_LENGTHS_COLUMN)
        append(NEWLINE)
        for (night in nights.filter { it.complete }.sortedByDescending { it.date }) {
            append(night.date).append(',').append(night.usageMinutes).append(',').append(night.usageHours.toPlainString()).append(',').append(night.sessions)
            for (figure in figures) append(',').append(night.value(figure)?.toPlainString().orEmpty())
            if (withSessions) {
                val list = night.sessionList
                val corrected = list?.takeIf { sessions -> sessions.all { it.correctedMs != null } }
                append(',').append(corrected?.joinToString(" ") { CsvTime.format(it.correctedMs!!, night.zoneOffsetMinutes) }.orEmpty())
                append(',').append(list?.joinToString(" ") { CsvTime.format(it.start.epochMs, night.zoneOffsetMinutes) }.orEmpty())
                append(',').append(list?.joinToString(" ") { it.minutes.toString() }.orEmpty())
            }
            append(NEWLINE)
        }
    }

    /** What the table is, for whoever is handed it: said once, beside the table and not inside it. */
    public const val EXPLANATION: String =
        "CPAP therapy by night, from a ResMed AirSense 11, copied by PAPRelay. Every figure is the machine's own. " +
            "A night runs from noon to noon by the machine's clock and is dated by the day it began (the evening). " +
            "The indices are residual events the machine detected from airflow, per hour of use: they are not sleep-study indices. " +
            "The machine cuts each index off at one decimal place, so the parts need not add up to the AHI. " +
            "Leak and pressure are given as median, 95th percentile and maximum over the night."

    /** The most recent [limit] nights in a few plain lines, for sharing as text. */
    public fun text(nights: Collection<Night>, limit: Int = 30): String = buildString {
        append(EXPLANATION).append("\n\n")
        for (night in nights.filter { it.complete }.sortedByDescending { it.date }.take(limit)) {
            append(night.date).append(": ")
            if (night.usageMinutes == 0) {
                append("no therapy\n")
                continue
            }
            append("used ").append(night.usageMinutes / 60).append(" h ").append(night.usageMinutes % 60).append(" min")
            part(night, NightFigure.AHI, "AHI")
            part(night, NightFigure.OBSTRUCTIVE_APNEA_INDEX, "obstructive")
            part(night, NightFigure.CENTRAL_APNEA_INDEX, "central")
            part(night, NightFigure.HYPOPNEA_INDEX, "hypopnea")
            part(night, NightFigure.LEAK_95, "leak 95th", " L/min")
            part(night, NightFigure.THERAPY_PRESSURE_95, "therapy pressure 95th", " cmH2O")
            append('\n')
        }
    }

    private fun StringBuilder.part(night: Night, figure: NightFigure, name: String, unit: String = "") {
        night.value(figure)?.let { append(", ").append(name).append(' ').append(it.toPlainString()).append(unit) }
    }
}

/** Times in the tables: ISO 8601 with the offset given, to the second, and to the millisecond where there is one. */
public object CsvTime {
    private val SECONDS = java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss", java.util.Locale.ROOT)
    private val OFFSET = java.time.format.DateTimeFormatter.ofPattern("XXX", java.util.Locale.ROOT)

    /** [epochMs] as written at [offsetMinutes] from UTC: `2026-11-14T23:05:00-07:00`. Exact: nothing is rounded. */
    public fun format(epochMs: Long, offsetMinutes: Int): String {
        val time = java.time.Instant.ofEpochMilli(epochMs).atOffset(java.time.ZoneOffset.ofTotalSeconds(offsetMinutes * 60))
        val millis = Math.floorMod(epochMs, 1000L)
        return SECONDS.format(time) + (if (millis != 0L) String.format(java.util.Locale.ROOT, ".%03d", millis) else "") + OFFSET.format(time)
    }
}
