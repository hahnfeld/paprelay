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
    /** The figures given a column each, in this order. */
    public val COLUMNS: List<NightFigure> = NightFigure.entries

    private const val NEWLINE = "\r\n"

    /**
     * Comma-separated values, newest night first. Days still running are left out.
     *
     * A cell is empty when the machine recorded no such figure for that night; it is never a
     * guessed zero. No cell holds a comma, a quote or a line break, so none needs quoting.
     */
    public fun csv(nights: Collection<Night>): String = buildString {
        append("night_of,usage_minutes,usage_hours,sessions")
        for (figure in COLUMNS) append(',').append(figure.column)
        append(NEWLINE)
        for (night in nights.filter { it.complete }.sortedByDescending { it.date }) {
            append(night.date).append(',').append(night.usageMinutes).append(',').append(night.usageHours.toPlainString()).append(',').append(night.sessions)
            for (figure in COLUMNS) append(',').append(night.value(figure)?.toPlainString().orEmpty())
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
