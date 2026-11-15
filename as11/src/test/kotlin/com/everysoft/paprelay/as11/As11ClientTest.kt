// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.as11

import com.everysoft.paprelay.as11.sim.FakeAirSense11
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import java.security.SecureRandom
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class As11ClientTest {
    private val machine = FakeAirSense11(passkey = "0420")
    private val from = SpoolAddress.FromMachineTime(1_782_400_080_000)
    private val wallBase = 1_790_000_000_000L

    /** A link the test can cut, standing in for the phone walking out of range. */
    private class Severable(private val inner: As11Transport, scope: CoroutineScope) : As11Transport {
        private val pipe = Channel<ByteArray>(Channel.UNLIMITED)

        init {
            scope.launch { inner.incoming.collect { pipe.send(it) } }
        }

        override val incoming: Flow<ByteArray> = pipe.receiveAsFlow()
        override suspend fun send(frame: ByteArray) = inner.send(frame)
        fun sever() {
            pipe.close()
        }
    }

    /** A link on which the machine also sends a plaintext heartbeat at a steady interval. */
    private class Chattering(private val inner: As11Transport, scope: CoroutineScope, everyMs: Long) : As11Transport {
        private val pipe = Channel<ByteArray>(Channel.UNLIMITED)

        init {
            scope.launch { inner.incoming.collect { pipe.send(it) } }
            scope.launch {
                while (true) {
                    kotlinx.coroutines.delay(everyMs)
                    pipe.send(Fig.encode(0x0392, """{"jsonrpc":"2.0","method":"HeartBeat"}""".toByteArray()))
                }
            }
        }

        override val incoming: Flow<ByteArray> = pipe.receiveAsFlow()
        override suspend fun send(frame: ByteArray) = inner.send(frame)
    }

    /** A link that stops delivering for a while part-way through an answer, then carries on. */
    private class Pausing(
        private val inner: As11Transport,
        scope: CoroutineScope,
        private val pauseAfterChunks: Int,
        private val pauseMs: Long,
    ) : As11Transport {
        private val pipe = Channel<ByteArray>(Channel.UNLIMITED)
        var armed = false

        /** Arm automatically once this many frames have been sent, counting from zero. */
        var armAfterPlaintext: Int = -1
        private var sent = 0

        init {
            scope.launch {
                var sinceArmed = 0
                inner.incoming.collect {
                    if (armed && ++sinceArmed == pauseAfterChunks) kotlinx.coroutines.delay(pauseMs)
                    pipe.send(it)
                }
            }
        }

        override val incoming: Flow<ByteArray> = pipe.receiveAsFlow()
        override suspend fun send(frame: ByteArray) {
            if (sent++ == armAfterPlaintext) armed = true
            inner.send(frame)
        }
    }

    private fun TestScope.clientOn(transport: As11Transport): As11Client = As11Client(
        transport, backgroundScope, SecureRandom(), nowMs = { wallBase + currentTime }, elapsedMs = { currentTime },
    )

    private fun TestScope.connect(chunkSize: Int = 244): As11Client = clientOn(machine.connect(chunkSize))

    /** Pairs on one link, then opens a fresh link with a session, as the app does. */
    private suspend fun TestScope.securedClient(chunkSize: Int = 244): As11Client {
        val credentials = connect(chunkSize).pair { "0420" }
        return connect(chunkSize).also { it.startSession(credentials, NonceRegistry.AcceptAll) }
    }

    private class RememberingRegistry : NonceRegistry {
        val seen = HashSet<String>()
        override suspend fun isKnown(nonce: ByteArray) = Hex.encode(nonce) in seen
        override suspend fun remember(nonce: ByteArray) {
            seen += Hex.encode(nonce)
        }
    }

    private fun bytes(size: Int, seed: Long = 7): ByteArray = ByteArray(size).also { Random(seed).nextBytes(it) }

    private val serial = listOf(GetItem.SERIAL_NUMBER)

    // ---- pairing and session ------------------------------------------------

    @Test
    fun `pairs, opens a session and reads identity over small and large chunks`() = runTest {
        for (chunk in listOf(20, 23, 244, 512)) {
            val client = securedClient(chunk)
            assertTrue(client.isSecure)
            val identity = client.get(listOf(GetItem.SERIAL_NUMBER, GetItem.PRODUCT_NAME))
            assertEquals(machine.serialNumber, identity.values["SerialNumber"]!!.jsonPrimitive.content)
            assertTrue(identity.unavailable.isEmpty())
            assertNotNull(client.getVersion()["FlowGenerator"])
            assertEquals(0, client.discardedBytes)
            assertTrue(client.counters.snapshot().values.all { it == 0L }, client.counters.snapshot().toString())
            client.close()
        }
    }

    @Test
    fun `heartbeats and unrelated notifications between request and answer are ignored`() = runTest {
        machine.faults.chatter = true
        machine.spools["Summary"] = listOf(bytes(9_000))
        val client = securedClient()
        assertEquals(machine.serialNumber, client.get(serial).values["SerialNumber"]!!.jsonPrimitive.content)
        assertEquals(9_000, client.pullSpoolRound(SpoolName.SUMMARY, from).data.size)
        val counters = client.counters.snapshot()
        assertTrue(counters["plaintextAfterSession"]!! > 0)
        assertTrue(counters["ignoredNotifications"]!! > 0)
        assertEquals(0, counters["unmatchedResponses"])
        assertEquals(0, counters["undecodableEncrypted"])
    }

    @Test
    fun `a wrong passkey fails pairing and leaves the machine unpaired`() = runTest {
        assertFailsWith<As11AuthException.PasskeyRejected> { connect().pair { "0421" } }
        assertNull(machine.pairedClientId)
    }

    @Test
    fun `a machine that cannot prove it knows the passkey is refused`() = runTest {
        machine.faults.wrongServerProof = true
        assertFailsWith<As11AuthException.MachineUnauthenticated> { connect().pair { "0420" } }
    }

    @Test
    fun `a session cannot be started on the link that was used for pairing`() = runTest {
        val client = connect()
        val credentials = client.pair { "0420" }
        assertFailsWith<IllegalStateException> { client.startSession(credentials, NonceRegistry.AcceptAll) }
    }

    @Test
    fun `each way session setup can be refused has its own type`() = runTest {
        val credentials = connect().pair { "0420" }
        val stranger = PairingCredentials("client-unknown", credentials.pairingKey)
        val unknown = assertFailsWith<As11AuthException.PairingUnknown> { connect().startSession(stranger, NonceRegistry.AcceptAll) }
        assertEquals(As11Recovery.PAIRING_SUSPECT, unknown.recovery)

        val wrongKey = PairingCredentials(credentials.clientId, ByteArray(32) { 9 })
        val rejected = assertFailsWith<As11AuthException.SessionProofRejected> {
            connect().startSession(wrongKey, NonceRegistry.AcceptAll)
        }
        assertIs<As11RpcException>(rejected.cause) // the machine's own code is kept for diagnosis

        machine.faults.sessionConfirmation = false
        val unconfirmed = connect()
        assertFailsWith<As11AuthException.SessionProofRejected> { unconfirmed.startSession(credentials, NonceRegistry.AcceptAll) }
        assertFalse(unconfirmed.isSecure)
    }

    @Test
    fun `a session answer without the confirmation member is accepted and counted`() = runTest {
        val credentials = connect().pair { "0420" }
        machine.faults.sessionConfirmation = null
        val client = connect().also { it.startSession(credentials, NonceRegistry.AcceptAll) }
        assertTrue(client.isSecure)
        assertEquals(1, client.counters.snapshot()["sessionConfirmationMissing"])
    }

    @Test
    fun `a damaged session request is not mistaken for a lost pairing`() = runTest {
        val credentials = connect().pair { "0420" }
        machine.faults.parseErrorMethods += "RequestSession"
        val failure = assertFailsWith<As11RpcException> { connect().startSession(credentials, NonceRegistry.AcceptAll) }
        assertTrue(failure.anonymous)
        assertEquals(-32700, failure.code)
        assertEquals(As11Recovery.RETRY_LATER, failure.recovery)
    }

    @Test
    fun `re-pairing invalidates the previous credentials`() = runTest {
        val first = connect().pair { "0420" }
        val second = connect().pair { "0420" }
        assertFailsWith<As11AuthException.PairingUnknown> { connect().startSession(first, NonceRegistry.AcceptAll) }
        connect().startSession(second, NonceRegistry.AcceptAll)
    }

    @Test
    fun `a replayed session nonce is refused`() = runTest {
        val credentials = connect().pair { "0420" }
        val registry = RememberingRegistry()
        machine.faults.fixedNonce = ByteArray(32) { 3 }
        connect().startSession(credentials, registry)
        val replayed = connect()
        val failure = assertFailsWith<As11AuthException.NonceReplayed> { replayed.startSession(credentials, registry) }
        // Anyone in range can replay setup messages; it must not switch syncing off.
        assertEquals(As11Recovery.RECONNECT, failure.recovery)
        assertFalse(replayed.isSecure)
    }

    @Test
    fun `backing out of the passkey prompt is reported as a cancelled pairing`() = runTest {
        val failure = assertFailsWith<As11PairingCancelledException> {
            connect().pair { throw kotlinx.coroutines.CancellationException("dialog dismissed") }
        }
        assertEquals(As11Recovery.ASK_USER, failure.recovery)
        assertNull(machine.pairedClientId)
    }

    @Test
    fun `a passkey that is never entered times out`() = runTest {
        assertFailsWith<As11PairingCancelledException> { connect().pair { awaitCancellation() } }
    }

    @Test
    fun `a code that is not a code is refused without spending the machine's attempt`() = runTest {
        val failure = assertFailsWith<As11AuthException.PasskeyInvalid> { connect().pair { "12" } }
        assertEquals(As11Recovery.ASK_USER, failure.recovery)
        // The machine was never sent a confirmation.
        assertEquals(listOf("StartKeyExchange"), machine.plaintextMethodsSeen)
        assertNull(machine.pairedClientId)
    }

    @Test
    fun `waiting for the passkey ends when the link drops`() = runTest {
        val link = Severable(machine.connect(), backgroundScope)
        val client = clientOn(link)
        supervisorScope {
            val pairing = async { runCatching { client.pair { awaitCancellation() } } }
            advanceTimeBy(1_000)
            link.sever()
            assertIs<As11TransportException>(pairing.await().exceptionOrNull())
        }
    }

    // ---- reads --------------------------------------------------------------

    @Test
    fun `the clock offset is positive when the machine is behind, and the round trip is measured`() = runTest {
        machine.clockMs = { wallBase + currentTime - 100_000 }
        val client = securedClient()
        machine.faults.responseDelayMs = 400
        val reading = client.getDateTime()
        assertEquals(400, reading.roundTripMs)
        // The true offset is 100 s; the midpoint estimate can be off by half the round trip.
        assertTrue(reading.offsetMs in 99_800..100_200, "offset ${reading.offsetMs}")
    }

    @Test
    fun `items the machine cannot supply are reported without losing the rest`() = runTest {
        val client = securedClient()
        machine.faults.unavailableItems += "ProductCode"
        val result = client.get(listOf(GetItem.SERIAL_NUMBER, GetItem.PRODUCT_CODE))
        assertEquals(machine.serialNumber, result.values["SerialNumber"]!!.jsonPrimitive.content)
        assertEquals(listOf("ProductCode"), result.unavailable)
        assertNull(result.values["InvalidObjects"])
    }

    // ---- downloads ----------------------------------------------------------

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
    fun `every documented way of ending a round is understood`() = runTest {
        val data = bytes(9_000)
        machine.spools["Summary"] = listOf(data)
        val client = securedClient()
        for (separate in listOf(false, true)) {
            for (nullId in listOf(false, true)) {
                machine.faults.separateTerminalFragment = separate
                machine.faults.fragmentsCarryNullId = nullId
                assertContentEquals(data, client.pullSpoolRound(SpoolName.SUMMARY, from).data, "separate=$separate nullId=$nullId")
            }
        }
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
    fun `an empty log ends with a bare status fragment and is a valid empty round`() = runTest {
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
    fun `a fragment left over from another download is ignored`() = runTest {
        val data = bytes(9_000)
        machine.spools["Summary"] = listOf(data)
        machine.faults.strayFragmentBeforeRound = true
        val client = securedClient()
        assertContentEquals(data, client.pullSpoolRound(SpoolName.SUMMARY, from).data)
        assertEquals(1, client.counters.snapshot()["strayFragments"])
    }

    @Test
    fun `a damaged round fails on its own and can be pulled again`() = runTest {
        val data = bytes(9_000)
        machine.spools["Summary"] = listOf(data)
        val client = securedClient()

        machine.faults.dropFragments += 1
        assertFailsWith<As11SpoolIntegrityException> { client.pullSpoolRound(SpoolName.SUMMARY, from) }
        machine.faults.dropFragments.clear()

        machine.faults.duplicateFragments += 1
        assertFailsWith<As11SpoolIntegrityException> { client.pullSpoolRound(SpoolName.SUMMARY, from) }
        machine.faults.duplicateFragments.clear()

        machine.faults.corruptSpoolHash = true
        assertFailsWith<As11SpoolIntegrityException> { client.pullSpoolRound(SpoolName.SUMMARY, from) }
        machine.faults.corruptSpoolHash = false

        machine.faults.omitSpoolHash = true
        assertFailsWith<As11SpoolUnverifiedException> { client.pullSpoolRound(SpoolName.SUMMARY, from) }
        machine.faults.omitSpoolHash = false

        // None of that ended the conversation.
        assertFalse(client.terminated.isCompleted)
        assertContentEquals(data, client.pullSpoolRound(SpoolName.SUMMARY, from).data)
    }

    @Test
    fun `unavailable data is reported as such, with or without a sequence number`() = runTest {
        machine.spools["Summary"] = listOf(bytes(100))
        machine.faults.forceSpoolStatus = "ERROR_DATA_UNAVAILABLE"
        val client = securedClient()
        val failure = assertFailsWith<As11SpoolUnavailableException> { client.pullSpoolRound(SpoolName.SUMMARY, from) }
        assertEquals("ERROR_DATA_UNAVAILABLE", failure.status)
        assertFalse(client.terminated.isCompleted)
    }

    @Test
    fun `an unknown terminal status is named in the failure`() = runTest {
        machine.spools["Summary"] = listOf(bytes(100))
        machine.faults.forceSpoolStatus = "SPOOL_SOMETHING_NEW"
        val failure = assertFailsWith<As11ProtocolException> { securedClient().pullSpoolRound(SpoolName.SUMMARY, from) }
        assertTrue(failure.message!!.contains("SPOOL_SOMETHING_NEW"))
    }

    @Test
    fun `a promise of more data without an address is refused`() = runTest {
        machine.spools["Summary"] = listOf(bytes(100))
        machine.faults.forceSpoolStatus = "SPOOL_COMPLETE_MORE_DATA_PENDING"
        assertFailsWith<As11ProtocolException> { securedClient().pullSpoolRound(SpoolName.SUMMARY, from) }
    }

    @Test
    fun `a download that never finishes stalls out and the client stays usable`() = runTest {
        machine.spools["Summary"] = listOf(bytes(9_000))
        machine.faults.withholdTerminalFragment = true
        val client = securedClient()
        val stalled = assertFailsWith<As11SpoolStalledException> { client.pullSpoolRound(SpoolName.SUMMARY, from) }
        assertTrue(stalled.fragmentsReceived > 0)
        machine.faults.withholdTerminalFragment = false
        assertEquals(9_000, client.pullSpoolRound(SpoolName.SUMMARY, from).data.size)
    }

    // ---- lossy and hostile links ---------------------------------------------

    @Test
    fun `a lost chunk costs one request, not the ones after it`() = runTest {
        val client = securedClient(chunkSize = 20)
        machine.faults.dropChunkOfNextEncryptedFrame = 1
        assertFailsWith<As11TimeoutException> { client.get(serial) }
        // The next answer is as long as the damaged one, so it overruns it and is found at once.
        assertEquals(machine.serialNumber, client.get(serial).values["SerialNumber"]!!.jsonPrimitive.content)
        assertEquals(1, client.payloadCrcFailures)
        assertEquals(0, client.counters.snapshot()["unmatchedResponses"])
    }

    @Test
    fun `a short answer hidden behind a long damaged frame is recovered`() = runTest {
        machine.spools["Summary"] = listOf(bytes(2_000))
        val client = securedClient()
        // Lose a chunk of the single 2 kB fragment (the third encrypted frame of the round).
        machine.faults.encryptedFramesBeforeChunkDrop = 2
        machine.faults.dropChunkOfNextEncryptedFrame = 3
        assertFailsWith<As11SpoolStalledException> { client.pullSpoolRound(SpoolName.SUMMARY, from) }
        // This answer is far shorter than what the damaged frame still claims.
        assertEquals(machine.serialNumber, client.get(serial).values["SerialNumber"]!!.jsonPrimitive.content)
        assertEquals(1, client.abandonedFrames)
        assertEquals(2_000, client.pullSpoolRound(SpoolName.SUMMARY, from).data.size)
    }

    @Test
    fun `an answer that pauses mid-frame is waited for, not discarded`() = runTest {
        val link = Pausing(machine.connect(chunkSize = 20), backgroundScope, pauseAfterChunks = 3, pauseMs = 4_000)
        val credentials = connect().pair { "0420" }
        val client = clientOn(link).also { it.startSession(credentials, NonceRegistry.AcceptAll) }
        link.armed = true
        assertEquals(machine.serialNumber, client.get(serial).values["SerialNumber"]!!.jsonPrimitive.content)
        assertEquals(0, client.abandonedFrames)
        assertEquals(0, client.discardedBytes)
    }

    @Test
    fun `a peer that cannot answer on the encrypted channel never gets a session`() = runTest {
        val credentials = connect().pair { "0420" }
        machine.faults.silentMethods += "GetVersion"
        val client = connect()
        val failure = assertFailsWith<As11AuthException.SessionUnproven> {
            client.startSession(credentials, NonceRegistry.AcceptAll)
        }
        // Silence after setup is not evidence that the pairing is gone.
        assertEquals(As11Recovery.RECONNECT, failure.recovery)
        assertFalse(client.isSecure)
        assertTrue(client.terminated.isCompleted)
    }

    @Test
    fun `a keyless device that echoes doctored ciphertext cannot fake the session proof`() = runTest {
        val credentials = connect().pair { "0420" }
        val registry = RememberingRegistry()
        machine.faults.forgeSessionProof = true
        val client = connect()
        val failure = assertFailsWith<As11ProtocolException> { client.startSession(credentials, registry) }
        assertTrue(failure.message!!.contains("own encrypted data"), failure.message)
        assertFalse(client.isSecure)
        assertTrue(client.terminated.isCompleted)
        assertTrue(registry.seen.isEmpty())
        // And nothing can be asked on it afterwards.
        assertFailsWith<As11ProtocolException> { client.get(serial) }
        assertFailsWith<As11ProtocolException> { client.discardPairKey() }
    }

    @Test
    fun `a late answer to the first proof request still proves the session`() = runTest {
        val credentials = connect().pair { "0420" }
        // Slower than one request's patience, quicker than two.
        machine.faults.responseDelayMs = 0
        val link = Pausing(machine.connect(), backgroundScope, pauseAfterChunks = 1, pauseMs = 10_500)
        val client = clientOn(link)
        link.armAfterPlaintext = 2
        client.startSession(credentials, NonceRegistry.AcceptAll)
        assertTrue(client.isSecure)
    }

    @Test
    fun `a session interrupted before it is proven leaves nothing usable`() = runTest {
        val credentials = connect().pair { "0420" }
        machine.faults.silentMethods += "GetVersion"
        val client = connect()
        val setup = launch { client.startSession(credentials, NonceRegistry.AcceptAll) }
        advanceTimeBy(2_000) // past session setup, inside the proof
        setup.cancel()
        setup.join()
        assertFalse(client.isSecure)
        assertTrue(client.terminated.isCompleted)
        machine.faults.silentMethods.clear()
        assertFailsWith<As11TransportException> { client.get(serial) }
    }

    @Test
    fun `failing to record the nonce ends the session instead of leaving it half-open`() = runTest {
        val credentials = connect().pair { "0420" }
        val broken = object : NonceRegistry {
            override suspend fun isKnown(nonce: ByteArray): Boolean = false
            override suspend fun remember(nonce: ByteArray): Unit = throw java.io.IOException("disk full")
        }
        val client = connect()
        assertFailsWith<As11NonceStoreException> { client.startSession(credentials, broken) }
        assertFalse(client.isSecure)
        assertFailsWith<As11NonceStoreException> { client.get(serial) }
    }

    @Test
    fun `a nonce is remembered only after the session is proven`() = runTest {
        val credentials = connect().pair { "0420" }
        val registry = RememberingRegistry()
        machine.faults.silentMethods += "GetVersion"
        assertFailsWith<As11AuthException.SessionUnproven> { connect().startSession(credentials, registry) }
        assertTrue(registry.seen.isEmpty())
        machine.faults.silentMethods.clear()
        connect().startSession(credentials, registry)
        assertEquals(1, registry.seen.size)
    }

    @Test
    fun `trouble storing the nonce record is a typed, retryable failure`() = runTest {
        val credentials = connect().pair { "0420" }
        val broken = object : NonceRegistry {
            override suspend fun isKnown(nonce: ByteArray): Boolean = throw java.io.IOException("disk full")
            override suspend fun remember(nonce: ByteArray) = Unit
        }
        val failure = assertFailsWith<As11NonceStoreException> { connect().startSession(credentials, broken) }
        assertEquals(As11Recovery.RETRY_LATER, failure.recovery)
    }

    @Test
    fun `a round whose data was all lost is not mistaken for an empty log`() = runTest {
        machine.spools["Summary"] = listOf(bytes(5_000))
        machine.faults.separateTerminalFragment = true
        machine.faults.dropFragments += listOf(0, 1)
        val failure = assertFailsWith<As11SpoolIntegrityException> { securedClient().pullSpoolRound(SpoolName.SUMMARY, from) }
        assertEquals(As11Recovery.REPEAT_ROUND, failure.recovery)
    }

    @Test
    fun `sustained tampering exhausts the session's budget even when some frames get through`() = runTest {
        val client = securedClient()
        var requests = 0
        // Two damaged, one clean, repeatedly: never three in a row.
        while (requests < 30 && !client.terminated.isCompleted) {
            machine.faults.corruptEncryptedFrames = if (requests % 3 == 2) 0 else 1
            runCatching { client.get(serial) }
            requests++
        }
        // Without a budget the loop would run to its end with the conversation still alive.
        assertTrue(client.terminated.isCompleted, "still alive after $requests requests")
        assertIs<As11ProtocolException>(client.terminated.await())
        assertTrue(requests < 30)
    }

    @Test
    fun `other traffic does not keep a dead frame in the way`() = runTest {
        machine.spools["Summary"] = listOf(bytes(2_000))
        val credentials = connect().pair { "0420" }
        // A heartbeat every half second: the link is never quiet.
        val link = Chattering(machine.connect(), backgroundScope, everyMs = 500)
        val client = clientOn(link).also { it.startSession(credentials, NonceRegistry.AcceptAll) }
        // The single 2 kB fragment is cut off after its first chunk, leaving most of it owed.
        machine.faults.encryptedFramesBeforeChunkDrop = 2
        machine.faults.dropChunkOfNextEncryptedFrame = 1
        machine.faults.dropRestOfFrame = true
        assertFailsWith<As11SpoolStalledException> { client.pullSpoolRound(SpoolName.SUMMARY, from) }
        // The dead frame was dropped as soon as a heartbeat was seen complete behind it,
        // not left until enough chatter had piled up to overrun what it claimed.
        assertEquals(1, client.abandonedFrames)
        assertEquals(0, client.payloadCrcFailures)
        assertEquals(machine.serialNumber, client.get(serial).values["SerialNumber"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an empty round that promises more, with no hash to check it by, is refused`() = runTest {
        machine.spools["Summary"] = listOf(bytes(5_000), bytes(100))
        machine.faults.separateTerminalFragment = true
        machine.faults.omitSpoolHash = true
        machine.faults.dropFragments += listOf(0, 1)
        assertFailsWith<As11SpoolIntegrityException> { securedClient().pullSpoolRound(SpoolName.SUMMARY, from) }
    }

    @Test
    fun `limits that a second attempt cannot change are not marked for repeating`() = runTest {
        machine.spools["Summary"] = listOf(bytes(9_000))
        val failure = assertFailsWith<As11SpoolIntegrityException> {
            securedClient().pullSpoolRound(SpoolName.SUMMARY, from, maxSpoolSize = 1_000)
        }
        assertEquals(As11Recovery.DO_NOT_RETRY, failure.recovery)
    }

    @Test
    fun `a lost chunk in a download fails that round only`() = runTest {
        val data = bytes(9_000)
        machine.spools["Summary"] = listOf(data)
        val client = securedClient(chunkSize = 244)
        // Frame 0 is the StartSpool answer, so arrange the loss for the first fragment instead.
        client.get(serial)
        machine.faults.dropFragments += 0
        assertFailsWith<As11SpoolIntegrityException> { client.pullSpoolRound(SpoolName.SUMMARY, from) }
        machine.faults.dropFragments.clear()
        assertContentEquals(data, client.pullSpoolRound(SpoolName.SUMMARY, from).data)
    }

    @Test
    fun `a command the machine silently denies surfaces as a timeout`() = runTest {
        val client = securedClient()
        machine.faults.silentMethods += "Get"
        val failure = assertFailsWith<As11TimeoutException> { client.get(serial) }
        assertEquals("Get", failure.operation)
        assertEquals(0, failure.unmatchedAnswers)
    }

    @Test
    fun `an answer carrying the wrong id is not accepted, and the timeout says so`() = runTest {
        val client = securedClient()
        machine.faults.wrongResponseId = true
        val failure = assertFailsWith<As11TimeoutException> { client.get(serial) }
        assertEquals(1, failure.unmatchedAnswers)
    }

    @Test
    fun `plaintext injected after the session starts is ignored`() = runTest {
        val client = securedClient()
        machine.faults.plaintextInjection =
            """{"id":1,"jsonrpc":"1.0","result":{"SerialNumber":"FORGED"}}""".toByteArray()
        assertEquals(machine.serialNumber, client.get(serial).values["SerialNumber"]!!.jsonPrimitive.content)
        assertEquals(1, client.counters.snapshot()["plaintextAfterSession"])
    }

    @Test
    fun `one damaged encrypted frame is dropped, a run of them ends the conversation`() = runTest {
        val client = securedClient()
        machine.faults.corruptEncryptedFrames = 1
        assertFailsWith<As11TimeoutException> { client.get(serial) }
        assertFalse(client.terminated.isCompleted)
        assertEquals(machine.serialNumber, client.get(serial).values["SerialNumber"]!!.jsonPrimitive.content)

        machine.faults.corruptEncryptedFrames = 3
        repeat(2) { assertFailsWith<As11TimeoutException> { client.get(serial) } }
        assertFailsWith<As11ProtocolException> { client.get(serial) }
        assertIs<As11ProtocolException>(client.terminated.await())
        assertFalse(client.isSecure)

        // The link is no longer trusted: later calls fail without touching it.
        val sentBefore = machine.encryptedMethodsSeen.size
        assertFailsWith<As11ProtocolException> { client.getDateTime() }
        assertEquals(sentBefore, machine.encryptedMethodsSeen.size)
    }

    @Test
    fun `a link dropped mid-answer fails the pending request and every later one`() = runTest {
        machine.spools["Summary"] = listOf(bytes(9_000))
        val client = securedClient()
        machine.faults.disconnectAfterBytes = 300
        assertFailsWith<As11TransportException> { client.pullSpoolRound(SpoolName.SUMMARY, from) }
        assertFailsWith<As11TransportException> { client.getDateTime() }
        assertIs<As11TransportException>(client.terminated.await())
    }

    @Test
    fun `a write the link never completes is given up on`() = runTest {
        val client = securedClient()
        machine.faults.hangSend = true
        assertFailsWith<As11TransportException> { client.get(serial) }
        assertTrue(client.terminated.isCompleted)
    }

    // ---- lifecycle -------------------------------------------------------------

    @Test
    fun `cancelling a caller that is waiting for an answer leaves the client usable`() = runTest {
        val client = securedClient()
        machine.faults.silentMethods += "Get"
        val waiting = launch { client.get(serial) }
        runCurrent()
        waiting.cancel()
        waiting.join()
        machine.faults.silentMethods.clear()
        assertEquals(machine.serialNumber, client.get(serial).values["SerialNumber"]!!.jsonPrimitive.content)
    }

    @Test
    fun `cancelling a caller in the middle of a write ends the conversation`() = runTest {
        val client = securedClient()
        machine.faults.hangSend = true
        val writing = launch { client.get(serial) }
        runCurrent()
        writing.cancel()
        writing.join()
        // Part of a frame may be on the wire, so nothing more is sent.
        assertTrue(client.terminated.isCompleted)
    }

    @Test
    fun `closing the client fails a call that is in flight`() = runTest {
        val client = securedClient()
        machine.faults.silentMethods += "Get"
        supervisorScope {
            val call = async { runCatching { client.get(serial) } }
            runCurrent()
            client.close()
            assertIs<As11TransportException>(call.await().exceptionOrNull())
        }
        assertFalse(client.isSecure)
    }

    @Test
    fun `only allow-listed commands ever reach the machine`() = runTest {
        machine.spools["Summary"] = listOf(bytes(100))
        val client = securedClient()
        client.get(serial)
        client.getVersion()
        client.getDateTime()
        client.pullSpoolRound(SpoolName.SUMMARY, from)
        assertEquals(
            // The first GetVersion is the session proof.
            listOf("GetVersion", "Get", "GetVersion", "GetDateTime", "StartSpool", "PullSpoolFragments"),
            machine.encryptedMethodsSeen,
        )
    }

    @Test
    fun `forgetting the machine removes the pairing on its side`() = runTest {
        val credentials = connect().pair { "0420" }
        val client = connect().also { it.startSession(credentials, NonceRegistry.AcceptAll) }
        client.discardPairKey()
        assertNull(machine.pairedClientId)
        assertFailsWith<As11AuthException.PairingUnknown> { connect().startSession(credentials, NonceRegistry.AcceptAll) }
    }
}
