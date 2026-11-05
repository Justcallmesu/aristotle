package io.github.justcallmesu.aristotle.ui.stocks

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.IntState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.justcallmesu.aristotle.R
import io.github.justcallmesu.aristotle.data.stocks.AnalysisLang
import io.github.justcallmesu.aristotle.data.stocks.ChartFit
import io.github.justcallmesu.aristotle.data.stocks.ChartHold
import io.github.justcallmesu.aristotle.data.stocks.ChartLabels
import io.github.justcallmesu.aristotle.data.stocks.DetailRules
import io.github.justcallmesu.aristotle.data.stocks.ChartModel
import io.github.justcallmesu.aristotle.data.stocks.ChartRange
import io.github.justcallmesu.aristotle.data.stocks.ChartReadout
import io.github.justcallmesu.aristotle.data.stocks.HoldingBlock
import io.github.justcallmesu.aristotle.data.stocks.HoldingRow
import io.github.justcallmesu.aristotle.data.stocks.MarketStatus
import io.github.justcallmesu.aristotle.data.stocks.NewsItem
import io.github.justcallmesu.aristotle.data.stocks.QuotesBody
import io.github.justcallmesu.aristotle.data.stocks.SearchResult
import io.github.justcallmesu.aristotle.data.stocks.StockChanges
import io.github.justcallmesu.aristotle.data.stocks.StockFees
import io.github.justcallmesu.aristotle.data.stocks.StockFormat
import io.github.justcallmesu.aristotle.data.stocks.StockQuote
import io.github.justcallmesu.aristotle.data.stocks.StockTimes
import io.github.justcallmesu.aristotle.data.stocks.StocksError
import io.github.justcallmesu.aristotle.data.stocks.TodayOpen
import io.github.justcallmesu.aristotle.ui.components.GroupedRows
import io.github.justcallmesu.aristotle.ui.sessions.QuietLine
import io.github.justcallmesu.aristotle.ui.sessions.SectionHeader
import io.github.justcallmesu.aristotle.ui.theme.AristotleTextStyles
import io.github.justcallmesu.aristotle.ui.theme.ButtonShape
import io.github.justcallmesu.aristotle.ui.theme.Sizes
import io.github.justcallmesu.aristotle.ui.theme.Spacing
import java.time.LocalDate
import java.util.Locale

/**
 * A stock's page (TS3, board "Stock detail", route `stock/{code}`): the code and name; the last price with its change
 * and the time it is from; the readout, the chart and its range / type controls; today's numbers; the AI analysis
 * ([AnalysisSection], TA2); the holding (or Add holding); news that mentions it; Open in Ajaib when Ajaib is installed
 * (Q94). The chart, quote, news and analysis are [detail]'s; the watchlist, holdings, fees and the holding sheet are the
 * stocks pages' shared [viewModel].
 */
