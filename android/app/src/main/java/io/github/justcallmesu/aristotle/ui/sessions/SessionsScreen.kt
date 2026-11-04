package io.github.justcallmesu.aristotle.ui.sessions

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonColors
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.justcallmesu.aristotle.R
import io.github.justcallmesu.aristotle.data.LinkState
import io.github.justcallmesu.aristotle.data.Network
import io.github.justcallmesu.aristotle.data.OfflineCause
import io.github.justcallmesu.aristotle.data.ProfileOption
import io.github.justcallmesu.aristotle.ui.components.RunningSpinner
import io.github.justcallmesu.aristotle.ui.theme.AristotleTextStyles
import io.github.justcallmesu.aristotle.ui.theme.ButtonShape
import io.github.justcallmesu.aristotle.ui.theme.FieldShape
import io.github.justcallmesu.aristotle.ui.theme.Sizes
import io.github.justcallmesu.aristotle.ui.theme.Spacing
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
fun SessionsScreen(
    viewModel: SessionsViewModel,
    onPairAgain: () -> Unit,
    onSettings: () -> Unit,
    onOpen: (OpenRequest) -> Unit,
    onScheduled: (ScheduledRequest) -> Unit,
    terminalAvailable: Boolean = false,
    onTerminal: () -> Unit = {},
    whatsNew: (@Composable () -> Unit)? = null,
    pcId: String,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state.open) {
        val request = state.open ?: return@LaunchedEffect
        viewModel.opened()
        onOpen(request)
    }
    SessionsContentView(
        state = state,
        timeText = viewModel::timeText,
        onRetry = viewModel::retry,
        onPairAgain = onPairAgain,
        onSettings = onSettings,
        onScheduled = onScheduled,
        actions = remember(viewModel) { ViewModelListActions(viewModel) },
        terminalAvailable = terminalAvailable,
        onTerminal = onTerminal,
        whatsNew = whatsNew,
        pcId = pcId,
    )
    SessionSheets(state, remember(viewModel) { ViewModelSheetActions(viewModel) })
}

/** What the list screen can ask of its ViewModel. */
interface ListActions {
    fun open(row: SessionRow)
    fun showActions(row: SessionRow)
    fun openProfiles()
    fun openSearch()
    fun search(query: String)
    fun closeSearch()
    fun loadMore()
    fun retryHistory()
    fun refresh()
    fun newSession()
    fun messageShown()
}

private class ViewModelListActions(private val vm: SessionsViewModel) : ListActions {
    override fun open(row: SessionRow) = vm.open(row)
    override fun showActions(row: SessionRow) = vm.showActions(row)
    override fun openProfiles() = vm.openProfiles()
    override fun openSearch() = vm.openSearch()
    override fun search(query: String) = vm.search(query)
    override fun closeSearch() = vm.closeSearch()
    override fun loadMore() = vm.loadMore()
    override fun retryHistory() = vm.retryHistory()
    override fun refresh() = vm.refresh()
    override fun newSession() = vm.newSession()
    override fun messageShown() = vm.messageShown()
}

private class ViewModelSheetActions(private val vm: SessionsViewModel) : SheetActions {
    override fun closeProfiles() = vm.closeProfiles()
    override fun retryProfiles() = vm.openProfiles()
    override fun selectProfile(option: ProfileOption) = vm.selectProfile(option)
    override fun closeActions() = vm.closeActions()
    override fun togglePin(row: SessionRow) = vm.togglePin(row)
    override fun startRename(row: SessionRow) = vm.startRename(row)
    override fun cancelRename() = vm.cancelRename()
    override fun rename(title: String) = vm.rename(title)
}

/** The "Scheduled" page for the listed [profile] (null = the launch profile), named [label] on the page. */
data class ScheduledRequest(val profile: String?, val label: String?)

/**
 * Home C (Q75, design-notes §5.1 / §5.6): a head row (wordmark, PC line, profile avatar), up to two notices, live
 * sessions as wide cards over a one-line ledger, and a bottom app bar (search, Scheduled, ⋮, New chat).
 */
