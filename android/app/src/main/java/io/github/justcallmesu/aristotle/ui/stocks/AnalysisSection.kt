package io.github.justcallmesu.aristotle.ui.stocks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.justcallmesu.aristotle.R
import io.github.justcallmesu.aristotle.data.stocks.AnalysisPhase
import io.github.justcallmesu.aristotle.data.stocks.AnalysisProblem
import io.github.justcallmesu.aristotle.data.stocks.AnalysisResult
import io.github.justcallmesu.aristotle.data.stocks.AnalysisStates
import io.github.justcallmesu.aristotle.data.stocks.AnalysisView
import io.github.justcallmesu.aristotle.data.stocks.Confidence
import io.github.justcallmesu.aristotle.data.stocks.RecordLine
import io.github.justcallmesu.aristotle.data.stocks.SourceLabel
import io.github.justcallmesu.aristotle.data.stocks.StockFormat
import io.github.justcallmesu.aristotle.data.stocks.StockQuote
import io.github.justcallmesu.aristotle.data.stocks.StockTimes
import io.github.justcallmesu.aristotle.data.stocks.StocksError
import io.github.justcallmesu.aristotle.data.stocks.Verdict
import io.github.justcallmesu.aristotle.data.stocks.VerdictFlag
import io.github.justcallmesu.aristotle.data.stocks.VerdictSource
import io.github.justcallmesu.aristotle.data.stocks.VerdictTone
import io.github.justcallmesu.aristotle.ui.components.RunningSpinner
import io.github.justcallmesu.aristotle.ui.sessions.QuietLine
import io.github.justcallmesu.aristotle.ui.sessions.SectionHeader
import io.github.justcallmesu.aristotle.ui.theme.AristotleStockColors
import io.github.justcallmesu.aristotle.ui.theme.AristotleTextStyles
import io.github.justcallmesu.aristotle.ui.theme.ButtonShape
import io.github.justcallmesu.aristotle.ui.theme.Spacing
import java.time.LocalDate
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * The Analyze section of a stock's page (TA2; spec Q96-Q99, Q17): idle → Analyze and what it costs; running → a live
 * line with the elapsed time (the job runs on the PC, so the page may be left); done → the verdict card; failed → why,
 * and Retry, with the kept verdict below. No board screen exists for it: grouped `surfaceContainerLow` surfaces, Soft
 * corners, the stock palette only on the verdict word, verdigris only while the job runs (design.md §1).
 *
 * The model's words (summary, reasons) are plain `String`s in plain `Text`s: no Markdown, no link detection, nothing
 * clickable inside them (TA1 review MINOR-1). Only a headline chip opens a link, and only an http(s) one.
 */
@Composable
internal fun AnalysisSection(
    code: String,
    view: AnalysisView,
    readProblem: StocksError?,
    reachable: Boolean,
    quote: StockQuote?,
    today: LocalDate,
    locale: Locale,
    onAnalyze: () -> Unit,
    onReadAgain: () -> Unit,
    /** TR2: the card's record line for a verdict (Q124); null while the record is not read. */
    record: (Verdict?) -> RecordLine? = { null },
    onOpenRecord: () -> Unit = {},
) {
    SectionHeader(stringResource(R.string.stocks_analysis_title))
    when (view.phase) {
        AnalysisPhase.Unknown -> when {
            readProblem != null -> ReadProblemRow(readProblem, reachable, onReadAgain)
            reachable -> QuietLine(stringResource(R.string.stocks_loading))
            else -> QuietLine(stringResource(R.string.stocks_not_loaded))
        }
        AnalysisPhase.Idle -> AnalyzeRow(reachable, starting = false, onAnalyze)
        AnalysisPhase.Starting -> AnalyzeRow(reachable, starting = true, onAnalyze)
        AnalysisPhase.Running -> RunningRow(view.startedAt)
        AnalysisPhase.Failed -> FailedRow(code, view.problem, reachable, onAnalyze)
        AnalysisPhase.Done -> Unit
    }
    val result = view.result
    if (result != null && view.phase != AnalysisPhase.Running && view.phase != AnalysisPhase.Starting) {
        if (view.phase == AnalysisPhase.Failed) Spacer(Modifier.height(Spacing.s))
        VerdictCard(
            result = result,
            quote = quote,
            staleFrom = view.staleFrom,
            today = today,
            locale = locale,
            // A failure above already offers Retry; the card offers Analyze again only when it is the answer.
            onAnalyzeAgain = onAnalyze.takeIf { view.phase == AnalysisPhase.Done },
            enabled = reachable,
            recordLine = record(Verdict.of(result.verdict)),
            onOpenRecord = onOpenRecord,
        )
    }
}

