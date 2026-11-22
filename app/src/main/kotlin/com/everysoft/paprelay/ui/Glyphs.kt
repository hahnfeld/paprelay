// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/** The small drawings beside the ways to other pages and beside the items on Sharing. */
enum class GlyphKind {
    /** Nights: a crescent moon. */
    MOON,

    /** Sharing: one source, several destinations. */
    FORK,

    /** Device check: a line with the picture's tick on it. */
    CHECKED_LINK,

    /** About: an "i" in a ring. */
    INFO,

    /** The end of a row that opens a page. */
    CHEVRON,

    /** Health Connect: the heart from the picture. */
    HEART,

    /** The folder for the two files: a tab on the top left, and the body below it. */
    FOLDER,

    /** Handing something to another app: three points joined by two lines. */
    SHARE,

    /** The person's name and date of birth: a head and shoulders. */
    PERSON,

    /** Back to an earlier night: the chevron, pointing the other way. */
    CHEVRON_BACK,

    /** Breathing events: marks on a line, the night's picture in small. */
    TICKS,

    /** An obstructive apnoea: a breath, then a flat line that ends at a wall. */
    EVENT_OBSTRUCTIVE,

    /** A central apnoea: a breath, then a flat line. */
    EVENT_CENTRAL,

    /** An unclassified apnoea: a breath, then three dots where the breaths would be. */
    EVENT_UNCLASSIFIED,

    /** A hypopnoea: a breath, then smaller breaths. */
    EVENT_HYPOPNOEA,
}

/** The glyphs that show time or a direction, and so are mirrored in right-to-left languages. */
private val MIRRORED = setOf(
    GlyphKind.FORK, GlyphKind.CHEVRON, GlyphKind.CHEVRON_BACK,
    GlyphKind.EVENT_OBSTRUCTIVE, GlyphKind.EVENT_CENTRAL, GlyphKind.EVENT_UNCLASSIFIED, GlyphKind.EVENT_HYPOPNOEA,
)

/**
 * One glyph, drawn in code in the picture's style (2 dp round lines) so that the app needs no
 * icon library and the glyphs follow the phone's theme. Always 24 dp, whatever the font size.
 * It says nothing to a screen reader: the row it sits in carries the words.
 *
 * Only the glyphs that show a direction are mirrored in right-to-left languages.
 */