@Composable
fun SessionsContentView(
    state: SessionsUiState,
    timeText: (Long) -> String,
    onRetry: () -> Unit,
    onPairAgain: () -> Unit,
    onSettings: () -> Unit,
    actions: ListActions,
    onScheduled: (ScheduledRequest) -> Unit = {},
    terminalAvailable: Boolean = false,
    onTerminal: () -> Unit = {},
    whatsNew: (@Composable () -> Unit)? = null,
    pcId: String,
) {
    val address = state.address?.hostForUrl.orEmpty()
    val snackbar = remember { SnackbarHostState() }
    val message = state.message?.let { sessionsMessage(it) }
    LaunchedEffect(state.message) {
        if (message != null) {
            snackbar.showSnackbar(message)
            actions.messageShown()
        }
    }
    // Edge-to-edge (D8): the surface runs under both system bars; the bottom bar (or, while searching, the list) takes
    // the navigation bar inset.
    val bottom = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)
    val page = pageFor(state)
    val showBar = !state.searching
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
            if (state.searching) SearchHead(state.query, actions) else HomeHead(state, address, timeText, onRetry, actions)
            if (page == null) {
                val offline = state.link as? LinkState.Offline
                val compatibility = compatibilityText(pcId)
                offline?.let { OfflineNotice(it, address, state.listedAt) }
                compatibility?.let { CompatibilityNotice(it) }
                if (showWhatsNewNotice(offline = offline != null, incompatible = compatibility != null)) whatsNew?.invoke()
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth().then(if (showBar) Modifier else Modifier.windowInsetsPadding(bottom))) {
                if (page != null) ConnectionPage(page.kind, page.details, address, onRetry, onPairAgain) else ListBody(state, address, actions)
                SnackbarHost(hostState = snackbar, modifier = Modifier.align(Alignment.BottomCenter))
            }
            if (showBar) {
                HomeBottomBar(
                    state = state,
                    newChat = state.link is LinkState.Online && page == null,
                    onScheduled = onScheduled,
                    onSettings = onSettings,
                    actions = actions,
                    terminalAvailable = terminalAvailable,
                    onTerminal = onTerminal,
                )
            }
        }
    }
}

private class Page(val kind: PageKind, val details: String)

/** design.md §5: Hermes down / updated and a changed certificate replace the list; everything else keeps it. */
private fun pageFor(state: SessionsUiState): Page? = when (val link = state.link) {
    is LinkState.HermesDown -> Page(PageKind.HermesDown, link.details)
    is LinkState.HermesUpdated -> Page(PageKind.HermesUpdated, link.details)
    is LinkState.Offline -> if (link.cause == OfflineCause.CertificateChanged) Page(PageKind.CertificateChanged, link.details) else null
    is LinkState.Online -> state.listProblem?.let { Page(PageKind.ListFailed, it) }
    else -> null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ListBody(state: SessionsUiState, address: String, actions: ListActions) {
    val items = state.items
    when {
        items == null -> if (state.link !is LinkState.Offline) ConnectingBody(address)
        items.isEmpty() -> EmptyBody()
        else -> PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = actions::refresh, modifier = Modifier.fillMaxSize()) {
            HomeListView(items, state.searching, state.staleAt, actions)
        }
    }
}

/** Home C head (§5.1): not a TopAppBar; wordmark + PC line, the profile avatar at the end, top-aligned. */
@Composable
private fun HomeHead(state: SessionsUiState, address: String, timeText: (Long) -> String, onRetry: () -> Unit, actions: ListActions) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = Spacing.l, end = Spacing.s, top = Spacing.m, bottom = Spacing.xs),
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = stringResource(R.string.app_name), style = homeWordmark(), maxLines = 1)
            PcLine(state.link, address, timeText, onRetry)
        }
        state.profileName?.let {
            ProfileAvatarButton(
                name = it,
                enabled = state.link is LinkState.Online,
                onClick = actions::openProfiles,
                modifier = Modifier.padding(start = Spacing.s),
            )
        }
    }
}

/** Literata 600 at 28 / 36 (the wordmark token is 22 / 28), kept at the app's text size. */
@Composable
private fun homeWordmark(): TextStyle {
    val base = AristotleTextStyles.wordmark
    return base.copy(fontSize = base.fontSize * WORDMARK_SIZE_SCALE, lineHeight = base.lineHeight * WORDMARK_LINE_SCALE)
}

/**
 * "100.74.61.96 · via Tailscale" / "… · via Wi-Fi"; offline: cloud-off icon, "Offline since HH:MM" and an inline
 * Retry now (§5.1 PC line states). The offline notice under the head carries the cause and the retry countdown.
 */
