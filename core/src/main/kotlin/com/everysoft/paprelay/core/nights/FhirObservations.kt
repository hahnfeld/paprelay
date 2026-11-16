// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.core.nights

import java.math.BigDecimal
import java.time.format.DateTimeFormatter

/** One FHIR resource ready to be stored: its id, and itself as JSON. */
public class FhirResource(public val id: String, public val json: String) {
    /** Shows the id only: the JSON holds health values. */
    override fun toString(): String = "FhirResource($id)"
}

/**
 * A night's figures as FHIR R4 Observation resources, for a phone's health-record store.
 *
 * A night becomes at most five Observations: usage, the event indices, leak, pressure and
 * breathing. Each has one headline value (the figure usually looked at) and carries the
 * rest of its group as components, so that a simple reader shows something sensible and a
 * thorough one has everything. Each has a fixed id made of the machine, the night and the
 * group, so that storing a night again replaces what was stored before and never adds to it.
 *
 * The category is `laboratory`. These are not laboratory results; they are a therapy
 * device's nightly totals. But Android's health-record store keeps only Observations that
 * are vital signs (a fixed list of codes, none of which is one of these), laboratory
 * results, social history or pregnancy, and takes no others. Laboratory is the one that
 * takes a coded number with a unit, which is what these are. The text of each record says
 * plainly what it is and where it comes from.
 *
 * Coding: PAPRelay's own code system only. The standard codes for these names (LOINC's
 * polysomnography panel) mean indices scored in a sleep study, per hour of sleep; a therapy
 * machine's residual indices per hour of use are another thing, and are not given those codes.
 */
public object FhirObservations {
    public const val FHIR_VERSION: String = "4.0.1"

    /**
     * Raised whenever what is written for a given night changes (a label, a unit, a grouping),
     * so that nights already stored are recognised as out of date and stored again.
     */
    public const val FORMAT: Int = 1

    /** PAPRelay's own code system for the figures. A name, not an address that answers. */
    public const val CODE_SYSTEM: String = "https://github.com/hahnfeld/paprelay/fhir/CodeSystem/cpap-night"
    private const val UCUM = "http://unitsofmeasure.org"
    private const val CATEGORY_SYSTEM = "http://terminology.hl7.org/CodeSystem/observation-category"
    private const val DEVICE = "ResMed AirSense 11 (copied by PAPRelay)"
    private const val MAX_ID = 64
    private val ID_PART = Regex("[A-Za-z0-9-]{1,24}")
    private val COMPACT_DATE = DateTimeFormatter.BASIC_ISO_DATE
    private val TIME = DateTimeFormatter.ISO_OFFSET_DATE_TIME

    /** One Observation: its headline figure, and the others it carries as components. */
    private class Group(val key: String, val label: String, val plain: String, val headline: NightFigure, val others: List<NightFigure>)

    private const val USAGE_KEY = "usage"
    private const val USAGE_LABEL = "CPAP therapy usage in the night (hours)"
    private const val USAGE_MINUTES_KEY = "usage-minutes"
    private const val USAGE_MINUTES_LABEL = "CPAP therapy usage in the night (minutes)"
    private const val SESSIONS_KEY = "sessions"
    private const val SESSIONS_LABEL = "Times the mask was put on in the night"

    private val GROUPS = listOf(
        Group(
            "events", NightFigure.AHI.label, "Residual breathing events on CPAP therapy in the night", NightFigure.AHI,
            listOf(
                NightFigure.APNEA_INDEX, NightFigure.OBSTRUCTIVE_APNEA_INDEX, NightFigure.CENTRAL_APNEA_INDEX,
                NightFigure.UNCLASSIFIED_APNEA_INDEX, NightFigure.HYPOPNEA_INDEX, NightFigure.RERA_INDEX,
            ),
        ),
        Group("leak", "CPAP mask leak in the night, 95th percentile", "CPAP mask leak in the night", NightFigure.LEAK_95, listOf(NightFigure.LEAK_MEDIAN, NightFigure.LEAK_MAX)),
        Group(
            "pressure", "CPAP therapy pressure in the night, 95th percentile", "CPAP pressure in the night", NightFigure.THERAPY_PRESSURE_95,
            listOf(
                NightFigure.THERAPY_PRESSURE_MEDIAN, NightFigure.THERAPY_PRESSURE_MAX,
                NightFigure.EXPIRATORY_PRESSURE_MEDIAN, NightFigure.EXPIRATORY_PRESSURE_95, NightFigure.EXPIRATORY_PRESSURE_MAX,
                NightFigure.MASK_PRESSURE_MEDIAN, NightFigure.MASK_PRESSURE_95, NightFigure.MASK_PRESSURE_MAX,
            ),
        ),
        Group(
            "breathing", "Respiratory rate during CPAP therapy in the night, median", "Breathing during CPAP therapy in the night", NightFigure.RESPIRATORY_RATE_MEDIAN,
            listOf(NightFigure.TIDAL_VOLUME_MEDIAN, NightFigure.MINUTE_VENTILATION_MEDIAN),
        ),
    )

