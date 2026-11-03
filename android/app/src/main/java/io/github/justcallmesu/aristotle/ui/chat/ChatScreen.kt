package io.github.justcallmesu.aristotle.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
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
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.justcallmesu.aristotle.R
import io.github.justcallmesu.aristotle.data.LinkState
import io.github.justcallmesu.aristotle.data.chat.ChatItem
import io.github.justcallmesu.aristotle.data.chat.ChatState
import io.github.justcallmesu.aristotle.data.chat.ToolStatus
import io.github.justcallmesu.aristotle.ui.components.RunningSpinner
import io.github.justcallmesu.aristotle.ui.theme.AristotleTextStyles
import io.github.justcallmesu.aristotle.ui.theme.ButtonShape
import io.github.justcallmesu.aristotle.ui.theme.Sizes
import io.github.justcallmesu.aristotle.ui.theme.Spacing
import kotlinx.coroutines.launch

@Composable
fun ChatScreen(viewModel: ChatViewModel, fallbackTitle: String?, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ChatContentView(state = state, fallbackTitle = fallbackTitle, onBack = onBack, onRetry = viewModel::retry)
}

/**
 * design.md §5 chat, mockup screen 3 (read-only until T13): top bar with the session, the transcript, and a
 * placeholder chip while a prompt waits (T12). Edge-to-edge: the list scrolls under the navigation bar.
 */
@Composable
fun ChatContentView(state: ChatUiState, fallbackTitle: String?, onBack: () -> Unit, onRetry: () -> Unit) {
    val feed = state.feed
    val chat = feed.chat
    val live = ChatLive.of(feed, state.link)
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))) {
            ChatTopBar(chat, state.link, live, fallbackTitle, onBack)
            if (ChatLive.reattachFailed(feed)) ReattachBanner(onRetry)
            val bottom = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal).asPaddingValues()
            when {
                feed.loaded && chat.items.isEmpty() && !chat.running && chat.openPrompts.isEmpty() -> QuietLine(stringResource(R.string.chat_empty))
                feed.loaded -> Transcript(chat, live = live, insets = bottom)
                feed.problem != null -> LoadFailed(feed.problem, onRetry)
                else -> QuietLine(stringResource(R.string.chat_loading))
            }
        }
    }
}

@Composable
private fun ChatTopBar(chat: ChatState, link: LinkState, live: Boolean, fallbackTitle: String?, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = Sizes.topBarHeight).padding(horizontal = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(painter = painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.nav_back))
        }
        Column(modifier = Modifier.weight(1f).padding(horizontal = Spacing.xs)) {
            Text(
                text = chat.title ?: fallbackTitle ?: stringResource(R.string.session_untitled),
                style = AristotleTextStyles.chatTitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
            Subtitle(chat, live = live)
        }
        linkLabel(link)?.let { LinkChip(it, offline = link is LinkState.Offline) }
    }
}

