// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.as11

import com.everysoft.paprelay.as11.sim.FakeAirSense11
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import java.security.SecureRandom
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class As11ClientTest {
    private val machine = FakeAirSense11(passkey = "0420")
    private val from = SpoolAddress.FromMachineTime(1_782_400_080_000)

    private fun TestScope.connect(chunkSize: Int = 244): As11Client =
        As11Client(machine.connect(chunkSize), backgroundScope, SecureRandom(), nowMs = { currentTime })

    /** Pairs on one link, then opens a fresh link with a session, as the app does. */
    private suspend fun TestScope.securedClient(chunkSize: Int = 244): As11Client {
        val credentials = connect(chunkSize).pair { "0420" }
        return connect(chunkSize).also { it.startSession(credentials, NonceRegistry.AcceptAll) }
    }

    private fun bytes(size: Int, seed: Long = 7): ByteArray = ByteArray(size).also { Random(seed).nextBytes(it) }

    @Test
    fun `pairs, opens a session and reads identity over small and large chunks`() = runTest {
        for (chunk in listOf(20, 23, 244, 512)) {
            val client = securedClient(chunk)
            assertTrue(client.isSecure)
            val identity = client.get(listOf(GetItem.SERIAL_NUMBER, GetItem.PRODUCT_NAME))
            assertEquals(machine.serialNumber, identity["SerialNumber"]!!.jsonPrimitive.content)
            assertNotNull(client.getVersion()["FlowGenerator"])
            assertEquals(0, client.discardedBytes)
            assertTrue(client.counters.snapshot().values.all { it == 0L }, client.counters.snapshot().toString())
            client.close()
        }
    }

    @Test
    fun `a wrong passkey fails pairing and leaves the machine unpaired`() = runTest {
        assertFailsWith<As11AuthException> { connect().pair { "0421" } }
        assertNull(machine.pairedClientId)
    }

    @Test
    fun `a machine that cannot prove it knows the passkey is refused`() = runTest {
        machine.faults.wrongServerProof = true
        assertFailsWith<As11AuthException> { connect().pair { "0420" } }
    }

    @Test
    fun `an unknown pairing and a rejected proof are authentication failures`() = runTest {
        val credentials = connect().pair { "0420" }
        val stranger = PairingCredentials("client-unknown", credentials.pairingKey)
        assertFailsWith<As11AuthException> { connect().startSession(stranger, NonceRegistry.AcceptAll) }

        val wrongKey = PairingCredentials(credentials.clientId, ByteArray(32) { 9 })
        assertFailsWith<As11AuthException> { connect().startSession(wrongKey, NonceRegistry.AcceptAll) }

        machine.faults.rejectSession = true
        assertFailsWith<As11AuthException> { connect().startSession(credentials, NonceRegistry.AcceptAll) }
    }

    @Test
    fun `re-pairing invalidates the previous credentials`() = runTest {
        val first = connect().pair { "0420" }
        val second = connect().pair { "0420" }
        assertFailsWith<As11AuthException> { connect().startSession(first, NonceRegistry.AcceptAll) }
        connect().startSession(second, NonceRegistry.AcceptAll)
    }

    @Test
    fun `a replayed session nonce is refused`() = runTest {
        val credentials = connect().pair { "0420" }
        val seen = HashSet<String>()
        val registry = NonceRegistry { seen.add(Hex.encode(it)) }
        machine.faults.fixedNonce = ByteArray(32) { 3 }
        connect().startSession(credentials, registry)
        val replayed = connect()
        assertFailsWith<As11AuthException> { replayed.startSession(credentials, registry) }
        assertFalse(replayed.isSecure)
    }

    @Test
    fun `the clock reading brackets the machine time with local time`() = runTest {
        machine.clockMs = { 1_790_000_123_456L }
        val reading = securedClient().getDateTime()
        assertEquals(1_790_000_123_456L, reading.machineEpochMs)
        assertEquals(currentTime, reading.receivedAtMs)
        assertEquals(reading.sentAtMs + reading.roundTripMs / 2 - 1_790_000_123_456L, reading.offsetMs)
    }

    @Test
    fun `a multi-fragment download is reassembled and hash-verified`() = runTest {
        val data = bytes(9_000)
        machine.spools["Summary"] = listOf(data)
        val round = securedClient().pullSpoolRound(SpoolName.SUMMARY, from)
        assertContentEquals(data, round.data)
        assertNotNull(round.sha256)
        assertNull(round.next)
        assertEquals("Summary" to "2026-06-25T15:08:00.000Z", machine.spoolStartTimes.single())
    }

    @Test
    fun `continuation rounds follow the machine's next address`() = runTest {
        val rounds = listOf(bytes(5_000, 1), bytes(3_000, 2), bytes(10, 3))
        machine.spools["TherapyOneMinutePeriodic"] = rounds
        val client = securedClient()
        var address: SpoolAddress = from
        val received = ArrayList<ByteArray>()
        while (true) {
            val round = client.pullSpoolRound(SpoolName.ONE_MINUTE, address)
            received += round.data
            address = SpoolAddress.Continuation(round.next ?: break)
        }
        assertEquals(3, received.size)
        rounds.forEachIndexed { i, expected -> assertContentEquals(expected, received[i]) }
    }

    @Test
    fun `an empty log is a valid empty round`() = runTest {
        val round = securedClient().pullSpoolRound(SpoolName.RESPIRATORY_EVENTS, from)
        assertEquals(0, round.data.size)
        assertNull(round.sha256)
    }

    @Test
    fun `fragments arriving out of order are still assembled correctly`() = runTest {
        val data = bytes(9_000)
        machine.spools["Summary"] = listOf(data)
        machine.faults.reverseFragments = true
        assertContentEquals(data, securedClient().pullSpoolRound(SpoolName.SUMMARY, from).data)
    }

    @Test
    fun `a missing fragment fails the round`() = runTest {
        machine.spools["Summary"] = listOf(bytes(9_000))
        machine.faults.dropFragments += 1
        assertFailsWith<As11ProtocolException> { securedClient().pullSpoolRound(SpoolName.SUMMARY, from) }
    }

    @Test
    fun `a repeated fragment fails the round`() = runTest {
        machine.spools["Summary"] = listOf(bytes(9_000))
        machine.faults.duplicateFragments += 1
        assertFailsWith<As11ProtocolException> { securedClient().pullSpoolRound(SpoolName.SUMMARY, from) }
    }

    @Test
    fun `a wrong hash fails the round`() = runTest {
        machine.spools["Summary"] = listOf(bytes(9_000))
        machine.faults.corruptSpoolHash = true
        assertFailsWith<As11ProtocolException> { securedClient().pullSpoolRound(SpoolName.SUMMARY, from) }
    }

    @Test
    fun `a missing hash fails the round`() = runTest {
        machine.spools["Summary"] = listOf(bytes(9_000))
        machine.faults.omitSpoolHash = true
        assertFailsWith<As11ProtocolException> { securedClient().pullSpoolRound(SpoolName.SUMMARY, from) }
    }

    @Test
    fun `unavailable data is reported as such`() = runTest {
        machine.spools["Summary"] = listOf(bytes(100))
        machine.faults.forceSpoolStatus = "ERROR_DATA_UNAVAILABLE"
        val failure = assertFailsWith<As11SpoolUnavailableException> {
            securedClient().pullSpoolRound(SpoolName.SUMMARY, from)
        }
        assertEquals("ERROR_DATA_UNAVAILABLE", failure.status)
    }

    @Test
    fun `an unknown terminal status fails the round`() = runTest {
        machine.spools["Summary"] = listOf(bytes(100))
        machine.faults.forceSpoolStatus = "SPOOL_SOMETHING_NEW"
        assertFailsWith<As11ProtocolException> { securedClient().pullSpoolRound(SpoolName.SUMMARY, from) }
    }

    @Test
    fun `a download that never finishes times out and the client stays usable`() = runTest {
        machine.spools["Summary"] = listOf(bytes(9_000))
        machine.faults.withholdTerminalFragment = true
        val client = securedClient()
        assertFailsWith<As11TimeoutException> { client.pullSpoolRound(SpoolName.SUMMARY, from) }
        machine.faults.withholdTerminalFragment = false
        assertEquals(9_000, client.pullSpoolRound(SpoolName.SUMMARY, from).data.size)
    }

    @Test
    fun `a command the machine silently denies surfaces as a timeout`() = runTest {
        val client = securedClient()
        machine.faults.silentMethods += "Get"
        val failure = assertFailsWith<As11TimeoutException> { client.get(listOf(GetItem.SERIAL_NUMBER)) }
        assertEquals("Get", failure.operation)
    }

    @Test
    fun `an answer carrying the wrong id is not accepted`() = runTest {
        val client = securedClient()
        machine.faults.wrongResponseId = true
        assertFailsWith<As11TimeoutException> { client.get(listOf(GetItem.SERIAL_NUMBER)) }
        assertEquals(1, client.counters.snapshot()["unmatchedResponses"])
    }

    @Test
    fun `plaintext injected after the session starts is ignored`() = runTest {
        val client = securedClient()
        machine.faults.plaintextInjection =
            """{"id":1,"jsonrpc":"1.0","result":{"SerialNumber":"FORGED"}}""".toByteArray()
        val identity = client.get(listOf(GetItem.SERIAL_NUMBER))
        assertEquals(machine.serialNumber, identity["SerialNumber"]!!.jsonPrimitive.content)
        assertEquals(1, client.counters.snapshot()["plaintextAfterSession"])
    }

    @Test
    fun `tampering on the encrypted channel ends the conversation`() = runTest {
        val client = securedClient()
        machine.faults.corruptNextEncryptedFrame = true
        assertFailsWith<As11Exception> { client.get(listOf(GetItem.SERIAL_NUMBER)) }
        // The link is no longer trusted: later calls fail without touching it.
        val sentBefore = machine.encryptedMethodsSeen.size
        assertFailsWith<As11ProtocolException> { client.getDateTime() }
        assertEquals(sentBefore, machine.encryptedMethodsSeen.size)
    }

    @Test
    fun `a link dropped mid-answer fails the pending request`() = runTest {
        machine.spools["Summary"] = listOf(bytes(9_000))
        val client = securedClient()
        machine.faults.disconnectAfterBytes = 300
        assertFailsWith<As11TransportException> { client.pullSpoolRound(SpoolName.SUMMARY, from) }
        assertFailsWith<As11TransportException> { client.getDateTime() }
    }

    @Test
    fun `only allow-listed commands ever reach the machine`() = runTest {
        machine.spools["Summary"] = listOf(bytes(100))
        val client = securedClient()
        client.get(listOf(GetItem.SERIAL_NUMBER))
        client.getVersion()
        client.getDateTime()
        client.pullSpoolRound(SpoolName.SUMMARY, from)
        assertEquals(
            listOf("Get", "GetVersion", "GetDateTime", "StartSpool", "PullSpoolFragments"),
            machine.encryptedMethodsSeen,
        )
    }

    @Test
    fun `forgetting the machine removes the pairing on its side`() = runTest {
        val credentials = connect().pair { "0420" }
        val client = connect().also { it.startSession(credentials, NonceRegistry.AcceptAll) }
        client.discardPairKey()
        assertNull(machine.pairedClientId)
        assertFailsWith<As11AuthException> { connect().startSession(credentials, NonceRegistry.AcceptAll) }
    }
}
