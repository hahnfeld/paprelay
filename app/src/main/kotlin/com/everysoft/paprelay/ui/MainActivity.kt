// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.health.connect.client.PermissionController
import com.everysoft.paprelay.PapRelayApp
import com.everysoft.paprelay.R
import com.everysoft.paprelay.pairing.Pairing
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { PapRelayTheme { Root(application as PapRelayApp) } }
    }
}

@Composable
private fun Root(app: PapRelayApp) {
    val context = LocalContext.current
    val check = app.deviceCheck
    val steps by check.steps.collectAsState()
    val busy by check.busy.collectAsState()
    val passkeyRequest by check.passkeyRequest.collectAsState()
    var pairing by remember { mutableStateOf<Pairing?>(app.pairingStore.load()) }
    var showAbout by rememberSaveable { mutableStateOf(false) }
    var confirmForget by rememberSaveable { mutableStateOf(false) }
    var pendingFull by rememberSaveable { mutableStateOf(true) }
    val uiScope = rememberCoroutineScope()

    // The check outlives this screen: it runs in the application's scope.
    fun start(full: Boolean) {
        app.scope.launch {
            check.run(full)
            pairing = app.pairingStore.load()
        }
    }

    val healthPermission = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract(),
    ) { start(pendingFull) } // the check itself reports a missing grant

    val bluetoothPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        if (granted.values.all { it }) {
            uiScope.launch {
                if (app.healthConnect.available && !app.healthConnect.hasPermission()) {
                    healthPermission.launch(setOf(app.healthConnect.writePermission))
                } else {
                    start(pendingFull)
                }
            }
        } else {
            Toast.makeText(context, R.string.permission_bluetooth_denied, Toast.LENGTH_LONG).show()
        }
    }

    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { it.write(check.reportText().toByteArray()) }
            }
        }
    }

    fun request(full: Boolean) {
        pendingFull = full
        val needed = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        if (needed.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
            bluetoothPermission.launch(needed) // already granted: returns at once and continues the flow
        } else {
            bluetoothPermission.launch(needed)
        }
    }

    BackHandler(enabled = showAbout) { showAbout = false }

    if (showAbout) {
        AboutScreen(onBack = { showAbout = false })
    } else {
        HomeScreen(
            pairing = pairing,
            steps = steps,
            busy = busy,
            hasReport = check.reportText().isNotEmpty(),
            onRunFull = { request(full = true) },
            onRunReconnect = { request(full = false) },
            onForget = { confirmForget = true },
            onExport = { export.launch("paprelay-device-check.txt") },
            onAbout = { showAbout = true },
        )
    }

    passkeyRequest?.let { request ->
        PasskeyDialog(
            onConfirm = { request.complete(it) },
            onCancel = { request.completeExceptionally(IllegalStateException("pairing cancelled")) },
        )
    }

    if (confirmForget) {
        ConfirmDialog(
            title = R.string.forget_title,
            body = R.string.forget_body,
            confirm = R.string.action_forget,
            onConfirm = {
                app.pairingStore.clear()
                pairing = null
                confirmForget = false
            },
            onCancel = { confirmForget = false },
        )
    }
}
