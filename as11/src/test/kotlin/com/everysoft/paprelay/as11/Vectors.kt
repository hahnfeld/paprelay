// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.as11

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Known answers produced by tools/gen_protocol_vectors.py (Python and OpenSSL). */
internal object Vectors {
    private val root: JsonObject = Json.parseToJsonElement(
        Vectors::class.java.getResourceAsStream("/protocol_vectors.json")!!.readBytes().decodeToString(),
    ).jsonObject

    fun list(name: String): List<JsonObject> = root[name]!!.jsonArray.map { it.jsonObject }
    fun obj(name: String): JsonObject = root[name]!!.jsonObject
}

internal fun JsonObject.str(name: String): String = this[name]!!.jsonPrimitive.content
internal fun JsonObject.bytes(name: String): ByteArray = str(name).let { if (it.isEmpty()) ByteArray(0) else Hex.decode(it, 1 shl 20) }
