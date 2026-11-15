// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.as11

/** Base type for every failure raised by the AirSense 11 protocol layer. */
public sealed class As11Exception(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The machine sent something malformed, out of sequence or failing an integrity check. */
public class As11ProtocolException(message: String, cause: Throwable? = null) : As11Exception(message, cause)

/** The machine rejected our credentials, or failed to prove it knows the pairing secret. */
public class As11AuthException(message: String) : As11Exception(message)

/** The machine answered a request with a JSON-RPC error object. */
public class As11RpcException(public val method: String, public val code: Int?, public val deviceMessage: String?) :
    As11Exception("$method failed: code=$code")

/**
 * No answer arrived in time. The machine answers denied commands with silence,
 * so this can be a refusal as well as a link problem.
 */
public class As11TimeoutException(public val operation: String) : As11Exception("timed out: $operation")

/** The machine reported that the requested stored data is not available. */
public class As11SpoolUnavailableException(public val status: String) : As11Exception("spool unavailable: $status")

/** The underlying link closed or failed. */
public class As11TransportException(message: String, cause: Throwable? = null) : As11Exception(message, cause)
