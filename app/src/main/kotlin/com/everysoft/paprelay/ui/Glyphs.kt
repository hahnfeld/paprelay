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

    /** A file: a sheet of paper with a folded corner. */
    PAGE,

    /** Handing something to another app: three points joined by two lines. */
    SHARE,
}

/**
 * One glyph, drawn in code in the picture's style (2 dp round lines) so that the app needs no
 * icon library and the glyphs follow the phone's theme. Always 24 dp, whatever the font size.
 * It says nothing to a screen reader: the row it sits in carries the words.
 *
 * Only the glyphs that show a direction are mirrored in right-to-left languages.
 */
@Composable
fun Glyph(kind: GlyphKind, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val scheme = MaterialTheme.colorScheme
    val base = if (kind == GlyphKind.CHEVRON) scheme.onSurfaceVariant else scheme.onSurface
    val ink = if (enabled) base else scheme.onSurface.copy(alpha = DISABLED_ALPHA)
    val paper = scheme.surface
    val mirrored = (kind == GlyphKind.FORK || kind == GlyphKind.CHEVRON) && LocalLayoutDirection.current == LayoutDirection.Rtl
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
                GlyphKind.PAGE -> {
                    // A sheet 14 by 20 at (5, 2), its corner folded over by a third of its width.
                    val fold = 14f * 0.32f
                    val sheet = polyline(5f, 2f, 19f - fold, 2f, 19f, 2f + fold, 19f, 22f, 5f, 22f).apply { close() }
                    val corner = polyline(19f - fold, 2f, 19f - fold, 2f + fold, 19f, 2f + fold)
                    val rows = Path().apply {
                        for (row in listOf(0.55f, 0.75f)) {
                            moveTo(7.8f * k, (2f + 20f * row) * k)
                            lineTo(16.2f * k, (2f + 20f * row) * k)
                        }
                    }
                    onDrawBehind {
                        drawPath(sheet, ink, style = line)
                        drawPath(corner, ink, style = thin)
                        drawPath(rows, ink, style = thin)
                    }
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
private const val ARROW_REACH = 3f
private const val COS45 = 0.70710677f
private const val DISABLED_ALPHA = 0.38f
