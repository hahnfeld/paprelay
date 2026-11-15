// SPDX-License-Identifier: GPL-3.0-or-later
//
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
// Based on SomnoTrace, originally created by Ilya Kruchinin (https://github.com/ilyakruchinin).
//
// GATT layout and write-chunking rules derived from SomnoTrace main/as11_ble.c
// (do_connect_and_discover, send_fig), Copyright (C) 2026 Ilya Kruchinin,
// GPL-3.0-or-later with the Section 7(b) attribution term reproduced in NOTICE.
// Written for Android 2026-11-15.
package com.everysoft.paprelay.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import com.everysoft.paprelay.as11.As11ProtocolException
import com.everysoft.paprelay.as11.As11Transport
import com.everysoft.paprelay.as11.As11TransportException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** What the machine's GATT database looked like, for diagnostics. */
data class GattSummary(val mtu: Int, val services: List<String>)

/**
 * One GATT connection to the machine, exposed as a byte pipe.
 *
 * Rules this class exists to enforce:
 * - one GATT operation in flight at a time, each with its own timeout;
 * - writes are ordinary write requests of at most MTU-3 bytes. A larger write
 *   would be turned into a "long write" by the stack, which the machine rejects
 *   and may not recover from;
 * - a connection object is never reused: after any disconnect it is closed.
 *
 * Callers must hold BLUETOOTH_CONNECT.
 */
