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
}
