// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.ui

import android.Manifest
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.health.connect.client.PermissionController
import com.everysoft.paprelay.PapRelayApp
import com.everysoft.paprelay.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    // Everything shown comes from state owned by the application, so it survives
    // rotation and theme changes while a check is running.
    val pairing by app.pairingStore.state.collectAsState()
    val steps by check.steps.collectAsState()
    val running by check.running.collectAsState()
    val passkeyRequest by check.passkeyRequest.collectAsState()
    val machineChoice by check.machineChoice.collectAsState()
    var showAbout by rememberSaveable { mutableStateOf(false) }
    var confirmForget by rememberSaveable { mutableStateOf(false) }
    var pendingFull by rememberSaveable { mutableStateOf(true) }
    val uiScope = rememberCoroutineScope()

    val healthPermission = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract(),
    ) { check.start(pendingFull) } // the check itself reports a missing grant

    val bluetoothPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        if (granted.values.all { it }) {
            uiScope.launch {
                if (app.healthConnect.available && !app.healthConnect.hasPermission()) {
                    healthPermission.launch(setOf(app.healthConnect.writePermission))
                } else {
                    check.start(pendingFull)
                }
            }
        } else {
            Toast.makeText(context, R.string.permission_bluetooth_denied, Toast.LENGTH_LONG).show()
        }
    }

    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) {
            uiScope.launch {
                val saved = withContext(Dispatchers.IO) {
                    runCatching {
                        // "wt" truncates: a shorter report must not leave the tail of an older file behind.
                        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(check.reportText().toByteArray()) }
                            ?: error("no stream")
                    }.isSuccess
                }
                if (!saved) Toast.makeText(context, R.string.export_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    fun request(full: Boolean) {
        pendingFull = full
        // Returns at once when already granted, then carries on to Health Connect.
        bluetoothPermission.launch(arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT))
    }

    BackHandler(enabled = showAbout) { showAbout = false }

    if (showAbout) {
        AboutScreen(onBack = { showAbout = false })
    } else {
        HomeScreen(
            pairing = pairing,
            steps = steps,
            running = running,
            hasReport = check.reportText().isNotEmpty(),
            onRunFull = { request(full = true) },
            onRunReconnect = { request(full = false) },
            onStop = { check.cancel() },
            onForget = { confirmForget = true },
            onExport = { export.launch("paprelay-device-check.txt") },
            onAbout = { showAbout = true },
        )
    }

    passkeyRequest?.let { request ->
        PasskeyDialog(
            onConfirm = { request.complete(it) },
            // Seen by the protocol layer as the user backing out of pairing.
            onCancel = { request.completeExceptionally(CancellationException("code entry dismissed")) },
        )
    }

    machineChoice?.let { choice ->
        ChoiceDialog(
            options = choice.options,
            onChoose = { choice.answer.complete(it) },
            onCancel = { check.cancel() },
        )
    }

    if (confirmForget) {
        ConfirmDialog(
            title = R.string.forget_title,
            body = R.string.forget_body,
            confirm = R.string.action_forget,
            onConfirm = {
                confirmForget = false
                app.scope.launch {
                    app.pairingStore.clear()
                    check.clearData()
                }
            },
            onCancel = { confirmForget = false },
        )
    }
}
