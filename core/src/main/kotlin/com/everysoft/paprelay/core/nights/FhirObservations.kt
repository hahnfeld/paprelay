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
 * breathing. Figures that describe the machine rather than the person (humidifier, heated
 * tube, blower; [NightFigure.healthRecord] false) are not written here. Each has one headline value (the figure usually looked at) and carries the
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
 * Coding: PAPRelay's own code system first, which says exactly what each figure is (the indices
 * per hour of therapy use). Where LOINC's Positive airway pressure panel (69999-1), of measures a
 * device reports over 24 hours, has a code for the same measure, it is given beside it ([LOINC]);
 * nowhere else. The sleep-study codes (apnea, hypopnea and RERA indices scored in a sleep study) and the vital-signs
 * codes ([VITAL_SIGNS], which would move a record out of lab results) are never given.
 */
public object FhirObservations {
    public const val FHIR_VERSION: String = "4.0.1"

    /**
     * Raised whenever what is written for a given night changes (a label, a unit, a grouping),
     * so that nights already stored are recognised as out of date and stored again. 4 (0.4.1-test): a
     * record may name the Patient record. 5 (0.4.1): LOINC codes beside the own ones.
     */
    public const val FORMAT: Int = 5

    /**
     * The id of the one Patient record, the person whose nights these are, written only while they
     * have given their name and date of birth. Fixed, so that writing it again replaces it.
     */
    public const val PATIENT_ID: String = "paprelay-patient"

    /** How an Observation names its Patient: a relative FHIR reference, as Health Connect resolves it within the data source. */
    public const val PATIENT_REFERENCE: String = "Patient/$PATIENT_ID"

    /** The LOINC codes given beside PAPRelay's own: these, and no others (the tests hold [LOINC] to them). */
    public val LOINC_CODES: Set<String> = setOf(
        "69988-4", "69990-0", "69997-5", "69986-8", "70002-1", "70003-9", "70001-3", "69998-3", "70000-5", "69987-6",
    )

    /** Health Connect's vital-signs codes: a record carrying one would be filed as a vital sign, not a lab result. */
    public val VITAL_SIGNS: Set<String> = setOf(
        "85353-1", "9279-1", "8867-4", "2708-6", "8310-5", "8302-2", "9843-4", "29463-7", "39156-5", "85354-9", "8480-6", "8462-4",
    )

    /**
     * The LOINC code and display given beside PAPRelay's own code, by that code; the displays are
     * LOINC's own (LOINC 2.83). Usage minutes, sessions, the other indices, leak 70th, expiratory
     * and mask pressure and the whole breathing record have none.
     */
    private val LOINC: Map<String, Pair<String, String>> = mapOf(
        USAGE_KEY to ("69988-4" to "Positive airway pressure device usage 24 hour"),
        NightFigure.AHI.key to ("69990-0" to "Apnea hypopnea index 24 hour"),
        NightFigure.OBSTRUCTIVE_APNEA_INDEX.key to ("70002-1" to "Obstructive apnea index 24 hour"),
        NightFigure.CENTRAL_APNEA_INDEX.key to ("70003-9" to "Central apnea index 24 hour"),
        NightFigure.LEAK_95.key to ("69997-5" to "Leak.95% 24 hour maximum Positive airway pressure device"),
        NightFigure.LEAK_MEDIAN.key to ("70001-3" to "Leak 24 hour median Positive airway pressure device"),
        NightFigure.LEAK_MAX.key to ("69998-3" to "Positive airway pressure device Leak"),
        NightFigure.THERAPY_PRESSURE_95.key to ("69986-8" to "Pressure.continuous positive airway.95% 24 hour maximum Respiratory system"),
        NightFigure.THERAPY_PRESSURE_MEDIAN.key to ("70000-5" to "Continuous positive airway pressure 24 hour median Respiratory system"),
        NightFigure.THERAPY_PRESSURE_MAX.key to ("69987-6" to "Continuous positive airway pressure 24 hour maximum Respiratory system"),
    )

