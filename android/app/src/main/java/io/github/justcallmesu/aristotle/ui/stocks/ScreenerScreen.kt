package io.github.justcallmesu.aristotle.ui.stocks

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.justcallmesu.aristotle.R
import io.github.justcallmesu.aristotle.data.LinkState
import io.github.justcallmesu.aristotle.data.stocks.ScanCooldown
import io.github.justcallmesu.aristotle.data.stocks.ScreenerKind
import io.github.justcallmesu.aristotle.data.stocks.ScreenerMode
import io.github.justcallmesu.aristotle.data.stocks.ScreenerSettings
import io.github.justcallmesu.aristotle.data.stocks.ScreenerState
import io.github.justcallmesu.aristotle.data.stocks.ScreenerStates
import io.github.justcallmesu.aristotle.data.stocks.SetupFilter
import io.github.justcallmesu.aristotle.data.stocks.SetupLabels
import io.github.justcallmesu.aristotle.data.stocks.StockTimes
import io.github.justcallmesu.aristotle.data.stocks.StocksError
import io.github.justcallmesu.aristotle.ui.theme.AristotleTextStyles
import io.github.justcallmesu.aristotle.ui.theme.ButtonShape
import io.github.justcallmesu.aristotle.ui.theme.Sizes
import io.github.justcallmesu.aristotle.ui.theme.Spacing
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * The Screener page (screener-notes §2, `PairedPage.StocksScreener`): top bar (subline "80 stocks · scanned 10:45 · next
 * 11:00", gear, ⋮ Scan now with the server's 2 min cooldown), Today | Last close, the setup filter, the candidates, "Your
 * stocks · bearish" (Q106), and a footer with the data time and what was skipped. Pull to refresh only reads.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenerScreen(viewModel: ScreenerViewModel, onBack: () -> Unit, onOpenStock: (String) -> Unit, onOpenSettings: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel) {
        viewModel.pageVisible(true)
        onPauseOrDispose { viewModel.pageVisible(false) }
    }
    val snackbar = remember { SnackbarHostState() }
    ScreenerMessages(state.message, snackbar, viewModel::messageShown)
    val locale = currentLocale()
    val screen = state.pageState
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Box(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Column(modifier = Modifier.fillMaxSize()) {
                StocksTopBar(
                    title = stringResource(R.string.screener_title),
                    subline = pageSubline(screen, locale),
                    onBack = onBack,
                    titleStyle = AristotleTextStyles.chatTitle,
                ) {
                    val icons = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
                    IconButton(onClick = onOpenSettings, colors = icons) {
                        Icon(painter = painterResource(R.drawable.ic_settings), contentDescription = stringResource(R.string.screener_settings))
                    }
                    ScanMenu(state, viewModel::scanNow, icons)
                }
                if (screen.failedCodes > 0) Note(pluralStringResource(R.plurals.screener_partial, screen.failedCodes, screen.failedCodes))
                if (screen.offline) {
                    OfflineLine(state.link, screen, viewModel::retry)
                } else if (screen.stale) {
                    StaleNotice(whyLine(screen).orEmpty(), state, onScan = viewModel::scanNow)
                }
                if (screen.kind != ScreenerKind.NoBars) {
                    ModeChoice(state.shownMode, viewModel::selectMode)
                    FilterRow(state.filter, viewModel::setFilter)
                }
                PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = viewModel::refresh, modifier = Modifier.weight(1f)) {
                    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(top = Spacing.xs, bottom = Spacing.l)) {
                        item(key = "body") { PageBody(screen, state.filter, state.settings, state.problem, locale, onOpenStock, onOpenSettings) }
                    }
                }
            }
            SnackbarHost(hostState = snackbar, modifier = Modifier.align(Alignment.BottomCenter))
        }
    }
}

