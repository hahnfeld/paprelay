// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.as11

/**
 * Base type for every failure raised by the AirSense 11 protocol layer.
 *
 * The subtypes are chosen so a caller can decide what to do without reading
 * messages:
 * - [As11TransportException], [As11TimeoutException]: the link; reconnect and retry later.
 * - [As11SpoolIntegrityException], [As11SpoolUnverifiedException], [As11SpoolStalledException]: one download round
 *   was unusable; the connection may still be fine, so pull the round again.
 * - [As11SpoolUnavailableException]: the machine has no such data; not an error to retry.
 * - [As11ProtocolException]: the conversation can no longer be trusted; reconnect.
 * - [As11AuthException] subtypes: see each one. Only [PairingUnknown] and
 *   [SessionProofRejected] suggest the pairing is gone, and a single occurrence
 *   is not proof of it.
 * - [As11RpcException]: the machine refused a specific request.
 */
public sealed class As11Exception(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The machine sent something malformed or out of sequence, or failed an integrity check. */
public class As11ProtocolException(message: String, cause: Throwable? = null) : As11Exception(message, cause)

/** Something about pairing or session authentication failed. */
public sealed class As11AuthException(message: String, cause: Throwable? = null) : As11Exception(message, cause) {
    /** The machine rejected the code the user typed. Ask again; nothing is stored. */
    public class PasskeyRejected(cause: Throwable?) : As11AuthException("the machine rejected the pairing code", cause)

    /**
     * The other end could not prove it is the machine, or sent values no honest
     * machine would. Do not retry automatically.
     */
    public class MachineUnauthenticated(message: String) : As11AuthException(message)

    /** The machine answered session setup with an error for our client id. */
    public class PairingUnknown(cause: Throwable?) : As11AuthException("the machine does not recognise this pairing", cause)

    /** The machine rejected our proof of the pairing key. */
    public class SessionProofRejected(cause: Throwable?) : As11AuthException("the machine rejected the session proof", cause)

    /** Session setup presented a nonce that was used before: a replay, or a faulty machine. */
    public class NonceReplayed : As11AuthException("session setup reused a nonce")
}

/** The machine answered a request with a JSON-RPC error object. */
public class As11RpcException(
    public val method: String,
    public val code: Int?,
    public val deviceMessage: String?,
    /**
     * True when the error carried no request id. The machine does this when it
     * could not parse what it received, which points at a damaged request, not
     * at a decision about this one.
     */
    public val anonymous: Boolean,
    /** The error's `data` member, which for some errors carries partial results. */
    public val data: kotlinx.serialization.json.JsonElement? = null,
) : As11Exception("$method failed: code=$code${if (anonymous) " (no id)" else ""}")

/**
 * No answer arrived in time. The machine answers denied commands with silence,
 * so this can be a refusal as well as a link problem.
 */
public class As11TimeoutException(
    public val operation: String,
    /** Answers that arrived meanwhile but matched no request: a hint that ids are not echoed as expected. */
    public val unmatchedAnswers: Long = 0,
) : As11Exception("timed out: $operation" + if (unmatchedAnswers > 0) " ($unmatchedAnswers unmatched answers arrived)" else "")

/** A download round arrived damaged: a gap, a repeat, a size overrun or a hash mismatch. */
public class As11SpoolIntegrityException(message: String) : As11Exception(message)

/**
 * A download round arrived without the hash that proves it is intact. PAPRelay
 * does not accept unverified data; a machine that never sends hashes cannot be
 * synced, and this type lets that be reported for what it is.
 */
public class As11SpoolUnverifiedException : As11Exception("download arrived without an integrity hash")

/** A download round stopped arriving, or did not finish within its deadline. */
public class As11SpoolStalledException(public val fragmentsReceived: Int) :
    As11Exception("download stalled after $fragmentsReceived fragments")

/** The machine reported that the requested stored data is not available. */
public class As11SpoolUnavailableException(public val status: String) : As11Exception("spool unavailable: $status")

/** The underlying link closed or failed. */
public class As11TransportException(message: String, cause: Throwable? = null) : As11Exception(message, cause)