@Composable
fun Glyph(kind: GlyphKind, modifier: Modifier = Modifier, enabled: Boolean = true, color: Color? = null) {
    val scheme = MaterialTheme.colorScheme
    val base = color ?: if (kind == GlyphKind.CHEVRON) scheme.onSurfaceVariant else scheme.onSurface
    val ink = if (enabled) base else scheme.onSurface.copy(alpha = DISABLED_ALPHA)
    val paper = scheme.surface
    val mirrored = kind in MIRRORED && LocalLayoutDirection.current == LayoutDirection.Rtl
    val flip = if (mirrored) -1f else 1f
    Spacer(
        modifier.size(GLYPH_SIZE).scale(scaleX = flip, scaleY = 1f).drawWithCache {
            // Every glyph is laid out on a grid of 24 by 24 dp. Its paths are built once per size,
            // not at every drawing.
            val k = size.width / 24f
            val width = 2.dp.toPx()
            val line = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round)
            val thin = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            fun at(x: Float, y: Float) = Offset(x * k, y * k)
            fun polyline(vararg points: Float) = Path().apply {
                moveTo(points[0] * k, points[1] * k)
                for (i in 2 until points.size step 2) lineTo(points[i] * k, points[i + 1] * k)
            }
            when (kind) {
                GlyphKind.MOON -> {
                    val crescent = Path().apply {
                        op(
                            Path().apply { addOval(Rect(at(11f, 13f), 8f * k)) },
                            Path().apply { addOval(Rect(at(16f, 8.5f), 7f * k)) },
                            PathOperation.Difference,
                        )
                    }
                    onDrawBehind { drawPath(crescent, ink, style = line) }
                }
                GlyphKind.FORK -> {
                    // Three branches from one source, each ending in an open arrowhead like the
                    // picture's moving line: data going several ways, not the share icon's rings.
                    val branches = Path()
                    val from = at(6.5f, 12f)
                    for (end in FORK_ENDS) {
                        val tip = at(end.x, end.y)
                        branches.moveTo(from.x, from.y)
                        branches.lineTo(tip.x, tip.y)
                        val along = (tip - from) / (tip - from).getDistance()
                        for (sign in listOf(1f, -1f)) {
                            // The arrowhead's two wings run back from the tip at 45 degrees to the branch.
                            val wing = Offset(along.x * COS45 - sign * along.y * COS45, sign * along.x * COS45 + along.y * COS45)
                            val back = tip - wing * (ARROW_REACH * k)
                            branches.moveTo(back.x, back.y)
                            branches.lineTo(tip.x, tip.y)
                        }
                    }
                    onDrawBehind {
                        drawCircle(ink, 2.25f * k, at(4f, 12f))
                        drawPath(branches, ink, style = line)
                    }
                }
                GlyphKind.CHECKED_LINK -> {
                    val tick = polyline(9.87f, 12.08f, 11.34f, 13.64f, 14.3f, 10.52f)
                    onDrawBehind {
                        drawLine(ink, at(2f, 12f), at(22f, 12f), width, StrokeCap.Round)
                        ring(at(12f, 12f), 4.5f * k, 6f * k, ink, paper, width)
                        drawPath(tick, ink, style = line)
                    }
                }
                GlyphKind.INFO -> onDrawBehind {
                    drawCircle(ink, 9f * k, at(12f, 12f), style = Stroke(width))
                    drawCircle(ink, 1.3f * k, at(12f, 7.6f))
                    drawLine(ink, at(12f, 11f), at(12f, 16.6f), width, StrokeCap.Round)
                }
                GlyphKind.CHEVRON -> {
                    val chevron = polyline(9f, 6f, 15f, 12f, 9f, 18f)
                    onDrawBehind { drawPath(chevron, ink, style = line) }
                }
                GlyphKind.HEART -> {
                    val heart = heartPath(20f * k).apply { translate(at(3.6f, 2f)) }
                    onDrawBehind { drawPath(heart, ink, style = line) }
                }
                GlyphKind.FOLDER -> {
                    val folder = polyline(3f, 20f, 3f, 5f, 10f, 5f, 12.5f, 7.5f, 21f, 7.5f, 21f, 20f).apply { close() }
                    onDrawBehind {
                        drawPath(folder, ink, style = line)
                        drawLine(ink, at(3f, 11f), at(21f, 11f), thin.width, StrokeCap.Round)
                    }
                }
                GlyphKind.PERSON -> {
                    val shoulders = Path().apply {
                        moveTo(5f * k, 20f * k)
                        cubicTo(5f * k, 13.5f * k, 19f * k, 13.5f * k, 19f * k, 20f * k)
                    }
                    onDrawBehind {
                        drawCircle(ink, 3.6f * k, at(12f, 7.8f), style = Stroke(width))
                        drawPath(shoulders, ink, style = line)
                    }
                }
                GlyphKind.CHEVRON_BACK -> {
                    val chevron = polyline(15f, 6f, 9f, 12f, 15f, 18f)
                    onDrawBehind { drawPath(chevron, ink, style = line) }
                }
                GlyphKind.TICKS -> onDrawBehind {
                    drawLine(ink, at(3f, 18f), at(21f, 18f), width, StrokeCap.Round)
                    for ((x, top) in TICK_TOPS) drawLine(ink, at(x, 18f), at(x, top), width, StrokeCap.Round)
                }
                GlyphKind.EVENT_UNCLASSIFIED -> {
                    val breath = Path().apply {
                        moveTo(2f * k, 12f * k)
                        quadraticTo(4f * k, 5f * k, 6f * k, 12f * k)
                        quadraticTo(8f * k, 19f * k, 10f * k, 12f * k)
                    }
                    onDrawBehind {
                        drawPath(breath, ink, style = line)
                        for (x in UNCLASSIFIED_DOTS) drawCircle(ink, 1.25f * k, at(x, 12f))
                    }
                }
                GlyphKind.EVENT_OBSTRUCTIVE, GlyphKind.EVENT_CENTRAL, GlyphKind.EVENT_HYPOPNOEA -> {
                    // One full breath, then what the event does to the breaths that follow.
                    val path = Path().apply {
                        moveTo(2f * k, 12f * k)
                        quadraticTo(4f * k, 5f * k, 6f * k, 12f * k)
                        quadraticTo(8f * k, 19f * k, 10f * k, 12f * k)
                        if (kind == GlyphKind.EVENT_HYPOPNOEA) {
                            quadraticTo(12f * k, 9f * k, 14f * k, 12f * k)
                            quadraticTo(16f * k, 15f * k, 18f * k, 12f * k)
                            quadraticTo(19.5f * k, 10.6f * k, 21f * k, 12f * k)
                        }
                        if (kind == GlyphKind.EVENT_OBSTRUCTIVE) {
                            // The air runs into a wall and stops there: the line ends at it, and does not cross it.
                            lineTo(17f * k, 12f * k)
                            moveTo(17f * k, 6f * k)
                            lineTo(17f * k, 18f * k)
                        } else {
                            lineTo(22f * k, 12f * k)
                        }
                    }
                    onDrawBehind { drawPath(path, ink, style = line) }
                }
                GlyphKind.SHARE -> {
                    val left = at(4.8f, 12f)
                    val top = at(19.2f, 4.32f)
                    val bottom = at(19.2f, 19.68f)
                    onDrawBehind {
                        drawLine(ink, left, top, width, StrokeCap.Round)
                        drawLine(ink, left, bottom, width, StrokeCap.Round)
                        for (node in listOf(left, top, bottom)) ring(node, 2.5f * k, 3.5f * k, ink, paper, width)
                    }
                }
            }
        },
    )
}

/** An open ring on a disc of paper, so that lines running into it stop short of its edge. */
private fun DrawScope.ring(centre: Offset, radius: Float, clear: Float, ink: Color, paper: Color, width: Float) {
    drawCircle(paper, clear, centre)
    drawCircle(ink, radius, centre, style = Stroke(width))
}

private val GLYPH_SIZE = 24.dp

/** Where the fork's three branches end, on the 24-dp grid: straight on, and up and down to the end. */
private val FORK_ENDS = listOf(Offset(21f, 12f), Offset(19.5f, 3.5f), Offset(19.5f, 20.5f))
/** The breathing-events glyph's marks: where each stands on the line, and how high it reaches. */
private val TICK_TOPS = listOf(6f to 9f, 10f to 13f, 14f to 6f, 18f to 12f)

/** Where the unclassified apnoea's three dots sit on its line. */
private val UNCLASSIFIED_DOTS = listOf(13.5f, 17f, 20.5f)
private const val ARROW_REACH = 3f
private const val COS45 = 0.70710677f
private const val DISABLED_ALPHA = 0.38f