@Composable
private fun PageBody(
    screen: ScreenerState,
    filter: SetupFilter,
    settings: ScreenerSettings?,
    problem: StocksError?,
    locale: Locale,
    onOpenStock: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val body = screen.body
    when (screen.kind) {
        ScreenerKind.Loading -> QuietLineOr(problem, stringResource(R.string.stocks_loading))
        ScreenerKind.NotLoaded -> QuietLineOr(null, stringResource(R.string.stocks_not_loaded))
        ScreenerKind.NoBars -> {
            StateBlock(
                stringResource(R.string.screener_building),
                stringResource(R.string.screener_building_page, body?.universe?.count ?: 0, StockTimes.clock(body?.scan?.nextRun) ?: "16:20"),
                modifier = Modifier.padding(top = Spacing.l),
            )
            SettingsButton(onOpenSettings)
        }
        ScreenerKind.NoScanYet -> StateBlock(stringResource(R.string.screener_no_scan), noScanBody(screen), modifier = Modifier.padding(top = Spacing.l))
        ScreenerKind.Empty, ScreenerKind.List -> {
            val candidates = ScreenerStates.filtered(body?.candidates.orEmpty(), filter)
            val flags = ScreenerStates.filtered(body?.flags.orEmpty(), filter)
            when {
                screen.kind == ScreenerKind.Empty && filter == SetupFilter.All -> {
                    StateBlock(stringResource(R.string.screener_none), noneBody(screen, settings), modifier = Modifier.padding(top = Spacing.l))
                    SettingsButton(onOpenSettings)
                }
                candidates.isEmpty() && flags.isEmpty() -> StateBlock(
                    stringResource(R.string.screener_nothing_filter),
                    stringResource(R.string.screener_nothing_filter_body),
                    modifier = Modifier.padding(top = Spacing.l),
                )
            }
            if (candidates.isNotEmpty()) {
                SectionHeaderLine(stringResource(R.string.screener_candidates, candidates.size))
                CandidateList(candidates, flag = false, muted = screen.muted, locale = locale, onOpen = onOpenStock)
            }
            if (flags.isNotEmpty()) {
                SectionHeaderLine(stringResource(R.string.screener_bearish, flags.size))
                CandidateList(flags, flag = true, muted = screen.muted, locale = locale, onOpen = onOpenStock)
                Note(stringResource(R.string.screener_bearish_note), modifier = Modifier.padding(top = Spacing.xs))
            }
            if ((body != null && screen.kind == ScreenerKind.List) || flags.isNotEmpty()) Footer(screen, onOpenSettings)
        }
    }
}

@Composable
private fun QuietLineOr(problem: StocksError?, fallback: String) =
    io.github.justcallmesu.aristotle.ui.sessions.QuietLine(problem?.let { stocksErrorText(it) } ?: fallback)

@Composable
private fun SectionHeaderLine(text: String) = io.github.justcallmesu.aristotle.ui.sessions.SectionHeader(text)

@Composable
private fun SettingsButton(onOpenSettings: () -> Unit) {
    FilledTonalButton(onClick = onOpenSettings, shape = ButtonShape, modifier = Modifier.padding(horizontal = Spacing.listGutter, vertical = Spacing.m)) {
        Text(stringResource(R.string.screener_settings))
    }
}

/** "80 stocks scanned at 10:45. On: breakout, …. Stocks under Rp 5 bn a day are skipped. A looser threshold finds more." */
@Composable
private fun noneBody(screen: ScreenerState, settings: ScreenerSettings?): String {
    val body = screen.body
    val first = stringResource(
        R.string.screener_none_page,
        body?.scan?.codesOk ?: 0,
        StockTimes.clock(body?.scan?.lastRun) ?: "-",
        settings?.let { setupsOn(it) } ?: "-",
    )
    val floor = settings?.minValueBn
    val second = if (floor != null && floor > 0) stringResource(R.string.screener_none_page_floor, SetupLabels.oneDecimal(floor).removeSuffix(",0"))
    else stringResource(R.string.screener_none_page_no_floor)
    return "$first $second"
}

@Composable
private fun setupsOn(s: ScreenerSettings): String {
    val on = listOfNotNull(
        stringResource(R.string.screener_setup_breakout).takeIf { s.setups.breakout.on },
        stringResource(R.string.screener_setup_volume).takeIf { s.setups.volumeSpike.on },
        stringResource(R.string.screener_setup_ma).takeIf { s.setups.maCross.on },
        stringResource(R.string.screener_setup_rsi).takeIf { s.setups.rsiRebound.on },
        stringResource(R.string.screener_setup_gap).takeIf { s.setups.gap.on },
    )
    return if (on.isEmpty()) stringResource(R.string.screener_none_setups_off) else on.joinToString(", ")
}

/** "Prices as of 10:35, 10 min delayed." or "Closing prices…", what was skipped, and "Change in settings". */
@Composable
private fun Footer(screen: ScreenerState, onOpenSettings: () -> Unit) {
    val body = screen.body ?: return
    val first = if (body.screenerMode == ScreenerMode.Eod) stringResource(R.string.screener_foot_eod)
    else stringResource(R.string.screener_foot_intraday, StockTimes.clock(body.dataAsOf) ?: "-")
    val skipped = stringResource(R.string.screener_foot_skipped, body.skipped.illiquid, body.skipped.noBars)
    Column(modifier = Modifier.fillMaxWidth().padding(top = Spacing.m)) {
        Note("$first $skipped")
        TextButton(onClick = onOpenSettings, shape = ButtonShape, modifier = Modifier.padding(horizontal = Spacing.s)) {
            Text(stringResource(R.string.screener_change_settings))
        }
    }
}

