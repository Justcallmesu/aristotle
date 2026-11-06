package io.github.justcallmesu.aristotle.ui.stocks

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.justcallmesu.aristotle.R
import io.github.justcallmesu.aristotle.data.stocks.BarSegment
import io.github.justcallmesu.aristotle.data.stocks.BarTone
import io.github.justcallmesu.aristotle.data.stocks.CountKind
import io.github.justcallmesu.aristotle.data.stocks.CountPart
import io.github.justcallmesu.aristotle.data.stocks.StockChanges
import io.github.justcallmesu.aristotle.data.stocks.StockFormat
import io.github.justcallmesu.aristotle.data.stocks.StockTimes
import io.github.justcallmesu.aristotle.data.stocks.TrackDisplay
import io.github.justcallmesu.aristotle.data.stocks.TrackFilters
import io.github.justcallmesu.aristotle.data.stocks.TrackRecords
import io.github.justcallmesu.aristotle.data.stocks.Verdict
import io.github.justcallmesu.aristotle.data.stocks.VerdictRecord
import io.github.justcallmesu.aristotle.ui.components.GroupedRows
import io.github.justcallmesu.aristotle.ui.sessions.QuietLine
import io.github.justcallmesu.aristotle.ui.sessions.SectionHeader
import io.github.justcallmesu.aristotle.ui.theme.AristotleStockColors
import io.github.justcallmesu.aristotle.ui.theme.AristotleTextStyles
import io.github.justcallmesu.aristotle.ui.theme.ButtonShape
import io.github.justcallmesu.aristotle.ui.theme.Sizes
import io.github.justcallmesu.aristotle.ui.theme.Spacing
import java.time.LocalDate
import java.util.Locale

/**
 * The AI track record (TR2, board `track-record.html`, variants Q121: a card per verdict type and tonal result chips): filters
 * (model, trigger, confidence), a notice for what is scored after 17:00, one card per verdict type, "How scoring works". The
 * PC is the only source (P14): out of reach, the page says so and draws nothing from an earlier read.
 */
@Composable
fun TrackRecordScreen(viewModel: TrackRecordViewModel, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel) {
        viewModel.pageVisible(true)
        onPauseOrDispose { viewModel.pageVisible(false) }
    }
    val locale = currentLocale()
    val view = state.view()
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            StocksTopBar(
                title = stringResource(R.string.stocks_record_title),
                subline = null,
                onBack = onBack,
                actions = {
                    IconButton(onClick = { viewModel.showSheet(true) }) {
                        Icon(painter = painterResource(R.drawable.ic_info), contentDescription = stringResource(R.string.stocks_record_how), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
            )
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = Spacing.xxl)) {
                if (view is TrackPageView.Content) item(key = "subline") { TrackSubline(trackSubline(view, locale)) }
                when (view) {
                    TrackPageView.Loading -> item { QuietLine(stringResource(R.string.stocks_loading)) }
                    TrackPageView.Offline -> item { ProblemBlock(stringResource(R.string.stocks_record_offline), onRetry = viewModel::retry) }
                    is TrackPageView.Failed -> item { ProblemBlock(stringResource(R.string.stocks_record_failed, stocksErrorText(view.error)), onRetry = viewModel::retry) }
                    TrackPageView.NoHistory -> item {
                        Column {
                            EmptyBlock(stringResource(R.string.stocks_record_nohist_title), stringResource(R.string.stocks_record_nohist_body))
                            HowButton(onClick = { viewModel.showSheet(true) })
                        }
                    }
                    is TrackPageView.OnlyPending -> item {
                        Column {
                            EmptyBlock(stringResource(R.string.stocks_record_first_title), pluralStringResource(R.plurals.stocks_record_first_body, view.pending, view.pending))
                            HowButton(onClick = { viewModel.showSheet(true) })
                        }
                    }
                    is TrackPageView.Content -> content(view, viewModel, locale)
                }
            }
        }
    }
    if (state.sheet) ScoringSheet(onClose = { viewModel.showSheet(false) })
}