@Composable
private fun PcLine(link: LinkState, address: String, timeText: (Long) -> String, onRetry: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val lineStyle = MaterialTheme.typography.bodySmall
    if (link is LinkState.Offline) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Icon(painter = painterResource(R.drawable.ic_cloud_off), contentDescription = null, tint = muted, modifier = Modifier.size(Sizes.lineIcon))
            Text(
                text = stringResource(R.string.sessions_offline_since, timeText(link.sinceMillis)),
                style = AristotleTextStyles.tabular(lineStyle),
                color = muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false).semantics { liveRegion = LiveRegionMode.Polite },
            )
            TextButton(onClick = onRetry, shape = ButtonShape, contentPadding = RETRY_PADDING) { Text(stringResource(R.string.conn_retry_now)) }
        }
        return
    }
    val text = when {
        link is LinkState.Online && link.endpoint.network == Network.Tailscale -> stringResource(R.string.sessions_via_tailscale, address)
        link is LinkState.Online -> stringResource(R.string.sessions_via_wifi, address)
        else -> address
    }
    Text(text = text, style = lineStyle, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** Search mode replaces the head: back, then a filled search field (§5.1 field: FieldShape, surfaceContainerHigh). */
@Composable
private fun SearchHead(query: String, actions: ListActions) {
    val colors = MaterialTheme.colorScheme
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = Sizes.topBarHeight).padding(start = Spacing.xs, end = Spacing.l),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = actions::closeSearch) {
                Icon(painter = painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.sessions_search_close), tint = colors.onSurfaceVariant)
            }
            SearchField(query, actions, Modifier.weight(1f))
        }
        Text(
            text = stringResource(R.string.sessions_search_scope),
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(start = Spacing.listGutter, end = Spacing.listGutter, bottom = Spacing.s),
        )
    }
}

