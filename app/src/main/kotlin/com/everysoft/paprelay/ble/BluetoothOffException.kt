// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.ble

import com.everysoft.paprelay.as11.As11TransportException

/**
 * Bluetooth is switched off on this phone, or was switched off part-way. The
 * link is gone like any other lost link, but the cause is here and not at the
 * machine, and the screen must say so.
 */
class BluetoothOffException(message: String) : As11TransportException(message)
