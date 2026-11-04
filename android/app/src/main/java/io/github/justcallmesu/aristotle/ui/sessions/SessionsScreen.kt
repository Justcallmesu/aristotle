package io.github.justcallmesu.aristotle.ui.sessions

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.justcallmesu.aristotle.R
import io.github.justcallmesu.aristotle.data.LinkState
import io.github.justcallmesu.aristotle.data.Network
import io.github.justcallmesu.aristotle.data.OfflineCause
import io.github.justcallmesu.aristotle.data.ProfileOption
import io.github.justcallmesu.aristotle.ui.components.RunningSpinner
import io.github.justcallmesu.aristotle.ui.theme.AristotleTextStyles
import io.github.justcallmesu.aristotle.ui.theme.ButtonShape
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
    // Edge-to-edge (D8): the surface runs under both system bars; only the list scrolls under the navigation bar.
    val bottom = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
                if (state.searching) SearchBar(state.query, actions) else TopBar(state, address, onSettings, onScheduled, actions, terminalAvailable, onTerminal)
                val page = pageFor(state)
                if (page != null) {
                    Box(modifier = Modifier.windowInsetsPadding(bottom)) { ConnectionPage(page.kind, page.details, address, onRetry, onPairAgain) }
                } else {
                    val offline = state.link as? LinkState.Offline
                    offline?.let { OfflineBanner(it, address, state.listedAt, timeText, onRetry) }
                    CompatibilityBanner()
                    ListBody(state, address, bottom.asPaddingValues(), actions)
                }
            }
            if (!state.searching && state.link is LinkState.Online && pageFor(state) == null) {
                NewSessionButton(busy = state.creating, onClick = actions::newSession, modifier = Modifier.align(Alignment.BottomEnd).windowInsetsPadding(bottom))
            }
            SnackbarHost(hostState = snackbar, modifier = Modifier.align(Alignment.BottomCenter).windowInsetsPadding(bottom))
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
private fun ListBody(state: SessionsUiState, address: String, insets: PaddingValues, actions: ListActions) {
    val items = state.items
    when {
        items == null -> if (state.link !is LinkState.Offline) ConnectingBody(address)
        items.isEmpty() -> EmptyBody()
        else -> PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = actions::refresh, modifier = Modifier.fillMaxSize()) {
            SessionList(items, state.searching, state.staleAt, insets, actions)
        }
    }
}

@Composable
private fun TopBar(
    state: SessionsUiState,
    address: String,
    onSettings: () -> Unit,
    onScheduled: (ScheduledRequest) -> Unit,
    actions: ListActions,
    terminalAvailable: Boolean,
    onTerminal: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Sizes.topBarHeight)
            .padding(start = Spacing.l, end = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = stringResource(R.string.app_name), style = AristotleTextStyles.wordmark)
            ConnectionLine(state.link, address)
        }
        state.profileName?.let { ProfileChip(it, enabled = state.link is LinkState.Online, onClick = actions::openProfiles) }
        IconButton(onClick = actions::openSearch, enabled = state.items != null) {
            Icon(painter = painterResource(R.drawable.ic_search), contentDescription = stringResource(R.string.sessions_search), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OverflowMenu(
            entries = listMenuEntries(terminalAvailable),
            onEntry = { entry ->
                when (entry) {
                    // T18: the listed profile's Hermes cron jobs.
                    ListMenuEntry.Scheduled -> onScheduled(ScheduledRequest(state.profile, state.profileName))
                    ListMenuEntry.Terminal -> onTerminal()
                    ListMenuEntry.Settings -> onSettings()
                }
            },
        )
    }
}