/** Filters the titles loaded on this phone (the relay has no search); focused = 2 dp primary ring (§4). */
@Composable
private fun SearchField(query: String, actions: ListActions, modifier: Modifier = Modifier) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    var focused by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val description = stringResource(R.string.sessions_search)
    Surface(
        shape = FieldShape,
        color = colors.surfaceContainerHigh,
        border = if (focused) BorderStroke(FOCUS_RING, colors.primary) else null,
        modifier = modifier,
    ) {
        Row(modifier = Modifier.heightIn(min = Sizes.minTouchTarget).padding(start = Spacing.m), verticalAlignment = Alignment.CenterVertically) {
            Icon(painter = painterResource(R.drawable.ic_search), contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(Sizes.fieldIcon))
            BasicTextField(
                value = query,
                onValueChange = actions::search,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
                cursorBrush = SolidColor(colors.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = Spacing.m)
                    .focusRequester(focus)
                    .onFocusChanged { focused = it.isFocused }
                    .semantics { contentDescription = description },
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) Text(description, style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant, maxLines = 1)
                        inner()
                    }
                },
            )
            if (query.isNotEmpty()) {
                IconButton(onClick = { actions.search("") }) {
                    Icon(painter = painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.sessions_search_clear), tint = colors.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun ConnectingBody(address: String) {
    Text(
        text = stringResource(R.string.sessions_connecting, address),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Spacing.listGutter, vertical = Spacing.xxl),
    )
}

@Composable
private fun EmptyBody() {
    Column(modifier = Modifier.padding(horizontal = Spacing.listGutter, vertical = Spacing.l)) {
        Text(text = stringResource(R.string.sessions_empty_title), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = stringResource(R.string.sessions_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** [staleAt]: the list is from that time and the link is not up, so nothing reads as live (design.md §5). */
@Composable
private fun HomeListView(items: List<SessionListItem>, searching: Boolean, staleAt: String?, actions: ListActions) {
    val list = rememberLazyListState()
    LoadMoreAtTheEnd(list, items, searching, actions::loadMore)
    val home = remember(items) { homeList(items) }
    LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = Spacing.l)) {
        if (home.live.isNotEmpty()) {
            item(key = "live-header") { SectionHeader(stringResource(R.string.sessions_section_live_now)) }
            item(key = "live-cards") { LiveCards(home.live, staleAt, actions) }
        }
        items(home.ledger, key = ::ledgerKey) { item ->
            when (item) {
                is LedgerItem.Header -> LedgerHeader(item)
                is LedgerItem.Row -> LedgerRow(item.row, staleAt, actions)
                is LedgerItem.Footer -> HistoryFooter(item.end, actions::loadMore, actions::retryHistory)
                LedgerItem.NoMatch -> QuietLine(stringResource(R.string.sessions_search_none))
            }
        }
    }
}

/** History pages in as the end of the list comes into view (protocol §4: the same `session.list`, a larger limit). */
@Composable
private fun LoadMoreAtTheEnd(list: LazyListState, items: List<SessionListItem>, searching: Boolean, loadMore: () -> Unit) {
    // Not while searching: a few matches must not page the whole history in (the footer button still can).
    val wantsMore = !searching && (items.lastOrNull() as? SessionListItem.Footer)?.end == HistoryEnd.More
    LaunchedEffect(list, wantsMore) {
        if (!wantsMore) return@LaunchedEffect
        snapshotFlow { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index == list.layoutInfo.totalItemsCount - 1 }
            .distinctUntilChanged()
            .collect { atEnd -> if (atEnd) loadMore() }
    }
}

private fun ledgerKey(item: LedgerItem): String = when (item) {
    is LedgerItem.Header -> "header-${item.section}"
    is LedgerItem.Row -> "row-${item.row.key}"
    is LedgerItem.Footer -> "footer"
    LedgerItem.NoMatch -> "no-match"
}

/** `labelMedium` `onSurfaceVariant` section header on the 16 dp gutter; Pinned carries a 16 dp pin (board `.sec`). */
@Composable
private fun SectionHeader(text: String, pinned: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Spacing.listGutter, end = Spacing.listGutter, top = Spacing.l, bottom = Spacing.s)
            .semantics(mergeDescendants = true) { heading() },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        if (pinned) {
            Icon(
                painter = painterResource(R.drawable.ic_push_pin),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(SECTION_ICON),
            )
        }
        Text(text = text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LedgerHeader(header: LedgerItem.Header) {
    val date = header.date.orEmpty()
    when (header.section) {
        LedgerSection.Pinned -> SectionHeader(stringResource(R.string.sessions_section_pinned), pinned = true)
        LedgerSection.Today -> SectionHeader(stringResource(R.string.sessions_section_today, date))
        LedgerSection.Yesterday -> SectionHeader(stringResource(R.string.sessions_section_yesterday, date))
        LedgerSection.Earlier -> SectionHeader(stringResource(R.string.sessions_section_earlier))
    }
}

/** §5.1 live cards: 300 dp wide, snapping to the start, 12 dp apart on the 16 dp gutter. */
@Composable
private fun LiveCards(rows: List<SessionRow>, staleAt: String?, actions: ListActions) {
    val cards = rememberLazyListState()
    LazyRow(
        state = cards,
        flingBehavior = rememberSnapFlingBehavior(cards, SnapPosition.Start),
        contentPadding = PaddingValues(start = Spacing.listGutter, end = Spacing.listGutter, top = Spacing.xs, bottom = Spacing.s),
        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        items(rows, key = { "card-${it.key}" }) { row -> LiveCard(row, staleAt, actions) }
    }
}

/**
 * One live session (`large`, `surfaceContainerLow`): status + time, the title in two lines, and the message count at
 * the foot (the relay lists no step number, approval or last step, so those parts of the board's card are absent).
 * Tap opens; long press opens the row's actions, as on the ledger.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LiveCard(row: SessionRow, staleAt: String?, actions: ListActions) {
    val more = stringResource(R.string.session_actions)
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.width(CARD_WIDTH).heightIn(min = CARD_MIN_HEIGHT),
    ) {
        Column(
            modifier = Modifier
                .combinedClickable(onClick = { actions.open(row) }, onLongClick = { actions.showActions(row) }, role = Role.Button)
                .semantics { onLongClick(label = more) { actions.showActions(row); true } }
                .padding(horizontal = Spacing.l, vertical = CARD_VERTICAL_PADDING),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(CARD_GAP)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CARD_GAP)) {
                    CardStatus(row, staleAt, Modifier.weight(1f))
                    row.time?.let { RowTimeText(it, staleAt, AristotleTextStyles.tabular(MaterialTheme.typography.bodySmall)) }
                }
                Text(
                    text = row.title ?: stringResource(R.string.session_untitled),
                    style = AristotleTextStyles.chatTitle,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = pluralStringResource(R.plurals.session_messages, row.messageCount, row.messageCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.padding(top = CARD_GAP),
            )
        }
    }
}

/** Running: spinner + "Running" (tertiary); listed while offline: "Was running at HH:MM"; idle: nothing. */
@Composable
private fun CardStatus(row: SessionRow, staleAt: String?, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CARD_GAP)) {
        if (row.running && staleAt != null) {
            Text(
                text = stringResource(R.string.session_was_running, staleAt),
                style = AristotleTextStyles.liveStatusSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else if (row.running) {
            RunningSpinner()
            Text(
                text = stringResource(R.string.session_running),
                style = AristotleTextStyles.liveStatusSmall,
                color = MaterialTheme.colorScheme.tertiary,
                maxLines = 1,
            )
        }
    }
}

/** §5.1 ledger row: one line, 52 dp; a 56 dp time column (today/yesterday a clock, earlier a date) + the title. No count (Q75). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LedgerRow(row: SessionRow, staleAt: String?, actions: ListActions) {
    val more = stringResource(R.string.session_actions)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = { actions.open(row) }, onLongClick = { actions.showActions(row) }, role = Role.Button)
            .semantics { onLongClick(label = more) { actions.showActions(row); true } }
            .heightIn(min = LEDGER_ROW_HEIGHT)
            .padding(horizontal = Spacing.listGutter, vertical = LEDGER_VERTICAL_PADDING),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.padding(end = Spacing.xs).widthIn(min = TIME_COLUMN)) {
            row.time?.let { RowTimeText(it, staleAt, ledgerTimeStyle()) }
        }
        Text(
            text = row.title ?: stringResource(R.string.session_untitled),
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/** bodyMedium at 13 sp (it is 14), tabular, kept at the app's text size. */
@Composable
private fun ledgerTimeStyle(): TextStyle {
    val base = MaterialTheme.typography.bodyMedium
    return AristotleTextStyles.tabular(base.copy(fontSize = base.fontSize * LEDGER_TIME_SCALE))
}

@Composable
private fun RowTimeText(time: RowTime, staleAt: String?, style: TextStyle, modifier: Modifier = Modifier) {
    Text(
        text = when (time) {
            RowTime.Now -> staleAt ?: stringResource(R.string.session_now)
            is RowTime.At -> time.text
        },
        style = style,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        modifier = modifier,
    )
}

/** Under the history: "Load more" (the scroll also loads), "Loading…", or where the relay's paging stops. */
@Composable
private fun HistoryFooter(end: HistoryEnd, loadMore: () -> Unit, retry: () -> Unit) {
    when (end) {
        HistoryEnd.More -> Box(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.s, vertical = Spacing.s)) {
            TextButton(onClick = loadMore, shape = ButtonShape) {
                Text(stringResource(R.string.sessions_load_more))
            }
        }
        HistoryEnd.Loading -> QuietLine(stringResource(R.string.sessions_loading_more))
        HistoryEnd.Capped -> QuietLine(stringResource(R.string.sessions_history_capped))
        HistoryEnd.Failed -> Row(
            modifier = Modifier.fillMaxWidth().padding(start = Spacing.listGutter, end = Spacing.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.sessions_history_failed),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f).padding(vertical = Spacing.l),
            )
            TextButton(onClick = retry, shape = ButtonShape) {
                Text(stringResource(R.string.sessions_history_retry))
            }
        }
        HistoryEnd.All -> Unit
    }
}

@Composable
private fun QuietLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Spacing.listGutter, vertical = Spacing.l),
    )
}