/** "Running · step N" in verdigris with the spinner, then profile and model; no live colour unless [live]. */
@Composable
private fun Subtitle(chat: ChatState, live: Boolean) {
    val details = listOfNotNull(chat.profileName, chat.model).joinToString(" · ")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (chat.running && live) {
            RunningSpinner()
            Text(
                text = chat.step?.let { stringResource(R.string.chat_running_step, it) } ?: stringResource(R.string.session_running),
                style = AristotleTextStyles.liveStatusSmall,
                color = MaterialTheme.colorScheme.tertiary,
                maxLines = 1,
            )
        }
        if (details.isNotEmpty()) {
            Text(
                text = if (chat.running && live) "· $details" else details,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The link state when it is not plain online; null hides the chip. */
@Composable
private fun linkLabel(link: LinkState): String? = when (link) {
    is LinkState.Online, LinkState.Revoked -> null
    LinkState.Idle, LinkState.Connecting -> stringResource(R.string.chat_link_connecting)
    is LinkState.Offline -> stringResource(if (link.trying) R.string.chat_link_reconnecting else R.string.chat_link_offline)
    is LinkState.HermesDown -> stringResource(R.string.chat_link_hermes_down)
    is LinkState.HermesUpdated -> stringResource(R.string.chat_link_hermes_updated)
    LinkState.Stopped -> stringResource(R.string.chat_link_stopped)
}

@Composable
private fun LinkChip(label: String, offline: Boolean) {
    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.padding(end = Spacing.s)) {
        Row(
            modifier = Modifier.heightIn(min = 32.dp).padding(horizontal = Spacing.s),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            if (offline) {
                Icon(
                    painter = painterResource(R.drawable.ic_cloud_off),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(Sizes.lineIcon),
                )
            }
            Text(text = label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * The message list. It follows new content while the user is at the bottom; scrolling up stops that and
 * shows "Jump to latest". Stable keys: a delta recomposes only the last row.
 */
@Composable
private fun Transcript(chat: ChatState, live: Boolean, insets: PaddingValues) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val follow = remember { mutableStateOf(true) }
    val folds = rememberFolds()
    val lastFailed = remember(chat.items) { chat.items.lastOrNull { it is ChatItem.Tool && it.status == ToolStatus.Failed }?.key }
    val thinking = live && chat.running && chat.thinking && !chat.streaming
    val waiting = chat.openPrompts.isNotEmpty()
    val userScroll = remember(listState) { UserScrollUp(listState, follow) }
    LaunchedEffect(listState) {
        // At the bottom: follow again. Content grew under a follower (a new chunk, a Markdown block that finished
        // parsing, a row that unfolded): keep the end in view.
        snapshotFlow { listState.canScrollForward }.collect { more ->
            if (!more) follow.value = true else if (follow.value) listState.scrollToEnd()
        }
    }
    LaunchedEffect(chat.items.size, chat.items.lastOrNull(), thinking, waiting) {
        if (follow.value) listState.scrollToEnd()
    }
    val direction = LocalLayoutDirection.current
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().nestedScroll(userScroll),
            contentPadding = PaddingValues(
                start = Spacing.listGutter + insets.calculateStartPadding(direction),
                end = Spacing.listGutter + insets.calculateEndPadding(direction),
                top = Spacing.s,
                bottom = Spacing.l + insets.calculateBottomPadding(),
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            items(chat.items, key = { it.key }, contentType = { it::class }) { item -> ChatRow(item, folds, lastFailed, live) }
            if (thinking) item(key = "thinking") { ThinkingLine() }
            if (waiting) item(key = "waiting") { WaitingChip() }
        }
        if (!follow.value && listState.canScrollForward) {
            FilledTonalButton(
                onClick = {
                    follow.value = true
                    scope.launch { listState.scrollToEnd() }
                },
                shape = ButtonShape,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = Spacing.l + insets.calculateBottomPadding()),
            ) {
                Icon(painter = painterResource(R.drawable.ic_arrow_downward), contentDescription = null, modifier = Modifier.size(Sizes.chipIcon))
                Spacer(Modifier.size(Spacing.s))
                Text(stringResource(R.string.chat_jump_latest))
            }
        }
    }
}

/** A drag or fling towards older messages (only the user's, programmatic scrolls do not pass here). */
private class UserScrollUp(private val list: LazyListState, private val follow: MutableState<Boolean>) : NestedScrollConnection {
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (available.y > 0f && list.canScrollBackward) follow.value = false
        return Offset.Zero
    }
}

/** To the very end of the last item (a long streaming message is taller than the screen). */
private suspend fun LazyListState.scrollToEnd() {
    val last = layoutInfo.totalItemsCount - 1
    if (last < 0) return
    scrollToItem(last)
    scrollBy(END_SCROLL_PX)
}

private const val END_SCROLL_PX = 1_000_000f

@Composable
private fun rememberFolds(): FoldState {
    val opened = rememberSaveable(saver = foldSaver) { mutableStateMapOf() }
    return remember(opened) {
        object : FoldState {
            override fun isOpen(key: String, default: Boolean): Boolean = opened[key] ?: default
            override fun toggle(key: String, default: Boolean) {
                opened[key] = !(opened[key] ?: default)
            }
        }
    }
}

private val foldSaver = listSaver<SnapshotStateMap<String, Boolean>, String>(
    save = { map -> map.flatMap { (key, open) -> listOf(key, open.toString()) } },
    restore = { flat -> mutableStateMapOf<String, Boolean>().apply { flat.chunked(2).forEach { (key, open) -> put(key, open.toBoolean()) } } },
)

@Composable
private fun QuietLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Spacing.listGutter, vertical = Spacing.xxl),
    )
}

/**
 * design.md §5 banner under the top bar: Hermes refused (or did not answer) the resume on this socket, so the
 * transcript below is what was loaded and no longer follows the session. Retry subscribes again.
 */
@Composable
private fun ReattachBanner(onRetry: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.m, vertical = Spacing.xs),
    ) {
        Column {
            Row(modifier = Modifier.padding(start = Spacing.l, end = Spacing.m, top = Spacing.m)) {
                Icon(
                    painter = painterResource(R.drawable.ic_error),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(Sizes.fieldIcon),
                )
                Column(modifier = Modifier.padding(start = Spacing.m).weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Text(
                        text = stringResource(R.string.chat_reattach_failed_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    Text(
                        text = stringResource(R.string.chat_reattach_failed_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(modifier = Modifier.fillMaxWidth().padding(end = Spacing.xs), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onRetry, shape = ButtonShape) { Text(stringResource(R.string.chat_reattach_retry)) }
            }
        }
    }
}

/** design.md §5 connection-page form: left-aligned headline, one paragraph, mono details, one button. */
@Composable
private fun LoadFailed(details: String, onRetry: () -> Unit) {
    Column(modifier = Modifier.padding(horizontal = Spacing.xxl, vertical = Spacing.xxl), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
        Text(text = stringResource(R.string.chat_load_failed_title), style = MaterialTheme.typography.headlineSmall)
        Text(text = stringResource(R.string.chat_load_failed_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = details, style = AristotleTextStyles.details, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(Spacing.s))
        Button(onClick = onRetry, shape = ButtonShape) { Text(stringResource(R.string.conn_retry_now)) }
    }
}
