// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A "%%" is turned into "%" only where a string is formatted with arguments. Read without them, it
 * stays "%%" on the screen (as "95%%" once did); and a lone "%" in a string with arguments breaks
 * its formatting. Every text in the app is held to the rule for its kind.
 */
class StringsTest {
    /** A format placeholder: %s, %1$s, %1$d, %1$.1f and the like. */
    private val placeholder = Regex("%(\\d+\\$)?[-#+ 0,(]*\\d*(\\.\\d+)?[sdfxX]")

    private fun texts(): List<Pair<String, String>> {
        val file = listOf(File("src/main/res/values/strings.xml"), File("app/src/main/res/values/strings.xml")).first { it.exists() }
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val out = ArrayList<Pair<String, String>>()
        val strings = document.getElementsByTagName("string")
        for (i in 0 until strings.length) {
            val node = strings.item(i)
            out += node.attributes.getNamedItem("name").nodeValue to node.textContent
        }
        val items = document.getElementsByTagName("item")
        for (i in 0 until items.length) {
            val node = items.item(i)
            out += (node.parentNode.attributes.getNamedItem("name").nodeValue + "/" + node.attributes.getNamedItem("quantity").nodeValue) to node.textContent
        }
        return out
    }

    @Test
    fun `a percent sign is written the way the string is read`() {
        val all = texts()
        assertTrue(all.size > 100)
        val wrong = all.filter { (_, text) ->
            if (placeholder.containsMatchIn(text)) {
                // Formatted: every % is part of a placeholder or doubled.
                placeholder.replace(text, "").replace("%%", "").contains('%')
            } else {
                // Read as it is: a doubled % would show doubled.
                text.contains("%%")
            }
        }
        assertTrue(wrong.isEmpty(), "strings with a percent sign that will show wrongly: ${wrong.map { it.first }}")
    }

    @Test
    fun `the steps before the search ask for the serial number, and only the code prompt for the code`() {
        // In pairing mode the machine shows its serial number. The code appears only once the phone
        // has connected and started pairing: nothing shown before the search may tell the user to wait for it.
        val all = texts().toMap()
        val beforeSearch = listOf("pair_step_1", "pair_step_2", "pair_step_3", "pair_picture", "action_serial_shown", "machine_unpaired_hint", "machine_unreadable_hint")
        for (name in beforeSearch) {
            val text = all.getValue(name)
            assertTrue(!text.contains("code", ignoreCase = true), "$name speaks of the code before the search")
        }
        assertTrue(all.getValue("pair_step_3").contains("serial number"))
        assertTrue(all.getValue("action_serial_shown").contains("serial number"))
        // The machine's screens are named as it shows them, OK, Downloaded included.
        assertTrue(all.getValue("pair_step_2").contains("OK, Downloaded"))
        assertTrue(all.getValue("passkey_no_code").contains("OK, Downloaded"))
        // Once connected, the prompt says that the code is on the machine now.
        assertTrue(all.getValue("passkey_shown").contains("code"))
    }

    @Test
    fun `the preview says how long and how many, and never a rate or an index`() {
        val all = texts().toMap()
        val preview = all.filterKeys { it.startsWith("preview_") || it.startsWith("night_of_so_far") || it.startsWith("night_session_running") || it.startsWith("night_session_open") }
        assertTrue(preview.size >= 8, "${preview.keys}")
        for ((name, text) in preview) {
            for (word in listOf("AHI", "per hour", "for each hour", "index", "percentile")) {
                assertTrue(!text.contains(word, ignoreCase = true), "$name says $word")
            }
        }
        assertTrue(all.getValue("night_of_so_far").contains("so far"))
        // Not the moment the mask went on: it was still on then.
        assertTrue(all.getValue("preview_usage_running").contains("still on at"))
        // "Running" is what the machine said at the last read, never the present: each says the time.
        for (name in listOf("preview_usage_running", "preview_running", "timeline_running", "night_session_running", "night_session_running_spoken", "night_session_open_at", "night_session_open_at_spoken")) {
            assertTrue(Regex(" at %\\d\\\$s").containsMatchIn(all.getValue(name)), name)
            assertTrue(!all.getValue(name).contains("still running"), name)
        }
        assertTrue(all.getValue("preview_note").contains("midday"))
    }
}
