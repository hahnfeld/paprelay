// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.everysoft.paprelay.BuildConfig
import com.everysoft.paprelay.R
import com.everysoft.paprelay.check.Step
import com.everysoft.paprelay.check.StepState
import com.everysoft.paprelay.pairing.Pairing
import java.text.DateFormat
import java.util.Date

/** Common page frame: system-bar insets, a readable column width, one scroll. */
@Composable
private fun Page(title: String, action: (@Composable () -> Unit)? = null, content: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
        Box(Modifier.windowInsetsPadding(WindowInsets.safeDrawing), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 28.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(title, style = MaterialTheme.typography.headlineSmall)
                    action?.invoke()
                }
                content()
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun SectionLabel(@StringRes text: Int) {
    Text(
        stringResource(text).uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
fun HomeScreen(
    pairing: Pairing?,
    steps: List<Step>,
    busy: Boolean,
    hasReport: Boolean,
    onRunFull: () -> Unit,
    onRunReconnect: () -> Unit,
    onForget: () -> Unit,
    onExport: () -> Unit,
    onAbout: () -> Unit,
) {
    Page(
        title = stringResource(R.string.app_name),
        action = { TextButton(onClick = onAbout) { Text(stringResource(R.string.action_about)) } },
    ) {
        SectionLabel(R.string.machine_heading)
        if (pairing == null) {
            Text(stringResource(R.string.machine_unpaired), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.machine_unpaired_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        } else {
            val serial = pairing.serialNumber
            Text(
                if (serial != null) stringResource(R.string.machine_paired, serial.takeLast(4)) else stringResource(R.string.machine_paired_unknown),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(R.string.machine_paired_on, DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(pairing.pairedAtMs))),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onRunFull, enabled = !busy) { Text(stringResource(R.string.action_run_check)) }
            if (pairing != null) {
                OutlinedButton(onClick = onRunReconnect, enabled = !busy) { Text(stringResource(R.string.action_reconnect_check)) }
            }
        }

        Spacer(Modifier.height(36.dp))
        SectionLabel(R.string.check_heading)
        Text(
            stringResource(R.string.check_explanation),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        steps.forEachIndexed { index, step ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            StepRow(step)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onExport, enabled = hasReport && !busy) { Text(stringResource(R.string.action_export)) }
            if (pairing != null) {
                TextButton(onClick = onForget, enabled = !busy) {
                    Text(stringResource(R.string.action_forget), color = MaterialTheme.colorScheme.error)
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        Text(
            stringResource(R.string.early_build_notice),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
            StepState.PENDING -> R.string.state_pending
        },
    )
    Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 4.dp).size(16.dp), contentAlignment = Alignment.Center) {
            StateMark(step.state, stateText)
        }
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

/** A small mark whose shape, not just its colour, tells the states apart. */
@Composable
private fun StateMark(state: StepState, description: String) {
    val scheme = MaterialTheme.colorScheme
    if (state == StepState.RUNNING) {
        CircularProgressIndicator(
            strokeWidth = 2.dp,
            modifier = Modifier.size(14.dp).semantics { contentDescription = description },
        )
        return
    }
    val color: Color = when (state) {
        StepState.PASSED -> scheme.primary
        StepState.FAILED -> scheme.error
        else -> scheme.outline
    }
    Canvas(Modifier.size(12.dp).semantics { contentDescription = description }) {
        val stroke = Stroke(width = 1.5.dp.toPx())
        when (state) {
            StepState.PASSED -> drawCircle(color)
            StepState.FAILED -> {
                drawLine(color, start = center.copy(x = 0f, y = 0f), end = center.copy(x = size.width, y = size.height), strokeWidth = 2.dp.toPx())
                drawLine(color, start = center.copy(x = size.width, y = 0f), end = center.copy(x = 0f, y = size.height), strokeWidth = 2.dp.toPx())
            }
            StepState.SKIPPED -> drawLine(
                color,
                start = center.copy(x = 0f),
                end = center.copy(x = size.width),
                strokeWidth = 1.5.dp.toPx(),
            )
            else -> drawCircle(color, style = stroke)
        }
    }
}

@Composable
fun AboutScreen(onBack: () -> Unit) {
    Page(
        title = stringResource(R.string.about_title),
        action = { TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) } },
    ) {
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
        modifier = Modifier.padding(top = if (first) 0.dp else 24.dp),
    )
}

@Composable
private fun Paragraph(@StringRes text: Int) {
    Text(
        stringResource(text),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp),
    )
}

@Composable
fun PasskeyDialog(onConfirm: (String) -> Unit, onCancel: () -> Unit) {
    var code by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
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
