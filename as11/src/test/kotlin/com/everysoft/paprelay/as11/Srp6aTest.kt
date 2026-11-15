// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.as11

import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class Srp6aTest {
    @Test
    fun `key exchange matches the independent implementation, including leading-zero values`() {
        for (case in Vectors.list("srp")) {
            val client = Srp6aClient(case.bytes("a"))
            assertContentEquals(case.bytes("A"), client.publicKey, case.str("name"))
            // The machine may send its value with or without leading zeros.
            for (serverKey in listOf(case.str("B"), case.str("B_unpadded"), case.str("B").uppercase())) {
                val proof = client.computeProof(serverKey, case.str("salt"), case.str("passkey"))
                assertContentEquals(case.bytes("K"), proof.sharedKey, case.str("name"))
                assertContentEquals(case.bytes("M1"), proof.clientProof, case.str("name"))
                assertContentEquals(case.bytes("M2"), proof.expectedServerProof, case.str("name"))
            }
        }
    }

    @Test
    fun `a wrong passkey yields a different proof`() {
        val case = Vectors.list("srp").first()
        val proof = Srp6aClient(case.bytes("a")).computeProof(case.str("B"), case.str("salt"), "9999")
        assertFalse(proof.clientProof.contentEquals(case.bytes("M1")))
    }

    @Test
    fun `degenerate server values are refused`() {
        val case = Vectors.list("srp").first()
        val client = Srp6aClient(case.bytes("a"))
        val n = Srp6aClient.N
        for (bad in listOf(BigInteger.ZERO, n, n.multiply(BigInteger.TWO))) {
            // 2N is wider than the group and is rejected as malformed before the range check.
            assertFailsWith<As11Exception> { client.computeProof(bad.toString(16), case.str("salt"), "0420") }
        }
        assertFailsWith<As11ProtocolException> { client.computeProof("zz", case.str("salt"), "0420") }
        assertFailsWith<As11ProtocolException> { client.computeProof(case.str("B"), "abc", "0420") }
    }

    @Test
    fun `the passkey is text and must be digits`() {
        val case = Vectors.list("srp").first()
        val client = Srp6aClient(case.bytes("a"))
        assertFailsWith<IllegalArgumentException> { client.computeProof(case.str("B"), case.str("salt"), "42") }
        assertFailsWith<IllegalArgumentException> { client.computeProof(case.str("B"), case.str("salt"), "12a4") }
    }

    @Test
    fun `padding keeps leading zeros and drops the sign byte`() {
        assertContentEquals(ByteArray(255) + byteArrayOf(2), Srp6aClient.pad(BigInteger.TWO))
        val top = BigInteger.ONE.shiftLeft(2047) // needs a sign byte in two's complement
        val padded = Srp6aClient.pad(top)
        assertContentEquals(byteArrayOf(0x80.toByte()) + ByteArray(255), padded)
    }
}
