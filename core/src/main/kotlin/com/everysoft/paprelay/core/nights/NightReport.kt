// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.core.nights

/**
 * The nights as a table that a person, a spreadsheet or an assistant can read.
 *
 * The file is meant to explain itself to a reader who has never heard of PAPRelay: what
 * each column is, what an empty cell means, and where the figures come from.
 */
public object NightReport {
    /** The figures given a column each, in this order. */
    public val COLUMNS: List<NightFigure> = listOf(
        NightFigure.AHI,
        NightFigure.OBSTRUCTIVE_APNEA_INDEX,
        NightFigure.CENTRAL_APNEA_INDEX,
        NightFigure.UNCLASSIFIED_APNEA_INDEX,
        NightFigure.HYPOPNEA_INDEX,
        NightFigure.RERA_INDEX,
        NightFigure.LEAK_MEDIAN,
        NightFigure.LEAK_95,
        NightFigure.LEAK_MAX,
        NightFigure.PRESSURE_MEDIAN,
        NightFigure.PRESSURE_95,
        NightFigure.PRESSURE_MAX,
        NightFigure.RESPIRATORY_RATE_MEDIAN,
        NightFigure.TIDAL_VOLUME_MEDIAN,
        NightFigure.MINUTE_VENTILATION_MEDIAN,
    )

    private const val NEWLINE = "\r\n"

    /**
     * Comma-separated values, newest night first. Days still running are left out.
     *
     * The lines before the header start with `#` and say what the file is. A cell is empty
     * when the machine recorded no such figure for that night; it is never a guessed zero.
     */
    public fun csv(nights: Collection<Night>): String = buildString {
        append("# CPAP therapy, one line per night, as recorded by a ResMed AirSense 11 and copied by PAPRelay.").append(NEWLINE)
        append("# A night runs from noon to noon by the machine's clock; night_of is the date it began (the evening).").append(NEWLINE)
        append("# Every figure is the machine's own. An empty cell means the machine recorded none. usage_hours 0 means no therapy that night.").append(NEWLINE)
        append("# Units: indices in events per hour; leak in L/min; pressure in cmH2O; respiratory rate in breaths/min; tidal volume in L; minute ventilation in L/min.").append(NEWLINE)
        append("night_of,usage_hours,sessions")
        for (figure in COLUMNS) append(',').append(figure.key.replace('-', '_'))
        append(NEWLINE)
        for (night in nights.filter { it.complete }.sortedByDescending { it.date }) {
            append(night.date).append(',').append(night.usageHours.toPlainString()).append(',').append(night.sessions)
            for (figure in COLUMNS) append(',').append(night.value(figure)?.toPlainString().orEmpty())
            append(NEWLINE)
        }
    }

    /**
     * The same in a few plain lines for sharing as text: the most recent [limit] nights.
     */
    public fun text(nights: Collection<Night>, limit: Int = 30): String = buildString {
        append("CPAP therapy by night (ResMed AirSense 11, the machine's own figures). Night = noon to noon, dated by the evening.\n")
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
            part(night, NightFigure.PRESSURE_95, "pressure 95th", " cmH2O")
            append('\n')
        }
    }

    private fun StringBuilder.part(night: Night, figure: NightFigure, name: String, unit: String = "") {
        night.value(figure)?.let { append(", ").append(name).append(' ').append(it.toPlainString()).append(unit) }
    }
}
