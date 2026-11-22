// SPDX-License-Identifier: GPL-3.0-or-later
// PAPRelay — Copyright (C) 2026 PAPRelay contributors.
package com.everysoft.paprelay.ui

import android.text.format.DateFormat
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.everysoft.paprelay.R
import com.everysoft.paprelay.core.nights.BreathingEvent
import com.everysoft.paprelay.core.nights.BreathingEventKind
import com.everysoft.paprelay.core.nights.EventReport
import com.everysoft.paprelay.core.nights.Night
import com.everysoft.paprelay.core.nights.NightFigure
import com.everysoft.paprelay.core.nights.NightUnit
import com.everysoft.paprelay.sync.Events
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

// One night at a time, with all its figures, and the list of breathing events.

/**
 * The finished nights, one to a page, oldest to the start and newest to the end, opening on
 * [shown] (an epoch day) or, with none, on the newest. Moved between by the arrows beside the
 * date, by a swipe, or by the date's own actions in a screen reader.
 */
@Composable
fun NightScreen(
    nights: List<Night>,
    events: Events,
    shown: Long?,
    onShown: (Long) -> Unit,
    onOpenEvents: (Long) -> Unit,
    onBack: () -> Unit,
) {
    val pages = remember(nights) { nights.filter { it.complete }.sortedBy { it.date } }
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
        Box(
            Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(Modifier.widthIn(max = 560.dp).fillMaxSize()) {
                TitleRow(R.string.nights_title, onBack, Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 12.dp))
                if (pages.isEmpty()) {
                    Text(stringResource(R.string.nights_empty), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 24.dp))
                    return@Column
                }
                val start = pages.indexOfFirst { it.date.toEpochDay() == shown }.takeIf { it >= 0 } ?: pages.lastIndex
                val pager = rememberPagerState(initialPage = start) { pages.size }
                val scope = rememberCoroutineScope()
                // A synchronisation that adds a night leaves the page on the same date.
                LaunchedEffect(pages) {
                    val keep = pages.indexOfFirst { it.date.toEpochDay() == shown }
                    if (keep >= 0 && keep != pager.currentPage) pager.scrollToPage(keep)
                }
                LaunchedEffect(pager) {
                    snapshotFlow { pager.settledPage }.distinctUntilChanged().collect { page -> pages.getOrNull(page)?.let { onShown(it.date.toEpochDay()) } }
                }
                val settled = pager.settledPage.coerceIn(0, pages.lastIndex)
                fun go(page: Int) {
                    if (!pager.isScrollInProgress && page in pages.indices) scope.launch { pager.animateScrollToPage(page) }
                }
                Navigator(pages[settled], earlier = settled > 0, later = settled < pages.lastIndex, onEarlier = { go(settled - 1) }, onLater = { go(settled + 1) })
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                HorizontalPager(pager, Modifier.weight(1f), key = { pages[it].date.toEpochDay() }) { page ->
                    val night = pages[page]
                    Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(start = 24.dp, end = 24.dp, top = 20.dp)
                            .padding(bottom = 32.dp + WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()),
                    ) {
                        NightPage(night, events.of(night), onOpenEvents = { onOpenEvents(night.date.toEpochDay()) })
                    }
                }
            }
        }
    }
}

@Composable
private fun TitleRow(@StringRes title: Int, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        BackButton(onBack)
        Spacer(Modifier.width(4.dp))
        Text(stringResource(title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f).semantics { heading() })
    }
}

/** The date of the night shown, between the arrows to the nights either side. */
@Composable
private fun Navigator(night: Night, earlier: Boolean, later: Boolean, onEarlier: () -> Unit, onLater: () -> Unit) {
    val heading = stringResource(R.string.night_of, remember(night.date) { dayFormatter().format(night.date) })
    val earlierLabel = stringResource(R.string.night_earlier)
    val laterLabel = stringResource(R.string.night_later)
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 12.dp, end = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        ArrowButton(GlyphKind.CHEVRON_BACK, earlierLabel, earlier, onEarlier)
        Text(
            heading,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f).semantics {
                heading()
                liveRegion = LiveRegionMode.Polite
                customActions = listOfNotNull(
                    CustomAccessibilityAction(earlierLabel) { onEarlier(); true }.takeIf { earlier },
                    CustomAccessibilityAction(laterLabel) { onLater(); true }.takeIf { later },
                )
            },
        )
        ArrowButton(GlyphKind.CHEVRON, laterLabel, later, onLater)
    }
}