/**
 * The subline: "80 stocks · scanned 10:45 · next 11:00"; Last close: "Last close · Thu 5 Nov · 80 stocks"; stale: "… · as
 * of 10:20 · next try 11:00"; offline: "… · PC offline"; between the sessions: "Session break · last scan 12:05".
 */
@Composable
private fun pageSubline(screen: ScreenerState, locale: Locale): String? {
    val body = screen.body ?: return null
    val count = pluralStringResource(R.plurals.stocks_count, body.universe.count, body.universe.count)
    val scanned = StockTimes.clock(body.scan.lastRun)
    val next = StockTimes.dayClock(body.scan.nextRun, StockTimes.today(System.currentTimeMillis()), locale)
    return when {
        screen.kind == ScreenerKind.NoBars -> stringResource(R.string.screener_building)
        body.screenerMode == ScreenerMode.Eod -> sessionDay(body.sessionDate, locale)?.let { stringResource(R.string.screener_subline_eod, it, count) } ?: count
        scanned == null -> count
        screen.offline -> stringResource(R.string.screener_subline_offline, count, scanned)
        screen.sessionBreak -> stringResource(R.string.screener_subline_break, scanned)
        screen.stale -> stringResource(R.string.screener_subline_stale, count, StockTimes.clock(body.dataAsOf) ?: scanned, next ?: "-")
        next != null -> stringResource(R.string.screener_subline, count, scanned, next)
        else -> stringResource(R.string.screener_subline_no_next, count, scanned)
    }
}

/** ⋮ with one item, Scan now: disabled with "in 1:20" while the server would answer `too_soon`, "Scanning…" while one runs. */
@Composable
private fun ScanMenu(state: ScreenerUiState, onScan: () -> Unit, icons: androidx.compose.material3.IconButtonColors) {
    var open by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(open, state.cooldownUntil) {
        while (open) {
            now = System.currentTimeMillis()
            delay(TICK_MILLIS)
        }
    }
    val left = state.scanWaitSeconds(now)
    val enabled = state.canScan(now)
    Box {
        IconButton(onClick = { now = System.currentTimeMillis(); open = true }, colors = icons) {
            Icon(painter = painterResource(R.drawable.ic_more_vert), contentDescription = stringResource(R.string.sessions_more))
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            shape = MaterialTheme.shapes.medium,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(Sizes.divider, MaterialTheme.colorScheme.outlineVariant),
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(if (state.scanBusy) R.string.screener_scanning else R.string.screener_scan_now)) },
                leadingIcon = { Icon(painter = painterResource(R.drawable.ic_refresh), contentDescription = null) },
                trailingIcon = left?.let { s ->
                    { Text(stringResource(R.string.screener_scan_in, ScanCooldown.clock(s)), style = AristotleTextStyles.tabular(MaterialTheme.typography.bodySmall)) }
                },
                enabled = enabled,
                onClick = {
                    open = false
                    onScan()
                },
            )
        }
    }
}

/**
 * Yahoo refused (or the scan failed): the notice names it and the list shown; Scan now (board `notice`), under the same
 * rule as the ⋮ item: disabled while a scan runs or during the cooldown, with "in 1:20".
 */
@Composable
private fun StaleNotice(text: String, state: ScreenerUiState, onScan: () -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.cooldownUntil) {
        do {
            now = System.currentTimeMillis()
            delay(TICK_MILLIS)
        } while (state.scanWaitSeconds(now) != null)
        now = System.currentTimeMillis()
    }
    val left = state.scanWaitSeconds(now)
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.listGutter, vertical = Spacing.xs),
    ) {
        Row(modifier = Modifier.padding(start = Spacing.m, end = Spacing.xs), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
            Icon(painter = painterResource(R.drawable.ic_info), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(NOTICE_ICON))
            // TSC3b MINOR-6: only the notice's words are a live region; the countdown beside it changes every second and
            // stays out of it, so TalkBack announces the notice once, not each tick.
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f).padding(vertical = Spacing.s).semantics { liveRegion = LiveRegionMode.Polite },
            )
            left?.let {
                Text(
                    stringResource(R.string.screener_scan_in, ScanCooldown.clock(it)),
                    style = AristotleTextStyles.tabular(MaterialTheme.typography.bodySmall),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onScan, enabled = state.canScan(now), shape = ButtonShape) { Text(stringResource(R.string.screener_scan_now)) }
        }
    }
}

