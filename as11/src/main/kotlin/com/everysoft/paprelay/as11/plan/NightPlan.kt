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
import com.everysoft.paprelay.core.nights.NightSession
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
    internal val indices: Map<SummaryIndex, NightFigure> = mapOf(
        SummaryIndex.AHI to NightFigure.AHI,
        SummaryIndex.APNEA to NightFigure.APNEA_INDEX,
        SummaryIndex.HYPOPNEA to NightFigure.HYPOPNEA_INDEX,
        SummaryIndex.OBSTRUCTIVE_APNEA to NightFigure.OBSTRUCTIVE_APNEA_INDEX,
        SummaryIndex.CENTRAL_APNEA to NightFigure.CENTRAL_APNEA_INDEX,
        SummaryIndex.UNKNOWN_APNEA to NightFigure.UNCLASSIFIED_APNEA_INDEX,
        SummaryIndex.RERA to NightFigure.RERA_INDEX,
    )

    internal class Source(val metric: SummaryMetric, val percentile: Percentile, val unit: PapUnit)

    /**
     * Where each figure comes from. A figure is given only while its metric is confirmed
     * ([SummaryMetric.confirmed]): the decoder serves no other, and [FIGURES] lists no other.
     * The lines for metrics not yet confirmed are here so that confirming one is a change of its
     * flag alone; [VERSION] follows, so the nights are then read again and rewritten.
     *
     * Not here at all, because more than a flag is needed (see docs/FIRST-DEVICE-RUN.md):
     * inspiratory duration (stored in thousandths of a second, finer than a night keeps), SpO2
     * and heart rate (no place among the five health records yet), and the whole-number fields
     * for Cheyne-Stokes respiration, spontaneous triggering and cycling, and whether the
     * humidifier and the heated tube were connected (their meaning has not been confirmed).
     */
    internal val metrics: Map<NightFigure, Source> = mapOf(
        NightFigure.LEAK_MEDIAN to Source(SummaryMetric.LEAK, Percentile.P50, PapUnit.LITRES_PER_MINUTE),
        NightFigure.LEAK_70 to Source(SummaryMetric.LEAK, Percentile.P70, PapUnit.LITRES_PER_MINUTE),
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
        NightFigure.RESPIRATORY_RATE_95 to Source(SummaryMetric.RESPIRATORY_RATE, Percentile.P95, PapUnit.BREATHS_PER_MINUTE),
        NightFigure.RESPIRATORY_RATE_MAX to Source(SummaryMetric.RESPIRATORY_RATE, Percentile.P100, PapUnit.BREATHS_PER_MINUTE),
        NightFigure.TIDAL_VOLUME_MEDIAN to Source(SummaryMetric.TIDAL_VOLUME, Percentile.P50, PapUnit.MILLILITRES),
        NightFigure.TIDAL_VOLUME_95 to Source(SummaryMetric.TIDAL_VOLUME, Percentile.P95, PapUnit.MILLILITRES),
        NightFigure.TIDAL_VOLUME_MAX to Source(SummaryMetric.TIDAL_VOLUME, Percentile.P100, PapUnit.MILLILITRES),
        NightFigure.MINUTE_VENTILATION_MEDIAN to Source(SummaryMetric.MINUTE_VENTILATION, Percentile.P50, PapUnit.LITRES_PER_MINUTE),
        NightFigure.MINUTE_VENTILATION_95 to Source(SummaryMetric.MINUTE_VENTILATION, Percentile.P95, PapUnit.LITRES_PER_MINUTE),
        NightFigure.MINUTE_VENTILATION_MAX to Source(SummaryMetric.MINUTE_VENTILATION, Percentile.P100, PapUnit.LITRES_PER_MINUTE),
        // Every flow in litres a minute, as leak is (stored in litres a second; times 60, exact).
        NightFigure.RESPIRATORY_FLOW_5 to Source(SummaryMetric.RESPIRATORY_FLOW, Percentile.P5, PapUnit.LITRES_PER_MINUTE),
        NightFigure.RESPIRATORY_FLOW_95 to Source(SummaryMetric.RESPIRATORY_FLOW, Percentile.P95, PapUnit.LITRES_PER_MINUTE),
        NightFigure.BLOWER_PRESSURE_5 to Source(SummaryMetric.BLOWER_PRESSURE, Percentile.P5, PapUnit.CM_H2O),
        NightFigure.BLOWER_PRESSURE_95 to Source(SummaryMetric.BLOWER_PRESSURE, Percentile.P95, PapUnit.CM_H2O),
        NightFigure.BLOWER_FLOW_MEDIAN to Source(SummaryMetric.BLOWER_FLOW, Percentile.P50, PapUnit.LITRES_PER_MINUTE),
        NightFigure.AMBIENT_HUMIDITY_MEDIAN to Source(SummaryMetric.AMBIENT_HUMIDITY, Percentile.P50, PapUnit.MILLIGRAMS_PER_LITRE),
        NightFigure.HUMIDIFIER_TEMPERATURE_MEDIAN to Source(SummaryMetric.HUMIDIFIER_TEMPERATURE, Percentile.P50, PapUnit.CELSIUS),
        NightFigure.HUMIDIFIER_POWER_MEDIAN to Source(SummaryMetric.HUMIDIFIER_POWER, Percentile.P50, PapUnit.PERCENT),
        NightFigure.HEATED_TUBE_TEMPERATURE_MEDIAN to Source(SummaryMetric.HEATED_TUBE_TEMPERATURE, Percentile.P50, PapUnit.CELSIUS),
        NightFigure.HEATED_TUBE_POWER_MEDIAN to Source(SummaryMetric.HEATED_TUBE_POWER, Percentile.P50, PapUnit.PERCENT),
        NightFigure.TARGET_VENTILATION_MEDIAN to Source(SummaryMetric.TARGET_MINUTE_VENTILATION, Percentile.P50, PapUnit.LITRES_PER_MINUTE),
        NightFigure.TARGET_VENTILATION_95 to Source(SummaryMetric.TARGET_MINUTE_VENTILATION, Percentile.P95, PapUnit.LITRES_PER_MINUTE),
        NightFigure.TARGET_VENTILATION_MAX to Source(SummaryMetric.TARGET_MINUTE_VENTILATION, Percentile.P100, PapUnit.LITRES_PER_MINUTE),
        NightFigure.ALVEOLAR_VENTILATION_MEDIAN to Source(SummaryMetric.ALVEOLAR_MINUTE_VENTILATION, Percentile.P50, PapUnit.LITRES_PER_MINUTE),
        NightFigure.ALVEOLAR_VENTILATION_95 to Source(SummaryMetric.ALVEOLAR_MINUTE_VENTILATION, Percentile.P95, PapUnit.LITRES_PER_MINUTE),
        NightFigure.ALVEOLAR_VENTILATION_MAX to Source(SummaryMetric.ALVEOLAR_MINUTE_VENTILATION, Percentile.P100, PapUnit.LITRES_PER_MINUTE),
        NightFigure.IE_RATIO_MEDIAN to Source(SummaryMetric.IE_RATIO, Percentile.P50, PapUnit.PERCENT),
        NightFigure.IE_RATIO_95 to Source(SummaryMetric.IE_RATIO, Percentile.P95, PapUnit.PERCENT),
        NightFigure.IE_RATIO_MAX to Source(SummaryMetric.IE_RATIO, Percentile.P100, PapUnit.PERCENT),
    )

    /** The figures a night can have, in the order of [NightFigure]: the table has a column for each, and no others. */
    public val FIGURES: List<NightFigure> = NightFigure.entries.filter { figure ->
        figure in indices.values || metrics[figure]?.metric?.confirmed == true
    }

    /**
     * Raised by hand when the way a night is made from a record changes in any way other than the
     * set of [FIGURES] and their units (which [VERSION] follows by itself). 2: the sessions one by
     * one (0.3.0).
     */
    private const val RULES = 2

    /**
     * What a night made by this plan holds. Nights kept under another version are read from the
     * machine again, as far back as it holds them, so that they gain what they lacked.
     */
    public val VERSION: String = run {
        // Each figure with its unit: a figure given in another unit is another figure to whoever reads it.
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(FIGURES.joinToString(",") { "${it.key}:${it.unit.name}" }.toByteArray(Charsets.US_ASCII))
        "$RULES-" + digest.take(8).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    private const val HOUR_MS = 3_600_000L
    private const val CLEAR_MARGIN_MINUTES = 12 * 60
    private const val DAY_MS = 24 * HOUR_MS
    private val HUNDRED = BigDecimal(100)
    private val SIXTY = BigDecimal(60)
    private val THOUSAND = BigDecimal(1000)

    /**
     * @param machineNow the machine's clock when the record was downloaded; a day that has not
     *   ended by it is marked as still running.
     * @param correction how the sessions' starts are put into true time.
     * @return the night, or null if the record cannot stand as one: its software is not one whose data is read,
     *   it has no duration, its session list is not whole, or its day does not begin at a local noon.
     */
    public fun night(record: SummaryRecord, machineNow: MachineTime, correction: ClockCorrection = ClockCorrection.NONE): Night? {
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
                // The decoder serves confirmed metrics only; asked again here, so that no figure can come from another.
                if (!source.metric.confirmed) continue
                record.metrics[source.metric]?.get(source.percentile)?.let { hundredths(it, source.unit, figure) }?.let { figures[figure] = it }
            }
        }
        // One by one, only when every session has its length: a list with gaps is not given.
        val sessionList = record.sessions.takeIf { list -> list.all { it.durationMinutes != null } }
            ?.map { NightSession(it.start, it.durationMinutes!!, correction.of(it.start)) }
        return Night(
            date = date,
            zoneOffsetMinutes = offsetMinutes,
            usageMinutes = usage,
            sessions = record.sessions.size,
            hundredths = figures,
            complete = record.periodEnd <= machineNow,
            hours = hours,
            sessionList = sessionList,
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
            // Tidal volume in millilitres, as it is usually given (stored in hundredths of a litre; times 1000, exact).
            quantity.unit == PapUnit.LITRES && unit == PapUnit.MILLILITRES -> quantity.exact().multiply(THOUSAND)
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