/** Analyze (tonal) and one line on what it does and costs. */
@Composable
private fun AnalyzeRow(reachable: Boolean, starting: Boolean, onAnalyze: () -> Unit) {
    SectionSurface {
        Text(stringResource(R.string.stocks_analyze_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FilledTonalButton(onClick = onAnalyze, enabled = reachable && !starting, shape = ButtonShape, modifier = Modifier.padding(top = Spacing.m)) {
            Text(stringResource(if (starting) R.string.stocks_analyze_starting else R.string.stocks_analyze))
        }
    }
}

/** "Analyzing · 0:42" beside the verdigris spinner (live work), and that the page may be left. */
@Composable
private fun RunningRow(startedAt: String?) {
    val seconds by produceState(AnalysisStates.elapsedSeconds(startedAt, System.currentTimeMillis()), startedAt) {
        while (true) {
            delay(TICK_MILLIS)
            value = AnalysisStates.elapsedSeconds(startedAt, System.currentTimeMillis())
        }
    }
    SectionSurface {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            RunningSpinner()
            Text(
                stringResource(R.string.stocks_analyze_running, seconds?.let(AnalysisStates::elapsedText) ?: StockFormat.missing),
                style = AristotleTextStyles.tabular(AristotleTextStyles.liveStatus),
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        Text(
            stringResource(R.string.stocks_analyze_running_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.xs),
        )
    }
}

/** "Analysis failed" (error: it is one), the cause and the fix, and Retry. */
@Composable
private fun FailedRow(code: String, problem: AnalysisProblem?, reachable: Boolean, onRetry: () -> Unit) {
    SectionSurface(modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        Text(stringResource(R.string.stocks_analyze_failed), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
        problem?.let {
            Text(analysisProblemText(it, code), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Spacing.xs))
        }
        TextButton(onClick = onRetry, enabled = reachable, shape = ButtonShape, modifier = Modifier.padding(top = Spacing.xs)) {
            Text(stringResource(R.string.conn_retry_now))
        }
    }
}

@Composable
private fun ReadProblemRow(problem: StocksError, reachable: Boolean, onRetry: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(start = Spacing.listGutter, end = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.stocks_analysis_read_failed, stocksErrorText(problem)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (reachable) TextButton(onClick = onRetry, shape = ButtonShape) { Text(stringResource(R.string.conn_retry_now)) }
    }
}

/** Why an analysis failed, one line per error code (protocol §20.1). */
@Composable
internal fun analysisProblemText(problem: AnalysisProblem, code: String): String = when (problem) {
    AnalysisProblem.HermesUnavailable -> stringResource(R.string.stocks_analysis_error_hermes)
    AnalysisProblem.HermesRefused -> stringResource(R.string.stocks_analysis_error_hermes_refused)
    AnalysisProblem.ShuttingDown -> stringResource(R.string.stocks_analysis_error_shutting_down)
    AnalysisProblem.NotSetUp -> stringResource(R.string.stocks_analysis_error_not_set_up)
    AnalysisProblem.NoData -> stringResource(R.string.stocks_analysis_error_no_data)
    AnalysisProblem.AlreadyRunning -> stringResource(R.string.stocks_analysis_error_running, code)
    AnalysisProblem.Busy -> stringResource(R.string.stocks_analysis_error_busy)
    AnalysisProblem.Timeout -> stringResource(R.string.stocks_analysis_error_timeout)
    AnalysisProblem.InvalidVerdict -> stringResource(R.string.stocks_analysis_error_invalid)
    AnalysisProblem.ModelError -> stringResource(R.string.stocks_analysis_error_model)
    AnalysisProblem.ToolsPresent -> stringResource(R.string.stocks_analysis_error_tools)
    AnalysisProblem.UnexpectedPrompt -> stringResource(R.string.stocks_analysis_error_prompt)
    AnalysisProblem.Interrupted -> stringResource(R.string.stocks_analysis_error_interrupted)
    is AnalysisProblem.Request -> stocksErrorText(problem.error)
    is AnalysisProblem.Job -> stringResource(R.string.stocks_analysis_error_job, problem.code ?: "-")
}

/**
 * The verdict card (Q98): the verdict word large in its tone and the confidence; the summary; entry zone, target and
 * stop with their percent from the last price; 3-5 reasons with their sources; quiet flags; data time, model and
 * duration; the fixed disclaimer (Q17); Analyze again. A verdict for an earlier session says so first and drops its
 * colour ([staleFrom]).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VerdictCard(
    result: AnalysisResult,
    quote: StockQuote?,
    staleFrom: LocalDate?,
    today: LocalDate,
    locale: Locale,
    onAnalyzeAgain: (() -> Unit)?,
    enabled: Boolean,
    recordLine: RecordLine?,
    onOpenRecord: () -> Unit,
) {
    val model = VerdictCardModel.of(result, quote)
    val muted = staleFrom != null
    SectionSurface {
        if (staleFrom != null) {
            Text(
                stringResource(R.string.stocks_verdict_stale, StockTimes.day(staleFrom, locale)),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = Spacing.s),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                verdictWord(model.verdict, model.verdictWire),
                style = AristotleTextStyles.stockValueLarge,
                color = verdictColor(model.verdict?.tone, muted),
                modifier = Modifier.weight(1f),
            )
            model.confidence?.let { ConfidenceChip(it) }
        }
        // TR2 (Q124): this verdict type's record across all models and triggers; tap opens the track record page.
        recordLine?.let { RecordLineRow(it, muted = !enabled, onOpen = onOpenRecord) }
        if (model.summary.isNotEmpty()) {
            // Plain text, verbatim: never Markdown, never linkified (TA1 review MINOR-1).
            Text(model.summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = Spacing.s))
        }
        if (model.hasLevels) Levels(model, muted)
        if (model.reasons.isNotEmpty()) {
            Text(stringResource(R.string.stocks_verdict_why), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Spacing.l))
            model.reasons.forEachIndexed { i, reason ->
                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(vertical = Spacing.s))
                Reason(reason, today, locale, first = i == 0)
            }
        }
        val flags = model.flags.map { flagText(it) } + if (model.repaired) listOf(stringResource(R.string.stocks_flag_repaired)) else emptyList()
        if (flags.isNotEmpty()) {
            Text(flags.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Spacing.m))
        }
        val asOf = StockTimes.dayClock(result.dataAsOf, today, locale) ?: StockFormat.missing
        // TBA2: a run the PC started on its own (≥ 2 setups fired, Q87/Q107) says so; it counts in today's automatic runs.
        if (model.auto) {
            Text(
                stringResource(R.string.stocks_verdict_auto),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.m),
            )
        }
        Text(
            stringResource(R.string.stocks_verdict_meta, asOf, result.model.ifEmpty { StockFormat.missing }, AnalysisStates.durationSeconds(result.durationMs)),
            style = AristotleTextStyles.tabular(MaterialTheme.typography.bodySmall),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = if (model.auto) Spacing.xxs else Spacing.m),
        )
        Text(stringResource(R.string.stocks_verdict_disclaimer), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        onAnalyzeAgain?.let {
            OutlinedButton(onClick = it, enabled = enabled, shape = ButtonShape, modifier = Modifier.padding(top = Spacing.m)) {
                Text(stringResource(R.string.stocks_analyze_again))
            }
        }
    }
}

/** Entry zone, target and stop in three columns, then which price the percents are from. */
@Composable
private fun Levels(model: VerdictCardModel, muted: Boolean) {
    Row(modifier = Modifier.fillMaxWidth().padding(top = Spacing.m), horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
        LevelColumn(stringResource(R.string.stocks_verdict_entry), model.entry, muted, Modifier.weight(1f))
        LevelColumn(stringResource(R.string.stocks_verdict_target), model.target, muted, Modifier.weight(1f))
        LevelColumn(stringResource(R.string.stocks_verdict_stop), model.stop, muted, Modifier.weight(1f))
    }
    if (model.reference > 0) {
        Text(
            stringResource(R.string.stocks_verdict_pct_from, StockFormat.number(model.reference)),
            style = AristotleTextStyles.tabular(MaterialTheme.typography.bodySmall),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.xs),
        )
    }
}

@Composable
private fun LevelColumn(label: String, cell: LevelCell?, muted: Boolean, modifier: Modifier) {
    Column(modifier = modifier.semantics(mergeDescendants = true) {}) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            cell?.value ?: StockFormat.missing,
            style = AristotleTextStyles.tabular(MaterialTheme.typography.labelLarge),
            color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
        cell?.pct?.let { ends ->
            val text = if (ends.size == 2) stringResource(R.string.stocks_verdict_pct_range, ends[0], ends[1]) else ends.first()
            Text(text, style = AristotleTextStyles.tabular(MaterialTheme.typography.bodySmall), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** One reason: its text (plain, verbatim), the headline chips that open their article, the other sources as labels. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Reason(reason: ReasonLine, today: LocalDate, locale: Locale, first: Boolean) {
    val openLink = rememberLinkOpener()
    Column(modifier = Modifier.fillMaxWidth().padding(top = if (first) Spacing.xs else 0.dp)) {
        Text(reason.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        if (reason.labels.isNotEmpty()) {
            val labels = reason.labels.map { sourceLabel(it) }.joinToString(", ")
            Text(stringResource(R.string.stocks_verdict_sources, labels), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Spacing.xxs))
        }
        if (reason.news.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                reason.news.forEach { chip ->
                    val meta = listOf(sourceName(chip.source), StockTimes.dayClock(chip.publishedAt, today, locale).orEmpty()).filter { it.isNotEmpty() }.joinToString(" · ")
                    AssistChip(
                        onClick = { chip.link?.let(openLink) },
                        enabled = chip.link != null,
                        label = {
                            Text(
                                listOf(meta, chip.title).filter { it.isNotEmpty() }.joinToString(" · "),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = NEWS_CHIP_MAX),
                            )
                        },
                        leadingIcon = { Icon(painter = painterResource(R.drawable.ic_newspaper), contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize)) },
                        shape = MaterialTheme.shapes.small,
                        border = AssistChipDefaults.assistChipBorder(enabled = chip.link != null, borderColor = MaterialTheme.colorScheme.outlineVariant),
                    )
                }
            }
        }
    }
}

/** "Medium confidence": a quiet tag (not a control), Soft small corners on a 28 dp height (no pill). */
@Composable
internal fun ConfidenceChip(confidence: Confidence) {
    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHighest, contentColor = MaterialTheme.colorScheme.onSurface) {
        Text(
            stringResource(
                when (confidence) {
                    Confidence.Low -> R.string.stocks_confidence_low
                    Confidence.Medium -> R.string.stocks_confidence_med
                    Confidence.High -> R.string.stocks_confidence_high
                },
            ),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = CHIP_PADDING, vertical = Spacing.xs),
        )
    }
}