/**
 * §5.1 bottom app bar (`surfaceContainer`, icons `onSurfaceVariant`): search, Scheduled, ⋮ (Terminal when shown,
 * Settings), and the New chat FAB while online with the list showing. Settings stays reachable on every page.
 */
@Composable
private fun HomeBottomBar(
    state: SessionsUiState,
    newChat: Boolean,
    onScheduled: (ScheduledRequest) -> Unit,
    onSettings: () -> Unit,
    actions: ListActions,
    terminalAvailable: Boolean,
    onTerminal: () -> Unit,
) {
    val icons = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
    // T18: the listed profile's Hermes cron jobs.
    val scheduled = { onScheduled(ScheduledRequest(state.profile, state.profileName)) }
    BottomAppBar(
        actions = {
            IconButton(onClick = actions::openSearch, enabled = state.items != null, colors = icons) {
                Icon(painter = painterResource(R.drawable.ic_search), contentDescription = stringResource(R.string.sessions_search))
            }
            IconButton(onClick = scheduled, colors = icons) {
                Icon(painter = painterResource(R.drawable.ic_schedule), contentDescription = stringResource(R.string.cron_open))
            }
            OverflowMenu(
                entries = homeMenuEntries(terminalAvailable),
                colors = icons,
                onEntry = { entry ->
                    when (entry) {
                        ListMenuEntry.Scheduled -> scheduled()
                        ListMenuEntry.Terminal -> onTerminal()
                        ListMenuEntry.Settings -> onSettings()
                    }
                },
            )
        },
        floatingActionButton = if (newChat) {
            { NewChatButton(busy = state.creating, onClick = actions::newSession) }
        } else {
            null
        },
        windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom),
    )
}

