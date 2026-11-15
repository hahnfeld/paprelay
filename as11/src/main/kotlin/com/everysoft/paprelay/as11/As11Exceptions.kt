// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.as11

import kotlinx.serialization.json.JsonElement

/**
 * What a caller should do about a failure, so it never has to read messages.
 *
 * Every kind of retry must be capped by the caller. A fault that recurs on
 * every attempt is permanent whatever its type says, and the caller is the
 * only one who can see that.
 */
public enum class As11Recovery {
    /** The link or the machine was not ready. Try again on a later connection. */
    RETRY_LATER,

    /** One download round was unusable. The connection may be fine: pull the round again. */
    REPEAT_ROUND,

    /** This conversation can no longer be trusted. Drop the link and reconnect. */
    RECONNECT,

    /**
     * The machine may have forgotten this pairing. One occurrence proves
     * nothing; several in a row, on separate connections, do.
     */
    PAIRING_SUSPECT,

    /** The user has to act: re-enter the code, or start pairing again. */
    ASK_USER,

    /** Repeating the request will not help. Report it. */
    DO_NOT_RETRY,

    /** A bug in PAPRelay, not a condition of the link or the machine. */
    DEFECT,
}

/** Base type for every failure raised by the AirSense 11 protocol layer. */
public sealed class As11Exception(message: String, cause: Throwable? = null) : Exception(message, cause) {
    public abstract val recovery: As11Recovery
}

/** The machine sent something malformed or out of sequence. */
public class As11ProtocolException(
    message: String,
    cause: Throwable? = null,
    override val recovery: As11Recovery = As11Recovery.RECONNECT,
) : As11Exception(message, cause)

/** Something about pairing or session authentication failed. */
public sealed class As11AuthException(message: String, cause: Throwable? = null) : As11Exception(message, cause) {
    /** The machine rejected the code the user typed. Ask again; nothing is stored. */
    public class PasskeyRejected(cause: Throwable?) : As11AuthException("the machine rejected the pairing code", cause) {
        override val recovery: As11Recovery get() = As11Recovery.ASK_USER
    }

    /**
     * The other end could not prove it is the machine, or sent values no honest
     * machine would. Never retried automatically.
     */
    public class MachineUnauthenticated(message: String) : As11AuthException(message) {
        override val recovery: As11Recovery get() = As11Recovery.DO_NOT_RETRY
    }

    /**
     * What was typed is not a code the machine could have shown. Nothing was
     * sent, so the machine's attempt is unspent, but this exchange is over:
     * pairing has to be started again.
     */
    public class PasskeyInvalid : As11AuthException("the code entered is not a pairing code") {
        override val recovery: As11Recovery get() = As11Recovery.ASK_USER
    }

    /** The machine answered session setup with an error for our client id. */
    public class PairingUnknown(cause: Throwable?) : As11AuthException("the machine does not recognise this pairing", cause) {
        override val recovery: As11Recovery get() = As11Recovery.PAIRING_SUSPECT
    }

    /** The machine rejected our proof of the pairing key. */
    public class SessionProofRejected(cause: Throwable?) : As11AuthException("the machine rejected the session proof", cause) {
        override val recovery: As11Recovery get() = As11Recovery.PAIRING_SUSPECT
    }

    /**
     * Session setup appeared to succeed, but no valid encrypted answer
     * followed. Only the real machine can produce one, so until it does the
     * session is not treated as open.
     *
     * This says nothing about the pairing: a forgotten pairing or a wrong key
     * is refused earlier, by name. Silence here means the phone is at the edge
     * of range, the machine dropped its first request, or the other end is not
     * the machine.
     */
    public class SessionUnproven(cause: Throwable?) : As11AuthException("the session could not be confirmed", cause) {
        override val recovery: As11Recovery get() = As11Recovery.RECONNECT
    }

    /**
     * Session setup presented a nonce that was used before. The setup messages
     * are not encrypted, so anyone in range can replay an old one; refusing it
     * and trying again on a new connection is the whole remedy.
     */
    public class NonceReplayed : As11AuthException("session setup reused a nonce") {
        override val recovery: As11Recovery get() = As11Recovery.RECONNECT
    }
}

