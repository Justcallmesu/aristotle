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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
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
import io.github.justcallmesu.aristotle.data.stocks.HoldingRow
import io.github.justcallmesu.aristotle.data.stocks.HoldingsBody
import io.github.justcallmesu.aristotle.data.stocks.NewsFilter
import io.github.justcallmesu.aristotle.data.stocks.NewsItem
import io.github.justcallmesu.aristotle.data.stocks.NewsLists
import io.github.justcallmesu.aristotle.data.stocks.SetupLabel
import io.github.justcallmesu.aristotle.data.stocks.StockChanges
import io.github.justcallmesu.aristotle.data.stocks.StockFees
import io.github.justcallmesu.aristotle.data.stocks.StockFormat
import io.github.justcallmesu.aristotle.data.stocks.StockTimes
import io.github.justcallmesu.aristotle.ui.components.GroupedRows
import io.github.justcallmesu.aristotle.ui.sessions.QuietLine
import io.github.justcallmesu.aristotle.ui.sessions.SectionHeader
import io.github.justcallmesu.aristotle.ui.theme.AristotleTextStyles
import io.github.justcallmesu.aristotle.ui.theme.Sizes
import io.github.justcallmesu.aristotle.ui.theme.Spacing
import java.util.Locale

/** A stocks page's top bar: back, a Literata title and a muted subline (board `.topbar`); [actions] at the end (TSC3). */
@Composable
internal fun StocksTopBar(
    title: String,
    subline: String?,
    onBack: () -> Unit,
    titleStyle: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.titleLarge,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
) {
    Row(modifier = Modifier.fillMaxWidth().heightIn(min = Sizes.topBarHeight).padding(horizontal = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(painter = painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.nav_back), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(modifier = Modifier.padding(start = Spacing.xs, end = Spacing.l).weight(1f)) {
            Text(title, style = titleStyle, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
            subline?.let {
                Text(it, style = AristotleTextStyles.tabular(MaterialTheme.typography.bodySmall), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        actions()
    }
}

/**
 * Holdings (board "holdings"): value, P/L after fees, today and the fee rule in one line; rows by value (code, "20 lots ·
 * avg 9.150", value, P/L after fees with %); Add holding. A row opens the edit sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HoldingsScreen(viewModel: StocksViewModel, onBack: () -> Unit, screener: ScreenerViewModel? = null) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // Loads on every show and again when the server comes back while shown (not only once).
    LifecycleResumeEffect(viewModel, screener) {
        viewModel.holdingsVisible(true)
        screener?.holdingsVisible(true)
        onPauseOrDispose {
            viewModel.holdingsVisible(false)
            screener?.holdingsVisible(false)
        }
    }
    val screen = screener?.uiState?.collectAsStateWithLifecycle()?.value
    val snackbar = remember { SnackbarHostState() }
    StocksMessages(state.message, snackbar, viewModel::messageShown)
    val holdings = state.holdings
    val locale = currentLocale()
    val muted = !state.reachable
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Box(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Column(modifier = Modifier.fillMaxSize()) {
                val count = holdings?.holdings?.size ?: 0
                val asOf = state.quotes?.let { StockTimes.dayClock(it.asOf, StockTimes.today(System.currentTimeMillis()), locale) }
                    ?: holdings?.let { StockTimes.clock(it.at) }
                StocksTopBar(
                    title = stringResource(R.string.stocks_holdings),
                    subline = holdings?.let { asOf?.let { t -> stringResource(R.string.stocks_holdings_subline, pluralStringResource(R.plurals.stocks_count, count, count), t) } },
                    onBack = onBack,
                )
                PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = viewModel::refresh, modifier = Modifier.weight(1f)) {
                    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = FAB_CLEARANCE)) {
                        when {
                            holdings == null -> item { QuietLine(stringResource(if (state.reachable) R.string.stocks_loading else R.string.stocks_not_loaded)) }
                            holdings.holdings.isEmpty() -> item { QuietLine(stringResource(R.string.stocks_no_holdings_body)) }
                            else -> {
                                item(key = "summary") { HoldingsSummary(holdings, state.fees, muted) }
                                item(key = "by-value") { SectionHeader(stringResource(R.string.stocks_by_value)) }
                                item(key = "rows") {
                                    val rows = holdings.byValue
                                    GroupedRows(rows.size, modifier = Modifier.padding(horizontal = Spacing.listGutter)) { index ->
                                        HoldingRowView(rows[index], muted, screen?.labelsFor(rows[index].code).orEmpty(), muted || screen?.labelsMuted == true, locale, onOpen = viewModel::editHolding)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (state.reachable) {
                ExtendedFloatingActionButton(
                    onClick = viewModel::addHolding,
                    shape = MaterialTheme.shapes.large,
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    icon = { Icon(painter = painterResource(R.drawable.ic_add), contentDescription = null) },
                    text = { Text(stringResource(R.string.stocks_add_holding)) },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(Spacing.l),
                )
            }
            SnackbarHost(hostState = snackbar, modifier = Modifier.align(Alignment.BottomCenter))
        }
    }
    StocksSheets(state, viewModel)
}

@Composable
private fun HoldingsSummary(holdings: HoldingsBody, fees: StockFees?, offline: Boolean) {
    val total = holdings.total
    val muted = offline || holdings.holdings.any { it.stale }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.listGutter, vertical = Spacing.s)) {
        Text(stringResource(R.string.stocks_value), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            StockFormat.orDash(total.value, StockFormat::rupiah),
            style = AristotleTextStyles.stockValueLarge,
            color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
        Row(modifier = Modifier.padding(top = Spacing.s), horizontalArrangement = Arrangement.spacedBy(Spacing.xxl)) {
            LabelledChange(stringResource(R.string.stocks_pl_after_fees), StockChanges.profit(total.pl, total.plPct), muted)
            LabelledChange(stringResource(R.string.stocks_today), StockChanges.today(total.today), muted)
        }
        val f = fees ?: StockFees()
        Text(
            text = stringResource(R.string.stocks_fee_rule, StockFormat.rupiah(total.cost), StockFormat.fee(f.buyPct), StockFormat.fee(f.sellPct)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.m),
        )
    }
}

/** A holding row; TSC3: a setup label line when one fired on it (Q106: bearish flags in loss colours). */
@Composable
private fun HoldingRowView(row: HoldingRow, offline: Boolean, labels: List<SetupLabel>, labelsMuted: Boolean, locale: Locale, onOpen: (String) -> Unit) {
    val muted = offline || row.stale
    val change = StockChanges.profit(row.pl, row.plPct)
    val value = StockFormat.orDash(row.value, StockFormat::rupiah)
    val spoken = if (labels.isEmpty()) "" else ". " + labelsSpoken(labels, locale)
    val description = stringResource(R.string.stocks_holding_description, row.code, StockFormat.number(row.lots), value, changeWords(change)) + spoken
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button) { onOpen(row.code) }
            .semantics(mergeDescendants = true) { contentDescription = description }
            .heightIn(min = HOLDING_ROW_HEIGHT)
            .padding(horizontal = Spacing.l, vertical = Spacing.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(row.code, style = AristotleTextStyles.stockCode)
            Text(
                stringResource(R.string.stocks_lots_avg, StockFormat.number(row.lots), StockFormat.price(row.avgPrice)),
                style = AristotleTextStyles.tabular(MaterialTheme.typography.bodySmall),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (labels.isNotEmpty()) LabelLine(labels, max = 1, muted = labelsMuted, locale = locale, modifier = Modifier.padding(top = Spacing.xs))
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(value, style = AristotleTextStyles.stockPrice, color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
            ChangeText(change, muted = muted)
        }
    }
}

/**
 * News (board "news"): the sources and when they were checked; All / Watchlist / Holdings; day sections; each item the
 * source and time, the headline as the feed wrote it (not translated) and its ticker chips. An item opens the article in
 * the browser, a chip the stock.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NewsScreen(viewModel: StocksViewModel, onBack: () -> Unit, onOpenStock: (String) -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel) {
        viewModel.newsVisible(true)
        onPauseOrDispose { viewModel.newsVisible(false) }
    }
    val locale = currentLocale()
    val news = state.news
    val openLink = rememberLinkOpener()
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            StocksTopBar(stringResource(R.string.stocks_news), news?.let { sourcesLine(it.sources.map { s -> s.source }, NewsLists.checkedAt(it.sources)) }, onBack)
            FlowRow(modifier = Modifier.padding(horizontal = Spacing.listGutter).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                NewsFilter.entries.forEach { filter ->
                    FilterChip(
                        selected = state.newsFilter == filter,
                        onClick = { viewModel.setNewsFilter(filter) },
                        label = { Text(stringResource(filterLabel(filter))) },
                        leadingIcon = if (state.newsFilter == filter) {
                            { Icon(painter = painterResource(R.drawable.ic_check), contentDescription = null, modifier = Modifier.size(Sizes.chipIcon)) }
                        } else {
                            null
                        },
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.semantics { role = Role.RadioButton },
                    )
                }
            }
            news?.let { body ->
                val failing = NewsLists.failing(body.sources)
                if (failing.isNotEmpty()) {
                    Text(
                        stringResource(R.string.stocks_news_source_failed, failing.joinToString(", ") { sourceName(it) }),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = Spacing.listGutter, vertical = Spacing.xs),
                    )
                }
            }
            PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = viewModel::refresh, modifier = Modifier.weight(1f)) {
                val items = news?.let { NewsLists.filter(it.items, state.newsFilter, state.watchCodes, state.holdingCodes) }
                LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = Spacing.l)) {
                    when {
                        news == null -> item {
                            QuietLine(state.newsProblem?.let { stringResource(R.string.stocks_news_failed, stocksErrorText(it)) } ?: stringResource(R.string.stocks_loading))
                        }
                        items.isNullOrEmpty() -> item { QuietLine(stringResource(if (state.newsFilter == NewsFilter.All) R.string.stocks_news_empty else R.string.stocks_news_none_matched)) }
                        else -> NewsLists.byDay(items).forEach { day ->
                            item(key = "day-${day.date}") { SectionHeader(dayLabel(day.date, locale)) }
                            items(day.items, key = { "item-${it.id}-${it.link}" }) { item ->
                                NewsRow(item, state, locale, first = item == day.items.first(), onOpen = { openLink(item.link) }, onStock = onOpenStock)
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NewsRow(item: NewsItem, state: StocksUiState, locale: Locale, first: Boolean, onOpen: () -> Unit, onStock: (String) -> Unit) {
    if (!first) HorizontalDivider(modifier = Modifier.padding(horizontal = Spacing.listGutter), color = MaterialTheme.colorScheme.outlineVariant)
    Column(modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onOpen).padding(horizontal = Spacing.listGutter, vertical = Spacing.m)) {
        Text(newsMeta(item, locale), style = AristotleTextStyles.tabular(MaterialTheme.typography.bodySmall), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(item.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = Spacing.xxs, bottom = Spacing.s))
        if (item.codes.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                item.codes.forEach { code -> TickerChip(code, state.quoteOf(code), !state.reachable || state.quoteOf(code)?.stale == true, onStock) }
            }
        }
    }
}

@Composable
private fun sourcesLine(sources: List<String>, checkedAt: String?): String {
    val names = sources.joinToString(", ") { sourceName(it) }
    val time = StockTimes.clock(checkedAt) ?: return names
    return stringResource(R.string.stocks_news_checked, names, time)
}

@Composable
private fun dayLabel(date: java.time.LocalDate?, locale: Locale): String {
    date ?: return stringResource(R.string.stocks_news_undated)
    val today = StockTimes.today(System.currentTimeMillis())
    val label = StockTimes.day(date, locale)
    return when (date) {
        today -> stringResource(R.string.stocks_news_today, label)
        today.minusDays(1) -> stringResource(R.string.stocks_news_yesterday, label)
        else -> label
    }
}

private fun filterLabel(filter: NewsFilter): Int = when (filter) {
    NewsFilter.All -> R.string.stocks_filter_all
    NewsFilter.Watchlist -> R.string.stocks_filter_watchlist
    NewsFilter.Holdings -> R.string.stocks_filter_holdings
}

private val HOLDING_ROW_HEIGHT = 64.dp
private val FAB_CLEARANCE = 96.dp
