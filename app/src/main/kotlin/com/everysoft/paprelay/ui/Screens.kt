// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.asPaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import androidx.compose.ui.semantics.clearAndSetSemantics
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
import com.everysoft.paprelay.core.nights.Night
import com.everysoft.paprelay.core.nights.NightFigure
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import com.everysoft.paprelay.sync.SyncOutcome
import com.everysoft.paprelay.sync.SyncStatus
import android.text.format.DateFormat
import android.text.format.DateUtils
import java.util.Locale
import kotlinx.coroutines.delay
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

/**
 * The main screen: the picture, the machine, one sentence on how things stand, and one button.
 * Everything for finding faults is on the device-check screen.
 */
@Composable
fun HomeScreen(
    pairing: PairingState,
    steps: List<Step>,
    checkRunning: Boolean,
    busy: Boolean,
    syncEnabled: Boolean,
    sync: SyncStatus,
    nights: List<Night>,
    healthAllowed: Boolean,
    healthMissing: Boolean,
    healthRefused: Boolean,
    permissionRefused: Boolean,
    pausesWhenUnused: Boolean,
    onSync: () -> Unit,
    onAllowHealth: () -> Unit,
    onAcceptClock: () -> Unit,
    onStartAgain: () -> Unit,
    onPair: () -> Unit,
    onOpenCheck: () -> Unit,
    onOpenNights: () -> Unit,
    onOpenChoices: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenHealthConnect: () -> Unit,
    onOpenUnusedSetting: () -> Unit,
    onAbout: () -> Unit,
) {
    val paired = pairing is PairingState.Paired
    val checkStarted = checkRunning || steps.any { it.state != StepState.PENDING }
    Page(
        title = stringResource(R.string.app_name),
        action = { TextButton(onClick = onAbout) { Text(stringResource(R.string.about_title)) } },
    ) {
        // The picture comes first: it says what the app does and how far it has got. With a machine
        // paired it shows how synchronising stands; while a check runs, or before there is a
        // machine, it shows the check. Until the pairing has been read it shows nothing in particular.
        when {
            pairing == PairingState.Loading -> FlowDiagram(machineLink = LinkState.NONE, healthLink = LinkState.NONE)
            syncEnabled && paired && !checkRunning -> {
                val links = syncLinks(sync, healthAllowed)
                FlowDiagram(machineLink = links.machine, healthLink = links.health, phoneFault = links.phoneFault)
            }
            else -> FlowDiagram(
                machineLink = machineLink(steps, pairing, checkRunning),
                healthLink = healthLink(steps, healthAllowed),
                // A failure that lies with the phone is marked on the phone, not on either link.
                phoneFault = steps.any { it.state == StepState.FAILED && it.onPhone && it.id != CheckSteps.HEALTH },
            )
        }
        Spacer(Modifier.height(28.dp))
        MachineLine(pairing)

        val free = !sync.running && !checkRunning && !busy
        if (paired && syncEnabled) {
            // What the app is for: the latest finished night, in the machine's own figures.
            nights.firstOrNull { it.complete }?.let { night ->
                Spacer(Modifier.height(20.dp))
                NightSummary(night, prominent = true)
                Spacer(Modifier.height(8.dp))
            }
            // Only the sentence is announced when it changes; the time under it ticks on quietly.
            Text(
                syncLine(sync),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 12.dp).semantics { liveRegion = LiveRegionMode.Polite },
            )
            lastTimeLine(sync)?.let { Secondary(it, Modifier.padding(top = 4.dp)) }
            Spacer(Modifier.height(20.dp))
            // Wraps instead of clipping at large font sizes.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val outcome = sync.lastReport?.outcome
                if (sync.storeUnreadable) {
                    // The one way forward; synchronising cannot work until it is taken.
                    Button(onClick = onStartAgain, enabled = free) { Text(stringResource(R.string.action_start_again)) }
                } else {
                    Button(onClick = onSync, enabled = free) { Text(stringResource(R.string.action_sync)) }
                    // A second button only when it is the way forward.
                    when {
                        !free -> Unit
                        outcome == SyncOutcome.CLOCK_CHANGED ->
                            OutlinedButton(onClick = onAcceptClock) { Text(stringResource(R.string.action_accept_clock)) }
                        outcome == SyncOutcome.BLUETOOTH_NOT_ALLOWED ->
                            OutlinedButton(onClick = onOpenSettings) { Text(stringResource(R.string.action_open_settings)) }
                        // Something is switched on to go to Health Connect and is not allowed there. Asking is
                        // tried first; once Android has stopped asking, the way is through Health Connect itself.
                        healthMissing && healthRefused ->
                            OutlinedButton(onClick = onOpenHealthConnect) { Text(stringResource(R.string.action_open_health)) }
                        healthMissing -> OutlinedButton(onClick = onAllowHealth) { Text(stringResource(R.string.action_allow_health)) }
                    }
                }
            }
            // Said whenever it is so, whatever the last run found: a run made before now knows nothing of it.
            if (healthMissing && sync.lastReport?.outcome != SyncOutcome.HEALTH_CONNECT_NOT_ALLOWED) {
                Secondary(stringResource(R.string.sync_hc_not_allowed), Modifier.padding(top = 12.dp))
            }
            // Switched on, and stopped by the machine's software: said here too, or it would stop unnoticed.
            if (sync.respiratoryRate && !sync.rateReadable) {
                Secondary(stringResource(R.string.choice_respiratory_unsupported), Modifier.padding(top = 12.dp))
            }
            if (sync.exportFile != null && sync.exportFailed) {
                Secondary(stringResource(R.string.choice_file_failed), Modifier.padding(top = 12.dp))
            }
        } else {
            if (paired) Secondary(stringResource(R.string.debug_no_sync), Modifier.padding(top = 12.dp))
            Spacer(Modifier.height(20.dp))
            when (pairing) {
                PairingState.Loading -> Unit
                // Pairing is the first part of the device check, which then proves the rest works.
                PairingState.Absent, PairingState.Unreadable ->
                    Button(onClick = onPair, enabled = !busy && !checkRunning) { Text(stringResource(R.string.action_pair)) }
                PairingState.Unavailable, is PairingState.Paired ->
                    Button(onClick = onOpenCheck) { Text(stringResource(R.string.action_device_check)) }
            }
        }

        // Not said twice: the sentence above already says it when the last run was stopped by this.
        val saidAbove = paired && syncEnabled && free && !sync.storeUnreadable && sync.lastReport?.outcome == SyncOutcome.BLUETOOTH_NOT_ALLOWED
        if (permissionRefused && !checkRunning && !saidAbove) {
            Secondary(stringResource(R.string.permission_needed), Modifier.padding(top = 12.dp))
            TextButton(onClick = onOpenSettings, modifier = Modifier.offset(x = (-12).dp)) {
                Text(stringResource(R.string.action_open_settings))
            }
        }

        // Android stops an app nobody opens. This one is meant not to need opening.
        if (paired && syncEnabled && sync.lastReport != null && pausesWhenUnused) {
            Secondary(stringResource(R.string.unused_hint), Modifier.padding(top = 20.dp))
            TextButton(onClick = onOpenUnusedSetting, modifier = Modifier.offset(x = (-12).dp)) {
                Text(stringResource(R.string.action_unused_setting))
            }
        }

        // Where the primary button is not already the way to the check.
        if (paired && syncEnabled || pairing == PairingState.Unreadable || pairing == PairingState.Absent && checkStarted) {
            Spacer(Modifier.height(24.dp))
            // Text buttons carry 12 dp of padding; pulled out so the first label sits on the text margin.
            FlowRow(Modifier.offset(x = (-12).dp)) {
                if (paired && syncEnabled) {
                    if (nights.any { it.complete }) TextButton(onClick = onOpenNights) { Text(stringResource(R.string.nights_title)) }
                    TextButton(onClick = onOpenChoices) { Text(stringResource(R.string.choices_title)) }
                }
                TextButton(onClick = onOpenCheck) { Text(stringResource(R.string.action_device_check)) }
            }
        }
    }
}

