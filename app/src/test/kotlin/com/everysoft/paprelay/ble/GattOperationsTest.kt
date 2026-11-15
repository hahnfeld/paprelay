// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.ble

import com.everysoft.paprelay.as11.As11TransportException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class GattOperationsTest {
    private val operations = GattOperations()

    @Test
    fun `a completion of the right kind finishes the request`() = runTest {
        val result = async { operations.run(GattOperation.DISCOVERY, 1_000) { true } }
        runCurrent()
        operations.complete(GattOperation.DISCOVERY, 0)
        assertEquals(0, result.await())
    }

    @Test
    fun `a late MTU completion does not finish service discovery`() = runTest {
        // The MTU request times out and is tolerated...
        assertFailsWith<As11TransportException> { operations.run(GattOperation.MTU, 100) { true } }
        // ...then its callback arrives while discovery is waiting.
        val discovery = async { operations.run(GattOperation.DISCOVERY, 1_000) { true } }
        runCurrent()
        operations.complete(GattOperation.MTU, 0)
        runCurrent()
        assertTrue(discovery.isActive)
        operations.complete(GattOperation.DISCOVERY, 0)
        assertEquals(0, discovery.await())
    }

    @Test
    fun `an unsolicited completion while nothing is waiting is ignored`() = runTest {
        operations.complete(GattOperation.MTU, 0)
        operations.complete(GattOperation.WRITE, 0)
        val write = async { operations.run(GattOperation.WRITE, 1_000) { true } }
        runCurrent()
        assertTrue(write.isActive)
        operations.complete(GattOperation.WRITE, 7)
        assertEquals(7, write.await())
    }

    @Test
    fun `requests run one at a time`() = runTest {
        val started = ArrayList<String>()
        val first = async {
            operations.run(GattOperation.WRITE, 1_000) {
                started += "first"
                true
            }
        }
        val second = async {
            operations.run(GattOperation.WRITE, 1_000) {
                started += "second"
                true
            }
        }
        runCurrent()
        assertEquals(listOf("first"), started)
        operations.complete(GattOperation.WRITE, 0)
        runCurrent()
        assertEquals(listOf("first", "second"), started)
        operations.complete(GattOperation.WRITE, 0)
        assertEquals(0, first.await())
        assertEquals(0, second.await())
    }

    @Test
    fun `a request the stack refuses fails at once`() = runTest {
        assertFailsWith<As11TransportException> { operations.run(GattOperation.WRITE, 1_000) { false } }
    }

    @Test
    fun `a link failure ends the waiting request and every later one`() = runTest {
        val waiting = launch { assertFailsWith<As11TransportException> { operations.run(GattOperation.WRITE, 60_000) { true } } }
        runCurrent()
        operations.fail(As11TransportException("link lost"))
        waiting.join()
        var startedAgain = false
        assertFailsWith<As11TransportException> {
            operations.run(GattOperation.WRITE, 1_000) {
                startedAgain = true
                true
            }
        }
        assertEquals(false, startedAgain)
    }

    @Test
    fun `write size never exceeds what the machine is known to accept`() {
        assertEquals(20, GattLink.writeChunk(23))
        assertEquals(244, GattLink.writeChunk(247))
        assertEquals(244, GattLink.writeChunk(517))
        assertEquals(20, GattLink.writeChunk(0))
    }
}
