package io.github.justcallmesu.aristotle.ui.stocks

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.justcallmesu.aristotle.R
import io.github.justcallmesu.aristotle.data.LinkState
import io.github.justcallmesu.aristotle.data.serverEndpoint
import io.github.justcallmesu.aristotle.data.stocks.HoldingsBody
import io.github.justcallmesu.aristotle.data.stocks.MarketLine
import io.github.justcallmesu.aristotle.data.stocks.MarketStatus
import io.github.justcallmesu.aristotle.data.stocks.NewsItem
import io.github.justcallmesu.aristotle.data.stocks.QuotesBody
import io.github.justcallmesu.aristotle.data.stocks.SetupLabel
import io.github.justcallmesu.aristotle.data.stocks.StockChanges
import io.github.justcallmesu.aristotle.data.stocks.StockFormat
import io.github.justcallmesu.aristotle.data.stocks.StockTimes
import io.github.justcallmesu.aristotle.data.stocks.StocksError
import io.github.justcallmesu.aristotle.data.stocks.WatchEntry
import io.github.justcallmesu.aristotle.ui.components.GroupedRows
import io.github.justcallmesu.aristotle.ui.sessions.QuietLine
import io.github.justcallmesu.aristotle.ui.sessions.SectionHeader
import io.github.justcallmesu.aristotle.ui.theme.AristotleTextStyles
import io.github.justcallmesu.aristotle.ui.theme.ButtonShape
import io.github.justcallmesu.aristotle.ui.theme.Sizes
import io.github.justcallmesu.aristotle.ui.theme.Spacing
import java.util.Locale

/** Where the Stocks view sends the user: a stock's page, Holdings, News, Settings › Stocks, Settings, the Screener. */
class StocksNav(
    val openStock: (String) -> Unit,
    val openHoldings: () -> Unit,
    val openNews: () -> Unit,
    val openFees: () -> Unit,
    val openSettings: () -> Unit,
    val openScreener: () -> Unit = {},
)

/**
 * Home's Stocks view under the head (stocks-notes §3, board "Home · Stocks"): the market line, the watchlist, the
 * Screener section (TSC3, under the watchlist), the holdings card and the news strip, then the bottom bar (News,
 * Holdings, ⋮, + adds to the watchlist). Prices and the screener are read while this is on screen and resumed
 * ([StocksViewModel.homeVisible], [ScreenerViewModel.homeVisible]).
 */
@Composable
fun ColumnScope.StocksHomeBody(viewModel: StocksViewModel, nav: StocksNav, timeText: (Long) -> String, screener: ScreenerViewModel? = null) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel, screener) {
        viewModel.homeVisible(true)
        screener?.homeVisible(true)
        onPauseOrDispose {
            viewModel.homeVisible(false)
            screener?.homeVisible(false)
        }
    }
    val screen = screener?.uiState?.collectAsStateWithLifecycle()?.value
    val snackbar = remember { SnackbarHostState() }
    StocksMessages(state.message, snackbar, viewModel::messageShown)
    val locale = currentLocale()
    MarketStatusLine(state.link, state.quotes, state.problem, timeText, viewModel::retry, locale)
    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
        StocksHomeList(state, screen, viewModel, nav, locale)
        SnackbarHost(hostState = snackbar, modifier = Modifier.align(Alignment.BottomCenter))
    }
    StocksBottomBar(online = state.reachable, nav = nav, onAdd = viewModel::openWatchSearch)
    StocksSheets(state, viewModel)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StocksHomeList(state: StocksUiState, screen: ScreenerUiState?, viewModel: StocksViewModel, nav: StocksNav, locale: Locale) {
    val muted = !state.reachable
    PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = viewModel::refresh, modifier = Modifier.fillMaxSize()) {
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = Spacing.l)) {
            val watch = state.watchlist
            item(key = "watch-header") {
                SectionHeader(if (watch.isNullOrEmpty()) stringResource(R.string.stocks_watchlist) else stringResource(R.string.stocks_watchlist_count, watch.size))
            }
            item(key = "watch") {
                when {
                    watch == null -> QuietLine(stringResource(if (state.reachable) R.string.stocks_loading else R.string.stocks_not_loaded))
                    watch.isEmpty() -> EmptyWatchlist(enabled = state.reachable, onAdd = viewModel::openWatchSearch)
                    else -> GroupedRows(watch.size, modifier = Modifier.padding(horizontal = Spacing.listGutter)) { index ->
                        val entry = watch[index]
                        WatchRow(entry, state.quotes, muted, screen?.labelsFor(entry.code).orEmpty(), muted || screen?.labelsMuted == true, locale, onOpen = nav.openStock, onActions = viewModel::openWatchActions)
                    }
                }
            }
            if (screen != null) {
                item(key = "screener") { ScreenerHomeSection(screen.homeState, locale, onOpenPage = nav.openScreener, onOpenStock = nav.openStock) }
            }
            item(key = "hold-header") { SectionHeader(stringResource(R.string.stocks_holdings)) }
            item(key = "hold") {
                val holdings = state.holdings
                when {
                    holdings == null -> if (watch != null) QuietLine(stringResource(R.string.stocks_loading))
                    holdings.holdings.isEmpty() -> NoHoldingsRow(enabled = state.reachable, onAdd = viewModel::addHolding)
                    else -> HoldingsCard(holdings, muted, onOpen = nav.openHoldings)
                }
            }
            item(key = "news-header") { NewsHeader(nav.openNews) }
            item(key = "news") { NewsStrip(state, muted, nav, locale) }
        }
    }
}

