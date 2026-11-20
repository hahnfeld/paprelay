// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.as11.plan

import com.everysoft.paprelay.as11.decode.Percentile
import com.everysoft.paprelay.as11.decode.SummaryIndex
import com.everysoft.paprelay.as11.decode.SummaryMetric
import com.everysoft.paprelay.as11.decode.SummaryRecord
import com.everysoft.paprelay.core.MachineTime
import com.everysoft.paprelay.core.PapUnit
import com.everysoft.paprelay.core.Quantity
import com.everysoft.paprelay.core.nights.Night
import com.everysoft.paprelay.core.nights.NightFigure
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneOffset

/**
 * Turns one of the machine's daily Summary records into a [Night].
 *
 * Nothing is computed: each figure is one the machine stored, taken only from the fields
 * that were compared with an SD card, and a figure the decoder set aside as misread is left out.
 */
public object NightPlan {
    private val indices: Map<SummaryIndex, NightFigure> = mapOf(
        SummaryIndex.AHI to NightFigure.AHI,
        SummaryIndex.APNEA to NightFigure.APNEA_INDEX,
        SummaryIndex.HYPOPNEA to NightFigure.HYPOPNEA_INDEX,
        SummaryIndex.OBSTRUCTIVE_APNEA to NightFigure.OBSTRUCTIVE_APNEA_INDEX,
        SummaryIndex.CENTRAL_APNEA to NightFigure.CENTRAL_APNEA_INDEX,
        SummaryIndex.UNKNOWN_APNEA to NightFigure.UNCLASSIFIED_APNEA_INDEX,
        SummaryIndex.RERA to NightFigure.RERA_INDEX,
    )

    private class Source(val metric: SummaryMetric, val percentile: Percentile, val unit: PapUnit)

    private val metrics: Map<NightFigure, Source> = mapOf(
        NightFigure.LEAK_MEDIAN to Source(SummaryMetric.LEAK, Percentile.P50, PapUnit.LITRES_PER_MINUTE),
        NightFigure.LEAK_95 to Source(SummaryMetric.LEAK, Percentile.P95, PapUnit.LITRES_PER_MINUTE),
        NightFigure.LEAK_MAX to Source(SummaryMetric.LEAK, Percentile.P100, PapUnit.LITRES_PER_MINUTE),
        NightFigure.THERAPY_PRESSURE_MEDIAN to Source(SummaryMetric.INSPIRATORY_PRESSURE, Percentile.P50, PapUnit.CM_H2O),
        NightFigure.THERAPY_PRESSURE_95 to Source(SummaryMetric.INSPIRATORY_PRESSURE, Percentile.P95, PapUnit.CM_H2O),
        NightFigure.THERAPY_PRESSURE_MAX to Source(SummaryMetric.INSPIRATORY_PRESSURE, Percentile.P100, PapUnit.CM_H2O),
        NightFigure.EXPIRATORY_PRESSURE_MEDIAN to Source(SummaryMetric.EXPIRATORY_PRESSURE, Percentile.P50, PapUnit.CM_H2O),
        NightFigure.EXPIRATORY_PRESSURE_95 to Source(SummaryMetric.EXPIRATORY_PRESSURE, Percentile.P95, PapUnit.CM_H2O),
        NightFigure.EXPIRATORY_PRESSURE_MAX to Source(SummaryMetric.EXPIRATORY_PRESSURE, Percentile.P100, PapUnit.CM_H2O),
        NightFigure.MASK_PRESSURE_MEDIAN to Source(SummaryMetric.MEAN_MASK_PRESSURE, Percentile.P50, PapUnit.CM_H2O),
        NightFigure.MASK_PRESSURE_95 to Source(SummaryMetric.MEAN_MASK_PRESSURE, Percentile.P95, PapUnit.CM_H2O),
        NightFigure.MASK_PRESSURE_MAX to Source(SummaryMetric.MEAN_MASK_PRESSURE, Percentile.P100, PapUnit.CM_H2O),
        NightFigure.RESPIRATORY_RATE_MEDIAN to Source(SummaryMetric.RESPIRATORY_RATE, Percentile.P50, PapUnit.BREATHS_PER_MINUTE),
        NightFigure.TIDAL_VOLUME_MEDIAN to Source(SummaryMetric.TIDAL_VOLUME, Percentile.P50, PapUnit.LITRES),
        NightFigure.MINUTE_VENTILATION_MEDIAN to Source(SummaryMetric.MINUTE_VENTILATION, Percentile.P50, PapUnit.LITRES_PER_MINUTE),
    )

    private const val HOUR_MS = 3_600_000L
    private const val CLEAR_MARGIN_MINUTES = 12 * 60
    private const val DAY_MS = 24 * HOUR_MS
    private val HUNDRED = BigDecimal(100)
    private val SIXTY = BigDecimal(60)