private fun androidx.compose.foundation.lazy.LazyListScope.content(view: TrackPageView.Content, viewModel: TrackRecordViewModel, locale: Locale) {
    item(key = "filters") { FilterRow(view, viewModel) }
    if (view.pending > 0) item(key = "pending") { PendingNotice(view.pending) }
    if (view.noMatch) {
        item(key = "nomatch") {
            Column {
                EmptyBlock(stringResource(R.string.stocks_record_nomatch_title), stringResource(R.string.stocks_record_nomatch_body))
                TextButton(onClick = viewModel::clearFilters, shape = ButtonShape, modifier = Modifier.padding(horizontal = Spacing.s)) {
                    Text(stringResource(R.string.stocks_record_clear_all))
                }
            }
        }
    } else {
        item(key = "section") { SectionHeader(stringResource(R.string.stocks_record_section, view.records.size)) }
        item(key = "cards") {
            GroupedRows(view.records.size, modifier = Modifier.padding(horizontal = Spacing.listGutter)) { index -> VerdictRecordCard(view.records[index], locale) }
        }
    }
    item(key = "footer") {
        Column(modifier = Modifier.padding(horizontal = Spacing.listGutter, vertical = Spacing.m)) {
            Text(stringResource(R.string.stocks_record_footer), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HowButton(onClick = { viewModel.showSheet(true) })
        }
    }
}

/** The count line under the filters, in the body so a long date or a big text size wraps instead of being cut (it was a one-line top bar subline). */
@Composable
private fun TrackSubline(text: String) {
    Text(
        text,
        style = AristotleTextStyles.tabular(MaterialTheme.typography.bodySmall),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.listGutter, vertical = Spacing.xs),
    )
}

/** "117 verdicts scored · through Thu 5 Nov", or "15 of 117 scored verdicts" while filtered. */
@Composable
private fun trackSubline(view: TrackPageView.Content, locale: Locale): String {
    if (view.filters.any) return stringResource(R.string.stocks_record_subline_filtered, view.shown, view.total)
    val through = view.scoredThrough?.let { runCatching { LocalDate.parse(it) }.getOrNull() }?.let { StockTimes.day(it, locale) }
    return if (through != null) pluralStringResource(R.plurals.stocks_record_subline, view.total, view.total, through) else stringResource(R.string.stocks_record_subline_no_date, view.total)
}

@Composable
private fun ProblemBlock(text: String, onRetry: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        QuietLine(text)
        TextButton(onClick = onRetry, shape = ButtonShape, modifier = Modifier.padding(horizontal = Spacing.s)) { Text(stringResource(R.string.conn_retry_now)) }
    }
}

@Composable
private fun EmptyBlock(title: String, body: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.listGutter, vertical = Spacing.l)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Spacing.xs))
    }
}

@Composable
private fun HowButton(onClick: () -> Unit) {
    TextButton(onClick = onClick, shape = ButtonShape) { Text(stringResource(R.string.stocks_record_how)) }
}