/**
 * The market line (stocks-notes §3 `MarketStatusLine`): the session state, then "Prices ~10 min delayed · as of 10:42".
 * Offline it becomes the offline line (cloud-off, "Offline since", Retry now) and the time the prices are from.
 */
@Composable
private fun MarketStatusLine(link: LinkState, quotes: QuotesBody?, problem: StocksError?, timeText: (Long) -> String, onRetry: () -> Unit, locale: Locale) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val small = AristotleTextStyles.tabular(MaterialTheme.typography.bodySmall)
    val today = StockTimes.today(System.currentTimeMillis())
    Column(modifier = Modifier.fillMaxWidth().padding(start = Spacing.l, end = Spacing.l, bottom = Spacing.s).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
        if (link is LinkState.Offline) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Icon(painter = painterResource(R.drawable.ic_cloud_off), contentDescription = null, tint = muted, modifier = Modifier.size(LINE_ICON))
                Text(
                    text = stringResource(R.string.sessions_offline_since, timeText(link.sinceMillis)),
                    style = AristotleTextStyles.tabular(MaterialTheme.typography.titleSmall),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                TextButton(onClick = onRetry, shape = ButtonShape, contentPadding = RETRY_PADDING) { Text(stringResource(R.string.conn_retry_now)) }
            }
            quotes?.let { Text(stringResource(R.string.stocks_prices_as_of, asOfText(it, today, locale)), style = small, color = muted) }
            return@Column
        }
        Text(text = marketText(quotes?.market, today, locale, link), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        quotes?.let {
            val line = if (it.market.state == MarketStatus.CLOSED) R.string.stocks_closing_prices else R.string.stocks_prices_delayed
            Text(stringResource(line, asOfText(it, today, locale)), style = small, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        problem?.let { Text(stocksErrorText(it), style = small, color = muted) }
    }
}

/**
 * "Session 1 · closes 12:00", "Session break · session 2 opens 13:30", "Market closed · opens Fri 09:00". Before the
 * first read it says the hours are being read whenever the server answers (Hermes down or updated too: stocks need no
 * Hermes), and that prices need the PC only when nothing answers.
 */
@Composable
private fun marketText(market: MarketStatus?, today: java.time.LocalDate, locale: Locale, link: LinkState): String =
    when (val line = StockTimes.marketLine(market, today, locale)) {
        is MarketLine.Open -> if (line.session != null && line.closesAt != null) stringResource(R.string.stocks_market_open, line.session, line.closesAt) else stringResource(R.string.stocks_market_open_plain)
        is MarketLine.Break -> line.opensAt?.let { stringResource(R.string.stocks_market_break, it) } ?: stringResource(R.string.stocks_market_break_plain)
        is MarketLine.Closed -> line.opensAt?.let { stringResource(R.string.stocks_market_closed, it) } ?: stringResource(R.string.stocks_market_closed_plain)
        MarketLine.Unknown -> stringResource(if (link.serverEndpoint != null) R.string.stocks_market_loading else R.string.stocks_market_waiting)
    }

private fun asOfText(quotes: QuotesBody, today: java.time.LocalDate, locale: Locale): String =
    StockTimes.dayClock(quotes.asOf, today, locale) ?: StockFormat.missing

/**
 * Two-line watchlist row (Q93, 64 dp): code + name, sparkline, last price + change. Tap opens; long press moves or removes.
 * TSC3: when a setup fired on it, a label line under the name (the best label and "+N"; Q106 bearish in loss colours), and
 * the row grows past 64 dp.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WatchRow(entry: WatchEntry, quotes: QuotesBody?, offline: Boolean, labels: List<SetupLabel>, labelsMuted: Boolean, locale: Locale, onOpen: (String) -> Unit, onActions: (String) -> Unit) {
    val quote = quotes?.quoteOf(entry.code)
    val muted = offline || quote?.stale == true
    val change = quote?.let(StockChanges::quote) ?: StockChanges.quotePercent(null)
    val actionsLabel = stringResource(R.string.stocks_watch_actions)
    val name = entry.name.ifEmpty { quote?.name.orEmpty() }
    // Plain digits for speech: a grouped "9.875" is read as a decimal by an English voice.
    val spoken = if (labels.isEmpty()) "" else ". " + labelsSpoken(labels, locale)
    val description = stringResource(R.string.stocks_row_description, entry.code, name, quote?.price?.toString() ?: StockFormat.missing, changeWords(change)) + spoken
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = { onOpen(entry.code) }, onLongClick = { onActions(entry.code) }, role = Role.Button)
            .semantics(mergeDescendants = true) {
                contentDescription = description
                onLongClick(label = actionsLabel) { onActions(entry.code); true }
            }
            .heightIn(min = WATCH_ROW_HEIGHT)
            .padding(horizontal = Spacing.l, vertical = WATCH_ROW_PADDING),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = entry.code, style = AristotleTextStyles.stockCode, maxLines = 1)
            Text(text = name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (labels.isNotEmpty()) LabelLine(labels, max = 1, muted = labelsMuted, locale = locale, modifier = Modifier.padding(top = Spacing.xs))
        }
        quote?.let { Sparkline(it.spark, it.prevClose, change.direction, muted) }
        Column(modifier = Modifier.width(PRICE_COLUMN), horizontalAlignment = Alignment.End) {
            Text(
                text = priceText(quote?.price),
                style = AristotleTextStyles.stockPrice,
                color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            ChangeText(change, muted = muted)
        }
    }
}

@Composable
private fun EmptyWatchlist(enabled: Boolean, onAdd: () -> Unit) {
    Column(modifier = Modifier.padding(horizontal = Spacing.listGutter, vertical = Spacing.s)) {
        Text(text = stringResource(R.string.stocks_watchlist_empty), style = MaterialTheme.typography.titleMedium)
        Text(
            text = stringResource(R.string.stocks_watchlist_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.xs, bottom = Spacing.m),
        )
        FilledTonalButton(onClick = onAdd, enabled = enabled, shape = ButtonShape) {
            Icon(painter = painterResource(R.drawable.ic_add), contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.width(Spacing.s))
            Text(stringResource(R.string.stocks_add_stock))
        }
    }
}

/** No holdings: one grouped row with + (board "Empty"). */
@Composable
private fun NoHoldingsRow(enabled: Boolean, onAdd: () -> Unit) {
    GroupedRows(1, modifier = Modifier.padding(horizontal = Spacing.listGutter)) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button, onClick = onAdd).heightIn(min = Sizes.listRowHeight).padding(horizontal = Spacing.l, vertical = Spacing.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.stocks_no_holdings), style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(R.string.stocks_no_holdings_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(painter = painterResource(R.drawable.ic_add), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The holdings card (`large`, `surfaceContainerLow`): "N stocks · value", the value, P/L after fees and today. Opens Holdings. */
@Composable
private fun HoldingsCard(holdings: HoldingsBody, offline: Boolean, onOpen: () -> Unit) {
    val total = holdings.total
    val muted = offline || holdings.holdings.any { it.stale }
    val count = holdings.holdings.size
    val value = StockFormat.orDash(total.value, StockFormat::rupiah)
    val description = stringResource(R.string.stocks_holdings_card_description, pluralStringResource(R.plurals.stocks_count, count, count), value)
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.listGutter),
    ) {
        Column(
            modifier = Modifier
                .clickable(role = Role.Button, onClick = onOpen)
                .semantics { contentDescription = description }
                .padding(horizontal = Spacing.l, vertical = Spacing.m),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.stocks_holdings_value_label, pluralStringResource(R.plurals.stocks_count, count, count)),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Icon(painter = painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(CHEVRON))
            }
            Text(text = value, style = AristotleTextStyles.stockValue, color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
            Row(modifier = Modifier.padding(top = Spacing.s), horizontalArrangement = Arrangement.spacedBy(Spacing.xxl)) {
                LabelledChange(stringResource(R.string.stocks_pl_after_fees), StockChanges.profit(total.pl, total.plPct), muted)
                LabelledChange(stringResource(R.string.stocks_today), StockChanges.today(total.today), muted)
            }
        }
    }
}