    /**
     * @param machineNow the machine's clock when the record was downloaded; a day that has not
     *   ended by it is marked as still running.
     * @return the night, or null if the record cannot stand as one: its software is not one whose data is read,
     *   it has no duration, its session list is not whole, or its day does not begin at a local noon.
     */
    public fun night(record: SummaryRecord, machineNow: MachineTime): Night? {
        if (!record.layoutAccepted || !record.sessionCountMatches) return null
        val usage = record.durationMinutes ?: return null
        // The decoder has already held the day to 23, 24 or 25 hours.
        val hours = (record.periodEnd.since(record.periodStart) / HOUR_MS).toInt()
        if (hours !in 23..25 || usage !in 0..hours * 60 || record.sessions.size > Night.MAX_SESSIONS) return null
        val offsetMinutes = zoneOffsetMinutes(record.periodStart, record.zoneOffsetMinutesWhenSent) ?: return null
        val date = Instant.ofEpochMilli(record.periodStart.epochMs).atOffset(ZoneOffset.ofTotalSeconds(offsetMinutes * 60)).toLocalDate()

        val figures = LinkedHashMap<NightFigure, Long>()
        // A day without therapy carries a duration of zero and nothing else worth passing on.
        if (usage > 0) {
            for ((index, figure) in indices) record.indices[index]?.let { hundredths(it, PapUnit.EVENTS_PER_HOUR, figure) }?.let { figures[figure] = it }
            for ((figure, source) in metrics) {
                record.metrics[source.metric]?.get(source.percentile)?.let { hundredths(it, source.unit, figure) }?.let { figures[figure] = it }
            }
        }
        return Night(
            date = date,
            zoneOffsetMinutes = offsetMinutes,
            usageMinutes = usage,
            sessions = record.sessions.size,
            hundredths = figures,
            complete = record.periodEnd <= machineNow,
            hours = hours,
        )
    }

    /**
     * How far the machine's local time was from UTC, read off the day's own start: the day begins
     * at local noon, so the moment it begins says what the offset was. (The record's zone field
     * is the machine's setting when the record was sent, which need not be the night's.)
     *
     * Two zones a whole day apart share a noon (UTC-11 and UTC+13, UTC-12 and UTC+12), and the
     * moment alone cannot tell them apart. Between such a pair the record's own zone field
     * decides: the nearer to it is taken, since a machine's zone setting moves by an hour with
     * the seasons, not by a day. With no zone field and two possibilities there is no telling
     * which date the night belongs to, and none is given.
     * Null also if the start is not at a quarter hour, which no zone's noon can be.
     */
    private fun zoneOffsetMinutes(periodStart: MachineTime, sent: Int?): Int? {
        val sinceMidnightUtc = Math.floorMod(periodStart.epochMs, DAY_MS)
        if (sinceMidnightUtc % (15 * 60_000L) != 0L) return null
        // Local noon is 12:00; UTC at that moment is 12:00 minus the offset.
        val base = ((12 * HOUR_MS - sinceMidnightUtc) / 60_000L).toInt()
        val possible = listOf(base - 24 * 60, base, base + 24 * 60).filter { it in -12 * 60..14 * 60 }
        return when {
            possible.size == 1 -> possible.single()
            sent == null -> null
            else -> {
                // The nearer to the machine's own zone, if it is clearly the nearer: by half a day or more.
                // A zone that moved an hour with the seasons, or a few hours with a journey, still points
                // plainly at one of the two. One that sits about midway between them decides nothing.
                val byDistance = possible.sortedBy { kotlin.math.abs(it - sent) }
                val margin = kotlin.math.abs(byDistance[1] - sent) - kotlin.math.abs(byDistance[0] - sent)
                byDistance[0].takeIf { margin >= CLEAR_MARGIN_MINUTES }
            }
        }
    }

    /**
     * A stored quantity in hundredths of [unit], exactly, or null if it is not a whole number of
     * hundredths or is more than [figure] can plausibly be: a misreading is left out, not passed on.
     */
    private fun hundredths(quantity: Quantity, unit: PapUnit, figure: NightFigure): Long? {
        val value = when {
            quantity.unit == unit -> quantity.exact()
            quantity.unit == PapUnit.LITRES_PER_SECOND && unit == PapUnit.LITRES_PER_MINUTE -> quantity.exact().multiply(SIXTY)
            else -> return null
        }
        val scaled = value.multiply(HUNDRED)
        return try {
            scaled.longValueExact().takeIf { it in 0..figure.maxHundredths }
        } catch (e: ArithmeticException) {
            null
        }
    }
}