    init {
        // Every figure belongs to exactly one group, or it would never be passed on.
        val placed = GROUPS.flatMap { listOf(it.headline) + it.others }
        check(placed.size == placed.toSet().size && placed.toSet() == NightFigure.entries.toSet()) { "the groups do not cover the figures once each" }
    }

    /**
     * @param machineTag the non-identifying name of the machine the night came from; part of
     *   each id, so that two machines' nights cannot replace one another.
     * @throws IllegalArgumentException if [night] is not complete, or the tag cannot be part of an id.
     */
    public fun forNight(night: Night, machineTag: String): List<FhirResource> {
        require(night.complete) { "a day still running is not passed on" }
        require(ID_PART.matches(machineTag)) { "the machine tag cannot be part of a record id" }
        val out = ArrayList<FhirResource>()
        out += observation(
            night, machineTag, USAGE_KEY, USAGE_LABEL,
            quantity(night.usageHours, NightUnit.HOURS),
            listOf(
                component(USAGE_MINUTES_KEY, USAGE_MINUTES_LABEL, quantity(BigDecimal(night.usageMinutes), NightUnit.MINUTES)),
                component(SESSIONS_KEY, SESSIONS_LABEL, """{"value":${night.sessions},"unit":"times","system":${quote(UCUM)},"code":"{times}"}"""),
            ),
        )
        for (group in GROUPS) {
            val headline = night.value(group.headline)
            val others = group.others.mapNotNull { figure -> night.value(figure)?.let { component(figure.key, figure.label, quantity(it, figure.unit)) } }
            // A group none of whose figures the night has is not written at all.
            if (headline == null && others.isEmpty()) continue
            // With its headline figure the record is named for that figure, whose value it carries. Without
            // it the record is named for the group alone, and says no more than that about what it holds.
            out += if (headline != null) {
                observation(night, machineTag, group.key, group.label, quantity(headline, group.headline.unit), others, group.headline.key)
            } else {
                observation(night, machineTag, group.key, group.plain, null, others, group.key)
            }
        }
        return out
    }

    /** Every id [forNight] can give a night's records, whatever figures it has: for removing those it no longer has. */
    public fun idsFor(night: Night, machineTag: String): List<String> = (listOf(USAGE_KEY) + GROUPS.map { it.key }).map { id(night, machineTag, it) }

    private fun id(night: Night, machineTag: String, key: String): String = "$machineTag-${COMPACT_DATE.format(night.date)}-$key"

    private fun observation(
        night: Night,
        machineTag: String,
        key: String,
        label: String,
        value: String?,
        components: List<String>,
        code: String = key,
    ): FhirResource {
        val id = id(night, machineTag, key)
        require(id.length <= MAX_ID) { "a record id too long" }
        val json = buildString {
            append("""{"resourceType":"Observation","id":""").append(quote(id))
            append(""","status":"final"""")
            append(""","category":[{"coding":[{"system":${quote(CATEGORY_SYSTEM)},"code":"laboratory","display":"Laboratory"}]}]""")
            append(""","code":""").append(concept(code, label))
            append(""","effectivePeriod":{"start":""").append(quote(TIME.format(night.start))).append(""","end":""").append(quote(TIME.format(night.end))).append('}')
            if (value != null) append(""","valueQuantity":""").append(value)
            append(""","device":{"display":""").append(quote(DEVICE)).append('}')
            if (components.isNotEmpty()) append(""","component":[""").append(components.joinToString(",")).append(']')
            append('}')
        }
        return FhirResource(id, json)
    }

    private fun component(key: String, label: String, value: String): String = """{"code":${concept(key, label)},"valueQuantity":$value}"""

    private fun concept(key: String, label: String): String =
        """{"coding":[{"system":${quote(CODE_SYSTEM)},"code":${quote(key)},"display":${quote(label)}}],"text":${quote(label)}}"""

    private fun quantity(value: BigDecimal, unit: NightUnit): String =
        """{"value":${value.toPlainString()},"unit":${quote(unit.symbol)},"system":${quote(UCUM)},"code":${quote(unit.ucum)}}"""

    /** A JSON string. Everything quoted here is a constant or made of letters, digits and punctuation chosen above. */
    private fun quote(text: String): String = buildString {
        append('"')
        for (char in text) {
            when {
                char == '"' -> append("\\\"")
                char == '\\' -> append("\\\\")
                char < ' ' -> append("\\u%04x".format(char.code))
                else -> append(char)
            }
        }
        append('"')
    }
}