/** A grouped surface of the section (`large`, `surfaceContainerLow`), like the Today and holding cards. */
@Composable
private fun SectionSurface(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = modifier.fillMaxWidth().padding(horizontal = Spacing.listGutter)) {
        Column(modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.m)) { content() }
    }
}

@Composable
internal fun verdictWord(verdict: Verdict?, wire: String): String = when (verdict) {
    Verdict.BuyToday -> stringResource(R.string.stocks_verdict_buy_today)
    Verdict.Wait -> stringResource(R.string.stocks_verdict_wait)
    Verdict.Avoid -> stringResource(R.string.stocks_verdict_avoid)
    Verdict.Add -> stringResource(R.string.stocks_verdict_add)
    Verdict.Hold -> stringResource(R.string.stocks_verdict_hold)
    Verdict.Sell -> stringResource(R.string.stocks_verdict_sell)
    null -> wire
}

/** Buy / Add the stock-up tone, Avoid / Sell the stock-down tone, Wait / Hold ink; muted (stale) drops the colour. The word is the cue. */
@Composable
private fun verdictColor(tone: VerdictTone?, muted: Boolean): Color = when {
    muted -> MaterialTheme.colorScheme.onSurfaceVariant
    tone == VerdictTone.Up -> AristotleStockColors.gain
    tone == VerdictTone.Down -> AristotleStockColors.loss
    else -> MaterialTheme.colorScheme.onSurface
}

