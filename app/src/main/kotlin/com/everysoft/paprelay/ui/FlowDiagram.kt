// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.ui

import androidx.annotation.StringRes
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.everysoft.paprelay.R

/** The state of one leg of the journey from the machine to Health Connect. */
enum class LinkState {
    /** Nothing connects the two ends yet. */
    NONE,

    /** Connected and idle. */
    READY,

    /** Data is moving along this leg right now. */
    ACTIVE,

    /** This leg has been shown to work. */
    OK,

    /** This leg did not work. */
    FAILED,
}

/**
 * The whole app in one picture: machine, phone, health records, and the two
 * links between them.
 *
 * It is meant to be understood without reading. The shapes say what the three
 * things are; the lines say what is happening: dotted when there is no
 * connection, solid when there is, moving while data flows, and marked with a
 * tick or a cross when a leg has succeeded or failed. Shape carries every
 * distinction, so it does not depend on telling colours apart.
 *
 * Drawn in code, so it needs no image files or icon library and follows the
 * phone's theme.
 */
@Composable
fun FlowDiagram(machineLink: LinkState, healthLink: LinkState, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val ink = scheme.onSurface
    val faint = scheme.outlineVariant
    val bad = scheme.error
    val paper = scheme.surface

    // Dashes travel from the machine towards Health Connect while data flows.
    val travel by rememberInfiniteTransition(label = "flow").animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 900, easing = LinearEasing), RepeatMode.Restart),
        label = "dash",
    )

    val description = stringResource(describe(machineLink, machine = true)) + ". " +
        stringResource(describe(healthLink, machine = false)) + "."

    Column(modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = description }) {
        Canvas(Modifier.fillMaxWidth().height(DIAGRAM_HEIGHT)) {
            val unit = size.height
            val stroke = 2.dp.toPx()
            val gap = 10.dp.toPx()
            val spots = Spots(size.width, unit)
            val middle = size.height / 2

            translate(left = spots.machineAt) { machine(spots.machineWidth, unit, ink, stroke) }
            translate(left = spots.phoneAt) { phone(spots.phoneWidth, unit, ink, stroke) }
            translate(left = spots.healthAt) { heart(unit, ink, stroke) }

            link(machineLink, spots.machineAt + spots.machineWidth + gap, spots.phoneAt - gap, middle, travel, ink, faint, bad, paper)
            link(healthLink, spots.phoneAt + spots.phoneWidth + gap, spots.healthAt - gap, middle, travel, ink, faint, bad, paper)
        }
        // Each caption sits under its own drawing, wherever the width puts it.
        Layout(
            content = {
                Caption(R.string.flow_label_machine)
                Caption(R.string.flow_label_phone)
                Caption(R.string.flow_label_health)
            },
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        ) { measurables, constraints ->
            val width = constraints.maxWidth
            val loose = constraints.copy(minWidth = 0, minHeight = 0, maxWidth = width / 3)
            val (machine, phone, health) = measurables.map { it.measure(loose) }
            val spots = Spots(width.toFloat(), DIAGRAM_HEIGHT.toPx())
            val phoneCentre = spots.phoneAt + spots.phoneWidth / 2
            layout(width, maxOf(machine.height, phone.height, health.height)) {
                machine.place(0, 0)
                phone.place((phoneCentre - phone.width / 2).toInt().coerceIn(0, width - phone.width), 0)
                health.place(width - health.width, 0)
            }
        }
    }
}

/**
 * Where the three drawings go. The machine is half as wide again as it is tall,
 * like the real one; the phone is narrow; and the phone sits midway between the
 * other two so both links are the same length.
 */
private class Spots(totalWidth: Float, unit: Float) {
    val machineWidth = unit * 1.5f
    val phoneWidth = unit * 0.5f
    val machineAt = 0f
    val healthAt = totalWidth - unit
    val phoneAt = machineWidth + (healthAt - machineWidth - phoneWidth) / 2
}

@Composable
private fun Caption(@StringRes text: Int) {
    Text(
        stringResource(text),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        maxLines = 2,
    )
}

@StringRes
private fun describe(state: LinkState, machine: Boolean): Int = when (state) {
    LinkState.NONE -> if (machine) R.string.flow_machine_none else R.string.flow_health_none
    LinkState.READY -> if (machine) R.string.flow_machine_ready else R.string.flow_health_none
    LinkState.ACTIVE -> if (machine) R.string.flow_machine_active else R.string.flow_health_active
    LinkState.OK -> if (machine) R.string.flow_machine_ok else R.string.flow_health_ok
    LinkState.FAILED -> if (machine) R.string.flow_machine_failed else R.string.flow_health_failed
}

private val DIAGRAM_HEIGHT = 56.dp

/**
 * The machine as its owner sees it on the bedside table: a low, wide body with
 * rounded corners, a screen at the left with a round button above it, and the
 * water chamber at the right with its level line. An outline of the general
 * shape only; no maker's mark.
 */