/**
 * One night in two lines: how long, the index that matters most, and the two figures usually
 * looked at next. Said in numbers and symbols, which need no translating; a screen reader is
 * given the same in words.
 */
@Composable
private fun NightSummary(night: Night, prominent: Boolean) {
    val date = remember(night.date) { dayFormatter().format(night.date) }
    val heading = stringResource(R.string.night_of, date)
    val headline = if (prominent) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge
    // Whole numbers are put in as text, so that they keep the digits the decimals beside them have.
    val hours = (night.usageMinutes / 60).toString()
    val minutes = (night.usageMinutes % 60).toString()
    val ahi = night.value(NightFigure.AHI)?.toPlainString()
    val leak = night.value(NightFigure.LEAK_95)?.toPlainString()
    val pressure = night.value(NightFigure.THERAPY_PRESSURE_95)?.toPlainString()
    val spoken = if (night.usageMinutes == 0) {
        heading + ". " + stringResource(R.string.night_none)
    } else {
        listOfNotNull(
            heading,
            stringResource(R.string.night_usage_spoken, hours, minutes),
            ahi?.let { stringResource(R.string.night_ahi_spoken, it) },
            leak?.let { stringResource(R.string.night_leak_spoken, it) },
            pressure?.let { stringResource(R.string.night_pressure_spoken, it) },
        ).joinToString(". ")
    }
    Column(Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = spoken }) {
        Secondary(heading)
        if (night.usageMinutes == 0) {
            Text(stringResource(R.string.night_none), style = headline)
            return@Column
        }
        Text(
            listOfNotNull(stringResource(R.string.night_usage, hours, minutes), ahi?.let { stringResource(R.string.night_ahi, it) }).joinToString(" · "),
            style = headline,
        )
        val details = listOfNotNull(leak?.let { stringResource(R.string.night_leak, it) }, pressure?.let { stringResource(R.string.night_pressure, it) })
        if (details.isNotEmpty()) Secondary(details.joinToString(" · "), Modifier.padding(top = 2.dp))
    }
}

