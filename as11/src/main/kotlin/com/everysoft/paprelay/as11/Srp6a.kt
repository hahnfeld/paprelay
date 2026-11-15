// SPDX-License-Identifier: GPL-3.0-or-later
//
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
// Based on SomnoTrace, originally created by Ilya Kruchinin (https://github.com/ilyakruchinin).
//
// Derived from SomnoTrace main/as11_ble.c (srp_begin, srp_compute),
// Copyright (C) 2026 Ilya Kruchinin, GPL-3.0-or-later with the Section 7(b)
// attribution term reproduced in NOTICE.
// Ported to Kotlin and modified 2026-11-15: the server public value and the
// scrambling parameter are validated, device-supplied lengths are bounded, and
// proofs are handled as bytes for constant-time comparison.
package com.everysoft.paprelay.as11

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/** Result of the client side of the pairing key exchange. */
public class SrpProof internal constructor(
    /** M1, sent to the machine as `clientConfirmation`. */
    public val clientProof: ByteArray,
    /** M2, which the machine must return as `serverConfirmation`. */
    public val expectedServerProof: ByteArray,
    /** K, the long-term pairing key. Secret: never log or persist unwrapped. */
    public val sharedKey: ByteArray,
)

/**
 * Client side of the machine's first-pairing key exchange: SRP-6a over the
 * RFC 5054 2048-bit group with SHA-256, where the password is the 4-digit code
 * shown on the machine's screen.
 *
 * Formulas (all integers serialised big-endian, left-padded to 256 bytes):
 * `k = H(N | g)`, `x = H(salt | H(passkey))`, `u = H(A | B)`,
 * `S = (B - k * g^x)^(a + u * x) mod N`, `K = H(S)`,
 * `M1 = H((H(N) xor H(g)) | salt | A | B | K)`, `M2 = H(A | M1 | K)`.
 */
public class Srp6aClient internal constructor(privateExponent: ByteArray) {
    private val a: BigInteger = BigInteger(1, privateExponent)
    private val bigA: BigInteger

    /** A, left-padded to 256 bytes; sent to the machine as `clientPk`. */
    public val publicKey: ByteArray

    public constructor(random: SecureRandom) : this(ByteArray(PRIVATE_EXPONENT_BYTES).also(random::nextBytes))

    init {
        require(a.signum() > 0) { "private exponent must be non-zero" }
        bigA = G.modPow(a, N)
        check(bigA.mod(N).signum() != 0) { "degenerate public key" }
        publicKey = pad(bigA)
    }

    /**
     * Completes the exchange from the machine's `serverPk` and `salt`.
     *
     * @param passkey the code exactly as displayed, kept as text so leading zeros survive.
     * @throws As11ProtocolException if the machine's values are malformed.
     * @throws As11AuthException if they are values no honest machine would send.
     */
    public fun computeProof(serverPublicKeyHex: String, saltHex: String, passkey: String): SrpProof {
        require(isValidPasskey(passkey)) { "passkey must be 4 to 8 digits" }
        val salt = Hex.decode(saltHex, MAX_SALT_BYTES)
        // The machine may omit leading zeros, so this is parsed as a number rather
        // than as a fixed-width byte string.
        if (!SERVER_KEY.matches(serverPublicKeyHex)) {
            throw As11ProtocolException("machine sent a malformed key-exchange value")
        }
        val bigB = BigInteger(serverPublicKeyHex, 16)
        if (bigB.signum() <= 0 || bigB >= N) {
            throw As11AuthException.MachineUnauthenticated("machine sent an invalid key-exchange value")
        }
        val paddedB = pad(bigB)

        val k = BigInteger(1, sha256(PAD_N, PAD_G))
        val x = BigInteger(1, sha256(salt, sha256(passkey.toByteArray(Charsets.US_ASCII))))
        val u = BigInteger(1, sha256(publicKey, paddedB))
        if (u.signum() == 0) {
            throw As11AuthException.MachineUnauthenticated("machine sent an invalid key-exchange value")
        }

        val base = bigB.subtract(k.multiply(G.modPow(x, N))).mod(N)
        val s = base.modPow(a.add(u.multiply(x)), N)
        val key = sha256(pad(s))
        val m1 = sha256(HN_XOR_HG, salt, publicKey, paddedB, key)
        val m2 = sha256(publicKey, m1, key)
        return SrpProof(clientProof = m1, expectedServerProof = m2, sharedKey = key)
    }

    public companion object {
        internal const val PAD = 256
        internal const val PRIVATE_EXPONENT_BYTES = 32
        private const val MAX_SALT_BYTES = 64
        private val PASSKEY = Regex("[0-9]{4,8}")

        /** Whether [passkey] has the form of a code the machine could display. */
        public fun isValidPasskey(passkey: String): Boolean = PASSKEY.matches(passkey)

        private val SERVER_KEY = Regex("[0-9A-Fa-f]{1,${PAD * 2}}")

        /** RFC 5054 Appendix A, 2048-bit group. */
        internal val N: BigInteger = BigInteger(
            "AC6BDB41324A9A9BF166DE5E1389582FAF72B6651987EE07FC3192943DB56050" +
                "A37329CBB4A099ED8193E0757767A13DD52312AB4B03310DCD7F48A9DA04FD50" +
                "E8083969EDB767B0CF6095179A163AB3661A05FBD5FAAAE82918A9962F0B93B8" +
                "55F97993EC975EEAA80D740ADBF4FF747359D041D5C33EA71D281E446B14773B" +
                "CA97B43A23FB801676BD207A436C6481F1D2B9078717461A5B9D32E688F87748" +
                "544523B524B0D57D5EA77A2775D2ECFA032CFBDBF52FB3786160279004E57AE6" +
                "AF874E7303CE53299CCC041C7BC308D82A5698F3A8D0C38271AE35F8E9DBFBB6" +
                "94B5C803D89F7AE435DE236D525F54759B65E372FCD68EF20FA7111F9E4AFF73",
            16,
        )
        internal val G: BigInteger = BigInteger.TWO

        private val PAD_N = pad(N)
        private val PAD_G = pad(G)
        private val HN_XOR_HG: ByteArray = run {
            val hn = sha256(PAD_N)
            val hg = sha256(PAD_G)
            ByteArray(hn.size) { (hn[it].toInt() xor hg[it].toInt()).toByte() }
        }

        /** Big-endian, exactly [PAD] bytes: no sign byte, leading zeros kept. */
        internal fun pad(value: BigInteger): ByteArray {
            val raw = value.toByteArray()
            val start = if (raw.size > 1 && raw[0].toInt() == 0) 1 else 0
            val magnitude = raw.size - start
            check(magnitude <= PAD) { "value wider than the group" }
            val out = ByteArray(PAD)
            raw.copyInto(out, PAD - magnitude, start, raw.size)
            return out
        }

        internal fun sha256(vararg parts: ByteArray): ByteArray {
            val digest = MessageDigest.getInstance("SHA-256")
            for (part in parts) digest.update(part)
            return digest.digest()
        }
    }
}