@Composable
internal fun LabelledChange(label: String, change: io.github.justcallmesu.aristotle.data.stocks.ChangeDisplay, muted: Boolean, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ChangeText(change, muted = muted, style = MaterialTheme.typography.labelLarge.let(AristotleTextStyles::tabular), arrow = 20.dp)
    }
}

@Composable
private fun NewsHeader(onAll: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(end = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.weight(1f)) { SectionHeader(stringResource(R.string.stocks_news)) }
        TextButton(onClick = onAll, shape = ButtonShape) {
            Text(stringResource(R.string.stocks_all_news))
            Icon(painter = painterResource(R.drawable.ic_chevron_right), contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
        }
    }
}

/** The news strip: 272 dp cards snapping to the start, like the live cards (stocks-notes §3). */
@Composable
private fun NewsStrip(state: StocksUiState, muted: Boolean, nav: StocksNav, locale: Locale) {
    val news = state.news
    when {
        news == null -> state.newsProblem?.let { QuietLine(stringResource(R.string.stocks_news_failed, stocksErrorText(it))) }
            ?: if (state.reachable) QuietLine(stringResource(R.string.stocks_loading)) else Unit
        news.items.isEmpty() -> QuietLine(stringResource(R.string.stocks_news_empty))
        else -> {
            val cards = rememberLazyListState()
            val openLink = rememberLinkOpener()
            LazyRow(
                state = cards,
                flingBehavior = rememberSnapFlingBehavior(cards, SnapPosition.Start),
                contentPadding = PaddingValues(start = Spacing.listGutter, end = Spacing.listGutter, top = Spacing.xs, bottom = Spacing.s),
                horizontalArrangement = Arrangement.spacedBy(Spacing.m),
            ) {
                items(news.items.take(NEWS_STRIP_SIZE), key = { "news-${it.id}-${it.link}" }) { item ->
                    NewsCard(item, state, muted, locale, onOpen = { openLink(item.link) }, onStock = nav.openStock)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NewsCard(item: NewsItem, state: StocksUiState, muted: Boolean, locale: Locale, onOpen: () -> Unit, onStock: (String) -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.width(NEWS_CARD_WIDTH).heightIn(min = NEWS_CARD_MIN_HEIGHT),
    ) {
        Column(
            modifier = Modifier.clickable(role = Role.Button, onClick = onOpen).padding(horizontal = Spacing.l, vertical = Spacing.m),
            verticalArrangement = Arrangement.spacedBy(NEWS_GAP),
        ) {
            Text(text = newsMeta(item, locale), style = AristotleTextStyles.tabular(MaterialTheme.typography.bodySmall), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Text(text = item.title, style = AristotleTextStyles.newsHeadline, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (item.codes.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    item.codes.take(MAX_CHIPS).forEach { code -> TickerChip(code, state.quoteOf(code), muted || state.quoteOf(code)?.stale == true, onStock) }
                }
            }
        }
    }
}

/** "CNBC Indonesia · 10:31", or with the day when it is not today ("Kontan · Wed 16:40"). */
@Composable
internal fun newsMeta(item: NewsItem, locale: Locale): String {
    val today = StockTimes.today(System.currentTimeMillis())
    val time = StockTimes.dayClock(item.publishedAt, today, locale).orEmpty()
    return listOf(sourceName(item.source), time).filter { it.isNotEmpty() }.joinToString(" · ")
}

/** The feed's own name: CNBC Indonesia, Kontan, IDX Channel (not translated). */
fun sourceName(source: String): String = when (source) {
    "cnbc" -> "CNBC Indonesia"
    "kontan" -> "Kontan"
    "idxchannel" -> "IDX Channel"
    else -> source
}

/** The Stocks bottom bar (stocks-notes §3): News, Holdings, ⋮ (Broker fees, Settings), and + to add to the watchlist. */
@Composable
private fun StocksBottomBar(online: Boolean, nav: StocksNav, onAdd: () -> Unit) {
    val icons = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
    var menu by remember { mutableStateOf(false) }
    BottomAppBar(
        actions = {
            IconButton(onClick = nav.openNews, colors = icons) {
                Icon(painter = painterResource(R.drawable.ic_newspaper), contentDescription = stringResource(R.string.stocks_news))
            }
            IconButton(onClick = nav.openHoldings, colors = icons) {
                Icon(painter = painterResource(R.drawable.ic_account_balance_wallet), contentDescription = stringResource(R.string.stocks_holdings))
            }
            Box {
                IconButton(onClick = { menu = true }, colors = icons) {
                    Icon(painter = painterResource(R.drawable.ic_more_vert), contentDescription = stringResource(R.string.sessions_more))
                }
                DropdownMenu(
                    expanded = menu,
                    onDismissRequest = { menu = false },
                    shape = MaterialTheme.shapes.medium,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(Sizes.divider, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.stocks_broker_fees)) },
                        leadingIcon = { Icon(painter = painterResource(R.drawable.ic_account_balance_wallet), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                        onClick = { menu = false; nav.openFees() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.settings_title)) },
                        leadingIcon = { Icon(painter = painterResource(R.drawable.ic_settings), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                        onClick = { menu = false; nav.openSettings() },
                    )
                }
            }
        },
        floatingActionButton = if (online) {
            {
                FloatingActionButton(
                    onClick = onAdd,
                    shape = MaterialTheme.shapes.large,
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    elevation = FloatingActionButtonDefaults.bottomAppBarFabElevation(),
                ) {
                    Icon(painter = painterResource(R.drawable.ic_add), contentDescription = stringResource(R.string.stocks_add_to_watchlist))
                }
            }
        } else {
            null
        },
        windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom),
    )
}

/** Snackbars for failed actions. */
@Composable
internal fun StocksMessages(message: StocksMessage?, snackbar: SnackbarHostState, onShown: () -> Unit) {
    val text = message?.let { stocksMessageText(it) }
    LaunchedEffect(message) {
        if (text != null) {
            snackbar.showSnackbar(text)
            onShown()
        }
    }
}

/** The app's locale for day names (the configuration's, so a language switch follows). */
@Composable
internal fun currentLocale(): Locale = LocalConfiguration.current.locales[0] ?: Locale.ENGLISH

private val LINE_ICON = 18.dp
private val CHEVRON = 20.dp
private val WATCH_ROW_HEIGHT = 64.dp
private val WATCH_ROW_PADDING = 10.dp
private val PRICE_COLUMN = 112.dp
private val NEWS_CARD_WIDTH = 272.dp
private val NEWS_CARD_MIN_HEIGHT = 140.dp
private val NEWS_GAP = 6.dp
private const val NEWS_STRIP_SIZE = 8
private const val MAX_CHIPS = 3
private val RETRY_PADDING = PaddingValues(horizontal = Spacing.s, vertical = ButtonDefaults.TextButtonContentPadding.calculateTopPadding())