/** "3 verdicts are scored after 17:00" (P11): a quiet surface with a clock, not an alert. */
@Composable
private fun PendingNotice(pending: Int) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.listGutter, vertical = Spacing.s),
    ) {
        Row(modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.m), verticalAlignment = Alignment.CenterVertically) {
            Icon(painter = painterResource(R.drawable.ic_schedule), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = Spacing.s).size(Sizes.lineIcon))
            Text(pluralStringResource(R.plurals.stocks_record_pending, pending, pending), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

// ---- filters ------------------------------------------------------------------------------------------------------

@Composable
private fun FilterRow(view: TrackPageView.Content, viewModel: TrackRecordViewModel) {
    val filters = view.filters
    LazyRow(contentPadding = PaddingValues(horizontal = Spacing.listGutter), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        if (filters.any) {
            item(key = "clear") {
                FilterChip(
                    selected = false,
                    onClick = viewModel::clearFilters,
                    label = { Text(stringResource(R.string.stocks_record_clear)) },
                    leadingIcon = { Icon(painter = painterResource(R.drawable.ic_close), contentDescription = null, modifier = Modifier.size(Sizes.chipIcon)) },
                    shape = MaterialTheme.shapes.small,
                )
            }
        }
        item(key = "model") {
            FilterMenu(
                name = stringResource(R.string.stocks_record_filter_model),
                active = filters.model?.let { familyText(it) },
                allLabel = stringResource(R.string.stocks_record_all_models),
                options = view.options.models.map { it to familyText(it) },
                selected = filters.model,
                onPick = viewModel::setModel,
            )
        }
        item(key = "trigger") {
            FilterMenu(
                name = stringResource(R.string.stocks_record_filter_trigger),
                active = filters.trigger?.let { triggerText(it) },
                allLabel = stringResource(R.string.stocks_record_all_triggers),
                options = view.options.triggers.map { it to triggerText(it) },
                selected = filters.trigger,
                onPick = viewModel::setTrigger,
            )
        }
        item(key = "confidence") {
            FilterMenu(
                name = stringResource(R.string.stocks_record_filter_confidence),
                active = filters.confidence?.let { levelText(it) },
                allLabel = stringResource(R.string.stocks_record_all_levels),
                options = view.options.confidences.map { it to levelText(it) },
                selected = filters.confidence,
                onPick = viewModel::setConfidence,
            )
        }
    }
}

@Composable
private fun familyText(key: String): String = when (key) {
    "opus" -> stringResource(R.string.stocks_record_family_opus)
    "sonnet" -> stringResource(R.string.stocks_record_family_sonnet)
    else -> stringResource(R.string.stocks_record_family_other)
}

@Composable
private fun levelText(wire: String): String = when (wire) {
    "low" -> stringResource(R.string.stocks_record_conf_low)
    "med" -> stringResource(R.string.stocks_record_conf_med)
    "high" -> stringResource(R.string.stocks_record_conf_high)
    else -> wire
}

/** One filter chip with its menu: All and the options, a check on the active one; active = `secondaryContainer`, "Model: Sonnet". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterMenu(name: String, active: String?, allLabel: String, options: List<Pair<String, String>>, selected: String?, onPick: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = selected != null,
            onClick = { open = true },
            label = { Text(if (active != null) stringResource(R.string.stocks_record_filter_active, name, active) else name) },
            trailingIcon = { Icon(painter = painterResource(R.drawable.ic_expand_more), contentDescription = null, modifier = Modifier.size(Sizes.chipIcon)) },
            shape = MaterialTheme.shapes.small,
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, shape = MaterialTheme.shapes.medium, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
            MenuChoice(allLabel, checked = selected == null) { open = false; onPick(null) }
            options.forEach { (value, label) -> MenuChoice(label, checked = selected == value) { open = false; onPick(value) } }
        }
    }
}

@Composable
private fun MenuChoice(label: String, checked: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        onClick = onClick,
        trailingIcon = if (checked) {
            { Icon(painter = painterResource(R.drawable.ic_check), contentDescription = null, modifier = Modifier.size(Sizes.chipIcon)) }
        } else {
            null
        },
    )
}

// ---- the card per verdict type ---------------------------------------------------------------------------------------

/**
 * One verdict type (board `VerdictRecordCard`): name and count; the rate in words with the average at the end; the split bar and
 * the counts; last, the 3-day result in grey, for information (Q117). Under 10 decided calls (Q122) the rate and the average are
 * replaced by "Not enough calls yet · n of 10", the bar and counts stay. A Wait has an average and no rate, bar or counts.
 */
@Composable
private fun VerdictRecordCard(record: VerdictRecord, locale: Locale) {
    val ink = MaterialTheme.colorScheme.onSurface
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.m)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(TrackText.verdictName(record.verdict)), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(pluralStringResource(R.plurals.stocks_record_n_verdicts, record.scored, record.scored), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = Spacing.s))
        }
        Row(modifier = Modifier.fillMaxWidth().padding(top = Spacing.xs), verticalAlignment = Alignment.Top) {
            Text(wordsText(TrackText.rate(record), ink), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            val avg = record.resultAvg
            if (avg != null && record.verdict != Verdict.Wait) Average(avg, record.verdict, modifier = Modifier.padding(start = Spacing.s))
        }
        val segments = TrackDisplay.bar(record)
        if (segments.isNotEmpty()) SplitBar(segments, modifier = Modifier.padding(top = Spacing.s))
        val counts = TrackDisplay.counts(record)
        if (counts.isNotEmpty()) {
            val line = counts.map { countText(it) }.joinToString(" · ")
            Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Spacing.s))
        }
        record.info3dAvg?.let { avg3 ->
            Text(stringResource(R.string.stocks_record_d3, StockFormat.percent(avg3)), style = AristotleTextStyles.tabular(MaterialTheme.typography.bodySmall), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Spacing.xs))
        }
    }
}