/** The user abandoned pairing, or did not enter the code in time. */
public class As11PairingCancelledException(message: String = "pairing was cancelled") : As11Exception(message) {
    override val recovery: As11Recovery get() = As11Recovery.ASK_USER
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
    public val data: JsonElement? = null,
) : As11Exception("$method failed: code=$code${if (anonymous) " (no id)" else ""}") {
    /**
     * True when the machine could not make sense of the request at all, which
     * points at damage in transit and says nothing about what was asked.
     */
    public val requestNotUnderstood: Boolean get() = anonymous || code == PARSE_ERROR || code == INVALID_REQUEST

    /**
     * True when trying again later may succeed: the request was not understood,
     * or the machine failed internally.
     *
     * Pairing and session setup deliberately do not use this. The machine's
     * codes for a wrong code or an unknown pairing are not documented, and the
     * generic internal error is a plausible one, so there only
     * [requestNotUnderstood] counts as "not a decision".
     */
    public val transient: Boolean get() = requestNotUnderstood || code == INTERNAL_ERROR

    override val recovery: As11Recovery get() = if (transient) As11Recovery.RETRY_LATER else As11Recovery.DO_NOT_RETRY

    private companion object {
        const val PARSE_ERROR = -32700
        const val INVALID_REQUEST = -32600
        const val INTERNAL_ERROR = -32603
    }
}

/**
 * No answer arrived in time. The machine answers denied commands with silence,
 * so a request that times out on every connection is a refusal, not bad luck.
 */
public class As11TimeoutException(
    public val operation: String,
    /** Answers that arrived meanwhile but matched no request: a hint that ids are not echoed as expected. */
    public val unmatchedAnswers: Long = 0,
) : As11Exception("timed out: $operation" + if (unmatchedAnswers > 0) " ($unmatchedAnswers unmatched answers arrived)" else "") {
    override val recovery: As11Recovery get() = As11Recovery.RETRY_LATER
}

/**
 * A download round arrived damaged: a gap, a repeat or a hash mismatch, which a
 * second attempt usually cures; or more data than was asked for, which it will not.
 */
public class As11SpoolIntegrityException(
    message: String,
    override val recovery: As11Recovery = As11Recovery.REPEAT_ROUND,
) : As11Exception(message)

/**
 * A download round arrived without the hash that proves it is intact. PAPRelay
 * does not accept unverified data. A machine that never sends hashes cannot be
 * synced, and repeating the round will not change that.
 */
public class As11SpoolUnverifiedException : As11Exception("download arrived without an integrity hash") {
    override val recovery: As11Recovery get() = As11Recovery.DO_NOT_RETRY
}

/** A download round stopped arriving, or did not finish within its deadline. */
public class As11SpoolStalledException(public val fragmentsReceived: Int) :
    As11Exception("download stalled after $fragmentsReceived fragments") {
    override val recovery: As11Recovery get() = As11Recovery.REPEAT_ROUND
}

/** The machine reported that the requested stored data is not available. */
public class As11SpoolUnavailableException(public val status: String) : As11Exception("spool unavailable: $status") {
    override val recovery: As11Recovery get() = As11Recovery.DO_NOT_RETRY
}

/** The underlying link closed or failed. */
public class As11TransportException(message: String, cause: Throwable? = null) : As11Exception(message, cause) {
    override val recovery: As11Recovery get() = As11Recovery.RETRY_LATER
}

/** The record of which session nonces have been used could not be read or written. */
public class As11NonceStoreException(cause: Throwable) : As11Exception("the session record could not be stored", cause) {
    override val recovery: As11Recovery get() = As11Recovery.RETRY_LATER
}

/** PAPRelay itself failed while handling the machine's messages. */
public class As11DefectException(cause: Throwable) : As11Exception("internal error while reading from the machine", cause) {
    override val recovery: As11Recovery get() = As11Recovery.DEFECT
}