/** The ⋮ menu (`surfaceContainerHigh`, `medium`, 1 dp `outlineVariant` border; TU1 S6). */
@Composable
private fun OverflowMenu(entries: List<ListMenuEntry>, colors: IconButtonColors, onEntry: (ListMenuEntry) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, colors = colors) {
            Icon(painter = painterResource(R.drawable.ic_more_vert), contentDescription = stringResource(R.string.sessions_more))
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            shape = MaterialTheme.shapes.medium,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(Sizes.divider, MaterialTheme.colorScheme.outlineVariant),
        ) {
            entries.forEach { entry ->
                val (icon, label) = when (entry) {
                    ListMenuEntry.Scheduled -> R.drawable.ic_schedule to R.string.cron_open
                    ListMenuEntry.Terminal -> R.drawable.ic_terminal to R.string.terminal_open
                    ListMenuEntry.Settings -> R.drawable.ic_settings to R.string.settings_title
                }
                DropdownMenuItem(
                    text = { Text(stringResource(label)) },
                    leadingIcon = { Icon(painter = painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    onClick = { open = false; onEntry(entry) },
                )
            }
        }
    }
}

/** §5.1 FAB in the bottom bar: `large` shape, `primaryContainer`, no shadow inside the bar. Shown only while online. */
@Composable
private fun NewChatButton(busy: Boolean, onClick: () -> Unit) {
    val label = stringResource(if (busy) R.string.sessions_new_busy else R.string.sessions_new_chat)
    FloatingActionButton(
        onClick = { if (!busy) onClick() },
        shape = MaterialTheme.shapes.large,
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        elevation = FloatingActionButtonDefaults.bottomAppBarFabElevation(),
        modifier = Modifier.semantics { contentDescription = label },
    ) {
        if (busy) RunningSpinner() else Icon(painter = painterResource(R.drawable.ic_add), contentDescription = null)
    }
}

@Composable
private fun sessionsMessage(message: SessionsMessage): String = when (message) {
    is SessionsMessage.RenameFailed -> stringResource(R.string.session_rename_failed, message.reason)
    is SessionsMessage.CreateFailed -> stringResource(R.string.sessions_new_failed, message.reason)
    is SessionsMessage.ProfileFellBack -> stringResource(R.string.sessions_profile_fell_back, message.name)
}

// Home C tokens (design-notes §5.1 / board `.bighead`, `.lcard`, `.ledger`) not in the theme.
private const val WORDMARK_SIZE_SCALE = 28f / 22f
private const val WORDMARK_LINE_SCALE = 36f / 28f
private const val LEDGER_TIME_SCALE = 13f / 14f
private val CARD_WIDTH = 300.dp
private val CARD_MIN_HEIGHT = 132.dp
private val CARD_VERTICAL_PADDING = 14.dp
private val CARD_GAP = 6.dp
private val LEDGER_ROW_HEIGHT = 52.dp
private val LEDGER_VERTICAL_PADDING = 14.dp
private val TIME_COLUMN = 56.dp
private val SECTION_ICON = 16.dp
private val FOCUS_RING = 2.dp
private val RETRY_PADDING = PaddingValues(horizontal = Spacing.s, vertical = ButtonDefaults.TextButtonContentPadding.calculateTopPadding())
