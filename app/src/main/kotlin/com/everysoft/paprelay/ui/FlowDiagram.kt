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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.everysoft.paprelay.R

/**
 * The whole app in one picture: machine, phone, health records, and the two
 * links between them.
 *
 * It is meant to be understood without reading. The shapes say what the three
 * things are; the lines say what is happening: broken when there is no
 * connection, whole when there is, moving while data flows, and marked with a
 * tick or a filled cross when a leg has succeeded or failed. Every distinction
 * is made by shape, so none depends on telling colours apart.
 *
 * Drawn in code, so it needs no image files or icon library and follows the
 * phone's theme. In right-to-left languages the order and the direction of
 * travel are mirrored; the drawings themselves are not.
 */
@Composable
fun FlowDiagram(links: Links, modifier: Modifier = Modifier) {
    val machineLink = links.machine
    val healthLink = links.health
    // True when something went wrong on the phone itself, such as its storage or Bluetooth being off.
    val phoneFault = links.phoneFault
    val scheme = MaterialTheme.colorScheme
    val palette = Palette(ink = scheme.onSurface, line = scheme.outline, bad = scheme.error, onBad = scheme.onError, paper = scheme.surface)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl

    // The animation exists only while something is moving, so an idle screen draws once and stops.
    val travel: State<Float>? = if (machineLink == LinkState.ACTIVE || healthLink == LinkState.ACTIVE) {
        rememberInfiniteTransition(label = "flow").animateFloat(
            initialValue = 1f,
            targetValue = 0f,
            animationSpec = infiniteRepeatable(tween(durationMillis = 900, easing = LinearEasing), RepeatMode.Restart),
            label = "dash",
        )
    } else {
        null
    }

    val links = stringResource(
        R.string.flow_description,
        stringResource(describe(machineLink, machine = true)),
        stringResource(describe(healthLink, machine = false)),
    )
    val description = if (phoneFault) stringResource(R.string.flow_with_phone_fault, links) else links

    Column(modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = description }) {
        Spacer(
            // Its own layer, so the moving dashes repaint this strip and not the whole page.
            Modifier.fillMaxWidth().height(DIAGRAM_HEIGHT).graphicsLayer().drawWithCache {
                val unit = size.height
                val spots = Spots(size.width, unit, rtl)
                val stroke = 2.dp.toPx()
                val gap = 10.dp.toPx()
                val middle = size.height / 2
                // Built once per size, not once per frame.
                val heartOutline = heartPath(unit)
                onDrawBehind {
                    translate(left = spots.machineAt) { machine(spots.machineWidth, unit, palette.ink, stroke) }
                    translate(left = spots.phoneAt) { phone(spots.phoneWidth, unit, palette.ink, stroke) }
                    translate(left = spots.healthAt) {
                        drawPath(heartOutline, palette.ink, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                    // Each link runs from the end nearer the machine to the end nearer Health Connect.
                    val d = spots.direction
                    link(machineLink, spots.machineEdge + d * gap, spots.phoneNearEdge - d * gap, d, middle, travel, palette)
                    link(healthLink, spots.phoneFarEdge + d * gap, spots.healthEdge - d * gap, d, middle, travel, palette)
                    // A fault on the phone is marked on the phone, at its upper corner, clear of both links.
                    if (phoneFault) crossBadge(Offset(spots.phoneFarEdge, unit * 0.16f), palette)
                }
            },
        )
        // Each caption is centred under its own drawing, wherever the width puts it.
        Layout(
            content = {
                Caption(R.string.flow_label_machine)
                Caption(R.string.flow_label_phone)
                Caption(R.string.flow_label_health)
            },
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        ) { measurables, constraints ->
            val width = constraints.maxWidth
            val spots = Spots(width.toFloat(), DIAGRAM_HEIGHT.toPx(), rtl)
            // The short middle caption is measured first; the two ends then share what is left,
            // so a long name wraps only when the screen really has no room for it.
            val phone = measurables[1].measure(constraints.copy(minWidth = 0, minHeight = 0, maxWidth = width / 3))
            // The phone is not at the centre of the row, so the room on its two sides differs.
            val phoneCentre = spots.phoneAt + spots.phoneWidth / 2
            val gap = 8.dp.roundToPx()
            val leftRoom = (phoneCentre - phone.width / 2f - gap).toInt().coerceAtLeast(0)
            val rightRoom = (width - phoneCentre - phone.width / 2f - gap).toInt().coerceAtLeast(0)
            fun end(measurable: androidx.compose.ui.layout.Measurable, room: Int): androidx.compose.ui.layout.Placeable {
                // A caption that has to wrap is given only the width of its longest word,
                // so its lines stay under its drawing instead of spreading towards the phone.
                val wraps = measurable.maxIntrinsicWidth(Constraints.Infinity) > room
                val allowed = if (wraps) measurable.minIntrinsicWidth(Constraints.Infinity).coerceAtMost(room) else room
                return measurable.measure(constraints.copy(minWidth = 0, minHeight = 0, maxWidth = allowed))
            }
            val machine = end(measurables[0], if (rtl) rightRoom else leftRoom)
            val health = end(measurables[2], if (rtl) leftRoom else rightRoom)
            fun centred(placeable: androidx.compose.ui.layout.Placeable, centre: Float): Int =
                (centre - placeable.width / 2f).toInt().coerceIn(0, (width - placeable.width).coerceAtLeast(0))
            layout(width, maxOf(machine.height, phone.height, health.height)) {
                // Absolute placement: the mirroring for right-to-left is already in Spots.
                machine.place(centred(machine, spots.machineAt + spots.machineWidth / 2), 0)
                phone.place(centred(phone, spots.phoneAt + spots.phoneWidth / 2), 0)
                health.place(centred(health, spots.healthAt + spots.healthWidth / 2), 0)
            }
        }
    }
}

private class Palette(val ink: Color, val line: Color, val bad: Color, val onBad: Color, val paper: Color)

/**
 * Where the three drawings go. The machine is half as wide again as it is tall,
 * like the real one; the phone is narrow; and the phone sits midway between the
 * other two so both links are the same length. Reading order follows the
 * language: the machine is at the start, Health Connect at the end.
 */
private class Spots(totalWidth: Float, unit: Float, rtl: Boolean) {
    val machineWidth = unit * 1.5f
    val phoneWidth = unit * 0.5f

    /** The heart is drawn to fill this width, so links meet it as closely as they meet the others. */
    val healthWidth = unit * 0.84f

    /** +1 when travel runs left to right, -1 when it runs right to left. */
    val direction = if (rtl) -1f else 1f

    private val phoneOffset = machineWidth + (totalWidth - healthWidth - machineWidth - phoneWidth) / 2
    val machineAt = if (rtl) totalWidth - machineWidth else 0f
    val phoneAt = if (rtl) totalWidth - phoneOffset - phoneWidth else phoneOffset
    val healthAt = if (rtl) 0f else totalWidth - healthWidth

    /** The edges the links attach to, in travel order. */
    val machineEdge = if (rtl) machineAt else machineAt + machineWidth
    val phoneNearEdge = if (rtl) phoneAt + phoneWidth else phoneAt
    val phoneFarEdge = if (rtl) phoneAt else phoneAt + phoneWidth
    val healthEdge = if (rtl) healthAt + healthWidth else healthAt
}

@Composable
private fun Caption(@StringRes text: Int) {
    Text(
        stringResource(text),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        // Three, so that a name of three words in another language still fits when it must wrap.
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
    )
}

@StringRes
private fun describe(state: LinkState, machine: Boolean): Int = when (state) {
    LinkState.NONE -> if (machine) R.string.flow_machine_none else R.string.flow_health_none
    LinkState.READY -> if (machine) R.string.flow_machine_ready else R.string.flow_health_ready
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
    drawRoundRect(color, Offset(w * 0.10f, h * 0.46f), Size(w * 0.30f, h * 0.26f), CornerRadius(h * 0.05f), style = line)
    drawCircle(color, radius = h * 0.065f, center = Offset(w * 0.25f, h * 0.33f), style = line)
    // Water chamber on the right, with the water level across it.
    val seam = w * 0.70f
    drawLine(color, Offset(seam, top + h * 0.07f), Offset(seam, bottom - h * 0.07f), width, StrokeCap.Round)
    drawLine(color, Offset(seam, h * 0.60f), Offset(w - inset, h * 0.60f), width, StrokeCap.Round)
    // The base it stands on.
    drawLine(color, Offset(w * 0.14f, h * 0.93f), Offset(w * 0.86f, h * 0.93f), width, StrokeCap.Round)
}

private fun DrawScope.phone(w: Float, h: Float, color: Color, width: Float) {
    val line = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val inset = width / 2
    drawRoundRect(color, Offset(inset, h * 0.06f), Size(w - 2 * inset, h * 0.88f), CornerRadius(h * 0.09f), style = line)
    drawLine(color, Offset(w * 0.34f, h * 0.84f), Offset(w * 0.66f, h * 0.84f), width, StrokeCap.Round)
}

/** A heart filling a box 0.84 units wide and one unit tall. */
internal fun heartPath(unit: Float): Path {
    val w = unit * 0.84f
    val h = unit
    return Path().apply {
        moveTo(w * 0.50f, h * 0.86f)
        cubicTo(w * -0.13f, h * 0.58f, w * -0.08f, h * 0.18f, w * 0.30f, h * 0.18f)
        cubicTo(w * 0.42f, h * 0.18f, w * 0.50f, h * 0.26f, w * 0.50f, h * 0.34f)
        cubicTo(w * 0.50f, h * 0.26f, w * 0.58f, h * 0.18f, w * 0.70f, h * 0.18f)
        cubicTo(w * 1.08f, h * 0.18f, w * 1.13f, h * 0.58f, w * 0.50f, h * 0.86f)
        close()
    }
}

/**
 * One leg, drawn along the line y = [y] from [from] (the end nearer the
 * machine) to [to]. [to] may be to the left of [from] in right-to-left layouts.
 */
private fun DrawScope.link(
    state: LinkState,
    from: Float,
    to: Float,
    heading: Float,
    y: Float,
    travel: State<Float>?,
    palette: Palette,
) {
    // Too narrow to draw a link, or so narrow that the two ends have crossed.
    if ((to - from) * heading < 12.dp.toPx()) return
    val width = 2.dp.toPx()
    val start = Offset(from, y)
    val end = Offset(to, y)
    when (state) {
        LinkState.NONE -> {
            // A line with its middle missing: the two ends do not meet.
            val stub = (to - from) * 0.28f
            drawLine(palette.line, start, Offset(from + stub, y), width, StrokeCap.Round)
            drawLine(palette.line, Offset(to - stub, y), end, width, StrokeCap.Round)
        }
        LinkState.READY -> drawLine(palette.line, start, end, width, StrokeCap.Round)
        LinkState.ACTIVE -> {
            val dash = 8.dp.toPx()
            val space = 8.dp.toPx()
            // Read here, in the draw phase, so each frame redraws without recomposing.
            val phase = (travel?.value ?: 0f) * (dash + space)
            drawLine(palette.ink, start, end, width, StrokeCap.Round, PathEffect.dashPathEffect(floatArrayOf(dash, space), phase))
            val reach = 5.dp.toPx()
            drawLine(palette.ink, end, Offset(to - heading * reach, y - reach), width, StrokeCap.Round)
            drawLine(palette.ink, end, Offset(to - heading * reach, y + reach), width, StrokeCap.Round)
        }
        LinkState.OK -> {
            drawLine(palette.ink, start, end, width, StrokeCap.Round)
            tickBadge(Offset((from + to) / 2, y), palette)
        }
        LinkState.FAILED -> {
            drawLine(palette.bad, start, end, width, StrokeCap.Round)
            crossBadge(Offset((from + to) / 2, y), palette)
        }
    }
}

/** An open ring holding a tick. */
private fun DrawScope.tickBadge(at: Offset, palette: Palette) {
    val radius = 9.dp.toPx()
    val width = 2.dp.toPx()
    drawCircle(palette.paper, radius + 3.dp.toPx(), at)
    drawCircle(palette.ink, radius, at, style = Stroke(width))
    val r = radius * 0.45f
    val elbow = Offset(at.x - r * 0.2f, at.y + r * 0.8f)
    drawLine(palette.ink, Offset(at.x - r, at.y), elbow, width, StrokeCap.Round)
    drawLine(palette.ink, elbow, Offset(at.x + r, at.y - r * 0.7f), width, StrokeCap.Round)
}

/** A solid disc holding a cross: unlike the tick's open ring in shape as well as colour. */
private fun DrawScope.crossBadge(at: Offset, palette: Palette) {
    val radius = 9.dp.toPx()
    val width = 2.dp.toPx()
    drawCircle(palette.paper, radius + 3.dp.toPx(), at)
    drawCircle(palette.bad, radius, at)
    val r = radius * 0.42f
    drawLine(palette.onBad, Offset(at.x - r, at.y - r), Offset(at.x + r, at.y + r), width, StrokeCap.Round)
    drawLine(palette.onBad, Offset(at.x + r, at.y - r), Offset(at.x - r, at.y + r), width, StrokeCap.Round)
}
