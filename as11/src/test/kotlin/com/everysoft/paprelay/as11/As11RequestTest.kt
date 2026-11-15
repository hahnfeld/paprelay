// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.as11

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class As11RequestTest {
    private val samples: List<As11Request> = listOf(
        As11Request.StartKeyExchange(ByteArray(256) { 1 }),
        As11Request.ConfirmKeyExchange(ByteArray(32)),
        As11Request.RequestSession("client-1"),
        As11Request.CheckSessionIntegrity(ByteArray(32)),
        As11Request.Get(listOf(GetItem.SERIAL_NUMBER, GetItem.PRODUCT_NAME)),
        As11Request.GetVersion,
        As11Request.GetDateTime,
        As11Request.StartSpool(SpoolName.SUMMARY, SpoolAddress.FromMachineTime(1_782_400_080_000)),
        As11Request.PullSpoolFragments(12),
        As11Request.DiscardPairKey,
    )

    /** Every concrete request type that exists, found from the compiled class hierarchy. */
    private fun concreteTypes(type: Class<*>): Set<Class<*>> {
        val permitted = type.permittedSubclasses ?: return setOf(type)
        return permitted.flatMap { concreteTypes(it) }.toSet()
    }

    @Test
    fun `the command set is exactly the read-only allow-list`() {
        val methods = samples.map { it.method }.toSet()
        assertEquals(
            setOf(
                "StartKeyExchange", "ConfirmKeyExchange", "RequestSession", "CheckSessionIntegrity",
                "Get", "GetVersion", "GetDateTime", "StartSpool", "PullSpoolFragments", "DiscardPairKey",
            ),
            methods,
        )
        // If someone adds a request type, this fails until it is reviewed and listed above.
        assertEquals(samples.map { it.javaClass }.toSet(), concreteTypes(As11Request::class.java))
        for (forbidden in listOf("Set", "EnterTherapy", "EnterStandby", "EraseData", "EnterMaskFit", "StartStream")) {
            assertTrue(forbidden !in methods)
        }
    }

    @Test
    fun `members are serialised in the pinned order`() {
        assertEquals(
            """{"id":20,"jsonrpc":"1.0","method":"GetDateTime"}""",
            As11Request.GetDateTime.encode(20),
        )
        assertEquals(
            """{"id":12,"jsonrpc":"1.0","method":"Get","params":["SerialNumber","ProductName"]}""",
            As11Request.Get(listOf(GetItem.SERIAL_NUMBER, GetItem.PRODUCT_NAME)).encode(12),
        )
        assertEquals(
            """{"id":30,"jsonrpc":"1.0","method":"StartSpool","params":{"spoolAddress":{"Summary":""" +
                """{"fromDateTime":"2026-06-25T15:08:00.000Z"}},"maxSpoolSize":100000}}""",
            As11Request.StartSpool(SpoolName.SUMMARY, SpoolAddress.FromMachineTime(1_782_400_080_000)).encode(30),
        )
        assertEquals(
            """{"id":31,"jsonrpc":"1.0","method":"PullSpoolFragments","params":{"spoolId":12,""" +
                """"maxFragmentSize":2808,"maxNotifications":0}}""",
            As11Request.PullSpoolFragments(12).encode(31),
        )
    }

    @Test
    fun `the method name never falls in the first cipher block, which an altered IV could rewrite`() {
        // Block 0 of the plaintext is a 2-byte length plus the first 14 JSON bytes.
        // Only that block can be rewritten undetectably by altering the IV.
        for (request in samples) {
            for (id in listOf(0, 1, 9, 1000, 999_999_999)) {
                val json = request.encode(id)
                val methodValueAt = json.indexOf("\"method\":\"") + "\"method\":\"".length
                assertTrue(methodValueAt >= 14, "${request.method} id=$id starts at $methodValueAt")
                assertEquals(request.method, Json.parseToJsonElement(json).jsonObject["method"].let { (it as JsonPrimitive).content })
            }
        }
    }

    @Test
    fun `a continuation address must name the same log and be small`() {
        val good = buildJsonObject { put("Summary", buildJsonObject { put("cursor", JsonPrimitive(3)) }) }
        val json = As11Request.StartSpool(SpoolName.SUMMARY, SpoolAddress.Continuation(good)).encode(1)
        assertTrue(json.contains("""{"Summary":{"cursor":3}}"""))

        val refused = assertFailsWith<As11ProtocolException> {
            As11Request.StartSpool(SpoolName.ONE_MINUTE, SpoolAddress.Continuation(good)).encode(1)
        }
        // The conversation is fine; it is this answer that is unusable, so reconnecting would not help.
        assertEquals(As11Recovery.DO_NOT_RETRY, refused.recovery)
        val twoLogs = JsonObject(good + ("TherapyOneMinutePeriodic" to JsonPrimitive(1)))
        assertFailsWith<As11ProtocolException> {
            As11Request.StartSpool(SpoolName.SUMMARY, SpoolAddress.Continuation(twoLogs)).encode(1)
        }
        val huge = buildJsonObject { put("Summary", JsonPrimitive("x".repeat(600))) }
        assertFailsWith<As11ProtocolException> {
            As11Request.StartSpool(SpoolName.SUMMARY, SpoolAddress.Continuation(huge)).encode(1)
        }
    }

    @Test
    fun `a client id is bounded and escaped, whatever characters the machine chose`() {
        val awkward = "a\",\"method\":\"Set"
        val json = As11Request.RequestSession(awkward).encode(7)
        val parsed = Json.parseToJsonElement(json).jsonObject
        assertEquals("RequestSession", (parsed["method"] as JsonPrimitive).content)
        assertEquals(awkward, (parsed["params"]!!.jsonObject["clientId"] as JsonPrimitive).content)

        assertFailsWith<IllegalArgumentException> { As11Request.RequestSession("") }
        assertFailsWith<IllegalArgumentException> { As11Request.RequestSession("line\nbreak") }
        assertFailsWith<IllegalArgumentException> { As11Request.RequestSession("x".repeat(129)) }
    }

    @Test
    fun `a start time outside any plausible range is refused`() {
        assertFailsWith<IllegalArgumentException> { SpoolAddress.FromMachineTime(Long.MAX_VALUE) }
        assertFailsWith<IllegalArgumentException> { SpoolAddress.FromMachineTime(0) }
    }
}