private fun DrawScope.machine(w: Float, h: Float, color: Color, width: Float) {
    val line = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val top = h * 0.20f
    val bottom = h * 0.84f
    val inset = width / 2
    drawRoundRect(color, Offset(inset, top), Size(w - 2 * inset, bottom - top), CornerRadius(h * 0.16f), style = line)
    // Screen and button on the left-hand face.
    drawRoundRect(color, Offset(w * 0.11f, h * 0.44f), Size(w * 0.30f, h * 0.27f), CornerRadius(h * 0.05f), style = line)
    drawCircle(color, radius = h * 0.045f, center = Offset(w * 0.26f, h * 0.32f), style = line)
    // Water chamber on the right.
    val seam = w * 0.71f
    drawLine(color, Offset(seam, top + h * 0.07f), Offset(seam, bottom - h * 0.07f), width, StrokeCap.Round)
    drawLine(color, Offset(w * 0.78f, h * 0.60f), Offset(w * 0.90f, h * 0.60f), width, StrokeCap.Round)
    // The base it stands on.
    drawLine(color, Offset(w * 0.14f, h * 0.93f), Offset(w * 0.86f, h * 0.93f), width, StrokeCap.Round)
}

private fun DrawScope.phone(w: Float, h: Float, color: Color, width: Float) {
    val line = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val inset = width / 2
    drawRoundRect(color, Offset(inset, h * 0.06f), Size(w - 2 * inset, h * 0.88f), CornerRadius(h * 0.09f), style = line)
    drawLine(color, Offset(w * 0.34f, h * 0.84f), Offset(w * 0.66f, h * 0.84f), width, StrokeCap.Round)
}

private fun DrawScope.heart(s: Float, color: Color, width: Float) {
    val outline = Path().apply {
        moveTo(s * 0.50f, s * 0.86f)
        cubicTo(s * 0.06f, s * 0.58f, s * 0.10f, s * 0.18f, s * 0.33f, s * 0.18f)
        cubicTo(s * 0.43f, s * 0.18f, s * 0.50f, s * 0.26f, s * 0.50f, s * 0.34f)
        cubicTo(s * 0.50f, s * 0.26f, s * 0.57f, s * 0.18f, s * 0.67f, s * 0.18f)
        cubicTo(s * 0.90f, s * 0.18f, s * 0.94f, s * 0.58f, s * 0.50f, s * 0.86f)
        close()
    }
    drawPath(outline, color, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** One leg, drawn from [from] to [to] along the line y = [y]. */
private fun DrawScope.link(
    state: LinkState,
    from: Float,
    to: Float,
    y: Float,
    travel: Float,
    ink: Color,
    faint: Color,
    bad: Color,
    paper: Color,
) {
    if (to <= from) return
    val width = 2.dp.toPx()
    val start = Offset(from, y)
    val end = Offset(to, y)
    when (state) {
        LinkState.NONE -> {
            val dot = 1.dp.toPx()
            drawLine(faint, start, end, width, StrokeCap.Round, PathEffect.dashPathEffect(floatArrayOf(dot, 7.dp.toPx())))
        }
        LinkState.READY -> drawLine(faint, start, end, width, StrokeCap.Round)
        LinkState.ACTIVE -> {
            val dash = 8.dp.toPx()
            val space = 8.dp.toPx()
            drawLine(
                ink, start, end, width, StrokeCap.Round,
                PathEffect.dashPathEffect(floatArrayOf(dash, space), travel * (dash + space)),
            )
            arrowHead(end, ink, width)
        }
        LinkState.OK -> {
            drawLine(ink, start, end, width, StrokeCap.Round)
            badge(Offset((from + to) / 2, y), ink, paper, tick = true)
        }
        LinkState.FAILED -> {
            drawLine(bad, start, end, width, StrokeCap.Round)
            badge(Offset((from + to) / 2, y), bad, paper, tick = false)
        }
    }
}

private fun DrawScope.arrowHead(tip: Offset, color: Color, width: Float) {
    val reach = 5.dp.toPx()
    drawLine(color, tip, Offset(tip.x - reach, tip.y - reach), width, StrokeCap.Round)
    drawLine(color, tip, Offset(tip.x - reach, tip.y + reach), width, StrokeCap.Round)
}

/** A small disc that interrupts the line, holding a tick or a cross. */
private fun DrawScope.badge(at: Offset, color: Color, paper: Color, tick: Boolean) {
    val radius = 9.dp.toPx()
    val width = 2.dp.toPx()
    drawCircle(paper, radius + 3.dp.toPx(), at)
    drawCircle(color, radius, at, style = Stroke(width))
    val r = radius * 0.45f
    if (tick) {
        drawLine(color, Offset(at.x - r, at.y), Offset(at.x - r * 0.2f, at.y + r * 0.8f), width, StrokeCap.Round)
        drawLine(color, Offset(at.x - r * 0.2f, at.y + r * 0.8f), Offset(at.x + r, at.y - r * 0.7f), width, StrokeCap.Round)
    } else {
        drawLine(color, Offset(at.x - r, at.y - r), Offset(at.x + r, at.y + r), width, StrokeCap.Round)
        drawLine(color, Offset(at.x + r, at.y - r), Offset(at.x - r, at.y + r), width, StrokeCap.Round)
    }
}