/** "Avg result" over a signed change; Avoid / Sell in ink, not green / red, since a fall is the right answer there. */
@Composable
private fun Average(avg: Double, verdict: Verdict, modifier: Modifier = Modifier) {
    val change = StockChanges.percent(avg)
    Column(modifier = modifier, horizontalAlignment = Alignment.End) {
        Text(stringResource(R.string.stocks_record_avg), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (TrackDisplay.inkAverage(verdict)) {
            Text(change.text, style = AristotleTextStyles.stockChange, color = MaterialTheme.colorScheme.onSurface)
        } else {
            ChangeText(change)
        }
    }
}

/** A real split: weighted boxes in the order of the counts (win gain, loss loss, flat outline, not triggered outlineVariant). Decorative. */
@Composable
private fun SplitBar(segments: List<BarSegment>, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().height(BAR_HEIGHT).clip(RoundedCornerShape(BAR_HEIGHT / 2)).clearAndSetSemantics {},
        horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
    ) {
        segments.forEach { segment -> Box(modifier = Modifier.weight(segment.n.toFloat()).fillMaxHeight().background(barColor(segment.tone))) }
    }
}

@Composable
private fun barColor(tone: BarTone): Color = when (tone) {
    BarTone.Gain -> AristotleStockColors.gain
    BarTone.Loss -> AristotleStockColors.loss
    BarTone.Flat -> MaterialTheme.colorScheme.outline
    BarTone.Muted -> MaterialTheme.colorScheme.outlineVariant
}

@Composable
private fun countText(part: CountPart): String = stringResource(
    when (part.kind) {
        CountKind.Win -> R.string.stocks_record_count_win
        CountKind.Loss -> R.string.stocks_record_count_loss
        CountKind.ClosedFlat -> R.string.stocks_record_count_closed
        CountKind.NotTriggered -> R.string.stocks_record_count_not_triggered
        CountKind.Right -> R.string.stocks_record_count_right
        CountKind.Wrong -> R.string.stocks_record_count_wrong
        CountKind.TargetHit -> R.string.stocks_record_count_target
        CountKind.Held -> R.string.stocks_record_count_held
        CountKind.StopHit -> R.string.stocks_record_count_stop
        CountKind.NotScored -> R.string.stocks_record_count_not_scored
    },
    part.n,
)

private val BAR_HEIGHT = 8.dp

// ---- how scoring works ---------------------------------------------------------------------------------------------

/** The scoring rules in plain words (Q117-Q125, P15); scrolls inside, a tonal Close at the end. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScoringSheet(onClose: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(start = Spacing.xxl, end = Spacing.xxl, bottom = Spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            Text(stringResource(R.string.stocks_record_how), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.stocks_record_sheet_intro), style = MaterialTheme.typography.bodyMedium)
            SHEET_ITEMS.forEach { (title, body) ->
                Column {
                    Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(stringResource(R.string.stocks_record_sheet_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FilledTonalButton(onClick = onClose, shape = ButtonShape, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.stocks_record_close)) }
        }
    }
}

private val SHEET_ITEMS = listOf(
    R.string.stocks_record_sheet_window_t to R.string.stocks_record_sheet_window_b,
    R.string.stocks_record_sheet_buy_t to R.string.stocks_record_sheet_buy_b,
    R.string.stocks_record_sheet_both_t to R.string.stocks_record_sheet_both_b,
    R.string.stocks_record_sheet_avoid_t to R.string.stocks_record_sheet_avoid_b,
    R.string.stocks_record_sheet_hold_t to R.string.stocks_record_sheet_hold_b,
    R.string.stocks_record_sheet_wait_t to R.string.stocks_record_sheet_wait_b,
    R.string.stocks_record_sheet_d3_t to R.string.stocks_record_sheet_d3_b,
    R.string.stocks_record_sheet_avg_t to R.string.stocks_record_sheet_avg_b,
    R.string.stocks_record_sheet_small_t to R.string.stocks_record_sheet_small_b,
    R.string.stocks_record_sheet_daily_t to R.string.stocks_record_sheet_daily_b,
)