@SuppressLint("MissingPermission")
class GattLink private constructor(
    private val gatt: BluetoothGatt,
    private val callback: Callback,
    private val thread: HandlerThread,
    private val tx: BluetoothGattCharacteristic,
    val summary: GattSummary,
) : As11Transport, AutoCloseable {

    private val closed = AtomicBoolean(false)

    override val incoming: Flow<ByteArray> = callback.incoming.receiveAsFlow()

    override suspend fun send(frame: ByteArray) {
        val chunk = callback.mtu - ATT_WRITE_OVERHEAD
        var at = 0
        while (at < frame.size) {
            val end = minOf(frame.size, at + chunk)
            val piece = frame.copyOfRange(at, end)
            val status = callback.operation("write", WRITE_TIMEOUT_MS) {
                gatt.writeCharacteristic(tx, piece, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                    BluetoothStatusCodes.SUCCESS
            }
            if (status != BluetoothGatt.GATT_SUCCESS) throw As11TransportException("write failed, status $status")
            at = end
        }
    }

    /** Disconnects and releases the connection. Safe to call more than once. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        callback.fail(As11TransportException("closed"))
        runCatching { gatt.disconnect() }
        runCatching { gatt.close() }
        thread.quitSafely()
    }

    private class Callback : BluetoothGattCallback() {
        val connected = CompletableDeferred<Unit>()
        val incoming = Channel<ByteArray>(Channel.UNLIMITED)
        private val pending = AtomicReference<CompletableDeferred<Int>?>(null)
        private val lock = Mutex()
        private val failure = AtomicReference<As11TransportException?>(null)

        @Volatile
        var mtu: Int = DEFAULT_MTU

        @Volatile
        var rx: UUID? = null

        /** Runs one GATT operation and waits for its completion callback. */
        suspend fun operation(name: String, timeoutMs: Long, start: () -> Boolean): Int = lock.withLock {
            failure.get()?.let { throw it }
            val done = CompletableDeferred<Int>()
            pending.set(done)
            try {
                failure.get()?.let { throw it }
                if (!start()) throw As11TransportException("$name could not be started")
                withTimeoutOrNull(timeoutMs) { done.await() } ?: throw As11TransportException("$name timed out")
            } finally {
                pending.set(null)
            }
        }

        fun fail(cause: As11TransportException) {
            if (!failure.compareAndSet(null, cause)) return
            connected.completeExceptionally(cause)
            pending.get()?.completeExceptionally(cause)
            incoming.close(cause)
        }

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                connected.complete(Unit)
            } else {
                fail(As11TransportException("link lost, status $status"))
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) this.mtu = mtu
            pending.get()?.complete(status)
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            pending.get()?.complete(status)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            pending.get()?.complete(status)
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            pending.get()?.complete(status)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (characteristic.uuid == rx) incoming.trySend(value)
        }

        override fun onServiceChanged(gatt: BluetoothGatt) {
            // Handles may have moved; the only safe response is a fresh connection.
            fail(As11TransportException("machine changed its GATT database"))
        }
    }

    companion object {
        val SERVICE: UUID = UUID.fromString("0000fd56-0000-1000-8000-00805f9b34fb")
        val TX: UUID = UUID.fromString("a6220002-35f1-4b20-afae-cb089d2044aa")
        val RX: UUID = UUID.fromString("a6220003-35f1-4b20-afae-cb089d2044aa")
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val DEFAULT_MTU = 23
        private const val REQUESTED_MTU = 517
        private const val ATT_WRITE_OVERHEAD = 3
        private const val WRITE_TIMEOUT_MS = 5_000L
        private const val MTU_TIMEOUT_MS = 5_000L
        private const val DISCOVERY_TIMEOUT_MS = 10_000L

        /**
         * Connects, negotiates the MTU, finds the machine's stream characteristics
         * and enables notifications.
         *
         * @throws As11TransportException if the link cannot be brought up.
         * @throws As11ProtocolException if the device is not an AirSense 11.
         */
        suspend fun open(context: Context, device: BluetoothDevice, connectTimeoutMs: Long): GattLink {
            val thread = HandlerThread("paprelay-gatt").apply { start() }
            val callback = Callback()
            val gatt = device.connectGatt(
                context.applicationContext,
                false,
                callback,
                BluetoothDevice.TRANSPORT_LE,
                BluetoothDevice.PHY_LE_1M_MASK,
                Handler(thread.looper),
            )
            try {
                withTimeoutOrNull(connectTimeoutMs) { callback.connected.await() }
                    ?: throw As11TransportException("machine did not answer")

                // Until the exchange completes only the default MTU may be assumed.
                runCatching { callback.operation("MTU exchange", MTU_TIMEOUT_MS) { gatt.requestMtu(REQUESTED_MTU) } }
                    .onFailure { if (it !is As11TransportException) throw it }

                val discovered = callback.operation("service discovery", DISCOVERY_TIMEOUT_MS) { gatt.discoverServices() }
                if (discovered != BluetoothGatt.GATT_SUCCESS) {
                    throw As11TransportException("service discovery failed, status $discovered")
                }
                val service = gatt.getService(SERVICE) ?: throw As11ProtocolException("device has no AirSense 11 service")
                val tx = service.getCharacteristic(TX) ?: throw As11ProtocolException("device has no command characteristic")
                val rx = service.getCharacteristic(RX) ?: throw As11ProtocolException("device has no data characteristic")
                val cccd = rx.getDescriptor(CCCD) ?: throw As11ProtocolException("data characteristic cannot notify")

                callback.rx = RX
                if (!gatt.setCharacteristicNotification(rx, true)) {
                    throw As11TransportException("notifications could not be enabled")
                }
                val enable = if (rx.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                } else {
                    BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                }
                val subscribed = callback.operation("subscribe", WRITE_TIMEOUT_MS) {
                    gatt.writeDescriptor(cccd, enable) == BluetoothStatusCodes.SUCCESS
                }
                if (subscribed != BluetoothGatt.GATT_SUCCESS) {
                    throw As11TransportException("subscribe failed, status $subscribed")
                }

                val services = gatt.services.map { s ->
                    s.uuid.toString() + s.characteristics.joinToString(prefix = " [", postfix = "]") {
                        it.uuid.toString().substring(0, 8) + ":" + it.properties.toString(16)
                    }
                }
                return GattLink(gatt, callback, thread, tx, GattSummary(callback.mtu, services))
            } catch (e: Throwable) {
                callback.fail(As11TransportException("setup abandoned"))
                runCatching { gatt.disconnect() }
                runCatching { gatt.close() }
                thread.quitSafely()
                throw e
            }
        }
    }
}