@Composable
fun StockDetailScreen(code: String, viewModel: StocksViewModel, detail: StockDetailViewModel, onBack: () -> Unit, onOpenNews: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val page by detail.uiState.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel, detail) {
        viewModel.detailVisible(true)
        detail.visible(true)
        onPauseOrDispose {
            viewModel.detailVisible(false)
            detail.visible(false)
        }
    }
    val context = LocalContext.current
    var brokerInstalled by remember { mutableStateOf(BrokerApp.launchIntent(context) != null) }
    LifecycleResumeEffect(Unit) {
        brokerInstalled = BrokerApp.launchIntent(context) != null
        onPauseOrDispose { }
    }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val brokerGone = stringResource(R.string.stocks_broker_missing, BrokerApp.NAME)
    StocksMessages(state.message, snackbar, viewModel::messageShown)
    val locale = currentLocale()
    val quote = page.quote ?: state.quoteOf(code)
    val muted = !page.reachable || quote?.stale == true
    val name = state.nameOf(code).ifEmpty { quote?.name.orEmpty() }
    val held = state.holdings?.rowOf(code)
    val actions = DetailActions(
        held = held != null,
        watched = state.watchlist?.let { list -> list.any { it.code == code } },
        enabled = page.reachable,
        holdingEnabled = DetailRules.holdingEnabled(page.reachable, state.holdings != null),
        onHolding = { if (held != null) viewModel.editHolding(code) else viewModel.pickForHolding(SearchResult(code, name)) },
        onWatch = { add -> if (add) viewModel.addToWatchlist(code) else viewModel.removeFromWatchlist(code) },
    )
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Box(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Column(modifier = Modifier.fillMaxSize()) {
                DetailTopBar(code, name, onBack, actions)
                Column(modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                    PriceHeader(quote, page.quotes ?: state.quotes, page.reachable, muted, locale)
                    ChartSection(page, code, quote, muted, locale, detail)
                    SectionHeader(stringResource(R.string.stocks_today))
                    KeyNumbers(quote, TodayOpen.of(page.day, quote), page.day?.prevClose, muted)
                    val today = StockTimes.today(System.currentTimeMillis())
                    AnalysisSection(
                        code = code,
                        view = page.analysisView((page.quotes ?: state.quotes)?.market, today),
                        readProblem = page.analysisReadProblem,
                        reachable = page.reachable,
                        quote = quote,
                        today = today,
                        locale = locale,
                        onAnalyze = { detail.analyze(AnalysisLang.of(locale)) },
                        onReadAgain = detail::retryAnalysisRead,
                    )
                    SectionHeader(stringResource(R.string.stocks_your_holding))
                    when {
                        held != null -> HoldingCard(held, quote, state.fees, muted, onEdit = { viewModel.editHolding(code) })
                        state.holdings != null -> NotHeldRow(code, enabled = page.reachable, onAdd = actions.onHolding)
                        else -> QuietLine(stringResource(if (page.reachable) R.string.stocks_loading else R.string.stocks_not_loaded))
                    }
                    RelatedNews(code, page.news, page.newsProblem, locale, onOpenNews)
                    Spacer(Modifier.height(Spacing.m))
                }
                if (brokerInstalled) {
                    FilledTonalButton(
                        onClick = {
                            DetailRules.brokerTap(open = { BrokerApp.open(context) }, notInstalled = {
                                brokerInstalled = false
                                scope.launch { snackbar.showSnackbar(brokerGone) }
                            })
                        },
                        shape = ButtonShape,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.s),
                    ) {
                        Icon(painter = painterResource(R.drawable.ic_open_in_new), contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.width(Spacing.s))
                        Text(stringResource(R.string.stocks_open_in_broker, BrokerApp.NAME))
                    }
                }
            }
            SnackbarHost(hostState = snackbar, modifier = Modifier.align(Alignment.BottomCenter))
        }
    }
    StocksSheets(state, viewModel)
}

/** The ⋮ menu's choices: Add / Edit holding, Add to / Remove from watchlist ([watched] null until the list is read). */
private class DetailActions(
    val held: Boolean,
    val watched: Boolean?,
    val enabled: Boolean,
    /** Add / Edit holding: only once the holdings are read. */
    val holdingEnabled: Boolean,
    val onHolding: () -> Unit,
    val onWatch: (add: Boolean) -> Unit,
)

