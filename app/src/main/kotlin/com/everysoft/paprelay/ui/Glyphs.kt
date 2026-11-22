// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.graphics.drawscope.translate
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
    Canvas(modifier.size(GLYPH_SIZE).scale(scaleX = flip, scaleY = 1f)) {
        // Every glyph is laid out on a grid of 24 by 24 dp.
        val k = size.width / 24f
        fun at(x: Float, y: Float) = Offset(x * k, y * k)
        val width = 2.dp.toPx()
        val thin = 1.5.dp.toPx()
        val line = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (kind) {
            GlyphKind.MOON -> {
                val crescent = Path().apply {
                    op(
                        Path().apply { addOval(Rect(at(11f, 13f), 8f * k)) },
                        Path().apply { addOval(Rect(at(16f, 8.5f), 7f * k)) },
                        PathOperation.Difference,
                    )
                }
                drawPath(crescent, ink, style = line)
            }
            GlyphKind.FORK -> {
                drawCircle(ink, 2.25f * k, at(4f, 12f))
                val from = at(6.5f, 12f)
                for (end in listOf(at(19f, 12f), at(18.6f, 5.6f), at(18.6f, 18.4f))) drawLine(ink, from, end, width, StrokeCap.Round)
                for (centre in listOf(at(20.5f, 12f), at(20.5f, 5f), at(20.5f, 19f))) ring(centre, 2.25f * k, 3.25f * k, ink, paper, width)
            }
            GlyphKind.CHECKED_LINK -> {
                drawLine(ink, at(2f, 12f), at(22f, 12f), width, StrokeCap.Round)
                ring(at(12f, 12f), 5.5f * k, 7.5f * k, ink, paper, width)
                val tick = Path().apply {
                    moveTo(9.4f * k, 12.1f * k)
                    lineTo(11.2f * k, 14.0f * k)
                    lineTo(14.8f * k, 10.2f * k)
                }
                drawPath(tick, ink, style = line)
            }
            GlyphKind.INFO -> {
                drawCircle(ink, 9f * k, at(12f, 12f), style = Stroke(width))
                drawCircle(ink, 1.3f * k, at(12f, 7.6f))
                drawLine(ink, at(12f, 11f), at(12f, 16.6f), width, StrokeCap.Round)
            }
            GlyphKind.CHEVRON -> {
                val chevron = Path().apply {
                    moveTo(9f * k, 6f * k)
                    lineTo(15f * k, 12f * k)
                    lineTo(9f * k, 18f * k)
                }
                drawPath(chevron, ink, style = line)
            }
            GlyphKind.HEART -> translate(3.6f * k, 2f * k) { drawPath(heartPath(20f * k), ink, style = line) }
            GlyphKind.PAGE -> {
                val x = 5f * k
                val y = 2f * k
                val w = 14f * k
                val h = 20f * k
                val fold = w * 0.32f
                val sheet = Path().apply {
                    moveTo(x, y)
                    lineTo(x + w - fold, y)
                    lineTo(x + w, y + fold)
                    lineTo(x + w, y + h)
                    lineTo(x, y + h)
                    close()
                }
                drawPath(sheet, ink, style = line)
                val corner = Path().apply {
                    moveTo(x + w - fold, y)
                    lineTo(x + w - fold, y + fold)
                    lineTo(x + w, y + fold)
                }
                drawPath(corner, ink, style = Stroke(thin, cap = StrokeCap.Round, join = StrokeJoin.Round))
                for (row in listOf(0.55f, 0.75f)) {
                    drawLine(ink, Offset(x + w * 0.2f, y + h * row), Offset(x + w * 0.8f, y + h * row), thin, StrokeCap.Round)
                }
            }
            GlyphKind.SHARE -> {
                val left = at(4.8f, 12f)
                val top = at(19.2f, 4.32f)
                val bottom = at(19.2f, 19.68f)
                drawLine(ink, left, top, width, StrokeCap.Round)
                drawLine(ink, left, bottom, width, StrokeCap.Round)
                for (node in listOf(left, top, bottom)) ring(node, 2.5f * k, 3.5f * k, ink, paper, width)
            }
        }
    }
}

/** An open ring on a disc of paper, so that lines running into it stop short of its edge. */
private fun DrawScope.ring(centre: Offset, radius: Float, clear: Float, ink: Color, paper: Color, width: Float) {
    drawCircle(paper, clear, centre)
    drawCircle(ink, radius, centre, style = Stroke(width))
}

private val GLYPH_SIZE = 24.dp
private const val DISABLED_ALPHA = 0.38f
