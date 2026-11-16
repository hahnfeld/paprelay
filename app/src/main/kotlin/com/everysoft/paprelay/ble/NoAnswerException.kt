// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.ble

import com.everysoft.paprelay.as11.As11TransportException

/**
 * A connection was asked for and nothing answered in time. The machine may be
 * out of range, switched off, or busy with another phone; unlike other link
 * failures, nothing at all was heard from it.
 */
class NoAnswerException(message: String) : As11TransportException(message)