/** A date with its weekday, in the phone's language and its usual order: "Sat, 14 Nov 2026". */
private fun dayFormatter(): DateTimeFormatter {
    val locale = Locale.getDefault()
    return try {
        DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "yMMMEd"), locale)
    } catch (e: IllegalArgumentException) {
        // A pattern the phone's language uses and this formatter does not know: the plain date instead.
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
    }
}

/**
 * Every night kept on the phone, newest first. Built row by row as it is scrolled: there can
 * be three years of them.
 */
@Composable
fun NightsScreen(nights: List<Night>, onShare: () -> Unit, onBack: () -> Unit) {
    val finished = remember(nights) { nights.filter { it.complete } }
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
        Box(
            Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
            contentAlignment = Alignment.TopCenter,
        ) {
            LazyColumn(
                Modifier.widthIn(max = 560.dp).fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 24.dp,
                    end = 24.dp,
                    bottom = 32.dp + WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding(),
                ),
            ) {
                item {
                    Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 28.dp), verticalAlignment = Alignment.CenterVertically) {
                        BackButton(onBack)
                        Spacer(Modifier.width(4.dp))
                        Text(
                            stringResource(R.string.nights_title),
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.weight(1f).semantics { heading() },
                        )
                        if (finished.isNotEmpty()) TextButton(onClick = onShare) { Text(stringResource(R.string.action_share)) }
                    }
                    Secondary(stringResource(R.string.nights_note))
                    Spacer(Modifier.height(12.dp))
                }
                itemsIndexed(finished, key = { _, night -> night.date.toEpochDay() }) { index, night ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Box(Modifier.padding(vertical = 12.dp)) { NightSummary(night, prominent = false) }
                }
            }
        }
    }
}