/** Q70: the ⋮ menu holding Scheduled, Terminal (only when shown) and Settings. */
@Composable
private fun OverflowMenu(entries: List<ListMenuEntry>, onEntry: (ListMenuEntry) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(painter = painterResource(R.drawable.ic_more_vert), contentDescription = stringResource(R.string.sessions_more), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            entries.forEach { entry ->
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(
                                when (entry) {
                                    ListMenuEntry.Scheduled -> R.string.cron_open
                                    ListMenuEntry.Terminal -> R.string.terminal_open
                                    ListMenuEntry.Settings -> R.string.settings_title
                                },
                            ),
                        )
                    },
                    onClick = { open = false; onEntry(entry) },
                )
            }
        }
    }
}

/** design.md §5 profile chip: `FilterChip`, small shape, the active profile's name only; opens the switcher. */
@Composable
private fun ProfileChip(name: String, enabled: Boolean, onClick: () -> Unit) {
    val description = stringResource(R.string.sessions_profile_description, name)
    // Mockup `.chip`: 32 dp, 1 dp outlineVariant border, 14/600 label, 12 start / 8 end, 18 dp trailing icon.
    FilterChip(
        selected = false,
        onClick = onClick,
        enabled = enabled,
        label = { Text(text = name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingIcon = {
            Icon(
                painter = painterResource(R.drawable.ic_arrow_drop_down),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(Sizes.chipIcon),
            )
        },
        shape = MaterialTheme.shapes.small,
        colors = FilterChipDefaults.filterChipColors(),
        border = FilterChipDefaults.filterChipBorder(enabled = enabled, selected = false, borderColor = MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.semantics { contentDescription = description },
    )
}

/** The search field replaces the top bar; it filters the titles loaded on this phone (the relay has no search). */
@Composable
private fun SearchBar(query: String, actions: ListActions) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val colors = MaterialTheme.colorScheme
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = Sizes.topBarHeight).padding(horizontal = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = actions::closeSearch) {
                Icon(painter = painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.sessions_search_close), tint = colors.onSurfaceVariant)
            }
            val description = stringResource(R.string.sessions_search)
            BasicTextField(
                value = query,
                onValueChange = actions::search,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
                cursorBrush = SolidColor(colors.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.weight(1f).focusRequester(focus).semantics { contentDescription = description },
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart, modifier = Modifier.padding(horizontal = Spacing.s)) {
                        if (query.isEmpty()) Text(stringResource(R.string.sessions_search), style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
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
        Text(
            text = stringResource(R.string.sessions_search_scope),
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(start = Spacing.listGutter, end = Spacing.listGutter, bottom = Spacing.s),
        )
    }
}

/** "192.168.1.23 · via Wi-Fi", "… · via Tailscale", or "… · offline" with the cloud-off icon. */
@Composable
private fun ConnectionLine(link: LinkState, address: String) {
    val offline = link is LinkState.Offline
    val text = when {
        link is LinkState.Online && link.endpoint.network == Network.Tailscale -> stringResource(R.string.sessions_via_tailscale, address)
        link is LinkState.Online -> stringResource(R.string.sessions_via_wifi, address)
        offline -> stringResource(R.string.sessions_offline_line, address)
        else -> address
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        if (offline) {
            Icon(
                painter = painterResource(R.drawable.ic_cloud_off),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(Sizes.lineIcon),
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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
private fun SessionList(items: List<SessionListItem>, searching: Boolean, staleAt: String?, insets: PaddingValues, actions: ListActions) {
    val list = rememberLazyListState()
    LoadMoreAtTheEnd(list, items, searching, actions::loadMore)
    // Room under the last row for the extended FAB (56 dp + its 16 dp margin).
    val padding = PaddingValues(bottom = insets.calculateBottomPadding() + FAB_CLEARANCE)
    LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = padding) {
        items(items, key = ::itemKey) { item ->
            when (item) {
                is SessionListItem.Header -> SectionHeader(item.section)
                is SessionListItem.Row -> SessionRowItem(item.row, staleAt, actions)
                is SessionListItem.Footer -> HistoryFooter(item.end, actions::loadMore, actions::retryHistory)
                SessionListItem.NoMatch -> QuietLine(stringResource(R.string.sessions_search_none))
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

private fun itemKey(item: SessionListItem): String = when (item) {
    is SessionListItem.Header -> "header-${item.section}"
    is SessionListItem.Row -> "row-${item.row.key}"
    is SessionListItem.Footer -> "footer"
    SessionListItem.NoMatch -> "no-match"
}

@Composable
private fun SectionHeader(section: ListSection) {
    Text(
        text = stringResource(
            when (section) {
                ListSection.Pinned -> R.string.sessions_section_pinned
                ListSection.Live -> R.string.sessions_section_live
                ListSection.History -> R.string.sessions_section_history
            },
        ),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .padding(start = Spacing.listGutter, end = Spacing.listGutter, top = Spacing.l, bottom = Spacing.s)
            .semantics { heading() },
    )
}

/** design.md §5 session row: 72 dp, title + one status line, trailing time, pin as leading icon. Long press: actions. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionRowItem(row: SessionRow, staleAt: String?, actions: ListActions) {
    val more = stringResource(R.string.session_actions)
    // A plain Row, not ListItem: M3 centres the trailing slot, the mockup top-aligns the time with the title (L3).
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = { actions.open(row) }, onLongClick = { actions.showActions(row) }, role = Role.Button)
            .semantics { onLongClick(label = more) { actions.showActions(row); true } }
            .heightIn(min = Sizes.sessionRowHeight)
            .padding(horizontal = Spacing.listGutter, vertical = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (row.pinned) {
            Icon(
                painter = painterResource(R.drawable.ic_push_pin),
                contentDescription = stringResource(R.string.session_pinned),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = Spacing.l).size(Sizes.chipIcon),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.title ?: stringResource(R.string.session_untitled),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            RowStatus(row, staleAt)
        }
        row.time?.let { time ->
            Column(modifier = Modifier.padding(start = Spacing.l).align(Alignment.Top)) {
                RowTimeText(time, staleAt, Modifier.padding(top = Spacing.xs))
            }
        }
    }
}

@Composable
private fun RowStatus(row: SessionRow, staleAt: String?) {
    if (row.running && staleAt != null) {
        Text(
            text = stringResource(R.string.session_was_running, staleAt),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    } else if (row.running) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            RunningSpinner()
            Text(
                text = stringResource(R.string.session_running),
                style = AristotleTextStyles.liveStatus,
                color = MaterialTheme.colorScheme.tertiary,
                maxLines = 1,
            )
        }
    } else {
        Text(
            text = pluralStringResource(R.plurals.session_messages, row.messageCount, row.messageCount),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun RowTimeText(time: RowTime, staleAt: String?, modifier: Modifier = Modifier) {
    Text(
        text = when (time) {
            RowTime.Now -> staleAt ?: stringResource(R.string.session_now)
            is RowTime.At -> time.text
        },
        style = AristotleTextStyles.tabular(MaterialTheme.typography.bodySmall),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
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

/** design.md §3/§4: extended FAB, `large` shape, the only shadowed control on the list. Shown only while online. */
@Composable
private fun NewSessionButton(busy: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    ExtendedFloatingActionButton(
        onClick = { if (!busy) onClick() },
        icon = { Icon(painter = painterResource(R.drawable.ic_add), contentDescription = null) },
        text = { Text(stringResource(if (busy) R.string.sessions_new_busy else R.string.sessions_new)) },
        shape = MaterialTheme.shapes.large,
        modifier = modifier.padding(Spacing.l),
    )
}

@Composable
private fun sessionsMessage(message: SessionsMessage): String = when (message) {
    is SessionsMessage.RenameFailed -> stringResource(R.string.session_rename_failed, message.reason)
    is SessionsMessage.CreateFailed -> stringResource(R.string.sessions_new_failed, message.reason)
    is SessionsMessage.ProfileFellBack -> stringResource(R.string.sessions_profile_fell_back, message.name)
}

private val FAB_CLEARANCE = Sizes.minTouchTarget + Spacing.xxl + Spacing.l