    /** PAPRelay's own code system for the figures. A name, not an address that answers. */
    public const val CODE_SYSTEM: String = "https://github.com/hahnfeld/paprelay/fhir/CodeSystem/cpap-night"
    private const val UCUM = "http://unitsofmeasure.org"
    private const val LOINC_SYSTEM = "http://loinc.org"
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
        Group(
            "leak", "CPAP mask leak in the night, 95th percentile", "CPAP mask leak in the night", NightFigure.LEAK_95,
            listOf(NightFigure.LEAK_MEDIAN, NightFigure.LEAK_70, NightFigure.LEAK_MAX),
        ),
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
            listOf(
                NightFigure.RESPIRATORY_RATE_95, NightFigure.RESPIRATORY_RATE_MAX,
                NightFigure.TIDAL_VOLUME_MEDIAN, NightFigure.TIDAL_VOLUME_95, NightFigure.TIDAL_VOLUME_MAX,
                NightFigure.MINUTE_VENTILATION_MEDIAN, NightFigure.MINUTE_VENTILATION_95, NightFigure.MINUTE_VENTILATION_MAX,
                NightFigure.RESPIRATORY_FLOW_5, NightFigure.RESPIRATORY_FLOW_95,
                // Not yet confirmed, so no night has them yet (see NightFigure).
                NightFigure.TARGET_VENTILATION_MEDIAN, NightFigure.TARGET_VENTILATION_95, NightFigure.TARGET_VENTILATION_MAX,
                NightFigure.ALVEOLAR_VENTILATION_MEDIAN, NightFigure.ALVEOLAR_VENTILATION_95, NightFigure.ALVEOLAR_VENTILATION_MAX,
                NightFigure.IE_RATIO_MEDIAN, NightFigure.IE_RATIO_95, NightFigure.IE_RATIO_MAX,
            ),
        ),
    )

    init {
        // Every figure about the person belongs to exactly one group, or it would never be passed on;
        // and none about the machine (humidifier, tube, blower) is put among them.
        val placed = GROUPS.flatMap { listOf(it.headline) + it.others }
        check(placed.size == placed.toSet().size && placed.toSet() == NightFigure.entries.filter { it.healthRecord }.toSet()) {
            "the groups do not cover the figures once each"
        }
    }

    /**
     * @param machineTag the non-identifying name of the machine the night came from; part of
     *   each id, so that two machines' nights cannot replace one another.
     * @param withSubject whether each record names the Patient ([PATIENT_REFERENCE]): only while the
     *   person's details are set and the Patient record is written.
     * @throws IllegalArgumentException if [night] is not complete, or the tag cannot be part of an id.
     */
    public fun forNight(night: Night, machineTag: String, withSubject: Boolean = false): List<FhirResource> {
        require(night.complete) { "a day still running is not passed on" }
        require(ID_PART.matches(machineTag)) { "the machine tag cannot be part of a record id" }
        val out = ArrayList<FhirResource>()
        out += observation(
            night, machineTag, withSubject, USAGE_KEY, USAGE_LABEL,
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
                observation(night, machineTag, withSubject, group.key, group.label, quantity(headline, group.headline.unit), others, group.headline.key)
            } else {
                observation(night, machineTag, withSubject, group.key, group.plain, null, others, group.key)
            }
        }
        return out
    }

    /**
     * The person as a FHIR R4 Patient: their name, as given and family names and the whole of it, and
     * their date of birth. Nothing else: no identifier, no address, no gender.
     */
    public fun patient(details: PatientDetails): FhirResource {
        val json = buildString {
            append("""{"resourceType":"Patient","id":""").append(quote(PATIENT_ID))
            append(""","name":[{"use":"official","family":""").append(quote(details.family))
            append(""","given":[""").append(details.given.joinToString(",") { quote(it) }).append(']')
            append(""","text":""").append(quote(details.text)).append("}]")
            append(""","birthDate":""").append(quote(details.birthDate.toString())).append('}')
        }
        return FhirResource(PATIENT_ID, json)
    }

    /** Every id [forNight] can give a night's records, whatever figures it has: for removing those it no longer has. */
    public fun idsFor(night: Night, machineTag: String): List<String> = (listOf(USAGE_KEY) + GROUPS.map { it.key }).map { id(night, machineTag, it) }

    private fun id(night: Night, machineTag: String, key: String): String = "$machineTag-${COMPACT_DATE.format(night.date)}-$key"

    private fun observation(
        night: Night,
        machineTag: String,
        withSubject: Boolean,
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
            if (withSubject) append(""","subject":{"reference":""").append(quote(PATIENT_REFERENCE)).append('}')
            append(""","effectivePeriod":{"start":""").append(quote(TIME.format(night.start))).append(""","end":""").append(quote(TIME.format(night.end))).append('}')
            if (value != null) append(""","valueQuantity":""").append(value)
            append(""","device":{"display":""").append(quote(DEVICE)).append('}')
            if (components.isNotEmpty()) append(""","component":[""").append(components.joinToString(",")).append(']')
            append('}')
        }
        return FhirResource(id, json)
    }

    private fun component(key: String, label: String, value: String): String = """{"code":${concept(key, label)},"valueQuantity":$value}"""

    // The own code first; LOINC's beside it, where it has the same measure.
    private fun concept(key: String, label: String): String {
        val loinc = LOINC[key]?.let { (code, display) -> """,{"system":${quote(LOINC_SYSTEM)},"code":${quote(code)},"display":${quote(display)}}""" }.orEmpty()
        return """{"coding":[{"system":${quote(CODE_SYSTEM)},"code":${quote(key)},"display":${quote(label)}}$loinc],"text":${quote(label)}}"""
    }

    private fun quantity(value: BigDecimal, unit: NightUnit): String =
        """{"value":${value.toPlainString()},"unit":${quote(unit.symbol)},"system":${quote(UCUM)},"code":${quote(unit.ucum)}}"""

    /** A JSON string, with quotes, backslashes and control characters escaped: names given by the person pass through here. */
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
