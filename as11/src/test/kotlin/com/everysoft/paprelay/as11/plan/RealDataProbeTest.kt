// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.as11.plan

import com.everysoft.paprelay.as11.decode.EventDecoder
import com.everysoft.paprelay.as11.decode.Firmware
import com.everysoft.paprelay.as11.decode.SummaryDecoder
import com.everysoft.paprelay.core.MachineTime
import com.everysoft.paprelay.core.nights.BreathingEvent
import com.everysoft.paprelay.core.nights.EventReport
import com.everysoft.paprelay.core.nights.NightFigure
import com.everysoft.paprelay.core.nights.NightReport
import java.io.File
import java.security.MessageDigest
import kotlin.test.Test

/**
 * Runs the 0.3.0 export over real downloads, for whoever holds them, and prints counts and
 * digests only: never a value, a time, a serial number or an address. It does nothing unless
 * `PAPRELAY_PROBE_DIR` names a directory laid out as the app's `raw` directory: the folders
 * `summary` and `respiratory_events`, each holding `.bin` files of one verified download each.
 *
 *     PAPRELAY_PROBE_DIR=/path/to/raw PAPRELAY_PROBE_SOFTWARE=SW04600.19.8.8.0.<build> \
 *         ./gradlew :as11:cleanTest :as11:test --offline --tests '*RealDataProbeTest*' -i | grep 'probe'
 */
class RealDataProbeTest {
    @Test
    fun `probe real downloads and print counts only`() {
        val directory = System.getenv("PAPRELAY_PROBE_DIR")?.let(::File)?.takeIf { it.isDirectory } ?: return
        val firmware = Firmware.reportedByMachine(System.getenv("PAPRELAY_PROBE_SOFTWARE") ?: "SW04600.19.8.8.0.0")
        fun files(name: String) = File(directory, name).listFiles { file -> file.name.endsWith(".bin") }.orEmpty().sortedBy { it.name }
        fun digest(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).take(8).joinToString("") { "%02x".format(it) }

        println("probe software accepted=${firmware.layoutsAccepted} compared=${firmware.perMinuteCompared}")
        val records = files("summary").flatMap { file ->
            val log = SummaryDecoder.decode(file.readBytes(), firmware)
            println("probe summary download: records=${log.entries.size} failed=${log.failures.size}")
            log.decoded
        }
        val now = records.maxOfOrNull { it.periodEnd } ?: MachineTime(0)
        val nights = records.mapNotNull { NightPlan.night(it, now) }.associateBy { it.date }.values.toList()
        println("probe nights: records=${records.size} nights=${nights.size} complete=${nights.count { it.complete }} refused=${records.size - records.mapNotNull { NightPlan.night(it, now) }.size}")
        println("probe nights with sessions listed: ${nights.count { it.sessionList != null }} of ${nights.count { it.usageMinutes > 0 }} used")
        for (figure in NightFigure.entries) {
            val count = nights.count { it.value(figure) != null }
            if (count > 0 || figure in NightPlan.FIGURES) println("probe figure ${figure.key}: nights=$count${if (figure in NightPlan.FIGURES) "" else " (not exported)"}")
        }
        val misread = records.flatMap { it.misreadMetrics }.groupingBy { it.name }.eachCount()
        println("probe misread metrics: $misread")
        println("probe unconfirmed metrics present: ${records.flatMap { it.unconfirmedMetrics.keys }.groupingBy { it.name }.eachCount()}")

        val events = ArrayList<BreathingEvent>()
        for (file in files("respiratory_events")) {
            val log = EventDecoder.respiratory(file.readBytes(), firmware)
            println("probe events download: records=${log.entries.size} failed=${log.failures.size} kinds=${log.decoded.groupingBy { it.kind?.name ?: "code ${it.code}" }.eachCount()}")
            log.decoded.mapNotNullTo(events) { EventPlan.event(log, it, ClockCorrection.NONE) }
        }
        val unique = events.associateBy { it.identity }.values.toList()
        println("probe events passed on: ${unique.size} (read ${events.size}) by kind ${unique.groupingBy { it.kind.key }.eachCount()}")
        println("probe events with a night: ${unique.count { EventReport.nightOf(it.machineTime.epochMs, nights) != null }}; with a length: ${unique.count { it.durationMs != null }}")
        // The tables themselves stay here; their digests show whether two runs agree.
        val nightsCsv = NightReport.csv(nights, NightPlan.FIGURES)
        val eventsCsv = EventReport.csv(unique, nights)
        println("probe paprelay_nights.csv lines=${nightsCsv.lines().size - 1} columns=${nightsCsv.lines().first().split(',').size} sha256/8=${digest(nightsCsv)}")
        println("probe paprelay_events.csv lines=${eventsCsv.lines().size - 1} sha256/8=${digest(eventsCsv)}")
        println("probe deterministic: ${NightReport.csv(nights.reversed(), NightPlan.FIGURES) == nightsCsv && EventReport.csv(unique.reversed(), nights) == eventsCsv}")
    }
}