@Composable
private fun flagText(flag: String): String = when (VerdictFlag.of(flag)) {
    VerdictFlag.LowRewardRisk -> stringResource(R.string.stocks_flag_low_reward_risk)
    VerdictFlag.WideStop -> stringResource(R.string.stocks_flag_wide_stop)
    VerdictFlag.EntryFar -> stringResource(R.string.stocks_flag_entry_far)
    VerdictFlag.TargetAboveR1 -> stringResource(R.string.stocks_flag_target_above_r1)
    VerdictFlag.MarketClosed -> stringResource(R.string.stocks_flag_market_closed)
    VerdictFlag.LimitsUnknown -> stringResource(R.string.stocks_flag_limits_unknown)
    VerdictFlag.NewsOnly -> stringResource(R.string.stocks_flag_news_only)
    null -> flag
}

@Composable
internal fun sourceLabel(source: VerdictSource): String = when (source) {
    is VerdictSource.Known -> source.label.fixed ?: knownLabel(source.label)
    is VerdictSource.Other -> source.text
    is VerdictSource.News -> source.item?.title ?: source.id
}

@Composable
internal fun knownLabel(label: SourceLabel): String = when (label) {
    SourceLabel.Price -> stringResource(R.string.stocks_source_price)
    SourceLabel.Trend -> stringResource(R.string.stocks_source_trend)
    SourceLabel.ChangeToday -> stringResource(R.string.stocks_source_change_today)
    SourceLabel.LastChange -> stringResource(R.string.stocks_source_last_change)
    SourceLabel.DistMa20 -> stringResource(R.string.stocks_source_dist_ma, SourceLabel.Ma20.fixed.orEmpty())
    SourceLabel.DistMa50 -> stringResource(R.string.stocks_source_dist_ma, SourceLabel.Ma50.fixed.orEmpty())
    SourceLabel.AvgVolume20 -> stringResource(R.string.stocks_source_avg_vol20)
    SourceLabel.VolumeToday -> stringResource(R.string.stocks_source_vol_today)
    SourceLabel.VolumeRatio -> stringResource(R.string.stocks_source_vol_ratio)
    SourceLabel.VolumePace -> stringResource(R.string.stocks_source_vol_pace)
    SourceLabel.VolumeLast -> stringResource(R.string.stocks_source_vol_last)
    SourceLabel.High52 -> stringResource(R.string.stocks_source_high52)
    SourceLabel.Low52 -> stringResource(R.string.stocks_source_low52)
    SourceLabel.Position52 -> stringResource(R.string.stocks_source_pos52)
    SourceLabel.Open -> stringResource(R.string.stocks_open)
    SourceLabel.DayHigh -> stringResource(R.string.stocks_source_day_high)
    SourceLabel.DayLow -> stringResource(R.string.stocks_source_day_low)
    SourceLabel.Gap -> stringResource(R.string.stocks_source_gap)
    SourceLabel.Support1 -> stringResource(R.string.stocks_source_support, 1)
    SourceLabel.Support2 -> stringResource(R.string.stocks_source_support, 2)
    SourceLabel.Resistance1 -> stringResource(R.string.stocks_source_resistance, 1)
    SourceLabel.Resistance2 -> stringResource(R.string.stocks_source_resistance, 2)
    SourceLabel.High20 -> stringResource(R.string.stocks_source_high20)
    SourceLabel.Low20 -> stringResource(R.string.stocks_source_low20)
    SourceLabel.HoldLots -> stringResource(R.string.stocks_source_hold_lots)
    SourceLabel.HoldAverage -> stringResource(R.string.stocks_source_hold_avg)
    SourceLabel.HoldProfit -> stringResource(R.string.stocks_source_hold_pl)
    // The fixed ones never reach here ([sourceLabel] uses [SourceLabel.fixed] first).
    SourceLabel.Ma20, SourceLabel.Ma50, SourceLabel.Rsi14, SourceLabel.Atr14, SourceLabel.Vwap,
    SourceLabel.Pivot, SourceLabel.PivotR1, SourceLabel.PivotS1,
    -> label.fixed.orEmpty()
}

private const val TICK_MILLIS = 1_000L
private val CHIP_PADDING = 10.dp
private val NEWS_CHIP_MAX = 260.dp