@Composable
private fun ArrowButton(glyph: GlyphKind, label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = label
                role = Role.Button
                if (!enabled) disabled()
            },
        contentAlignment = Alignment.Center,
    ) {
        // A control, so in full ink, not the muted ink of a row's chevron.
        Glyph(glyph, enabled = enabled, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** A night's page: the headline, the picture of the night, every figure it has by section, and the note. */
@Composable
private fun NightPage(night: Night, events: List<BreathingEvent>, onOpenEvents: () -> Unit) {
    val hours = (night.usageMinutes / 60).toString()
    val minutes = (night.usageMinutes % 60).toString()
    if (night.usageMinutes == 0) {
        Text(stringResource(R.string.night_none), style = MaterialTheme.typography.headlineSmall)
        Secondary(stringResource(R.string.night_none_body), Modifier.padding(top = 4.dp))
        Note()
        return
    }
    val ahi = night.value(NightFigure.AHI)?.toPlainString()
    val spoken = listOfNotNull(stringResource(R.string.night_usage_spoken, hours, minutes), ahi?.let { stringResource(R.string.night_ahi_spoken, it) }).joinToString(". ")
    Text(
        listOfNotNull(stringResource(R.string.night_usage, hours, minutes), ahi?.let { stringResource(R.string.night_ahi, it) }).joinToString(" · "),
        style = MaterialTheme.typography.headlineSmall,
        modifier = Modifier.clearAndSetSemantics { contentDescription = spoken },
    )
    val sessions = night.sessionList
    if (sessions != null) {
        Spacer(Modifier.height(20.dp))
        NightTimeline(night, events)
    }

    val time = rememberClockFormatter()
    Section(R.string.night_section_use) {
        FigureTable(null, listOf(Line.Count(R.string.night_sessions, night.sessions)), night)
        sessions?.forEach { session ->
            val at = clockTime(session.correctedMs ?: session.start.epochMs, night.zoneOffsetMinutes, time) + if (session.correctedMs == null) "*" else ""
            val length = stringResource(R.string.night_usage, (session.minutes / 60).toString(), (session.minutes % 60).toString())
            val duration = stringResource(
                R.string.duration_spoken,
                pluralStringResource(R.plurals.hours_spoken, session.minutes / 60, session.minutes / 60),
                pluralStringResource(R.plurals.minutes_spoken, session.minutes % 60, session.minutes % 60),
            )
            val said = stringResource(R.string.night_session_spoken, at.removeSuffix("*"), duration) +
                if (session.correctedMs == null) ". " + stringResource(R.string.events_machine_clock_spoken) else ""
            Text(
                stringResource(R.string.night_session, at, length),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp).clearAndSetSemantics { contentDescription = said },
            )
        }
        val uncorrected = sessions?.count { it.correctedMs == null } ?: 0
        if (uncorrected > 0) Secondary(pluralStringResource(R.plurals.machine_clock_note, uncorrected), Modifier.padding(top = 4.dp))
    }
    Section(R.string.night_section_events, R.string.night_events_per_hour, shown = EVENT_LINES.any { it.has(night) }) {
        FigureTable(null, EVENT_LINES, night)
        if (events.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            DestinationRow(GlyphKind.TICKS, pluralStringResource(R.plurals.night_events_link, events.size, events.size), onOpenEvents)
        }
    }
    Section(R.string.night_section_leak, shown = LEAK_LINES.any { it.has(night) }) {
        FigureTable(listOf(Column.MEDIAN, Column.P70, Column.P95, Column.MAX), LEAK_LINES, night)
    }
    Section(R.string.night_section_pressure, shown = PRESSURE_LINES.any { it.has(night) }) {
        FigureTable(listOf(Column.MEDIAN, Column.P95, Column.MAX), PRESSURE_LINES, night)
    }
    Section(R.string.night_section_breathing, shown = (BREATHING_LINES + FLOW_LINES).any { it.has(night) }) {
        FigureTable(listOf(Column.MEDIAN, Column.P95, Column.MAX), BREATHING_LINES, night)
        if (FLOW_LINES.any { it.has(night) }) {
            Spacer(Modifier.height(12.dp))
            FigureTable(listOf(Column.P5, Column.P95), FLOW_LINES, night)
        }
    }
    Section(R.string.night_section_humidifier, R.string.night_medians, shown = HUMIDIFIER_LINES.any { it.has(night) }) {
        FigureTable(null, HUMIDIFIER_LINES, night)
    }
    Section(R.string.night_section_machine, R.string.night_machine_note, shown = (BLOWER_LINES + BLOWER_FLOW_LINES).any { it.has(night) }) {
        FigureTable(listOf(Column.P5, Column.P95), BLOWER_LINES, night)
        if (BLOWER_FLOW_LINES.any { it.has(night) }) {
            Spacer(Modifier.height(12.dp))
            FigureTable(null, BLOWER_FLOW_LINES, night)
        }
    }
    Note()
}

@Composable
private fun Note() {
    Spacer(Modifier.height(32.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Spacer(Modifier.height(12.dp))
    Secondary(stringResource(R.string.nights_note))
}

@Composable
private fun Section(@StringRes title: Int, @StringRes subtitle: Int? = null, shown: Boolean = true, content: @Composable () -> Unit) {
    if (!shown) return
    Spacer(Modifier.height(28.dp))
    Text(stringResource(title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
    if (subtitle != null) Secondary(stringResource(subtitle), Modifier.padding(top = 2.dp))
    Spacer(Modifier.height(8.dp))
    content()
}

/** A column of a figure table: which point of the night's distribution it holds. */
private enum class Column(@StringRes val head: Int, @StringRes val spoken: Int) {
    P5(R.string.col_p5, R.string.col_p5_spoken),
    MEDIAN(R.string.col_median, R.string.col_median_spoken),
    P70(R.string.col_p70, R.string.col_p70_spoken),
    P95(R.string.col_p95, R.string.col_p95_spoken),
    MAX(R.string.col_max, R.string.col_max_spoken),
}

/**
 * One row of a figure table. Rows are listed here, not taken from [NightFigure], so that a figure
 * added there cannot appear without a label of its own.
 */
private sealed class Line(@StringRes val label: Int) {
    /** Figures, one for each column of the table, or one for a table of single values. */
    class Figures(
        @StringRes label: Int,
        val figures: List<NightFigure>,
        val indent: Boolean = false,
        val bold: Boolean = false,
        /** How a screen reader is to say the label, where the written form would be read wrongly (AHI). */
        @StringRes val spokenLabel: Int? = null,
    ) : Line(label)

    /** A count, not a figure: the sessions. */
    class Count(@StringRes label: Int, val count: Int) : Line(label)

    fun has(night: Night): Boolean = when (this) {
        is Figures -> figures.any { night.value(it) != null }
        is Count -> true
    }
}

private val EVENT_LINES = listOf(
    Line.Figures(R.string.fig_ahi, listOf(NightFigure.AHI), bold = true, spokenLabel = R.string.fig_ahi_spoken),
    Line.Figures(R.string.fig_apnoeas, listOf(NightFigure.APNEA_INDEX)),
    Line.Figures(R.string.fig_obstructive, listOf(NightFigure.OBSTRUCTIVE_APNEA_INDEX), indent = true),
    Line.Figures(R.string.fig_central, listOf(NightFigure.CENTRAL_APNEA_INDEX), indent = true),
    Line.Figures(R.string.fig_unclassified, listOf(NightFigure.UNCLASSIFIED_APNEA_INDEX), indent = true),
    Line.Figures(R.string.fig_hypopnoeas, listOf(NightFigure.HYPOPNEA_INDEX)),
    Line.Figures(R.string.fig_rera, listOf(NightFigure.RERA_INDEX)),
)
private val LEAK_LINES = listOf(Line.Figures(R.string.fig_leak, listOf(NightFigure.LEAK_MEDIAN, NightFigure.LEAK_70, NightFigure.LEAK_95, NightFigure.LEAK_MAX)))
private val PRESSURE_LINES = listOf(
    Line.Figures(R.string.fig_pressure_therapy, listOf(NightFigure.THERAPY_PRESSURE_MEDIAN, NightFigure.THERAPY_PRESSURE_95, NightFigure.THERAPY_PRESSURE_MAX)),
    Line.Figures(R.string.fig_pressure_out, listOf(NightFigure.EXPIRATORY_PRESSURE_MEDIAN, NightFigure.EXPIRATORY_PRESSURE_95, NightFigure.EXPIRATORY_PRESSURE_MAX)),
    Line.Figures(R.string.fig_pressure_mask, listOf(NightFigure.MASK_PRESSURE_MEDIAN, NightFigure.MASK_PRESSURE_95, NightFigure.MASK_PRESSURE_MAX)),
)
private val BREATHING_LINES = listOf(
    Line.Figures(R.string.fig_rate, listOf(NightFigure.RESPIRATORY_RATE_MEDIAN, NightFigure.RESPIRATORY_RATE_95, NightFigure.RESPIRATORY_RATE_MAX)),
    Line.Figures(R.string.fig_tidal, listOf(NightFigure.TIDAL_VOLUME_MEDIAN, NightFigure.TIDAL_VOLUME_95, NightFigure.TIDAL_VOLUME_MAX)),
    Line.Figures(R.string.fig_minute, listOf(NightFigure.MINUTE_VENTILATION_MEDIAN, NightFigure.MINUTE_VENTILATION_95, NightFigure.MINUTE_VENTILATION_MAX)),
)
private val FLOW_LINES = listOf(Line.Figures(R.string.fig_flow, listOf(NightFigure.RESPIRATORY_FLOW_5, NightFigure.RESPIRATORY_FLOW_95)))
private val HUMIDIFIER_LINES = listOf(
    Line.Figures(R.string.fig_humidity, listOf(NightFigure.AMBIENT_HUMIDITY_MEDIAN)),
    Line.Figures(R.string.fig_humidifier_temp, listOf(NightFigure.HUMIDIFIER_TEMPERATURE_MEDIAN)),
    Line.Figures(R.string.fig_humidifier_power, listOf(NightFigure.HUMIDIFIER_POWER_MEDIAN)),
    Line.Figures(R.string.fig_tube_temp, listOf(NightFigure.HEATED_TUBE_TEMPERATURE_MEDIAN)),
    Line.Figures(R.string.fig_tube_power, listOf(NightFigure.HEATED_TUBE_POWER_MEDIAN)),
)
private val BLOWER_LINES = listOf(Line.Figures(R.string.fig_blower_pressure, listOf(NightFigure.BLOWER_PRESSURE_5, NightFigure.BLOWER_PRESSURE_95)))
private val BLOWER_FLOW_LINES = listOf(Line.Figures(R.string.fig_blower_flow, listOf(NightFigure.BLOWER_FLOW_MEDIAN)))

/** Every figure the night screen shows, once each: a test holds it to what the plan gives. */
internal val NIGHT_SCREEN_FIGURES: List<NightFigure> =
    (EVENT_LINES + LEAK_LINES + PRESSURE_LINES + BREATHING_LINES + FLOW_LINES + HUMIDIFIER_LINES + BLOWER_LINES + BLOWER_FLOW_LINES).flatMap { it.figures }

/** A unit's symbol, and the same in words, as string resources. Null for a figure shown without a unit line (the indices). */
private fun unitOf(unit: NightUnit): Pair<Int, Int>? = when (unit) {
    NightUnit.EVENTS_PER_HOUR -> null
    NightUnit.LITRES_PER_MINUTE -> R.string.unit_l_min to R.string.unit_l_min_spoken
    NightUnit.CM_H2O -> R.string.unit_cmh2o to R.string.unit_cmh2o_spoken
    NightUnit.BREATHS_PER_MINUTE -> R.string.unit_breaths_min to R.string.unit_breaths_min_spoken
    NightUnit.MILLILITRES -> R.string.unit_ml to R.string.unit_ml_spoken
    NightUnit.MILLIGRAMS_PER_LITRE -> R.string.unit_mg_l to R.string.unit_mg_l_spoken
    NightUnit.CELSIUS -> R.string.unit_celsius to R.string.unit_celsius_spoken
    NightUnit.PERCENT -> R.string.unit_percent to R.string.unit_percent_spoken
    NightUnit.HOURS, NightUnit.MINUTES -> null
}

/**
 * Figures as a table: a label (and its unit beneath) with a value under each of [columns], or one
 * value with no column heads when [columns] is null. A row the night has nothing for is left out;
 * a single missing value is a dash, never a zero. At large font sizes each row is stacked instead.
 * Each row is read out as one sentence, with its units in words.
 */
@Composable
private fun FigureTable(columns: List<Column>?, lines: List<Line>, night: Night) {
    val shown = lines.filter { it.has(night) }
    if (shown.isEmpty()) return
    // Wide enough for "120.6" at the font size where rows are not yet stacked, and never less than 56 dp.
    val valueWidth = with(LocalDensity.current) { maxOf(VALUE_WIDTH, VALUE_WIDTH_SP.toDp()) }
    val stacked = LocalDensity.current.fontScale > 1.3f
    val dash = stringResource(R.string.figure_missing)
    val notRecorded = stringResource(R.string.figure_not_recorded)
    val numbers = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum")
    if (columns != null && !stacked) {
        Row(Modifier.fillMaxWidth().clearAndSetSemantics {}) {
            Spacer(Modifier.weight(1f))
            for (column in columns) {
                Text(
                    stringResource(column.head),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(valueWidth),
                )
            }
        }
    }
    for (line in shown) {
        val label = stringResource(line.label)
        val saidLabel = (line as? Line.Figures)?.spokenLabel?.let { stringResource(it) } ?: label
        val values: List<String?>
        var unit: Pair<String, String>? = null
        var indent = false
        var bold = false
        when (line) {
            is Line.Count -> values = listOf(line.count.toString())
            is Line.Figures -> {
                values = line.figures.map { night.value(it)?.toPlainString() }
                unit = unitOf(line.figures.first().unit)?.let { (symbol, words) -> stringResource(symbol) to stringResource(words) }
                indent = line.indent
                bold = line.bold
            }
        }
        val spokenUnit = unit?.second ?: if (line is Line.Figures) stringResource(R.string.unit_events_h_spoken) else ""
        val spoken = if (columns == null) {
            stringResource(R.string.figure_single_spoken, saidLabel, values.single() ?: notRecorded, spokenUnit).replace(" .", ".")
        } else {
            val cells = columns.zip(values).map { (column, value) -> stringResource(R.string.figure_cell_spoken, stringResource(column.spoken), value ?: notRecorded) }
            stringResource(R.string.figure_row_spoken, saidLabel, spokenUnit, cells.joinToString(", "))
        }
        val labelStyle = if (bold) MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold) else MaterialTheme.typography.bodyLarge
        // Not tappable, so not the height of a touch target: close together, as a table is read.
        val rowModifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 36.dp)
            .padding(start = if (indent) 16.dp else 0.dp, top = 4.dp, bottom = 4.dp)
            .clearAndSetSemantics { contentDescription = spoken }
        // At large font sizes a row of several columns is stacked; a single value stays beside its label.
        if (stacked && columns != null) {
            Column(rowModifier) {
                Text(label, style = labelStyle)
                unit?.let { Text(it.first, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (columns == null) {
                        Text(values.single() ?: dash, style = numbers)
                    } else {
                        for ((column, value) in columns.zip(values)) Text(stringResource(column.head) + " " + (value ?: dash), style = numbers)
                    }
                }
            }
        } else {
            Row(rowModifier, verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(label, style = labelStyle)
                    unit?.let { Text(it.first, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                for (value in values) {
                    Text(
                        value ?: dash,
                        style = if (bold) numbers.copy(fontWeight = FontWeight.Bold) else numbers,
                        textAlign = TextAlign.End,
                        maxLines = 1,
                        modifier = if (columns == null) Modifier.widthIn(min = valueWidth) else Modifier.width(valueWidth),
                    )
                }
            }
        }
    }
}

private val VALUE_WIDTH = 56.dp
private val VALUE_WIDTH_SP = 52.sp

/** Hours and minutes in the phone's style (24-hour or not), without seconds. */
@Composable
private fun rememberClockFormatter(): DateTimeFormatter {
    val is24 = DateFormat.is24HourFormat(LocalContext.current)
    return remember(is24) {
        val locale = Locale.getDefault()
        DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, if (is24) "Hm" else "hm"), locale)
    }
}

/** [epochMs] as a time of day at the night's own offset: the machine's local time, wherever the phone is. */
private fun clockTime(epochMs: Long, offsetMinutes: Int, formatter: DateTimeFormatter): String =
    formatter.format(Instant.ofEpochMilli(epochMs).atOffset(ZoneOffset.ofTotalSeconds(offsetMinutes * 60)))

/**
 * The night from midday to midday as a line drawing: the day as a grey line, the sessions as thick
 * ink on it, each event as a mark above it (tall for an apnoea, short for a hypopnoea). Everything
 * is placed by the machine's own clock, the clock the day and the sessions are measured by.
 */
@Composable
private fun NightTimeline(night: Night, events: List<BreathingEvent>) {
    val start = night.start.toInstant().toEpochMilli()
    val end = night.end.toInstant().toEpochMilli()
    val sessions = night.sessionList.orEmpty()
    val drawn = events.filter { it.machineTime.epochMs in start until end }
    val scheme = MaterialTheme.colorScheme
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val is24 = DateFormat.is24HourFormat(LocalContext.current)
    val hourFormat = remember(is24) {
        val locale = Locale.getDefault()
        DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, if (is24) "H" else "ha"), locale)
    }
    // Six-hour marks from the day's start; the last is its end, which is midday again.
    val marks = (0..3).map { start + it * SIX_HOURS } + end
    val labels = marks.mapIndexed { index, at -> clockTime(if (index == 4) start else at, night.zoneOffsetMinutes, hourFormat) }
    val sessionWords = pluralStringResource(R.plurals.timeline_sessions, sessions.size, sessions.size)
    val description = if (drawn.isEmpty()) {
        stringResource(R.string.timeline_description_no_events, sessionWords)
    } else {
        stringResource(R.string.timeline_description, sessionWords, pluralStringResource(R.plurals.timeline_events, drawn.size, drawn.size))
    }
    val fontScale = LocalDensity.current.fontScale
    Column(Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = description }) {
        Canvas(Modifier.fillMaxWidth().height(36.dp)) {
            val inset = 3.dp.toPx()
            fun x(t: Long): Float {
                val along = inset + (t - start).toFloat() / (end - start).toFloat() * (size.width - 2 * inset)
                return if (rtl) size.width - along else along
            }
            val lineY = 26.dp.toPx()
            drawLine(scheme.outline, Offset(x(start), lineY), Offset(x(end), lineY), 2.dp.toPx(), StrokeCap.Round)
            for (mark in marks) drawLine(scheme.outline, Offset(x(mark), lineY), Offset(x(mark), 31.dp.toPx()), 1.5.dp.toPx(), StrokeCap.Round)
            for (session in sessions) {
                val from = session.start.epochMs.coerceIn(start, end)
                val to = (session.start.epochMs + session.minutes * 60_000L).coerceIn(start, end)
                drawLine(scheme.onSurface, Offset(x(from), lineY), Offset(x(to), lineY), 6.dp.toPx(), StrokeCap.Round)
            }
            for (event in drawn) {
                val top = if (event.kind.stampIsEnd) 4.dp.toPx() else 11.dp.toPx()
                val at = x(event.machineTime.epochMs)
                drawLine(scheme.onSurface, Offset(at, top), Offset(at, 19.dp.toPx()), 1.5.dp.toPx(), StrokeCap.Round)
            }
        }
        Spacer(Modifier.height(2.dp))
        val style = MaterialTheme.typography.bodySmall
        Layout(
            content = { for (label in labels) Text(label, style = style, color = scheme.onSurfaceVariant, maxLines = 1) },
            modifier = Modifier.fillMaxWidth(),
        ) { measurables, constraints ->
            val width = constraints.maxWidth
            val placeables = measurables.map { it.measure(Constraints()) }
            val inset = 3.dp.toPx()
            fun centre(index: Int): Float {
                val along = inset + (marks[index] - start).toFloat() / (end - start).toFloat() * (width - 2 * inset)
                return if (rtl) width - along else along
            }
            fun left(index: Int): Int = (centre(index) - placeables[index].width / 2f).toInt().coerceIn(0, (width - placeables[index].width).coerceAtLeast(0))
            val all = placeables.indices.toList()
            val positions = all.map { left(it) }
            val ordered = all.sortedBy { positions[it] }
            val overlap = ordered.zipWithNext().any { (a, b) -> positions[a] + placeables[a].width > positions[b] }
            val showing = if (fontScale > 1.3f || overlap) listOf(0, 2, 4) else all
            val height = placeables.maxOf { it.height }
            layout(width, height) {
                for (index in showing) placeables[index].placeRelative(if (rtl) width - positions[index] - placeables[index].width else positions[index], 0)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.timeline_legend_sessions) + if (drawn.isNotEmpty()) " " + stringResource(R.string.timeline_legend_events) else "",
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
        )
    }
}

private const val SIX_HOURS = 6L * 60 * 60 * 1000

/**
 * The events of one night, newest first; [night] null for events outside every night kept, which
 * are each shown at the offset of the night nearest to it ([offsets], one for each event): a run of
 * them between two nights may lie nearer the one or the other.
 */
internal class EventGroup(val night: Night?, val events: List<BreathingEvent>, val offsets: List<Int>) {
    val key: String = "n" + (night?.date?.toEpochDay()?.toString() ?: ("x" + events.first().machineTime.epochMs))
}

/** What the list shows, one item each, in order. */
private sealed class EventItem(val key: String) {
    class Header(val group: EventGroup, val first: Boolean) : EventItem(group.key)

    class Row(val event: BreathingEvent, val offsetMinutes: Int) : EventItem("e" + event.identity)

    object More : EventItem("more")
}

/**
 * Groups the events shown (those of finished nights, as in the file: [EventReport.shown]) by the
 * night that holds each, newest night first; events outside every night are grouped by where they fall.
 */
internal fun groupByNight(events: Events, nights: List<Night>): List<EventGroup> {
    val finished = EventReport.Nights(nights.filter { it.complete })
    val shown = EventReport.shown(events.all, nights)
    val groups = ArrayList<EventGroup>()
    var current: Night? = null
    var members = ArrayList<BreathingEvent>()
    var open = false
    for (event in shown.asReversed()) {
        val ms = event.machineTime.epochMs
        val night = finished.holding(ms)
        if (open && night !== current) {
            groups += EventGroup(current, members, members.map { current?.zoneOffsetMinutes ?: finished.offsetNear(it.machineTime.epochMs) })
            members = ArrayList()
        }
        current = night
        open = true
        members += event
    }
    if (open) groups += EventGroup(current, members, members.map { current?.zoneOffsetMinutes ?: finished.offsetNear(it.machineTime.epochMs) })
    return groups
}

/**
 * Every breathing event kept, newest first, under the night it belongs to. Built a few nights at a
 * time as it is scrolled. Opened from a night ([startAt], an epoch day), it begins at that night.
 */
@Composable
fun EventsScreen(nights: List<Night>, events: Events, startAt: Long?, onBack: () -> Unit) {
    val groups by produceState<List<EventGroup>?>(null, events, nights) { value = withContext(Dispatchers.Default) { groupByNight(events, nights) } }
    var window by rememberSaveable { mutableIntStateOf(PAGE_NIGHTS) }
    val all = groups
    val target = remember(all, startAt) { all?.indexOfFirst { it.night?.date?.toEpochDay() == startAt }?.takeIf { it >= 0 } }
    if (target != null && window <= target) window = (target / PAGE_NIGHTS + 1) * PAGE_NIGHTS
    val items = remember(all, window) {
        val out = ArrayList<EventItem>()
        all?.take(window)?.forEachIndexed { index, group ->
            out += EventItem.Header(group, first = index == 0)
            group.events.forEachIndexed { index, event -> out += EventItem.Row(event, group.offsets[index]) }
        }
        if (all != null && all.size > window) out += EventItem.More
        out
    }
    val list = rememberLazyListState()
    // Two items come before the events: the title and the note.
    var scrolled by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(items, target) {
        if (!scrolled && target != null) {
            val index = items.indexOfFirst { it is EventItem.Header && it.group === all?.get(target) }
            if (index >= 0) {
                list.scrollToItem(index + 2)
                scrolled = true
            }
        }
    }
    LaunchedEffect(list, all) {
        snapshotFlow { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last ->
            if (all != null && all.size > window && last >= list.layoutInfo.totalItemsCount - LOAD_AHEAD) window += PAGE_NIGHTS
        }
    }
    val time = rememberClockFormatter()
    val measurer = rememberTextMeasurer()
    val numbers = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum")
    val sample = if (DateFormat.is24HourFormat(LocalContext.current)) "00:00*" else "12:00 PM*"
    val timeWidth = with(LocalDensity.current) { measurer.measure(sample, numbers).size.width.toDp() }
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
        Box(
            Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
            contentAlignment = Alignment.TopCenter,
        ) {
            LazyColumn(
                Modifier.widthIn(max = 560.dp).fillMaxSize(),
                state = list,
                contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 32.dp + WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()),
            ) {
                item(key = "title") { TitleRow(R.string.events_title, onBack, Modifier.padding(top = 16.dp, bottom = 12.dp)) }
                item(key = "note") {
                    if (all != null && all.isEmpty()) {
                        Text(stringResource(R.string.events_empty), style = MaterialTheme.typography.bodyLarge)
                    } else {
                        Secondary(stringResource(R.string.events_note), Modifier.padding(bottom = 8.dp))
                    }
                }
                for (entry in items) {
                    when (entry) {
                        is EventItem.Header -> stickyHeader(key = entry.key) { GroupHeader(entry.group, entry.first) }
                        is EventItem.Row -> item(key = entry.key) { EventRow(entry.event, entry.offsetMinutes, time, timeWidth, numbers) }
                        EventItem.More -> item(key = entry.key) {
                            Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) { Secondary(stringResource(R.string.machine_loading)) }
                        }
                    }
                }
            }
        }
    }
}