/** Offline: the same line as Stocks home (cloud-off, "Offline since", Retry now) and that the list is the kept one. */
@Composable
private fun OfflineLine(link: LinkState, screen: ScreenerState, onRetry: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.l).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Icon(painter = painterResource(R.drawable.ic_cloud_off), contentDescription = null, tint = muted, modifier = Modifier.size(NOTICE_ICON))
            val since = (link as? LinkState.Offline)?.sinceMillis?.let(::localClock)
            Text(
                text = since?.let { stringResource(R.string.sessions_offline_since, it) } ?: stringResource(R.string.stocks_error_unreachable),
                style = AristotleTextStyles.tabular(MaterialTheme.typography.titleSmall),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            TextButton(onClick = onRetry, shape = ButtonShape) { Text(stringResource(R.string.conn_retry_now)) }
        }
        StockTimes.clock(screen.body?.scan?.lastRun)?.let {
            Text(stringResource(R.string.screener_kept_page, it), style = AristotleTextStyles.tabular(MaterialTheme.typography.bodySmall), color = muted)
        }
    }
}

/** Today | Last close, full width, 40 dp, `small` corners (no pills, design.md §3). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeChoice(selected: ScreenerMode?, onSelect: (ScreenerMode) -> Unit) {
    val modes = listOf(ScreenerMode.Intraday to R.string.screener_today, ScreenerMode.Eod to R.string.screener_last_close)
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.listGutter, vertical = Spacing.s)) {
        modes.forEachIndexed { index, (mode, label) ->
            SegmentedButton(
                selected = selected == mode,
                onClick = { onSelect(mode) },
                shape = SegmentedButtonDefaults.itemShape(index, modes.size, baseShape = MaterialTheme.shapes.small),
                label = { Text(stringResource(label), maxLines = 1) },
            )
        }
    }
}

/** All · Breakout · Volume · MA · RSI · Gap (FilterChips, `small` shape, one at a time). */
@Composable
private fun FilterRow(selected: SetupFilter, onSelect: (SetupFilter) -> Unit) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().selectableGroup(),
        contentPadding = PaddingValues(horizontal = Spacing.listGutter),
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        items(SetupFilter.entries, key = { it.name }) { filter ->
            FilterChip(
                selected = selected == filter,
                onClick = { onSelect(filter) },
                label = { Text(stringResource(filterLabel(filter))) },
                leadingIcon = if (selected == filter) {
                    { Icon(painter = painterResource(R.drawable.ic_check), contentDescription = null, modifier = Modifier.size(Sizes.chipIcon)) }
                } else {
                    null
                },
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.semantics { role = Role.RadioButton },
            )
        }
    }
}

private fun filterLabel(filter: SetupFilter): Int = when (filter) {
    SetupFilter.All -> R.string.screener_filter_all
    SetupFilter.Breakout -> R.string.screener_filter_breakout
    SetupFilter.Volume -> R.string.screener_filter_volume
    SetupFilter.Ma -> R.string.screener_filter_ma
    SetupFilter.Rsi -> R.string.screener_filter_rsi
    SetupFilter.Gap -> R.string.screener_filter_gap
}

/** Snackbars for the screener's actions. */
@Composable
internal fun ScreenerMessages(message: ScreenerMessage?, snackbar: SnackbarHostState, onShown: () -> Unit) {
    val text = message?.let { screenerMessageText(it) }
    LaunchedEffect(message) {
        if (text != null) {
            snackbar.showSnackbar(text)
            onShown()
        }
    }
}

@Composable
private fun screenerMessageText(message: ScreenerMessage): String {
    val error = message.error
    val why = error?.let { stocksErrorText(it) }.orEmpty()
    return when (message.action) {
        ScreenerAction.ScanNow -> if ((error as? StocksError.Server)?.code == StocksError.SCAN_RUNNING) stringResource(R.string.screener_scan_running)
        else stringResource(R.string.screener_scan_failed, why)
        ScreenerAction.SaveSettings -> if (error == null) stringResource(R.string.screener_saved) else stringResource(R.string.screener_save_failed, why)
        ScreenerAction.SaveUniverse -> if (error == null) stringResource(R.string.screener_universe_saved) else stringResource(R.string.screener_universe_failed, why)
        ScreenerAction.ResetUniverse -> if (error == null) stringResource(R.string.screener_universe_reset_done) else stringResource(R.string.screener_universe_failed, why)
    }
}

/** "10:52" on the phone's clock (when the link went offline). */
private fun localClock(millis: Long): String = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))

private val NOTICE_ICON = 18.dp
private const val TICK_MILLIS = 1_000L
