package io.github.justcallmesu.aristotle.ui.stocks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.justcallmesu.aristotle.R
import io.github.justcallmesu.aristotle.data.stocks.AnalysisLang
import io.github.justcallmesu.aristotle.data.stocks.AnalysisNews
import io.github.justcallmesu.aristotle.data.stocks.BriefBody
import io.github.justcallmesu.aristotle.data.stocks.BriefCard
import io.github.justcallmesu.aristotle.data.stocks.BriefCite
import io.github.justcallmesu.aristotle.data.stocks.BriefCiteKind
import io.github.justcallmesu.aristotle.data.stocks.BriefRefs
import io.github.justcallmesu.aristotle.data.stocks.BriefStance
import io.github.justcallmesu.aristotle.data.stocks.Confidence
import io.github.justcallmesu.aristotle.data.stocks.SourceLabel
import io.github.justcallmesu.aristotle.data.stocks.StockCodes
import io.github.justcallmesu.aristotle.data.stocks.StockFormat
import io.github.justcallmesu.aristotle.data.stocks.StockTimes
import io.github.justcallmesu.aristotle.data.stocks.Verdict
import io.github.justcallmesu.aristotle.ui.chat.ChatLinks
import io.github.justcallmesu.aristotle.ui.components.GroupedRows
import io.github.justcallmesu.aristotle.ui.sessions.QuietLine
import io.github.justcallmesu.aristotle.ui.sessions.SectionHeader
import io.github.justcallmesu.aristotle.ui.theme.AristotleTextStyles
import io.github.justcallmesu.aristotle.ui.theme.ButtonShape
import io.github.justcallmesu.aristotle.ui.theme.Spacing
import java.time.LocalDate
import java.util.Locale

// TBA2: the 08:30 brief (Stocks home card + page) and the stock alerts list. Every model word (headline, summary,
// notes, news and risk texts) is a plain String in a plain Text: no Markdown, no link detection (TA1 MINOR-1).

