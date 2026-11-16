// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.everysoft.paprelay.PapRelayApp
import com.everysoft.paprelay.R
import com.everysoft.paprelay.pairing.PairingState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val BLUETOOTH_PERMISSIONS = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)

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
    var showCheck by rememberSaveable { mutableStateOf(false) }
    var confirmForget by rememberSaveable { mutableStateOf(false) }
    var confirmRewrite by rememberSaveable { mutableStateOf(false) }
    var pendingFull by rememberSaveable { mutableStateOf(true) }
    // What the permissions being asked for are wanted for: a device check, or a synchronisation.
    var pendingSync by rememberSaveable { mutableStateOf(false) }
    val sync by app.sync.status.collectAsState()
    var permissionRefused by rememberSaveable { mutableStateOf(false) }
    val forgetting by check.forgetting.collectAsState()
    val stopping by check.stopping.collectAsState()
    // Whether Health Connect already lets PAPRelay write, so the picture can show that leg as ready.
    var healthAllowed by remember { mutableStateOf(false) }
    val lifecycleScope = rememberCoroutineScope()
    fun refreshHealth() {
        lifecycleScope.launch {
            healthAllowed = try {
                app.healthConnect.available && app.healthConnect.hasPermission()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
        }
    }
    // On first showing, and again whenever a check or a synchronisation finishes.
    LaunchedEffect(running, sync.running) { if (!running && !sync.running) refreshHealth() }
    val uiScope = rememberCoroutineScope()

    // The user may grant the permission in system settings and come back.
    DisposableEffect(Unit) {
        val lifecycle = (context as? ComponentActivity)?.lifecycle
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (BLUETOOTH_PERMISSIONS.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
                    permissionRefused = false
                }
                refreshHealth()
            }
        }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }

    // A run takes a few minutes and needs the user nearby for the code; do not let the screen sleep on it.
    DisposableEffect(running) {
        val window = (context as? Activity)?.window
        if (running) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    val healthPermission = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract(),
    ) { if (pendingSync) app.sync.syncNow() else check.start(pendingFull) } // either reports a missing grant itself

    val bluetoothPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ ->
        // The result can come back empty if the request was interrupted, so the grant itself is checked.
        val allowed = BLUETOOTH_PERMISSIONS.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        // Whatever the reason, the same two ways forward apply: tap again, or open settings.
        permissionRefused = !allowed
        if (allowed) {
            uiScope.launch {
                // If Health Connect cannot even be asked, the check itself reports why.
                val needsGrant = try {
                    app.healthConnect.available && !app.healthConnect.hasPermission()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    false
                }
                if (needsGrant) {
                    healthPermission.launch(setOf(app.healthConnect.writePermission))
                } else if (pendingSync) {
                    app.sync.syncNow()
                } else {
                    check.start(pendingFull)
                }
            }
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

    fun request(full: Boolean, forSync: Boolean = false) {
        pendingFull = full
        pendingSync = forSync
        // Returns at once when already granted, then carries on to Health Connect.
        bluetoothPermission.launch(BLUETOOTH_PERMISSIONS)
    }

    BackHandler(enabled = showAbout || showCheck) {
        if (showAbout) showAbout = false else showCheck = false
    }

    if (showAbout) {
        AboutScreen(onBack = { showAbout = false })
    } else if (showCheck) {
        CheckScreen(
            pairing = pairing,
            steps = steps,
            running = running,
            stopping = stopping,
            hasReport = check.hasReport(),
            busy = forgetting,
            syncEnabled = app.sync.enabled,
            syncRunning = sync.running,
            onRunFull = { request(full = true) },
            onRunReconnect = { request(full = false) },
            onStop = { check.cancel() },
            onExport = { export.launch("paprelay-device-check.txt") },
            onRewrite = { confirmRewrite = true },
            onForget = { confirmForget = true },
            onBack = { showCheck = false },
        )
    } else {
        HomeScreen(
            pairing = pairing,
            steps = steps,
            checkRunning = running,
            busy = forgetting,
            syncEnabled = app.sync.enabled,
            sync = sync,
            healthAllowed = healthAllowed,
            permissionRefused = permissionRefused,
            onSync = { request(full = false, forSync = true) },
            onAcceptClock = { app.sync.acceptMachineClock() },
            onStartAgain = { app.sync.startAgain() },
            onPair = {
                // The steps are worth watching while the machine is paired for the first time.
                showCheck = true
                request(full = true)
            },
            onOpenCheck = { showCheck = true },
            onOpenSettings = {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                )
            },
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
                // Removes the pairing, and with it what synchronising remembers and its hourly run.
                check.forgetMachine()
            },
            onCancel = { confirmForget = false },
        )
    }

    if (confirmRewrite) {
        ConfirmDialog(
            title = R.string.rewrite_title,
            body = R.string.rewrite_body,
            confirm = R.string.action_rewrite,
            onConfirm = {
                confirmRewrite = false
                showCheck = false
                app.sync.rewriteEverything()
            },
            onCancel = { confirmRewrite = false },
            destructive = false,
        )
    }
}
