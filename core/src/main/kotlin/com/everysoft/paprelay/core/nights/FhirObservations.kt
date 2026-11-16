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
 * One Observation per figure, each with a fixed id made of the machine, the night and the
 * figure, so that storing a night again replaces what was stored before and never adds to it.
 *
 * The category is `laboratory`. These are not laboratory results; they are a therapy
 * device's nightly totals. But Android's health-record store keeps only Observations that
 * are vital signs (a fixed list of codes, none of which is one of these), laboratory
 * results, social history or pregnancy, and drops the rest. Laboratory is the one that
 * takes a coded number with a unit, which is what these are. The text of each record says
 * plainly where it comes from.
 *
 * Coding: every figure carries PAPRelay's own code, which is exact, and a LOINC code as well
 * where LOINC has one that means the same thing.
 */
public object FhirObservations {
    public const val FHIR_VERSION: String = "4.0.1"

    /** PAPRelay's own code system for the figures. A name, not an address that answers. */
    public const val CODE_SYSTEM: String = "https://github.com/hahnfeld/paprelay/fhir/CodeSystem/cpap-night"
    private const val LOINC = "http://loinc.org"
    private const val UCUM = "http://unitsofmeasure.org"
    private const val CATEGORY_SYSTEM = "http://terminology.hl7.org/CodeSystem/observation-category"
    private const val DEVICE = "ResMed AirSense 11 (copied by PAPRelay)"
    private const val USAGE_KEY = "usage-hours"
    private const val USAGE_LABEL = "CPAP therapy usage"
    private val ID_PART = Regex("[A-Za-z0-9-]{1,24}")
    private val COMPACT_DATE = DateTimeFormatter.BASIC_ISO_DATE
    private val TIME = DateTimeFormatter.ISO_OFFSET_DATE_TIME

    /**
     * @param machineTag the non-identifying name of the machine the night came from; part of
     *   each id, so that two machines' nights cannot replace one another.
     * @throws IllegalArgumentException if [night] is not complete, or the tag cannot be part of an id.
     */
    public fun forNight(night: Night, machineTag: String): List<FhirResource> {
        require(night.complete) { "a day still running is not passed on" }
        require(ID_PART.matches(machineTag)) { "the machine tag cannot be part of a record id" }
        val out = ArrayList<FhirResource>()
        out += observation(night, machineTag, USAGE_KEY, USAGE_LABEL, null, night.usageHours, NightUnit.HOURS)
        for ((figure, _) in night.hundredths) {
            out += observation(night, machineTag, figure.key, figure.label, figure.loinc, night.value(figure)!!, figure.unit)
        }
        return out
    }

    /** The ids [forNight] gives a night's records, whatever figures it has: for removing them. */
    public fun idsFor(night: Night, machineTag: String): List<String> =
        (listOf(USAGE_KEY) + NightFigure.entries.map { it.key }).map { id(night, machineTag, it) }

    private fun id(night: Night, machineTag: String, key: String): String = "$machineTag-${COMPACT_DATE.format(night.date)}-$key"

    private fun observation(night: Night, machineTag: String, key: String, label: String, loinc: String?, value: BigDecimal, unit: NightUnit): FhirResource {
        val id = id(night, machineTag, key)
        require(id.length <= MAX_ID) { "a record id too long" }
        val codings = buildList {
            add("""{"system":${quote(CODE_SYSTEM)},"code":${quote(key)},"display":${quote(label)}}""")
            if (loinc != null) add("""{"system":${quote(LOINC)},"code":${quote(loinc)}}""")
        }
        val json = buildString {
            append("""{"resourceType":"Observation","id":""").append(quote(id))
            append(""","status":"final"""")
            append(""","category":[{"coding":[{"system":${quote(CATEGORY_SYSTEM)},"code":"laboratory","display":"Laboratory"}]}]""")
            append(""","code":{"coding":[""").append(codings.joinToString(",")).append("""],"text":""").append(quote("$label (CPAP machine, nightly)")).append('}')
            append(""","effectivePeriod":{"start":""").append(quote(TIME.format(night.start))).append(""","end":""").append(quote(TIME.format(night.end))).append('}')
            append(""","valueQuantity":{"value":""").append(value.toPlainString())
            append(""","unit":""").append(quote(unit.symbol)).append(""","system":""").append(quote(UCUM)).append(""","code":""").append(quote(unit.ucum)).append('}')
            append(""","device":{"display":""").append(quote(DEVICE)).append("}}")
        }
        return FhirResource(id, json)
    }

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

    private const val MAX_ID = 64
}