/** Back, the code (Mono 600 18) over the name, and ⋮ (board `.topbar` with `.dcode`). */
@Composable
private fun DetailTopBar(code: String, name: String, onBack: () -> Unit, actions: DetailActions) {
    var menu by remember { mutableStateOf(false) }
    Row(modifier = Modifier.fillMaxWidth().heightIn(min = Sizes.topBarHeight).padding(horizontal = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(painter = painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.nav_back), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(modifier = Modifier.padding(start = Spacing.xs, end = Spacing.s).weight(1f)) {
            Text(code, style = AristotleTextStyles.stockCodeTitle, maxLines = 1, modifier = Modifier.semantics { heading() })
            if (name.isNotEmpty()) {
                Text(name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Box {
            IconButton(onClick = { menu = true }, enabled = actions.enabled) {
                Icon(painter = painterResource(R.drawable.ic_more_vert), contentDescription = stringResource(R.string.stocks_more_for, code))
            }
            DropdownMenu(
                expanded = menu,
                onDismissRequest = { menu = false },
                shape = MaterialTheme.shapes.medium,
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(Sizes.divider, MaterialTheme.colorScheme.outlineVariant),
            ) {
                DropdownMenuItem(
                    enabled = actions.holdingEnabled,
                    text = { Text(stringResource(if (actions.held) R.string.stocks_edit_holding else R.string.stocks_add_holding)) },
                    leadingIcon = { Icon(painter = painterResource(R.drawable.ic_account_balance_wallet), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    onClick = {
                        menu = false
                        actions.onHolding()
                    },
                )
                actions.watched?.let { watched ->
                    DropdownMenuItem(
                        text = { Text(stringResource(if (watched) R.string.stocks_remove_from_watchlist else R.string.stocks_add_to_watchlist)) },
                        leadingIcon = {
                            Icon(painter = painterResource(if (watched) R.drawable.ic_delete else R.drawable.ic_add), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        },
                        onClick = {
                            menu = false
                            actions.onWatch(!watched)
                        },
                    )
                }
            }
        }
    }
}

/** The last price (32 / 40), its change with arrow and sign (Q91), and when it is from. Offline or stale: muted. */
@Composable
private fun PriceHeader(quote: StockQuote?, quotes: QuotesBody?, reachable: Boolean, muted: Boolean, locale: Locale) {
    Column(modifier = Modifier.fillMaxWidth().padding(start = Spacing.l, end = Spacing.l, bottom = Spacing.xs)) {
        Text(
            text = priceText(quote?.price),
            style = AristotleTextStyles.stockPriceDetail,
            color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
        quote?.let { ChangeText(StockChanges.quote(it), muted = muted, style = MaterialTheme.typography.titleMedium.let(AristotleTextStyles::tabular), arrow = HEADER_ARROW) }
        val asOf = quotes?.let { StockTimes.dayClock(quote?.marketTime ?: it.asOf, StockTimes.today(System.currentTimeMillis()), locale) }
        if (quotes != null && asOf != null) {
            val line = when {
                !reachable -> R.string.stocks_prices_as_of
                quotes.market.state == MarketStatus.CLOSED -> R.string.stocks_closing_prices
                else -> R.string.stocks_prices_delayed
            }
            Text(stringResource(line, asOf), style = AristotleTextStyles.tabular(MaterialTheme.typography.bodySmall), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The readout, the chart (or why there is none), the range chips and the candles / line toggle, then any problem. */
@Composable
private fun ChartSection(page: StockDetailState, code: String, quote: StockQuote?, muted: Boolean, locale: Locale, detail: StockDetailViewModel) {
    val slot = page.chart
    val body = slot.body
    val range = page.range
    val prevClose = body?.prevClose ?: quote?.prevClose
    val fresh = remember(range, body, prevClose) { body?.let { ChartModel.of(range, it, prevClose) } }
    // A new range starts without a crosshair.
    val crosshair = remember(range) { mutableIntStateOf(NONE) }
    // A finger holding the crosshair keeps its model: the 60 s refresh's new bars show once it lets go.
    val holding by remember(crosshair) { derivedStateOf { crosshair.intValue != NONE } }
    var shown by remember { mutableStateOf<ChartModel?>(null) }
    val model = ChartHold.pick(shown, fresh, holding, sameSeries = shown?.range == range)
    SideEffect { shown = model }
    val today = StockTimes.today(System.currentTimeMillis())
    Readout(model, crosshair, page.type == ChartType.Candles, muted, today, locale)
    Box(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.l)) {
        if (model != null) {
            PriceChart(
                model = model,
                candles = page.type == ChartType.Candles,
                crosshair = crosshair,
                description = chartDescription(code, model, page.type, today, locale),
                prevCloseLabel = model.prevClose?.let { stringResource(R.string.stocks_chart_prev_close, StockFormat.number(it)) },
                locale = locale,
            )
        } else {
            val text = when {
                body != null -> stringResource(if (range == ChartRange.Day) R.string.stocks_chart_empty_today else R.string.stocks_chart_empty)
                slot.problem != null -> stringResource(R.string.stocks_chart_failed, stocksErrorText(slot.problem))
                page.reachable -> stringResource(R.string.stocks_loading)
                else -> stringResource(R.string.stocks_not_loaded)
            }
            Box(modifier = Modifier.fillMaxWidth().height(chartHeight())) {
                Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Spacing.l))
            }
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = Spacing.l, end = Spacing.l, top = Spacing.s, bottom = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val rangeLabel = stringResource(R.string.stocks_chart_range)
        Row(modifier = Modifier.weight(1f).selectableGroup().semantics { contentDescription = rangeLabel }, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            ChartRange.entries.forEach { r ->
                FilterChip(
                    selected = r == range,
                    onClick = { detail.selectRange(r) },
                    label = { Text(r.label, style = AristotleTextStyles.tabular(MaterialTheme.typography.labelLarge)) },
                    shape = MaterialTheme.shapes.small,
                    border = FilterChipDefaults.filterChipBorder(enabled = true, selected = r == range, borderColor = Color.Transparent, selectedBorderColor = Color.Transparent),
                    modifier = Modifier.semantics { role = Role.RadioButton },
                )
            }
        }
        TypeToggle(page.type, detail::selectType)
    }
    val problem = slot.problem
    when {
        problem != null -> ProblemLine(if (body != null) stringResource(R.string.stocks_chart_failed, stocksErrorText(problem)) else null, page.reachable, detail::retry)
        body?.stale == true -> ProblemLine(stringResource(R.string.stocks_chart_stale), page.reachable, detail::retry)
    }
}

/** A muted line under the chart (stale bars, a failed read) with Retry now when it can help. */
@Composable
private fun ProblemLine(text: String?, retry: Boolean, onRetry: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.l), verticalAlignment = Alignment.CenterVertically) {
        Text(text.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        if (retry) TextButton(onClick = onRetry, shape = ButtonShape) { Text(stringResource(R.string.conn_retry_now)) }
    }
}

/**
 * Two lines, 44 dp (more at a large font scale) so touching the chart never moves it: the range (span, change in %, high / low and how to get
 * one bar), or the touched bar (time, close, change from the bar before; O / H / L and volume in lots, or only the
 * volume in line mode). Reads [crosshair] here, so only this recomposes as the finger moves from bar to bar.
 */
@Composable
private fun Readout(model: ChartModel?, crosshair: IntState, candles: Boolean, muted: Boolean, today: LocalDate, locale: Locale) {
    val small = AristotleTextStyles.tabular(MaterialTheme.typography.bodySmall)
    val strong = AristotleTextStyles.tabular(MaterialTheme.typography.labelMedium)
    val height = ChartFit.readoutHeightDp(READOUT_HEIGHT.value, LocalDensity.current.fontScale).dp
    Column(modifier = Modifier.fillMaxWidth().height(height).padding(start = Spacing.l, end = Spacing.l, top = Spacing.xs)) {
        model ?: return@Column
        val bars = model.bars
        val reading = ChartReadout.bar(model.range, bars, crosshair.intValue, model.prevClose)
        if (reading != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                Text(
                    stringResource(R.string.stocks_readout_close, ChartLabels.barTime(model.range, reading.bar, today, locale), StockFormat.number(reading.bar.c)),
                    style = strong,
                    maxLines = 1,
                )
                ChangeText(StockChanges.move(reading.change, reading.changePct), muted = muted)
            }
            val b = reading.bar
            Text(
                if (candles) {
                    stringResource(R.string.stocks_readout_ohlv, StockFormat.number(b.o), StockFormat.number(b.h), StockFormat.number(b.l), StockFormat.number(reading.volumeLots))
                } else {
                    stringResource(R.string.stocks_readout_volume, StockFormat.number(reading.volumeLots))
                },
                style = small,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            return@Column
        }
        val summary = ChartReadout.summary(model.range, bars, model.prevClose) ?: return@Column
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Text(spanText(model, today, locale), style = strong, maxLines = 1)
            ChangeText(StockChanges.movePercent(summary.change, summary.changePct), muted = muted)
        }
        Text(
            stringResource(R.string.stocks_readout_high_low, StockFormat.number(summary.high), StockFormat.number(summary.low), hintText(model.body.interval)),
            style = small,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** "Today, 09:00–10:40" / "Wed 4 Nov, 09:00–16:00" / "1M · 6 Oct – 5 Nov". */
@Composable
private fun spanText(model: ChartModel, today: LocalDate, locale: Locale): String {
    val span = ChartLabels.span(model.range, model.bars, today, locale) ?: return model.range.label
    return when {
        model.range != ChartRange.Day -> stringResource(R.string.stocks_span_range, model.range.label, span.from, span.to)
        span.day == null -> stringResource(R.string.stocks_span_today, span.from, span.to)
        else -> stringResource(R.string.stocks_span_day, span.day, span.from, span.to)
    }
}

/** "hold the chart for one 5-minute bar / hour / day", from the server's interval. */
@Composable
private fun hintText(interval: String): String {
    val minutes = ChartLabels.intervalMinutes(interval) ?: MINUTES_PER_DAY
    return when {
        minutes < MINUTES_PER_HOUR -> stringResource(R.string.stocks_readout_hint_minutes, minutes)
        minutes < MINUTES_PER_DAY -> stringResource(R.string.stocks_readout_hint_hour)
        else -> stringResource(R.string.stocks_readout_hint_day)
    }
}

/** What TalkBack says for the chart: range, type, span, last, change, high and low (digits ungrouped for speech). */
@Composable
private fun chartDescription(code: String, model: ChartModel, type: ChartType, today: LocalDate, locale: Locale): String {
    val summary = ChartReadout.summary(model.range, model.bars, model.prevClose) ?: return code
    val span = ChartLabels.span(model.range, model.bars, today, locale)
    return stringResource(
        R.string.stocks_chart_description,
        code,
        model.range.label,
        stringResource(if (type == ChartType.Candles) R.string.stocks_chart_kind_candles else R.string.stocks_chart_kind_line),
        span?.from.orEmpty(),
        span?.to.orEmpty(),
        summary.last.c.toString(),
        changeWords(StockChanges.movePercent(summary.change, summary.changePct)),
        summary.high.toString(),
        summary.low.toString(),
    )
}

/** Candles / line: two 44 × 32 icon cells in one outlined box (board `.ttog`), a radio group. */
@Composable
private fun TypeToggle(type: ChartType, onType: (ChartType) -> Unit) {
    val label = stringResource(R.string.stocks_chart_type)
    val shape = MaterialTheme.shapes.small
    Row(
        modifier = Modifier
            .border(Sizes.divider, MaterialTheme.colorScheme.outline, shape)
            .clip(shape)
            .selectableGroup()
            .semantics { contentDescription = label },
    ) {
        ToggleCell(type == ChartType.Candles, R.drawable.ic_candlestick_chart, stringResource(R.string.stocks_chart_candles)) { onType(ChartType.Candles) }
        VerticalDivider(modifier = Modifier.height(TOGGLE_HEIGHT), color = MaterialTheme.colorScheme.outline)
        ToggleCell(type == ChartType.Line, R.drawable.ic_show_chart, stringResource(R.string.stocks_chart_line)) { onType(ChartType.Line) }
    }
}

@Composable
private fun ToggleCell(selected: Boolean, icon: Int, description: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(TOGGLE_WIDTH, TOGGLE_HEIGHT)
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(TOGGLE_ICON),
        )
    }
}

/**
 * Today's numbers (board "Today" card, two columns): open, previous close, high, low, volume in lots, the 52-week range,
 * the IDX tick and the auto-reject limits ("n/a" without them, e.g. past the server's dated table).
 */
@Composable
private fun KeyNumbers(quote: StockQuote?, open: Long?, chartPrevClose: Long?, muted: Boolean) {
    val na = stringResource(R.string.stocks_not_available)
    val week = if (quote?.week52Low != null && quote.week52High != null) {
        stringResource(R.string.stocks_span_value, StockFormat.number(quote.week52Low), StockFormat.number(quote.week52High))
    } else {
        StockFormat.missing
    }
    val volume = quote?.volume?.let { stringResource(R.string.stocks_lots_value, StockFormat.number(ChartReadout.lots(it))) } ?: StockFormat.missing
    val limits = stringResource(R.string.stocks_pair_value, quote?.ara?.let(StockFormat::number) ?: na, quote?.arb?.let(StockFormat::number) ?: na)
    val cells = listOf(
        stringResource(R.string.stocks_open) to priceText(open),
        stringResource(R.string.stocks_prev_close) to priceText(quote?.prevClose ?: chartPrevClose),
        stringResource(R.string.stocks_high) to priceText(quote?.high),
        stringResource(R.string.stocks_low) to priceText(quote?.low),
        stringResource(R.string.stocks_volume) to volume,
        stringResource(R.string.stocks_week52) to week,
        stringResource(R.string.stocks_tick) to (quote?.tick?.let(StockFormat::number) ?: na),
        stringResource(R.string.stocks_ara_arb) to limits,
    )
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.listGutter)) {
        Column(modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.m), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
            cells.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.l)) {
                    pair.forEach { (label, value) -> KeyValue(label, value, muted, Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun KeyValue(label: String, value: String, muted: Boolean, modifier: Modifier = Modifier) {
    Column(modifier = modifier.semantics(mergeDescendants = true) {}) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = AristotleTextStyles.tabular(MaterialTheme.typography.labelLarge),
            color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}

/** The holding (board "Your holding"): lots · avg and Edit; value and today; P/L after fees with %. */
@Composable
private fun HoldingCard(row: HoldingRow, quote: StockQuote?, fees: StockFees?, offline: Boolean, onEdit: () -> Unit) {
    val view = HoldingBlock.of(row, quote, fees)
    val muted = offline || row.stale || quote?.stale == true
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.listGutter)) {
        Column(modifier = Modifier.padding(start = Spacing.l, end = Spacing.xs, top = Spacing.xs, bottom = Spacing.m)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.stocks_lots_avg, StockFormat.number(view.lots), StockFormat.price(view.avgPrice)),
                    style = AristotleTextStyles.tabular(MaterialTheme.typography.titleSmall),
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onEdit, shape = ButtonShape) { Text(stringResource(R.string.stocks_edit)) }
            }
            Row(modifier = Modifier.padding(end = Spacing.m), horizontalArrangement = Arrangement.spacedBy(Spacing.l)) {
                KeyValue(stringResource(R.string.stocks_value), StockFormat.orDash(view.value, StockFormat::rupiah), muted, Modifier.weight(1f))
                LabelledChange(stringResource(R.string.stocks_today), StockChanges.today(view.today), muted, Modifier.weight(1f))
            }
            LabelledChange(stringResource(R.string.stocks_pl_after_fees), StockChanges.profit(view.pl, view.plPct), muted, Modifier.padding(top = Spacing.m))
        }
    }
}

/** Not held: one grouped row, "You don't hold X" and Add holding (board "detail-line"). */
@Composable
private fun NotHeldRow(code: String, enabled: Boolean, onAdd: () -> Unit) {
    GroupedRows(1, modifier = Modifier.padding(horizontal = Spacing.listGutter)) {
        Row(modifier = Modifier.fillMaxWidth().heightIn(min = Sizes.listRowHeight).padding(start = Spacing.l, end = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.stocks_not_held, code), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            TextButton(onClick = onAdd, enabled = enabled, shape = ButtonShape) {
                Icon(painter = painterResource(R.drawable.ic_add), contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(Spacing.s))
                Text(stringResource(R.string.stocks_add_holding))
            }
        }
    }
}

/** "News about X" and All, then up to five rows (source · time, headline); hidden when nothing mentions it. */
@Composable
private fun RelatedNews(code: String, news: List<NewsItem>?, problem: StocksError?, locale: Locale, onAll: () -> Unit) {
    if (news == null) {
        problem?.let {
            SectionHeader(stringResource(R.string.stocks_news_about, code))
            QuietLine(stringResource(R.string.stocks_news_failed, stocksErrorText(it)))
        }
        return
    }
    if (news.isEmpty()) return
    Row(modifier = Modifier.fillMaxWidth().padding(end = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.weight(1f)) { SectionHeader(stringResource(R.string.stocks_news_about, code)) }
        TextButton(onClick = onAll, shape = ButtonShape) {
            Text(stringResource(R.string.stocks_all))
            Icon(painter = painterResource(R.drawable.ic_chevron_right), contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
        }
    }
    val openLink = rememberLinkOpener()
    news.take(StockDetailViewModel.NEWS_COUNT).forEachIndexed { i, item ->
        if (i > 0) HorizontalDivider(modifier = Modifier.padding(horizontal = Spacing.listGutter), color = MaterialTheme.colorScheme.outlineVariant)
        Column(modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { openLink(item.link) }.padding(horizontal = Spacing.listGutter, vertical = NEWS_ROW_PADDING)) {
            Text(newsMeta(item, locale), style = AristotleTextStyles.tabular(MaterialTheme.typography.bodySmall), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Text(item.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = Spacing.xxs))
        }
    }
}

private val HEADER_ARROW = 22.dp
private val READOUT_HEIGHT = 44.dp
private val TOGGLE_WIDTH = 44.dp
private val TOGGLE_HEIGHT = 32.dp
private val TOGGLE_ICON = 20.dp
private val NEWS_ROW_PADDING = 10.dp
private const val MINUTES_PER_HOUR = 60
private const val MINUTES_PER_DAY = 1_440