/** What is passed on, and where to. */
@Composable
fun ChoicesScreen(
    sync: SyncStatus,
    hasNights: Boolean,
    recordsSupported: Boolean,
    recordsBlocked: Boolean,
    respiratoryBlocked: Boolean,
    onRecords: (Boolean) -> Unit,
    onRespiratoryRate: (Boolean) -> Unit,
    onOpenHealthConnect: () -> Unit,
    onChooseFile: () -> Unit,
    onNoFile: () -> Unit,
    onShare: () -> Unit,
    onBack: () -> Unit,
) {
    Page(title = stringResource(R.string.choices_title), onBack = onBack) {
        // A choice is stored once the run in progress has finished, so it is not offered during one.
        if (sync.running) Secondary(stringResource(R.string.sync_running), Modifier.padding(bottom = 8.dp))
        Choice(
            title = R.string.choice_records,
            body = if (recordsSupported) R.string.choice_records_body else R.string.choice_records_unsupported,
            checked = recordsSupported && sync.healthRecords,
            enabled = recordsSupported && !sync.running,
            onChange = onRecords,
        )
        if (recordsBlocked) NotAllowed(onOpenHealthConnect)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Choice(
            title = R.string.choice_respiratory,
            body = if (sync.rateReadable) R.string.choice_respiratory_body else R.string.choice_respiratory_unsupported,
            // Shown as it was left. While it cannot be read it can be switched off, not on.
            checked = sync.respiratoryRate,
            enabled = !sync.running && (sync.rateReadable || sync.respiratoryRate),
            onChange = onRespiratoryRate,
        )
        if (respiratoryBlocked) NotAllowed(onOpenHealthConnect)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
            Text(stringResource(R.string.choice_file), style = MaterialTheme.typography.bodyLarge)
            Secondary(stringResource(R.string.choice_file_body), Modifier.padding(top = 2.dp))
            if (sync.exportFile != null) {
                Text(
                    when {
                        sync.exportFailed -> stringResource(R.string.choice_file_failed)
                        sync.exportName != null -> stringResource(R.string.choice_file_named, sync.exportName)
                        else -> stringResource(R.string.choice_file_on)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (sync.exportFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onChooseFile, enabled = !sync.running) {
                    Text(stringResource(if (sync.exportFile == null) R.string.action_choose_file else R.string.action_other_file))
                }
                if (sync.exportFile != null) TextButton(onClick = onNoFile, enabled = !sync.running) { Text(stringResource(R.string.action_no_file)) }
            }
        }
        if (hasNights) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onShare, modifier = Modifier.offset(x = (-12).dp)) { Text(stringResource(R.string.action_share_nights)) }
        }
    }
}

/** Under a choice that Health Connect does not allow: what is wrong, and the way to put it right. */
@Composable
private fun NotAllowed(onOpenHealthConnect: () -> Unit) {
    Text(
        stringResource(R.string.choice_not_allowed),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
    )
    TextButton(onClick = onOpenHealthConnect, modifier = Modifier.offset(x = (-12).dp)) { Text(stringResource(R.string.action_open_health)) }
}

/** A choice with a switch: the whole row answers to a tap, and is read out as one thing. */
@Composable
private fun Choice(@StringRes title: Int, @StringRes body: Int, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
            Secondary(stringResource(body), Modifier.padding(top = 2.dp))
        }
        Spacer(Modifier.width(16.dp))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun MachineLine(pairing: PairingState) {
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
}

