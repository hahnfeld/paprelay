// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.sync

import android.Manifest
import android.content.Context
import android.bluetooth.BluetoothDevice
import android.content.pm.PackageManager
import com.everysoft.paprelay.as11.As11Client
import com.everysoft.paprelay.as11.As11ProtocolException
import com.everysoft.paprelay.as11.GetItem
import com.everysoft.paprelay.as11.MachineClockReading
import com.everysoft.paprelay.as11.SpoolAddress
import com.everysoft.paprelay.as11.SpoolName
import com.everysoft.paprelay.as11.SpoolRound
import com.everysoft.paprelay.ble.BluetoothOffException
import com.everysoft.paprelay.ble.GattLink
import com.everysoft.paprelay.ble.MachineScanner
import com.everysoft.paprelay.ble.NoAnswerException
import com.everysoft.paprelay.pairing.FileNonceRegistry
import com.everysoft.paprelay.pairing.PairingState
import com.everysoft.paprelay.pairing.PairingStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/**
 * Sessions with the paired machine over the phone's Bluetooth.
 *
 * It connects to the stored address and to nothing else, so no other machine in range can
 * be picked up by mistake (if it listens at all, it listens for that address only), and
 * the session only counts once the machine has proved it holds the pairing key.
 */
class GattMachineConnector(
    private val context: Context,
    private val scope: CoroutineScope,
    private val pairings: PairingStore,
    private val scanner: MachineScanner,
) : MachineConnector {
    override fun notReady(): SyncOutcome? = when {
        pairings.state.value !is PairingState.Paired -> SyncOutcome.NOT_PAIRED
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED -> SyncOutcome.BLUETOOTH_NOT_ALLOWED
        !scanner.bluetoothEnabled -> SyncOutcome.BLUETOOTH_OFF
        else -> null
    }

    override fun pairedSerial(): String? = (pairings.state.value as? PairingState.Paired)?.pairing?.serialNumber

    override suspend fun <T> withSession(block: suspend (MachineSession) -> T): T {
        val paired = (pairings.current() as? PairingState.Paired)?.pairing ?: throw IllegalStateException("no pairing")
        val device = scanner.deviceByAddress(paired.address) ?: throw BluetoothOffException("Bluetooth is off")
        val link = try {
            GattLink.open(context, device, CONNECT_MS)
        } catch (e: NoAnswerException) {
            // No answer to a direct call. If the machine can be heard announcing itself it is
            // there after all, and one more call is worth making; if it cannot, it is away.
            val heard = listenFor(paired.address) ?: throw e
            GattLink.open(context, heard, CONNECT_MS)
        }
        try {
            return As11Client(link, scope).use { client ->
                client.startSession(paired.credentials, FileNonceRegistry(File(context.noBackupFilesDir, "nonces.txt")))
                block(Session(client))
            }
        } finally {
            link.close()
        }
    }

    /** Listens briefly for the paired machine's own address, and for nothing else. Null if it is not heard. */
    private suspend fun listenFor(address: String): BluetoothDevice? {
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) return null
        val sightings = try {
            scanner.scan(LISTEN_MS, platformFilter = false, stopAtAddress = address, addressFilter = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: BluetoothOffException) {
            throw e
        } catch (e: Exception) {
            return null
        }
        return sightings.firstOrNull { it.device.address.equals(address, ignoreCase = true) }?.device
    }

    private class Session(private val client: As11Client) : MachineSession {
        override suspend fun identity(): MachineIdentity {
            val read = client.get(listOf(GetItem.SERIAL_NUMBER, GetItem.APPLICATION_IDENTIFIER)).values
            fun text(item: GetItem) = (read[item.wireName] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw As11ProtocolException("the machine did not say what it is")
            return MachineIdentity(text(GetItem.SERIAL_NUMBER), text(GetItem.APPLICATION_IDENTIFIER))
        }

        override suspend fun clock(): MachineClockReading = client.getDateTime()

        override suspend fun pull(log: SpoolName, from: SpoolAddress): SpoolRound = client.pullSpoolRound(log, from)
    }

    private companion object {
        /** A machine in the next room answers in a second or two. Longer than this and it is not there. */
        const val CONNECT_MS = 15_000L

        /**
         * A machine that is switched on announces itself several times a second, but a phone
         * with its screen off listens only for a moment every few seconds.
         */
        const val LISTEN_MS = 12_000L
    }
}
