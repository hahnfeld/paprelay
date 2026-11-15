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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.everysoft.paprelay.BuildConfig
import com.everysoft.paprelay.R
import com.everysoft.paprelay.check.Step
import com.everysoft.paprelay.check.StepState
import com.everysoft.paprelay.pairing.PairingState
import java.text.DateFormat
import java.util.Date

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
        Box(contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .windowInsetsPadding(WindowInsets.safeDrawing)
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
        Canvas(Modifier.size(20.dp)) {
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
private fun SectionLabel(@StringRes text: Int) {
    Text(
        stringResource(text).uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp).semantics { heading() },
    )
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
    onRunFull: () -> Unit,
    onRunReconnect: () -> Unit,
    onStop: () -> Unit,
    onForget: () -> Unit,
    onExport: () -> Unit,
    onAbout: () -> Unit,
) {
    Page(
        title = stringResource(R.string.app_name),
        action = { TextButton(onClick = onAbout) { Text(stringResource(R.string.action_about)) } },
    ) {
        SectionLabel(R.string.machine_heading)
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
            is PairingState.Paired -> {
                val serial = pairing.pairing.serialNumber
                Text(
                    if (serial != null) stringResource(R.string.machine_paired, serial.takeLast(4)) else stringResource(R.string.machine_paired_unknown),
                    style = MaterialTheme.typography.titleMedium,
                )
                Secondary(
                    stringResource(
                        R.string.machine_paired_on,
                        DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(pairing.pairing.pairedAtMs)),
                    ),
                    Modifier.padding(top = 4.dp),
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        // Wraps instead of clipping at large font sizes.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (running) {
                OutlinedButton(onClick = onStop) { Text(stringResource(R.string.action_stop)) }
            } else {
                Button(onClick = onRunFull, enabled = pairing != PairingState.Loading) { Text(stringResource(R.string.action_run_check)) }
                if (pairing is PairingState.Paired) {
                    OutlinedButton(onClick = onRunReconnect) { Text(stringResource(R.string.action_reconnect_check)) }
                }
            }
        }

        Spacer(Modifier.height(36.dp))
        SectionLabel(R.string.check_heading)
        Text(
            verdict(steps, running),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        Secondary(stringResource(R.string.check_explanation), Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(12.dp))
        steps.forEachIndexed { index, step ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            StepRow(step)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Spacer(Modifier.height(8.dp))
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onExport, enabled = hasReport && !running) { Text(stringResource(R.string.action_export)) }
            if (pairing is PairingState.Paired || pairing == PairingState.Unreadable) {
                TextButton(onClick = onForget, enabled = !running) {
                    Text(stringResource(R.string.action_forget), color = MaterialTheme.colorScheme.error)
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        Secondary(stringResource(R.string.early_build_notice))
    }
}

/** One line that says how the whole check went. */
@Composable
private fun verdict(steps: List<Step>, running: Boolean): String {
    val failed = steps.count { it.state == StepState.FAILED }
    return when {
        running -> stringResource(R.string.verdict_running)
        steps.all { it.state == StepState.PENDING } -> stringResource(R.string.verdict_idle)
        failed > 0 -> pluralStringResource(R.plurals.verdict_failed, failed, failed)
        // "Passed" means the last step did, not merely that nothing was attempted.
        steps.lastOrNull()?.state == StepState.PASSED -> stringResource(R.string.verdict_passed)
        else -> stringResource(R.string.verdict_incomplete)
    }
}

@Composable
private fun StepRow(step: Step) {
    val stateText = stringResource(
        when (step.state) {
            StepState.PASSED -> R.string.state_passed
            StepState.FAILED -> R.string.state_failed
            StepState.RUNNING -> R.string.state_running
            StepState.SKIPPED -> R.string.state_skipped
            StepState.NOTED -> R.string.state_noted
            StepState.PENDING -> R.string.state_pending
        },
    )
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
            Text(step.title, style = MaterialTheme.typography.bodyLarge)
            if (step.detail.isNotEmpty()) {
                Text(
                    step.detail,
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
            StepState.SKIPPED -> drawLine(color, Offset(0f, h / 2), Offset(w, h / 2), 1.5.dp.toPx(), StrokeCap.Round)
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
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.passkey_title)) },
        text = {
            Column {
                Text(stringResource(R.string.passkey_body), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = { entered -> code = entered.filter(Char::isDigit).take(8) },
                    label = { Text(stringResource(R.string.passkey_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
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
fun ChoiceDialog(options: List<String>, onChoose: (Int) -> Unit, onCancel: () -> Unit) {
    var selected by rememberSaveable { mutableIntStateOf(0) }
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.choose_title)) },
        text = {
            Column {
                Text(stringResource(R.string.choose_body), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                options.forEachIndexed { index, label ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = selected == index, role = Role.RadioButton, onClick = { selected = index })
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected == index, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(label, style = MaterialTheme.typography.bodyLarge)
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