/** Stocks home's "Today's brief" card (`large`, `surfaceContainerLow`): its state in one line, the headline when ready. */
@Composable
internal fun BriefHomeCard(card: BriefCard, briefAt: String?, locale: Locale, onOpen: () -> Unit) {
    if (card == BriefCard.Unknown) return
    val line = briefCardLine(card, briefAt, locale, withLast = true)
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(start = Spacing.listGutter, end = Spacing.listGutter, top = Spacing.s),
    ) {
        Row(
            modifier = Modifier.clickable(role = Role.Button, onClick = onOpen).padding(horizontal = Spacing.l, vertical = Spacing.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                Text(stringResource(R.string.stocks_brief_title), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (card is BriefCard.Ready) {
                    Text(card.headline, style = AristotleTextStyles.newsHeadline, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = Spacing.xs))
                }
                line?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Spacing.xs)) }
            }
            Icon(painter = painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * The status line for [card], on Home's card and on the page alike (null when the headline says it all). [briefAt] is the
 * brief job's own time; [withLast]: the page's top bar already says the day, so it leaves the "Last: ..." part out.
 */
@Composable
private fun briefCardLine(card: BriefCard, briefAt: String?, locale: Locale, withLast: Boolean): String? = when (card) {
    BriefCard.Unknown -> null
    is BriefCard.Ready -> when {
        card.running -> stringResource(R.string.stocks_brief_running)
        card.late -> stringResource(R.string.stocks_brief_late, briefAt ?: DEFAULT_BRIEF_AT)
        else -> null
    }
    is BriefCard.Pending -> stringResource(R.string.stocks_brief_pending, card.at ?: DEFAULT_BRIEF_AT)
    BriefCard.Running -> stringResource(R.string.stocks_brief_running)
    BriefCard.NoneToday -> stringResource(R.string.stocks_brief_none_today)
    is BriefCard.Failed -> stringResource(R.string.stocks_brief_failed, briefErrorText(card.error))
    BriefCard.Missed -> stringResource(R.string.stocks_brief_missed, briefAt ?: DEFAULT_BRIEF_AT)
    is BriefCard.NotTradingDay -> listOfNotNull(
        stringResource(R.string.stocks_brief_not_trading),
        card.last?.takeIf { withLast }?.let { stringResource(R.string.stocks_brief_last, StockTimes.day(it, locale)) },
    ).joinToString(" · ")
    is BriefCard.Off -> listOfNotNull(
        stringResource(R.string.stocks_brief_off),
        card.last?.takeIf { withLast }?.let { stringResource(R.string.stocks_brief_last, StockTimes.day(it, locale)) },
    ).joinToString(" · ")
}

@Composable
private fun briefErrorText(code: String?): String = when (code) {
    "hermes_unavailable", "hermes_lost" -> stringResource(R.string.stocks_brief_error_hermes)
    "busy" -> stringResource(R.string.stocks_brief_error_busy)
    "invalid_brief" -> stringResource(R.string.stocks_brief_error_invalid)
    "analyzer_not_set_up" -> stringResource(R.string.stocks_brief_error_setup)
    "no_data" -> stringResource(R.string.stocks_brief_error_no_data)
    else -> stringResource(R.string.stocks_brief_error_other, code ?: "-")
}

/**
 * The brief page (§20.4): headline, summary, Focus (code, stance, note, the cited levels and indicators drawn from the
 * server's stored values), News (text and the headlines as chips), Risks; data time and model; the disclaimer (Q17); Run
 * again (today only); the last 7 briefs as day chips.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BriefScreen(viewModel: BriefViewModel, onBack: () -> Unit, onOpenStock: (String) -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel) {
        viewModel.pageVisible(true)
        onPauseOrDispose { viewModel.pageVisible(false) }
    }
    val locale = currentLocale()
    val nowMillis by rememberMinuteClock()
    val today = StockTimes.today(nowMillis)
    val lang = AnalysisLang.of(locale)
    val snackbar = remember { SnackbarHostState() }
    val rerunText = state.rerunProblem?.let { stringResource(R.string.stocks_brief_rerun_failed, stocksErrorText(it)) }
    LaunchedEffect(state.rerunProblem) {
        if (rerunText != null) {
            snackbar.showSnackbar(rerunText)
            viewModel.rerunProblemShown()
        }
    }
    val brief = state.page
    val status = state.pageStatus(today, StockTimes.timeOfDay(nowMillis))
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Box(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Column(modifier = Modifier.fillMaxSize()) {
                StocksTopBar(
                    title = stringResource(R.string.stocks_brief_page_title),
                    subline = brief?.date?.let { d -> runCatching { LocalDate.parse(d) }.getOrNull()?.let { StockTimes.day(it, locale) } },
                    onBack = onBack,
                )
                LazyColumn(modifier = Modifier.weight(1f), contentPadding = PaddingValues(bottom = Spacing.xxl)) {
                    state.dayChips().takeIf { it.isNotEmpty() }?.let { chips ->
                        item(key = "days") {
                            FlowRow(modifier = Modifier.padding(horizontal = Spacing.listGutter), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                                chips.forEach { chip ->
                                    val day = runCatching { LocalDate.parse(chip.date) }.getOrNull()?.let { StockTimes.day(it, locale) } ?: chip.date
                                    FilterChip(
                                        selected = chip.selected,
                                        onClick = { viewModel.pick(chip.pick) },
                                        label = { Text(day) },
                                        shape = MaterialTheme.shapes.small,
                                    )
                                }
                            }
                        }
                    }
                    item(key = "body") {
                        when {
                            brief == null && state.latestMissing -> QuietLine(stringResource(R.string.stocks_brief_none))
                            brief == null -> QuietLine(state.problem?.let { stocksErrorText(it) } ?: stringResource(if (state.reachable) R.string.stocks_loading else R.string.stocks_not_loaded))
                            else -> BriefBodyView(brief, status, state.briefAt, today, locale, onOpenStock)
                        }
                    }
                    if (brief != null) {
                        item(key = "foot") {
                            Column(modifier = Modifier.padding(horizontal = Spacing.listGutter, vertical = Spacing.m)) {
                                Text(stringResource(R.string.stocks_verdict_disclaimer), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (brief.date == today.toString()) {
                                    OutlinedButton(
                                        onClick = { viewModel.runAgain(lang) },
                                        enabled = state.canRerun(today),
                                        shape = ButtonShape,
                                        modifier = Modifier.padding(top = Spacing.m),
                                    ) { Text(stringResource(if (brief.running || state.rerunning) R.string.stocks_brief_running_short else R.string.stocks_brief_run_again)) }
                                }
                            }
                        }
                    }
                }
            }
            SnackbarHost(hostState = snackbar, modifier = Modifier.align(Alignment.BottomCenter))
        }
    }
}

@Composable
private fun BriefBodyView(brief: BriefBody, card: BriefCard?, briefAt: String?, today: LocalDate, locale: Locale, onOpenStock: (String) -> Unit) {
    val result = brief.result
    val status = card?.let { briefCardLine(it, briefAt, locale, withLast = false) }
    // A focus code the model wrote is opened only when it is a real IDX code (^[A-Z]{4}$).
    val openFocus: (String) -> Unit = { code -> if (StockCodes.valid(code)) onOpenStock(code) }
    Column(modifier = Modifier.fillMaxWidth()) {
        status?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = Spacing.listGutter, vertical = Spacing.s))
        }
        if (result == null) return@Column
        Text(
            result.headline,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = Spacing.listGutter, vertical = Spacing.s).semantics { heading() },
        )
        if (result.summary.isNotEmpty()) {
            Text(result.summary, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = Spacing.listGutter))
        }
        if (result.focus.isNotEmpty()) {
            SectionHeader(stringResource(R.string.stocks_brief_focus))
            GroupedRows(result.focus.size, modifier = Modifier.padding(horizontal = Spacing.listGutter)) { index ->
                val focus = result.focus[index]
                Column(
                    modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { openFocus(focus.code) }.padding(horizontal = Spacing.l, vertical = Spacing.m),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(focus.code, style = AristotleTextStyles.stockCode, modifier = Modifier.weight(1f))
                        Text(stanceText(focus.stance), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(focus.note, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = Spacing.xs))
                    CitesView(BriefRefs.cites(focus.sources, brief), today, locale)
                }
            }
        }
        PointsSection(stringResource(R.string.stocks_brief_news), result.news, brief, today, locale)
        PointsSection(stringResource(R.string.stocks_brief_risks), result.risks, brief, today, locale)
        val written = StockTimes.dayClock(brief.finishedAt, today, locale)
        if (written != null) {
            Text(
                stringResource(R.string.stocks_brief_meta, written, brief.model?.ifEmpty { null } ?: StockFormat.missing),
                style = AristotleTextStyles.tabular(MaterialTheme.typography.bodySmall),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = Spacing.listGutter, end = Spacing.listGutter, top = Spacing.l),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PointsSection(title: String, points: List<io.github.justcallmesu.aristotle.data.stocks.BriefPoint>, brief: BriefBody, today: LocalDate, locale: Locale) {
    if (points.isEmpty()) return
    SectionHeader(title)
    points.forEachIndexed { i, point ->
        if (i > 0) HorizontalDivider(modifier = Modifier.padding(horizontal = Spacing.listGutter), color = MaterialTheme.colorScheme.outlineVariant)
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.listGutter, vertical = Spacing.s)) {
            Text(point.text, style = MaterialTheme.typography.bodyMedium)
            CitesView(BriefRefs.cites(point.sources, brief), today, locale)
        }
    }
}

/** The cited values as one quiet line ("Resistance 1 4.930 · RSI 14 38,2"), then the cited headlines as chips (TA2). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CitesView(cites: List<BriefCite>, today: LocalDate, locale: Locale) {
    val values = cites.filter { it.kind != BriefCiteKind.News }.map { citeText(it) }
    if (values.isNotEmpty()) {
        Text(
            values.joinToString(" · "),
            style = AristotleTextStyles.tabular(MaterialTheme.typography.bodySmall),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.xs),
        )
    }
    val news = cites.mapNotNull { it.news }
    if (news.isNotEmpty()) {
        val openLink = rememberLinkOpener()
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            news.forEach { item -> NewsCiteChip(item, today, locale, openLink) }
        }
    }
}

@Composable
private fun NewsCiteChip(item: AnalysisNews, today: LocalDate, locale: Locale, openLink: (String) -> Unit) {
    val link = ChatLinks.webTarget(item.link)
    val meta = listOf(sourceName(item.source), StockTimes.dayClock(item.publishedAt, today, locale).orEmpty()).filter { it.isNotEmpty() }.joinToString(" · ")
    AssistChip(
        onClick = { link?.let(openLink) },
        enabled = link != null,
        label = {
            Text(listOf(meta, item.title).filter { it.isNotEmpty() }.joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = NEWS_CHIP_MAX))
        },
        leadingIcon = { Icon(painter = painterResource(R.drawable.ic_newspaper), contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize)) },
        shape = MaterialTheme.shapes.small,
        border = AssistChipDefaults.assistChipBorder(enabled = link != null, borderColor = MaterialTheme.colorScheme.outlineVariant),
    )
}

/** "BBCA Resistance 1 4.930": the code when the line is not about one stock only, the label, the stored value. */
@Composable
private fun citeText(cite: BriefCite): String {
    val label = when (cite.kind) {
        BriefCiteKind.Close -> stringResource(R.string.stocks_cite_close)
        BriefCiteKind.LastChange -> knownLabel(SourceLabel.LastChange)
        BriefCiteKind.DistMa20 -> knownLabel(SourceLabel.DistMa20)
        BriefCiteKind.DistMa50 -> knownLabel(SourceLabel.DistMa50)
        BriefCiteKind.Rsi14 -> SourceLabel.Rsi14.fixed.orEmpty()
        BriefCiteKind.Trend -> knownLabel(SourceLabel.Trend)
        BriefCiteKind.Support1 -> knownLabel(SourceLabel.Support1)
        BriefCiteKind.Resistance1 -> knownLabel(SourceLabel.Resistance1)
        BriefCiteKind.Pivot -> SourceLabel.Pivot.fixed.orEmpty()
        BriefCiteKind.Ara -> stringResource(R.string.stocks_cite_ara)
        BriefCiteKind.Arb -> stringResource(R.string.stocks_cite_arb)
        BriefCiteKind.HoldProfit -> knownLabel(SourceLabel.HoldProfit)
        BriefCiteKind.Verdict -> stringResource(R.string.stocks_cite_verdict)
        BriefCiteKind.Screener -> stringResource(R.string.stocks_cite_screener)
        BriefCiteKind.News, BriefCiteKind.Other -> cite.id.substringAfterLast(':')
    }
    val value = when (cite.kind) {
        BriefCiteKind.Trend -> cite.words.firstOrNull()?.let { trendWord(it) }
        BriefCiteKind.Verdict -> cite.words.takeIf { it.isNotEmpty() && it[0].isNotEmpty() }?.let { w ->
            val verdict = verdictWord(Verdict.of(w[0]), w[0])
            val confidence = Confidence.of(w.getOrElse(1) { "" })?.let { confidenceShort(it) }
            val auto = if (w.getOrElse(2) { "" } == "auto") stringResource(R.string.stocks_cite_auto) else null
            listOfNotNull(verdict, confidence, auto).joinToString(", ")
        }
        else -> cite.value
    }
    return listOfNotNull(cite.code, label, value ?: StockFormat.missing).joinToString(" ")
}

@Composable
private fun trendWord(wire: String): String = when (wire) {
    "up" -> stringResource(R.string.stocks_trend_up)
    "down" -> stringResource(R.string.stocks_trend_down)
    "side" -> stringResource(R.string.stocks_trend_side)
    else -> stringResource(R.string.stocks_trend_unknown)
}

@Composable
private fun confidenceShort(confidence: Confidence): String = stringResource(
    when (confidence) {
        Confidence.Low -> R.string.stocks_confidence_low
        Confidence.Medium -> R.string.stocks_confidence_med
        Confidence.High -> R.string.stocks_confidence_high
    },
)

@Composable
private fun stanceText(wire: String): String = when (BriefStance.of(wire)) {
    BriefStance.WatchBuy -> stringResource(R.string.stocks_brief_stance_watch_buy)
    BriefStance.Watch -> stringResource(R.string.stocks_brief_stance_watch)
    BriefStance.Avoid -> stringResource(R.string.stocks_brief_stance_avoid)
    null -> wire
}

private const val DEFAULT_BRIEF_AT = "08:30"
private val NEWS_CHIP_MAX = 260.dp
