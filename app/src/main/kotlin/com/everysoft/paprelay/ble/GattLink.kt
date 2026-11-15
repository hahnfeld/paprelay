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
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.HandlerThread
import com.everysoft.paprelay.as11.As11ProtocolException
import com.everysoft.paprelay.as11.As11Transport
import com.everysoft.paprelay.as11.As11TransportException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** What the machine's GATT database looked like, for diagnostics. */
data class GattSummary(val mtu: Int, val services: List<String>, val connectAttempts: Int)

/**
 * One GATT connection to the machine, exposed as a byte pipe.
 *
 * Rules this class exists to enforce:
 * - one GATT request in flight at a time, each matched to its own completion
 *   (see [GattOperations]);
 * - writes are ordinary write requests, never longer than the link allows. A
 *   longer write would be turned into a "long write" by the stack, which the
 *   machine rejects and may not recover from;
 * - a connection object is never reused: after any disconnect it is closed;
 * - received bytes are buffered from the moment notifications are enabled, and
 *   the stream ends with an error when the link drops or Bluetooth is turned off.
 *
 * Callers must hold BLUETOOTH_CONNECT.
 */
@SuppressLint("MissingPermission")
class GattLink private constructor(
    private val watcher: AdapterWatcher,
    private val gatt: BluetoothGatt,
    private val callback: Callback,
    private val thread: HandlerThread,
    private val tx: BluetoothGattCharacteristic,
    val summary: GattSummary,
) : As11Transport, AutoCloseable {

    private val closed = AtomicBoolean(false)

    override val incoming: Flow<ByteArray> = callback.incoming.receiveAsFlow()

    override suspend fun send(frame: ByteArray) {
        val chunk = writeChunk(callback.mtu)
        var at = 0
        try {
            while (at < frame.size) {
                val end = minOf(frame.size, at + chunk)
                val piece = frame.copyOfRange(at, end)
                val status = callback.operations.run(GattOperation.WRITE, WRITE_TIMEOUT_MS) {
                    gatt.writeCharacteristic(tx, piece, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                        BluetoothStatusCodes.SUCCESS
                }
                if (status != BluetoothGatt.GATT_SUCCESS) throw As11TransportException("write failed, status $status")
                at = end
            }
        } catch (e: As11TransportException) {
            // Whatever went wrong, part of a frame may be on the wire and a late
            // completion may still arrive: this link carries nothing further.
            callback.fail(e)
            throw e
        }
    }

    /** Disconnects and releases the connection. Safe to call more than once. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        watcher.stop()
        callback.fail(As11TransportException("closed"))
        runCatching { gatt.disconnect() }
        runCatching { gatt.close() }
        thread.quitSafely()
    }

    /**
     * Android reports nothing on a connection when the radio is switched off, so
     * that is watched for separately, from before the connection is attempted.
     */
    private class AdapterWatcher(private val context: Context, private val onOff: () -> Unit) : BroadcastReceiver() {
        private val registered = AtomicBoolean(false)

        fun start() {
            // The system is the only possible sender of this protected broadcast.
            context.registerReceiver(this, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), Context.RECEIVER_EXPORTED)
            registered.set(true)
        }

        fun stop() {
            if (registered.compareAndSet(true, false)) runCatching { context.unregisterReceiver(this) }
        }

        override fun onReceive(context: Context, intent: Intent) {
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_ON)
            if (state != BluetoothAdapter.STATE_ON) onOff()
        }
    }

    private class Callback : BluetoothGattCallback() {
        val operations = GattOperations()
        val connected = CompletableDeferred<Unit>()
        val incoming = Channel<ByteArray>(Channel.UNLIMITED)

        /** Status of the first disconnect seen before the link came up, for retry decisions. */
        @Volatile
        var connectFailureStatus: Int? = null

        @Volatile
        var mtu: Int = DEFAULT_MTU

        fun fail(cause: As11TransportException) {
            connected.completeExceptionally(cause)
            operations.fail(cause)
            incoming.close(cause)
        }

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                connected.complete(Unit)
            } else {
                if (!connected.isCompleted) connectFailureStatus = status
                fail(As11TransportException("link lost, status $status"))
            }
        }

        // The machine often starts the MTU exchange itself, so this can arrive unasked.
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) this.mtu = mtu
            operations.complete(GattOperation.MTU, status)
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            operations.complete(GattOperation.DISCOVERY, status)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.uuid == CCCD) operations.complete(GattOperation.SUBSCRIBE, status)
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (characteristic.uuid == TX) operations.complete(GattOperation.WRITE, status)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (characteristic.uuid == RX) incoming.trySend(value)
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

        /** The largest write the reference client has been seen to use (MTU 247). */
        private const val MAX_WRITE_CHUNK = 244
        private const val WRITE_TIMEOUT_MS = 5_000L
        /** Generous: Android queues this behind its own first service discovery. */
        private const val MTU_TIMEOUT_MS = 12_000L
        private const val DISCOVERY_TIMEOUT_MS = 10_000L

        /** Android's catch-all connection failure; often clears on a second attempt. */
        private const val GATT_ERROR = 133
        private const val RETRY_PAUSE_MS = 1_000L
        private const val MTU_REFUSED = -1

        /** How many bytes one write may carry at this MTU. */
        fun writeChunk(mtu: Int): Int = (mtu - ATT_WRITE_OVERHEAD).coerceIn(DEFAULT_MTU - ATT_WRITE_OVERHEAD, MAX_WRITE_CHUNK)

        /**
         * Connects, negotiates the MTU, finds the machine's stream characteristics
         * and enables notifications. A first attempt that fails with Android's
         * generic status 133 is retried once.
         *
         * @throws As11TransportException if the link cannot be brought up.
         * @throws As11ProtocolException if the device is not an AirSense 11.
         */
        suspend fun open(context: Context, device: BluetoothDevice, connectTimeoutMs: Long): GattLink {
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            if (adapter?.isEnabled != true) throw BluetoothOffException("Bluetooth is off")
            return try {
                attempt(context, device, connectTimeoutMs, attemptNumber = 1)
            } catch (e: RetryableConnectFailure) {
                delay(RETRY_PAUSE_MS)
                if (adapter.isEnabled != true) throw BluetoothOffException("Bluetooth was turned off")
                try {
                    attempt(context, device, connectTimeoutMs, attemptNumber = 2)
                } catch (again: RetryableConnectFailure) {
                    throw again.cause
                }
            }
        }

        private class RetryableConnectFailure(override val cause: As11TransportException) : Exception()

        private suspend fun attempt(
            context: Context,
            device: BluetoothDevice,
            connectTimeoutMs: Long,
            attemptNumber: Int,
        ): GattLink {
            val thread = HandlerThread("paprelay-gatt").apply { start() }
            val callback = Callback()
            val watcher = AdapterWatcher(context.applicationContext) {
                callback.fail(BluetoothOffException("Bluetooth was turned off"))
            }
            var gatt: BluetoothGatt? = null
            try {
                watcher.start()
                gatt = device.connectGatt(
                    context.applicationContext,
                    false,
                    callback,
                    BluetoothDevice.TRANSPORT_LE,
                    BluetoothDevice.PHY_LE_1M_MASK,
                    Handler(thread.looper),
                ) ?: throw As11TransportException("Bluetooth could not start a connection")

                try {
                    withTimeoutOrNull(connectTimeoutMs) { callback.connected.await() }
                        ?: throw As11TransportException("the machine did not answer")
                } catch (e: As11TransportException) {
                    if (callback.connectFailureStatus == GATT_ERROR) throw RetryableConnectFailure(e)
                    throw e
                }

                // Optional: if the stack refuses, or the machine declines, the link stays at the
                // default size, which is slow but correct. A request that was accepted and never
                // answered is different: nothing else can be sent behind it, so setup is abandoned.
                callback.operations.run(GattOperation.MTU, MTU_TIMEOUT_MS, refusedResult = MTU_REFUSED) {
                    gatt.requestMtu(REQUESTED_MTU)
                }

                val discovered = callback.operations.run(GattOperation.DISCOVERY, DISCOVERY_TIMEOUT_MS) { gatt.discoverServices() }
                if (discovered != BluetoothGatt.GATT_SUCCESS) {
                    throw As11TransportException("service discovery failed, status $discovered")
                }
                val service = gatt.getService(SERVICE) ?: throw As11ProtocolException("device has no AirSense 11 service")
                val tx = service.getCharacteristic(TX) ?: throw As11ProtocolException("device has no command characteristic")
                val rx = service.getCharacteristic(RX) ?: throw As11ProtocolException("device has no data characteristic")
                val cccd = rx.getDescriptor(CCCD) ?: throw As11ProtocolException("data characteristic cannot notify")

                if (!gatt.setCharacteristicNotification(rx, true)) {
                    throw As11TransportException("notifications could not be enabled")
                }
                val enable = if (rx.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                } else {
                    BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                }
                val subscribed = callback.operations.run(GattOperation.SUBSCRIBE, WRITE_TIMEOUT_MS) {
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
                return GattLink(watcher, gatt, callback, thread, tx, GattSummary(callback.mtu, services, attemptNumber))
            } catch (e: Throwable) {
                watcher.stop()
                callback.fail(As11TransportException("setup abandoned"))
                gatt?.let {
                    runCatching { it.disconnect() }
                    runCatching { it.close() }
                }
                thread.quitSafely()
                throw e
            }
        }
    }
}