/** The device check: for pairing, and for finding out what is wrong when something is. */
@Composable
fun CheckScreen(
    pairing: PairingState,
    steps: List<Step>,
    running: Boolean,
    stopping: Boolean,
    hasReport: Boolean,
    busy: Boolean,
    syncEnabled: Boolean,
    syncRunning: Boolean,
    permissionRefused: Boolean,
    onRunFull: () -> Unit,
    onRunReconnect: () -> Unit,
    onStop: () -> Unit,
    onSync: () -> Unit,
    onExport: () -> Unit,
    onRewrite: () -> Unit,
    onForget: () -> Unit,
    onOpenSettings: () -> Unit,
    onBack: () -> Unit,
) {
    val paired = pairing is PairingState.Paired
    val passed = !running && steps.lastOrNull()?.state == StepState.PASSED && steps.none { it.state == StepState.FAILED || it.state == StepState.CANCELLED }
    Page(title = stringResource(R.string.action_device_check), onBack = onBack) {
        MachineLine(pairing)
        // Why nothing here can be started just now.
        if (syncRunning) Secondary(stringResource(R.string.sync_running), Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(20.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (running) {
                // Stopping can take a moment: a step that must not be cut short is finished first.
                OutlinedButton(onClick = onStop, enabled = !stopping) {
                    Text(stringResource(if (stopping) R.string.action_stopping else R.string.action_stop))
                }
            } else {
                // Held back while a pairing is being forgotten or the machine is in use, so a check cannot start on it.
                val free = !busy && !syncRunning && pairing != PairingState.Loading
                // Everything works: what comes next is to synchronise, and that is the button to see.
                val next = passed && paired && syncEnabled
                if (next) {
                    Button(onClick = onSync, enabled = free) { Text(stringResource(R.string.action_sync)) }
                    OutlinedButton(onClick = onRunFull, enabled = free) { Text(stringResource(R.string.action_run_check)) }
                } else {
                    Button(onClick = onRunFull, enabled = free) { Text(stringResource(R.string.action_run_check)) }
                }
                if (paired) {
                    OutlinedButton(onClick = onRunReconnect, enabled = free) { Text(stringResource(R.string.action_reconnect_check)) }
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

        val idle = !running && !busy && !syncRunning
        val canExport = hasReport && !running
        val canRewrite = paired && syncEnabled
        // Offered whenever something is stored, readable or not, so there is always a way to start over.
        val canForget = paired || pairing == PairingState.Unreadable || pairing == PairingState.Unavailable
        if (canExport || canRewrite || canForget) {
            Spacer(Modifier.height(16.dp))
            // Text buttons carry 12 dp of padding; pulled out so their labels sit on the text margin.
            Column(Modifier.offset(x = (-12).dp)) {
                if (canExport) TextButton(onClick = onExport) { Text(stringResource(R.string.action_export)) }
                if (canRewrite) TextButton(onClick = onRewrite, enabled = idle) { Text(stringResource(R.string.action_rewrite)) }
                if (canForget) {
                    TextButton(onClick = onForget, enabled = idle) {
                        Text(stringResource(R.string.action_forget), color = if (idle) MaterialTheme.colorScheme.error else Color.Unspecified)
                    }
                }
            }
        }
    }
}

/** How the two legs of the picture stand after a synchronisation, or during one. */
private class SyncLinks(val machine: LinkState, val health: LinkState, val phoneFault: Boolean)

private fun syncLinks(sync: SyncStatus, healthAllowed: Boolean): SyncLinks {
    val idleHealth = if (healthAllowed) LinkState.READY else LinkState.NONE
    if (sync.running) return SyncLinks(LinkState.ACTIVE, idleHealth, phoneFault = false)
    if (sync.storeUnreadable) return SyncLinks(LinkState.READY, idleHealth, phoneFault = true)
    return when (sync.lastReport?.outcome) {
        // The Health Connect leg is ticked only when something is switched on to go there and is allowed to.
        SyncOutcome.COMPLETE -> SyncLinks(LinkState.OK, if (healthAllowed) LinkState.OK else LinkState.NONE, phoneFault = false)
        // Under way, with nothing wrong: no ticks until everything is across. And out of range is
        // the ordinary state of a phone that is not at home.
        SyncOutcome.PARTIAL, SyncOutcome.MACHINE_AWAY, null -> SyncLinks(LinkState.READY, idleHealth, phoneFault = false)
        SyncOutcome.BLUETOOTH_OFF, SyncOutcome.BLUETOOTH_NOT_ALLOWED, SyncOutcome.PHONE_CLOCK_WRONG, SyncOutcome.INTERNAL,
        -> SyncLinks(LinkState.READY, idleHealth, phoneFault = true)
        SyncOutcome.HEALTH_CONNECT_NOT_ALLOWED -> SyncLinks(LinkState.READY, LinkState.NONE, phoneFault = false)
        SyncOutcome.HEALTH_CONNECT_UNAVAILABLE, SyncOutcome.HEALTH_CONNECT_FAILED -> SyncLinks(LinkState.READY, LinkState.FAILED, phoneFault = false)
        // Shown only with a machine paired, so this is the leftover of a run made while there was none.
        SyncOutcome.NOT_PAIRED -> SyncLinks(LinkState.READY, idleHealth, phoneFault = false)
        SyncOutcome.PAIRING_REJECTED, SyncOutcome.WRONG_MACHINE, SyncOutcome.SOFTWARE_NOT_CONFIRMED, SyncOutcome.CLOCK_UNRELIABLE,
        SyncOutcome.CLOCK_CHANGED, SyncOutcome.CONNECTION_LOST, SyncOutcome.DATA_NOT_UNDERSTOOD,
        -> SyncLinks(LinkState.FAILED, idleHealth, phoneFault = false)
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
            stringResource(R.string.about_version, BuildConfig.VERSION_NAME + " (" + BuildConfig.GIT_COMMIT + ")"),
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
    /** True for an action that removes something; its button is then drawn as a warning. */
    destructive: Boolean = true,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(title)) },
        text = { Text(stringResource(body), style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(confirm), color = if (destructive) MaterialTheme.colorScheme.error else Color.Unspecified)
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** The time now, renewed twice a minute, so that "5 minutes ago" does not stand still on an open screen. */
@Composable
private fun currentTime(): Long {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(30_000)
            value = System.currentTimeMillis()
        }
    }
    return now
}

@Composable
private fun ago(atMs: Long, now: Long): String =
    DateUtils.getRelativeTimeSpanString(atMs, maxOf(now, atMs), DateUtils.MINUTE_IN_MILLIS).toString()

/** One sentence on how synchronising stands. */
@Composable
private fun syncLine(sync: SyncStatus): String {
    val report = sync.lastReport
    return when {
        sync.running -> stringResource(R.string.sync_running)
        sync.storeUnreadable -> stringResource(R.string.sync_store_unreadable)
        report == null -> stringResource(R.string.sync_never)
        else -> when (report.outcome) {
            SyncOutcome.COMPLETE -> stringResource(R.string.sync_complete)
            SyncOutcome.PARTIAL -> stringResource(R.string.sync_partial)
            SyncOutcome.MACHINE_AWAY -> stringResource(R.string.sync_machine_away)
            SyncOutcome.BLUETOOTH_OFF -> stringResource(R.string.detail_bluetooth_off)
            SyncOutcome.BLUETOOTH_NOT_ALLOWED -> stringResource(R.string.sync_bluetooth_not_allowed)
            SyncOutcome.PHONE_CLOCK_WRONG -> stringResource(R.string.sync_phone_clock_wrong)
            SyncOutcome.HEALTH_CONNECT_NOT_ALLOWED -> stringResource(R.string.sync_hc_not_allowed)
            SyncOutcome.HEALTH_CONNECT_UNAVAILABLE -> stringResource(R.string.detail_hc_unavailable)
            SyncOutcome.PAIRING_REJECTED -> stringResource(R.string.sync_pairing_rejected)
            SyncOutcome.WRONG_MACHINE -> stringResource(R.string.sync_wrong_machine)
            SyncOutcome.SOFTWARE_NOT_CONFIRMED -> stringResource(R.string.sync_software_not_confirmed)
            SyncOutcome.CLOCK_UNRELIABLE -> stringResource(R.string.sync_clock_unreliable)
            SyncOutcome.CLOCK_CHANGED -> stringResource(R.string.sync_clock_changed)
            // Shown only with a machine paired: a run made while there was none says nothing about now.
            SyncOutcome.NOT_PAIRED -> stringResource(R.string.sync_never)
            SyncOutcome.CONNECTION_LOST -> stringResource(R.string.sync_connection_lost)
            SyncOutcome.DATA_NOT_UNDERSTOOD -> stringResource(R.string.sync_data_not_understood)
            SyncOutcome.HEALTH_CONNECT_FAILED -> stringResource(R.string.sync_hc_failed)
            SyncOutcome.INTERNAL -> stringResource(R.string.sync_internal)
        }
    }
}

/** The line of times under the sentence: when it last synchronised, or when everything was last up to date. */
@Composable
private fun lastTimeLine(sync: SyncStatus): String? {
    if (sync.running || sync.storeUnreadable) return null
    val now = currentTime()
    val report = sync.lastReport
    if (report?.outcome == SyncOutcome.COMPLETE) {
        return if (now - report.atMs < DateUtils.MINUTE_IN_MILLIS) stringResource(R.string.sync_last_now) else stringResource(R.string.sync_last, ago(report.atMs, now))
    }
    val at = sync.lastCompleteAtMs ?: return null
    return stringResource(R.string.sync_last_complete, ago(at, now))
}
