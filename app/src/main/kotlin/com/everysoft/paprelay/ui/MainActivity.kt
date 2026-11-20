// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.ui

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.health.connect.HealthConnectManager
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
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.everysoft.paprelay.PapRelayApp
import com.everysoft.paprelay.R
import com.everysoft.paprelay.sync.NightFile
import com.everysoft.paprelay.sync.SyncOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val BLUETOOTH_PERMISSIONS = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as PapRelayApp
        app.cleanUpOnce()
        setContent { PapRelayTheme { Root(app) } }
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
    val sync by app.sync.status.collectAsState()
    val nights by app.sync.nights.collectAsState()
    val forgetting by check.forgetting.collectAsState()
    val stopping by check.stopping.collectAsState()
    var page by rememberSaveable { mutableStateOf(PAGE_HOME) }
    var confirmForget by rememberSaveable { mutableStateOf(false) }
    var confirmRewrite by rememberSaveable { mutableStateOf(false) }
    var confirmClock by rememberSaveable { mutableStateOf(false) }
    // What is to happen once the permissions have been asked for: a device check, a
    // synchronisation, or a choice the user has just switched on.
    var pending by rememberSaveable { mutableStateOf(PENDING_SYNC) }
    var permissionRefused by rememberSaveable { mutableStateOf(false) }
    // True from asking for a permission until the answer has been dealt with. Not kept across a
    // restart of the screen: left set by mistake, it would stop the look on every later opening.
    var askingPermission by remember { mutableStateOf(false) }
    // Whether Health Connect lets PAPRelay write what is switched on to go there, so the picture
    // can show that leg as ready; and whether it was asked and still does not, so the way to its
    // settings can be shown.
    var healthAllowed by rememberSaveable { mutableStateOf(false) }
    var healthMissing by rememberSaveable { mutableStateOf(false) }
    var healthRefused by rememberSaveable { mutableStateOf(false) }
    var recordsAllowed by rememberSaveable { mutableStateOf(false) }
    var respiratoryAllowed by rememberSaveable { mutableStateOf(false) }
    // A choice the user tried to switch on and Health Connect did not allow: it stays off, and the row says why.
    var refusedChoice by rememberSaveable { mutableStateOf("") }
    // Whether Android would pause the app, and with it synchronising, if it is not opened for months.
    var pausesWhenUnused by rememberSaveable { mutableStateOf(false) }
    val recordsSupported = remember { app.healthRecords.supported }
    val uiScope = rememberCoroutineScope()

    /** The Health Connect permissions that what is switched on needs. */
    fun wanted(respiratoryRate: Boolean = sync.respiratoryRate, healthRecords: Boolean = sync.healthRecords): Set<String> = buildSet {
        if (respiratoryRate) add(app.healthConnect.writePermission)
        if (healthRecords && recordsSupported) add(app.healthRecords.writePermission)
    }

    fun missing(permissions: Set<String>): Set<String> =
        permissions.filterTo(LinkedHashSet()) { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

    fun refreshHealth() {
        val needed = wanted()
        healthMissing = missing(needed).isNotEmpty()
        healthAllowed = needed.isNotEmpty() && !healthMissing
        recordsAllowed = recordsSupported && app.healthRecords.allowed
        respiratoryAllowed = missing(setOf(app.healthConnect.writePermission)).isEmpty()
        if (!healthMissing) healthRefused = false
        if (refusedChoice == PENDING_RECORDS && recordsAllowed || refusedChoice == PENDING_RESPIRATORY && respiratoryAllowed) refusedChoice = ""
    }
    // On first showing, and again whenever a check or a synchronisation finishes or a choice changes.
    LaunchedEffect(running, sync.running, sync.respiratoryRate, sync.healthRecords) { if (!running && !sync.running) refreshHealth() }

    // The user may change things in system settings and come back.
    DisposableEffect(Unit) {
        val lifecycle = (context as? ComponentActivity)?.lifecycle
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (BLUETOOTH_PERMISSIONS.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
                    permissionRefused = false
                }
                pausesWhenUnused = !context.packageManager.isAutoRevokeWhitelisted
                refreshHealth()
                // What the screen says should be how things are now, not how they were at the last attempt.
                // Not on the way back from a permission question, whose answer starts what was asked for.
                if (!askingPermission) {
                    val status = app.sync.status.value
                    // Allowed in Health Connect's own settings a moment ago: the run that said "not allowed" is out of date at once.
                    // (Not within moments of that run: one that ends "not allowed" with nothing missing would
                    // otherwise bring another at every return to the screen.)
                    val report = status.lastReport
                    val settled = report != null && System.currentTimeMillis() - report.atMs > RECHECK_AFTER_MS
                    if (!healthMissing && !status.running && settled && report?.outcome == SyncOutcome.HEALTH_CONNECT_NOT_ALLOWED) {
                        app.sync.syncNow()
                    } else {
                        app.sync.onForeground()
                    }
                }
            }
        }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }

    // A check takes a few minutes and needs the user nearby for the code; do not let the screen sleep on it.
    DisposableEffect(running) {
        val window = (context as? Activity)?.window
        if (running) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    fun proceed() {
        askingPermission = false
        when (pending) {
            PENDING_CHECK -> check.start(true)
            PENDING_RECONNECT -> check.start(false)
            // A choice is switched on only once Health Connect allows what it needs: one that is on
            // and cannot write would only stand there saying so. Switching it on stores it and starts a run.
            PENDING_RECORDS -> if (app.healthRecords.allowed) app.sync.setHealthRecords(true) else refusedChoice = PENDING_RECORDS
            PENDING_RESPIRATORY ->
                if (missing(setOf(app.healthConnect.writePermission)).isEmpty()) app.sync.setRespiratoryRate(true) else refusedChoice = PENDING_RESPIRATORY
            else -> app.sync.syncNow()
        }
    }

    /** The Health Connect permissions the pending action needs, counting a choice about to be switched on. */
    fun wantedForPending(): Set<String> = when (pending) {
        PENDING_RECORDS -> wanted(healthRecords = true)
        PENDING_RESPIRATORY -> wanted(respiratoryRate = true)
        else -> wanted()
    }

    val healthPermission = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract(),
    ) {
        refreshHealth()
        // After two refusals Android no longer asks, and the request comes straight back: from then
        // on the way is through Health Connect's own settings.
        healthRefused = missing(wantedForPending()).isNotEmpty()
        // Whatever was asked for reports a missing grant itself.
        proceed()
    }

    /** Asks Health Connect for whatever the pending action still lacks, then carries it out. */
    fun askHealth() {
        val lacking = missing(wantedForPending())
        // If Health Connect cannot even be asked, the check or the synchronisation reports why.
        if (lacking.isNotEmpty() && app.healthConnect.available) healthPermission.launch(lacking) else proceed()
    }

    val bluetoothPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ ->
        // The result can come back empty if the request was interrupted, so the grant itself is checked.
        val allowed = BLUETOOTH_PERMISSIONS.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        // Whatever the reason, the same two ways forward apply: tap again, or open settings.
        permissionRefused = !allowed
        if (allowed) askHealth() else askingPermission = false
    }

    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) {
            uiScope.launch {
                val text = check.reportText()
                val saved = text.isNotEmpty() && withContext(Dispatchers.IO) {
                    runCatching {
                        // "wt" truncates: a shorter report must not leave the tail of an older file behind.
                        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write((text + app.sync.reportText()).toByteArray()) }
                            ?: error("no stream")
                    }.isSuccess
                }
                if (!saved) Toast.makeText(context, R.string.export_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    // The system's own "save as" screen: PAPRelay sees only the one file the user makes for it there.
    val file = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(NightFile.MIME)) { uri ->
        if (uri != null) app.sync.setExportFile(uri)
    }

    /** Asks for whatever permissions [action] needs, then carries it out. */
    fun request(action: String) {
        pending = action
        askingPermission = true
        // A choice switched on needs nothing of Bluetooth. Everything else talks to the machine;
        // the request returns at once when already granted, then carries on to Health Connect.
        if (action == PENDING_RECORDS || action == PENDING_RESPIRATORY) askHealth() else bluetoothPermission.launch(BLUETOOTH_PERMISSIONS)
    }

    fun open(vararg intents: Intent) {
        for (intent in intents) {
            try {
                context.startActivity(intent)
                return
            } catch (e: ActivityNotFoundException) {
                // The next one, if there is one.
            } catch (e: RuntimeException) {
                // Not allowed to, or too much to hand over at once: the same.
            }
        }
    }

    val appSettings = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
    val openSettings = { open(appSettings) }
    val openHealthConnect = {
        open(
            Intent(HealthConnectManager.ACTION_MANAGE_HEALTH_PERMISSIONS).putExtra(Intent.EXTRA_PACKAGE_NAME, context.packageName),
            Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS),
        )
    }
    val share = {
        // Handed to whichever app the user picks from the system's own list; PAPRelay sends nothing itself.
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, app.sync.shareText())
        open(Intent.createChooser(send, null))
    }

    BackHandler(enabled = page != PAGE_HOME) { page = PAGE_HOME }

    when (page) {
        PAGE_ABOUT -> AboutScreen(onBack = { page = PAGE_HOME })
        PAGE_NIGHTS -> NightsScreen(nights = nights, onShare = share, onBack = { page = PAGE_HOME })
        PAGE_CHOICES -> ChoicesScreen(
            sync = sync,
            hasNights = nights.any { it.complete },
            recordsSupported = recordsSupported,
            // Switched on and not allowed (the permission was taken away), or just tried and refused.
            recordsBlocked = recordsSupported && (sync.healthRecords && !recordsAllowed || refusedChoice == PENDING_RECORDS),
            respiratoryBlocked = sync.rateReadable && (sync.respiratoryRate && !respiratoryAllowed || refusedChoice == PENDING_RESPIRATORY),
            onRecords = { on ->
                refusedChoice = ""
                if (on) request(PENDING_RECORDS) else app.sync.setHealthRecords(false)
            },
            onRespiratoryRate = { on ->
                refusedChoice = ""
                if (on) request(PENDING_RESPIRATORY) else app.sync.setRespiratoryRate(false)
            },
            onOpenHealthConnect = openHealthConnect,
            onChooseFile = { file.launch(NightFile.SUGGESTED_NAME) },
            onNoFile = { app.sync.setExportFile(null) },
            onShare = share,
            onBack = { page = PAGE_HOME },
        )
        PAGE_CHECK -> CheckScreen(
            pairing = pairing,
            steps = steps,
            running = running,
            stopping = stopping,
            hasReport = check.hasReport(),
            busy = forgetting,
            syncEnabled = app.sync.enabled,
            syncRunning = sync.running,
            permissionRefused = permissionRefused,
            onRunFull = { request(PENDING_CHECK) },
            onRunReconnect = { request(PENDING_RECONNECT) },
            onStop = { check.cancel() },
            onSync = {
                page = PAGE_HOME
                request(PENDING_SYNC)
            },
            onExport = { export.launch("paprelay-device-check.txt") },
            onRewrite = { confirmRewrite = true },
            onForget = { confirmForget = true },
            onOpenSettings = openSettings,
            onBack = { page = PAGE_HOME },
        )
        else -> HomeScreen(
            pairing = pairing,
            steps = steps,
            checkRunning = running,
            busy = forgetting,
            syncEnabled = app.sync.enabled,
            sync = sync,
            nights = nights,
            healthAllowed = healthAllowed,
            healthMissing = healthMissing,
            healthRefused = healthRefused,
            permissionRefused = permissionRefused,
            pausesWhenUnused = pausesWhenUnused,
            onSync = { request(PENDING_SYNC) },
            onAllowHealth = { request(PENDING_SYNC) },
            onAcceptClock = { confirmClock = true },
            onStartAgain = { app.sync.startAgain() },
            onPair = {
                // The steps are worth watching while the machine is paired for the first time.
                page = PAGE_CHECK
                request(PENDING_CHECK)
            },
            onOpenCheck = { page = PAGE_CHECK },
            onOpenNights = { page = PAGE_NIGHTS },
            onOpenChoices = { page = PAGE_CHOICES },
            onOpenSettings = openSettings,
            onOpenHealthConnect = openHealthConnect,
            onOpenUnusedSetting = {
                open(Intent(Intent.ACTION_AUTO_REVOKE_PERMISSIONS, Uri.fromParts("package", context.packageName, null)), appSettings)
            },
            onAbout = { page = PAGE_ABOUT },
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
                // Removes the pairing, and with it what synchronising remembers and its background runs.
                // Not while something else has the machine, such as a run that began a moment ago.
                if (!check.forgetMachine()) Toast.makeText(context, R.string.busy_try_again, Toast.LENGTH_LONG).show()
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
                page = PAGE_HOME
                app.sync.rewriteEverything()
            },
            onCancel = { confirmRewrite = false },
            destructive = false,
        )
    }

    if (confirmClock) {
        ConfirmDialog(
            title = R.string.accept_clock_title,
            body = R.string.accept_clock_body,
            confirm = R.string.action_confirm,
            onConfirm = {
                confirmClock = false
                app.sync.acceptMachineClock()
            },
            onCancel = { confirmClock = false },
        )
    }
}

private const val RECHECK_AFTER_MS = 15_000L

private const val PAGE_HOME = "home"
private const val PAGE_ABOUT = "about"
private const val PAGE_CHECK = "check"
private const val PAGE_NIGHTS = "nights"
private const val PAGE_CHOICES = "choices"

private const val PENDING_SYNC = "sync"
private const val PENDING_CHECK = "check"
private const val PENDING_RECONNECT = "reconnect"
private const val PENDING_RECORDS = "records"
private const val PENDING_RESPIRATORY = "respiratory"
