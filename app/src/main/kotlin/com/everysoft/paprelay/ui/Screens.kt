// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.everysoft.paprelay.BuildConfig
import com.everysoft.paprelay.R
import com.everysoft.paprelay.sync.SyncOutcome
import com.everysoft.paprelay.sync.SyncStatus
import android.text.format.DateUtils
import com.everysoft.paprelay.check.CheckSteps
import com.everysoft.paprelay.check.Message
import com.everysoft.paprelay.check.Step
import com.everysoft.paprelay.check.StepState
import com.everysoft.paprelay.pairing.PairingState

/**
 * Common page frame: one scrolling column of readable width. The scroll area
 * runs edge to edge, so content passes behind the system bars; the insets are
 * applied inside it, so nothing rests under them.
 */
@Composable
private fun Page(
    title: String,
    onBack: (() -> Unit)? = null,
    action: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
        // Top and sides are inset outside the scroll, so text never slides under the status
        // bar. The bottom is inset inside it, so the last lines scroll clear of the navigation bar.
        Box(
            Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                    .padding(horizontal = 24.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 28.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (onBack != null) {
                        BackButton(onBack)
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        title,
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.weight(1f).semantics { heading() },
                    )
                    action?.invoke()
                }
                content()
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

/** A plain arrow, drawn here so the app needs no icon library. */
@Composable
private fun BackButton(onBack: () -> Unit) {
    val description = stringResource(R.string.action_back)
    val color = MaterialTheme.colorScheme.onSurface
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(onClick = onBack)
            .semantics {
                contentDescription = description
                role = Role.Button
            },
        contentAlignment = Alignment.Center,
    ) {
        // The arrow points back the way reading came from, so it flips in right-to-left languages.
        val flip = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f
        Canvas(Modifier.size(20.dp).scale(scaleX = flip, scaleY = 1f)) {
            val width = 2.dp.toPx()
            val middle = size.height / 2
            val head = size.width * 0.38f
            drawLine(color, Offset(0f, middle), Offset(size.width, middle), width, StrokeCap.Round)
            drawLine(color, Offset(0f, middle), Offset(head, middle - head), width, StrokeCap.Round)
            drawLine(color, Offset(0f, middle), Offset(head, middle + head), width, StrokeCap.Round)
        }
    }
}

@Composable
private fun Secondary(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

@Composable
fun HomeScreen(
    pairing: PairingState,
    steps: List<Step>,
    running: Boolean,
    hasReport: Boolean,
    busy: Boolean,
    sync: SyncStatus,
    onSync: () -> Unit,
    onAcceptClock: () -> Unit,
    stopping: Boolean,
    healthAllowed: Boolean,
    permissionRefused: Boolean,
    onOpenSettings: () -> Unit,
    onRunFull: () -> Unit,
    onRunReconnect: () -> Unit,
    onStop: () -> Unit,
    onForget: () -> Unit,
    onExport: () -> Unit,
    onAbout: () -> Unit,
) {
    Page(
        title = stringResource(R.string.app_name),
        action = { TextButton(onClick = onAbout) { Text(stringResource(R.string.about_title)) } },
    ) {
        // The picture comes first: it says what the app does and how far it has got.
        FlowDiagram(
            machineLink = machineLink(steps, pairing, running),
            healthLink = healthLink(steps, healthAllowed),
            // A failure that lies with the phone is marked on the phone, not on either link.
            phoneFault = steps.any { it.state == StepState.FAILED && it.onPhone && it.id != CheckSteps.HEALTH },
        )
        Spacer(Modifier.height(28.dp))
        when (pairing) {
            PairingState.Loading -> Text(stringResource(R.string.machine_loading), style = MaterialTheme.typography.titleMedium)
            PairingState.Absent -> {
                Text(stringResource(R.string.machine_unpaired), style = MaterialTheme.typography.titleMedium)
                Secondary(stringResource(R.string.machine_unpaired_hint), Modifier.padding(top = 4.dp))
            }
            PairingState.Unreadable -> {
                Text(stringResource(R.string.machine_unreadable), style = MaterialTheme.typography.titleMedium)
                Secondary(stringResource(R.string.machine_unreadable_hint), Modifier.padding(top = 4.dp))
            }
            PairingState.Unavailable -> {
                Text(stringResource(R.string.machine_unavailable), style = MaterialTheme.typography.titleMedium)
                Secondary(stringResource(R.string.machine_unavailable_hint), Modifier.padding(top = 4.dp))
            }
            is PairingState.Paired -> {
                val serial = pairing.pairing.serialNumber
                Text(
                    if (serial != null) stringResource(R.string.machine_paired, serial.takeLast(4)) else stringResource(R.string.machine_paired_unknown),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }

        if (pairing is PairingState.Paired) {
            // Synchronising is what the app is for; the check below it is for finding faults.
            Secondary(syncLine(sync), Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(16.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSync, enabled = !sync.running && !running && !busy) {
                    Text(stringResource(if (sync.running) R.string.sync_running else R.string.action_sync))
                }
                if (!sync.running && sync.lastReport?.outcome == SyncOutcome.CLOCK_CHANGED) {
                    OutlinedButton(onClick = onAcceptClock) { Text(stringResource(R.string.action_accept_clock)) }
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        // Wraps instead of clipping at large font sizes.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (running) {
                // Stopping can take a moment: a step that must not be cut short is finished first.
                OutlinedButton(onClick = onStop, enabled = !stopping) {
                    Text(stringResource(if (stopping) R.string.action_stopping else R.string.action_stop))
                }
            } else {
                // Held back while a pairing is being forgotten, so a check cannot start on it.
                // Once a machine is paired the check is the lesser action, and looks it.
                if (pairing is PairingState.Paired) {
                    OutlinedButton(onClick = onRunFull, enabled = !busy && !sync.running) { Text(stringResource(R.string.action_run_check)) }
                } else {
                    Button(onClick = onRunFull, enabled = !busy && pairing != PairingState.Loading) { Text(stringResource(R.string.action_run_check)) }
                }
                if (pairing is PairingState.Paired) {
                    OutlinedButton(onClick = onRunReconnect, enabled = !busy && !sync.running) { Text(stringResource(R.string.action_reconnect_check)) }
                }
            }
        }

        if (permissionRefused && !running) {
            Secondary(stringResource(R.string.permission_needed), Modifier.padding(top = 12.dp))
            TextButton(onClick = onOpenSettings, modifier = Modifier.offset(x = (-12).dp)) {
                Text(stringResource(R.string.action_open_settings))
            }
        }

        // Nothing is listed until a check has been started: an untouched list is only noise.
        val started = running || steps.any { it.state != StepState.PENDING }
        if (started) {
            Spacer(Modifier.height(32.dp))
            Text(
                verdict(steps, running),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics {
                    heading()
                    liveRegion = LiveRegionMode.Polite
                },
            )
            Spacer(Modifier.height(8.dp))
            steps.forEachIndexed { index, step ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                StepRow(step)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }

        val canExport = hasReport && !running
        // Offered whenever something is stored, readable or not, so there is always a way to start over.
        val canForget = pairing is PairingState.Paired || pairing == PairingState.Unreadable || pairing == PairingState.Unavailable
        if (canExport || canForget) {
            Spacer(Modifier.height(8.dp))
            // Text buttons carry 12 dp of padding; pull them out so their labels sit on the text margins.
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                if (canExport) {
                    TextButton(onClick = onExport, modifier = Modifier.offset(x = (-12).dp)) {
                        Text(stringResource(R.string.action_export))
                    }
                }
                if (canForget) {
                    TextButton(onClick = onForget, enabled = !running && !busy, modifier = Modifier.offset(x = if (canExport) 12.dp else (-12).dp)) {
                        Text(stringResource(R.string.action_forget), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        Secondary(stringResource(R.string.early_build_notice))
    }
}

/**
 * The machine-to-phone leg, from the steps that travel along it.
 *
 * A failure that lies with the phone itself (its storage, Bluetooth being off)
 * is not drawn as a broken link to the machine: the machine was never the
 * problem. A tick needs both a passed check and a pairing that still exists.
 */
private fun machineLink(steps: List<Step>, pairing: PairingState, running: Boolean): LinkState {
    val leg = steps.filter { it.id != CheckSteps.HEALTH }
    val healthDone = steps.firstOrNull { it.id == CheckSteps.HEALTH }?.state.let { it != StepState.PENDING && it != StepState.RUNNING }
    val machineFailed = leg.any { it.state == StepState.FAILED && !it.onPhone }
    val paired = pairing is PairingState.Paired
    return when {
        leg.any { it.state == StepState.RUNNING } -> LinkState.ACTIVE
        // Between two steps of a run, such as the pause after pairing.
        running && healthDone && !machineFailed && leg.any { it.state == StepState.PENDING } -> LinkState.ACTIVE
        machineFailed -> LinkState.FAILED
        paired && leg.lastOrNull()?.state == StepState.PASSED -> LinkState.OK
        paired -> LinkState.READY
        else -> LinkState.NONE
    }
}

/** The phone-to-Health-Connect leg. */
private fun healthLink(steps: List<Step>, allowed: Boolean): LinkState =
    when (steps.firstOrNull { it.id == CheckSteps.HEALTH }?.state) {
        StepState.RUNNING -> LinkState.ACTIVE
        StepState.PASSED -> LinkState.OK
        StepState.FAILED -> LinkState.FAILED
        else -> if (allowed) LinkState.READY else LinkState.NONE
    }

/** One line that says how the whole check went. Shown only once a check has started. */
@Composable
private fun verdict(steps: List<Step>, running: Boolean): String {
    val failed = steps.count { it.state == StepState.FAILED }
    return when {
        running -> stringResource(R.string.verdict_running)
        steps.any { it.state == StepState.CANCELLED } -> stringResource(R.string.verdict_stopped)
        failed > 0 -> pluralStringResource(R.plurals.verdict_failed, failed, failed)
        // "Passed" means the last step did, not merely that nothing went wrong.
        steps.lastOrNull()?.state == StepState.PASSED -> stringResource(R.string.verdict_passed)
        else -> stringResource(R.string.verdict_incomplete)
    }
}

/** This message in the user's language. */
@Composable
private fun Message.text(): String = stringResource(id, *args.map { if (it is Message) it.text() else it }.toTypedArray())

@Composable
private fun StepRow(step: Step) {
    val stateText = stringResource(
        when (step.state) {
            StepState.PASSED -> R.string.state_passed
            StepState.FAILED -> R.string.state_failed
            StepState.RUNNING -> R.string.state_running
            StepState.SKIPPED -> R.string.state_skipped
            StepState.NOTED -> R.string.state_noted
            StepState.CANCELLED -> R.string.state_cancelled
            StepState.PENDING -> R.string.state_pending
        },
    )
    val detail = step.detail?.text()
    // Read as one item: title, outcome, detail.
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 14.dp)
            .semantics(mergeDescendants = true) { stateDescription = stateText },
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.padding(top = 4.dp).size(16.dp), contentAlignment = Alignment.Center) { StateMark(step.state) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(CheckSteps.title(step.id)), style = MaterialTheme.typography.bodyLarge)
            if (!detail.isNullOrEmpty()) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (step.state == StepState.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

/**
 * A small mark whose shape tells the states apart, so it does not depend on
 * colour: a tick, a cross, a dash, a dot, a ring. The tick is drawn in the
 * text colour because the phone's accent colour can be any hue, red included.
 */
@Composable
private fun StateMark(state: StepState) {
    val scheme = MaterialTheme.colorScheme
    if (state == StepState.RUNNING) {
        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
        return
    }
    val color: Color = when (state) {
        StepState.PASSED -> scheme.onSurface
        StepState.FAILED -> scheme.error
        else -> scheme.outline
    }
    Canvas(Modifier.size(12.dp)) {
        val w = size.width
        val h = size.height
        val thick = 2.dp.toPx()
        when (state) {
            StepState.PASSED -> {
                drawLine(color, Offset(0f, h * 0.55f), Offset(w * 0.38f, h * 0.9f), thick, StrokeCap.Round)
                drawLine(color, Offset(w * 0.38f, h * 0.9f), Offset(w, h * 0.12f), thick, StrokeCap.Round)
            }
            StepState.FAILED -> {
                drawLine(color, Offset(0f, 0f), Offset(w, h), thick, StrokeCap.Round)
                drawLine(color, Offset(w, 0f), Offset(0f, h), thick, StrokeCap.Round)
            }
            StepState.SKIPPED, StepState.CANCELLED ->
                drawLine(color, Offset(0f, h / 2), Offset(w, h / 2), 1.5.dp.toPx(), StrokeCap.Round)
            StepState.NOTED -> drawCircle(color, radius = w * 0.28f)
            else -> drawCircle(color, style = Stroke(width = 1.5.dp.toPx()))
        }
    }
}

@Composable
fun AboutScreen(onBack: () -> Unit) {
    Page(title = stringResource(R.string.about_title), onBack = onBack) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.about_version, BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.about_attribution), style = MaterialTheme.typography.bodyLarge)
        Paragraph(R.string.about_licence)
        Heading(R.string.about_intended_use_heading)
        Paragraph(R.string.about_intended_use)
        Heading(R.string.about_check_heading)
        Paragraph(R.string.about_check)
        Heading(R.string.about_disclaimer_heading)
        Paragraph(R.string.about_disclaimer)
        Spacer(Modifier.height(12.dp))
        Paragraph(R.string.about_affiliation)
    }
}

@Composable
fun PrivacyScreen() {
    Page(title = stringResource(R.string.privacy_title)) {
        Heading(R.string.privacy_heading_what, first = true)
        Paragraph(R.string.privacy_what)
        Heading(R.string.privacy_heading_where)
        Paragraph(R.string.privacy_where)
        Heading(R.string.privacy_heading_read)
        Paragraph(R.string.privacy_read)
        Heading(R.string.privacy_heading_delete)
        Paragraph(R.string.privacy_delete)
    }
}

@Composable
private fun Heading(@StringRes text: Int, first: Boolean = false) {
    Text(
        stringResource(text),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = if (first) 0.dp else 24.dp).semantics { heading() },
    )
}

@Composable
private fun Paragraph(@StringRes text: Int) {
    Secondary(stringResource(text), Modifier.padding(top = 6.dp))
}

/**
 * Cannot be dismissed by a stray tap or the back gesture: abandoning the code
 * costs a trip back to the machine to restart pairing. The typed code survives
 * rotation.
 */
@Composable
fun PasskeyDialog(onConfirm: (String) -> Unit, onCancel: () -> Unit) {
    var code by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.passkey_title)) },
        text = {
            Column {
                // Requested from inside the dialog's own content, once the field exists.
                LaunchedEffect(Unit) { focus.requestFocus() }
                Text(stringResource(R.string.passkey_body), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = { entered -> code = entered.filter(Char::isDigit).take(8) },
                    label = { Text(stringResource(R.string.passkey_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (code.length >= 4) onConfirm(code) }),
                    supportingText = { Text(stringResource(R.string.passkey_expiry)) },
                    modifier = Modifier.focusRequester(focus),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(code) }, enabled = code.length >= 4) { Text(stringResource(R.string.action_confirm)) }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
fun ChoiceDialog(options: List<Message>, onChoose: (Int) -> Unit, onCancel: () -> Unit) {
    var selected by rememberSaveable { mutableIntStateOf(0) }
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.choose_title)) },
        text = {
            Column(Modifier.selectableGroup()) {
                Text(stringResource(R.string.choose_body), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                options.forEachIndexed { index, label ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(selected = selected == index, role = Role.RadioButton, onClick = { selected = index }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected == index, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(label.text(), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onChoose(selected) }) { Text(stringResource(R.string.action_use_this)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
fun ConfirmDialog(
    @StringRes title: Int,
    @StringRes body: Int,
    @StringRes confirm: Int,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(title)) },
        text = { Text(stringResource(body), style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(confirm), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** One sentence on how synchronising stands. */
@Composable
private fun syncLine(sync: SyncStatus): String {
    val report = sync.lastReport
    return when {
        sync.running -> stringResource(R.string.sync_running)
        sync.storeUnreadable -> stringResource(R.string.sync_store_unreadable)
        report == null -> stringResource(R.string.sync_never)
        else -> when (report.outcome) {
            SyncOutcome.COMPLETE -> stringResource(
                R.string.sync_complete,
                DateUtils.getRelativeTimeSpanString(report.atMs, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString(),
            )
            SyncOutcome.PARTIAL -> stringResource(R.string.sync_partial)
            SyncOutcome.MACHINE_AWAY -> stringResource(R.string.sync_machine_away)
            SyncOutcome.BLUETOOTH_OFF -> stringResource(R.string.detail_bluetooth_off)
            SyncOutcome.BLUETOOTH_NOT_ALLOWED -> stringResource(R.string.sync_bluetooth_not_allowed)
            SyncOutcome.HEALTH_CONNECT_NOT_ALLOWED -> stringResource(R.string.sync_hc_not_allowed)
            SyncOutcome.HEALTH_CONNECT_UNAVAILABLE -> stringResource(R.string.detail_hc_unavailable)
            SyncOutcome.PAIRING_REJECTED -> stringResource(R.string.sync_pairing_rejected)
            SyncOutcome.WRONG_MACHINE -> stringResource(R.string.sync_wrong_machine)
            SyncOutcome.SOFTWARE_NOT_CONFIRMED -> stringResource(R.string.sync_software_not_confirmed)
            SyncOutcome.CLOCK_UNRELIABLE -> stringResource(R.string.sync_clock_unreliable)
            SyncOutcome.CLOCK_CHANGED -> stringResource(R.string.sync_clock_changed)
            SyncOutcome.NOT_PAIRED -> stringResource(R.string.detail_not_paired)
            SyncOutcome.CONNECTION_LOST -> stringResource(R.string.sync_connection_lost)
            SyncOutcome.DATA_NOT_UNDERSTOOD -> stringResource(R.string.sync_data_not_understood)
            SyncOutcome.HEALTH_CONNECT_FAILED -> stringResource(R.string.sync_hc_failed)
            SyncOutcome.INTERNAL -> stringResource(R.string.error_internal)
        }
    }
}