/** How many nights with events are added to the list at a time, and how near its end the next are added. */
private const val PAGE_NIGHTS = 14
private const val LOAD_AHEAD = 20

@Composable
private fun GroupHeader(group: EventGroup, first: Boolean) {
    val title = group.night?.let { stringResource(R.string.night_of, remember(it.date) { dayFormatter().format(it.date) }) } ?: stringResource(R.string.events_no_night)
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { heading() }) {
        // On the page's own colour, so that the rows scrolling beneath it do not show through.
        Surface(color = MaterialTheme.colorScheme.surface) {
            Column {
                if (!first) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Text(
                        pluralStringResource(R.plurals.events_count, group.events.size, group.events.size),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun EventRow(event: BreathingEvent, offset: Int, time: DateTimeFormatter, timeWidth: androidx.compose.ui.unit.Dp, numbers: TextStyle) {
    val corrected = event.correctedMs
    val at = clockTime(corrected ?: event.machineTime.epochMs, offset, time)
    val (glyph, name) = when (event.kind) {
        BreathingEventKind.OBSTRUCTIVE_APNEA -> GlyphKind.EVENT_OBSTRUCTIVE to R.string.event_obstructive
        BreathingEventKind.CENTRAL_APNEA -> GlyphKind.EVENT_CENTRAL to R.string.event_central
        BreathingEventKind.UNCLASSIFIED_APNEA -> GlyphKind.EVENT_UNCLASSIFIED to R.string.event_unclassified
        BreathingEventKind.HYPOPNEA -> GlyphKind.EVENT_HYPOPNOEA to R.string.event_hypopnoea
    }
    // Exactly as stored, as in the file.
    val seconds = event.durationSeconds?.toPlainString()
    val kind = stringResource(name)
    val spoken = listOfNotNull(
        at,
        if (corrected == null) stringResource(R.string.events_machine_clock_spoken) else null,
        kind,
        seconds?.let { stringResource(R.string.event_length_spoken, it) },
    ).joinToString(". ")
    Row(
        Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(vertical = 4.dp).clearAndSetSemantics { contentDescription = spoken },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph(glyph)
        Spacer(Modifier.width(12.dp))
        Text(at + if (corrected == null) "*" else "", style = numbers, maxLines = 1, modifier = Modifier.width(timeWidth))
        Spacer(Modifier.width(12.dp))
        // The kind and its length share a baseline, whatever their sizes.
        Row(Modifier.weight(1f)) {
            Text(kind, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).alignByBaseline())
            if (seconds != null) {
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.event_length, seconds),
                    style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.alignByBaseline(),
                )
            }
        }
    }
}
