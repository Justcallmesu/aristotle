package io.github.justcallmesu.aristotle.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.justcallmesu.aristotle.R
import io.github.justcallmesu.aristotle.data.chat.ChatItem
import io.github.justcallmesu.aristotle.data.chat.DelegateRow
import io.github.justcallmesu.aristotle.data.chat.DelegateStatus
import io.github.justcallmesu.aristotle.data.chat.Subagent
import io.github.justcallmesu.aristotle.data.chat.SubagentStatus
import io.github.justcallmesu.aristotle.ui.components.RunningSpinner
import io.github.justcallmesu.aristotle.ui.theme.AristotleTextStyles
import io.github.justcallmesu.aristotle.ui.theme.ButtonShape
import io.github.justcallmesu.aristotle.ui.theme.Sizes
import io.github.justcallmesu.aristotle.ui.theme.Spacing
import kotlinx.coroutines.delay

/** What the subagent page can do (T17). */
interface SubagentActions {
    fun close()
    fun stop(id: String)
    fun messageShown()
}

/**
 * A `delegate_task` call as the fan-out it is (the Desktop's delegate card, `delegate.tsx:67-166`): one row per child,
 * goal and model on top, where it stands and its last line below. A row the roster knows opens the child's page.
 * Verdigris only while a child runs and the chat is live (design.md §1).
 */
@Composable
internal fun DelegateCard(item: ChatItem.Tool, live: Boolean, onOpen: ((String) -> Unit)?) {
    val running = live && item.subagents.any { it.status.live }
    val now = wallClockTicking(running)
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column {
            item.subagents.forEachIndexed { index, row ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = Sizes.divider)
                val id = row.subagentId
                val open: (() -> Unit)? = if (id != null && onOpen != null) { { onOpen(id) } } else null
                DelegateRowView(row, live, now, open)
            }
        }
    }
}

@Composable
private fun DelegateRowView(row: DelegateRow, live: Boolean, now: Long, onOpen: (() -> Unit)?) {
    val openLabel = stringResource(R.string.subagent_open)
    val clickable = onOpen?.let { Modifier.clickable(role = Role.Button, onClickLabel = openLabel, onClick = it) } ?: Modifier
    Row(
        modifier = Modifier.fillMaxWidth().then(clickable).heightIn(min = Sizes.minTouchTarget).padding(horizontal = Spacing.m, vertical = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        StatusGlyph(row.status, live)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = row.goal, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            MetaLine(row, live, now)
            row.lastLine?.let {
                Text(
                    text = it,
                    style = AristotleTextStyles.toolSummary,
                    // Red only on surfaceContainerLow (design.md §1 contrast trap); this card is that surface.
                    color = if (row.lastLineError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (onOpen != null) {
            Icon(painter = painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(Sizes.toolIcon))
        }
    }
}

/** "Running · 1 m 05 s · model" while live (verdigris), else "Done · model · 12 s". */
@Composable
private fun MetaLine(row: DelegateRow, live: Boolean, now: Long) {
    val running = live && row.status.live
    val elapsed = row.startedAtMillis?.takeIf { running }?.let { elapsedText((now - it).coerceAtLeast(0) / 1000) }
    val duration = row.durationSeconds?.takeIf { !running }?.let { elapsedText(it.toLong()) }
    val status = statusLabel(row.status)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = listOfNotNull(status, elapsed).joinToString(" · "),
            style = if (running) AristotleTextStyles.liveStatusSmall else MaterialTheme.typography.bodySmall,
            color = when {
                running -> MaterialTheme.colorScheme.tertiary
                row.status.red -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
        )
        val details = listOfNotNull(row.model, duration).joinToString(" · ")
        if (details.isNotEmpty()) {
            Text(
                text = "· $details",
                style = AristotleTextStyles.tabular(MaterialTheme.typography.bodySmall),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun StatusGlyph(status: DelegateStatus, live: Boolean) {
    Box(modifier = Modifier.size(Sizes.toolIcon), contentAlignment = Alignment.Center) {
        when (status) {
            // No spinner while the chat is not live (offline, or the session could not be reattached).
            DelegateStatus.Running, DelegateStatus.Queued -> if (live) {
                val label = statusLabel(status)
                RunningSpinner(modifier = Modifier.semantics { stateDescription = label })
            }
            DelegateStatus.Failed ->
                Icon(painter = painterResource(R.drawable.ic_error), contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(Sizes.chipIcon))
            DelegateStatus.Completed, DelegateStatus.Interrupted ->
                Icon(painter = painterResource(R.drawable.ic_check_circle), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(Sizes.chipIcon))
            // Parked, not watched: no spinner would be honest here (delegate.tsx:47-52).
            DelegateStatus.Dispatched ->
                Icon(painter = painterResource(R.drawable.ic_schedule), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(Sizes.chipIcon))
        }
    }
}

@Composable
private fun statusLabel(status: DelegateStatus): String = stringResource(
    when (status) {
        DelegateStatus.Queued -> R.string.subagent_status_queued
        DelegateStatus.Running -> R.string.subagent_status_running
        DelegateStatus.Dispatched -> R.string.subagent_status_dispatched
        DelegateStatus.Completed -> R.string.subagent_status_completed
        DelegateStatus.Failed -> R.string.subagent_status_failed
        DelegateStatus.Interrupted -> R.string.subagent_status_interrupted
    },
)

private fun SubagentStatus.asRow(): DelegateStatus = when (this) {
    SubagentStatus.Queued -> DelegateStatus.Queued
    SubagentStatus.Running -> DelegateStatus.Running
    SubagentStatus.Completed -> DelegateStatus.Completed
    SubagentStatus.Failed -> DelegateStatus.Failed
    SubagentStatus.Interrupted -> DelegateStatus.Interrupted
}

/** "12 s", or "3 m 05 s" from a minute on. */
@Composable
private fun elapsedText(seconds: Long): String =
    if (seconds < SECONDS_PER_MINUTE) {
        stringResource(R.string.subagent_seconds, seconds.toInt())
    } else {
        stringResource(R.string.subagent_minutes, (seconds / SECONDS_PER_MINUTE).toInt(), (seconds % SECONDS_PER_MINUTE).toInt())
    }

/** The wall clock, ticking once a second while [ticking] (a running child's elapsed time). */
@Composable
private fun wallClockTicking(ticking: Boolean): Long {
    val now by produceState(System.currentTimeMillis(), ticking) {
        value = System.currentTimeMillis()
        while (ticking) {
            delay(TICK_MS)
            value = System.currentTimeMillis()
        }
    }
    return now
}

/**
 * One child, opened (T17): its status, Stop while it runs, then what it did as chat rows (the parent's `subagent.*`
 * events, projected as Hermes' child mirror does) and its live transcript (`subagent.tail`). Read-only: nothing here
 * types into the child. Back closes it.
 */
@Composable
internal fun SubagentPage(child: Subagent, page: SubagentPageState, live: Boolean, actions: SubagentActions) {
    BackHandler(onBack = actions::close)
    val snackbar = remember { SnackbarHostState() }
    val message = page.message
    val messageText = message?.let { subagentMessage(it) }
    LaunchedEffect(message) {
        if (messageText != null) {
            snackbar.showSnackbar(messageText)
            actions.messageShown()
        }
    }
    val running = live && child.status.live
    val now = wallClockTicking(running)
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
            PageTopBar(child, running, now, actions::close)
            if (child.status.live) {
                OutlinedButton(
                    onClick = { actions.stop(child.id) },
                    enabled = live && page.shown?.armed == true && !page.stopping,
                    shape = ButtonShape,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.padding(horizontal = Spacing.listGutter, vertical = Spacing.xs),
                ) {
                    Icon(painter = painterResource(R.drawable.ic_stop), contentDescription = null, modifier = Modifier.size(Sizes.chipIcon))
                    Text(text = stringResource(R.string.subagent_stop), modifier = Modifier.padding(start = Spacing.s))
                }
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                PageBody(child, page, running)
                SnackbarHost(hostState = snackbar, modifier = Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)))
            }
        }
    }
}

@Composable
private fun PageTopBar(child: Subagent, running: Boolean, now: Long, onBack: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().heightIn(min = Sizes.topBarHeight).padding(horizontal = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(painter = painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.nav_back))
        }
        Column(modifier = Modifier.weight(1f).padding(horizontal = Spacing.xs)) {
            Text(text = child.goal, style = AristotleTextStyles.chatTitle, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (running) RunningSpinner()
                val status = statusLabel(child.status.asRow())
                val time = if (running) elapsedText((now - child.startedAtMillis).coerceAtLeast(0) / 1000) else child.durationSeconds?.let { elapsedText(it.toLong()) }
                Text(
                    text = listOfNotNull(status, time).joinToString(" · "),
                    style = if (running) AristotleTextStyles.liveStatusSmall else MaterialTheme.typography.bodySmall,
                    color = if (running) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                child.model?.let {
                    Text(text = "· $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun PageBody(child: Subagent, page: SubagentPageState, running: Boolean) {
    val folds = rememberFolds()
    val bottom = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)
    LazyColumn(
        modifier = Modifier.fillMaxSize().windowInsetsPadding(bottom),
        contentPadding = PaddingValues(horizontal = Spacing.listGutter, vertical = Spacing.s),
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        item(key = "hint") {
            Text(text = stringResource(R.string.subagent_read_only), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item(key = "activity") { SectionLabel(stringResource(R.string.subagent_activity)) }
        if (child.mirror.isEmpty()) {
            item(key = "waiting") { SectionNote(stringResource(R.string.subagent_waiting)) }
        }
        items(child.mirror, key = { it.key }, contentType = { it::class }) { item -> ChatRow(item, folds, lastFailedTool = null, live = running) }
        item(key = "transcript") { SectionLabel(stringResource(R.string.subagent_transcript)) }
        item(key = "tail") { TailBlock(page) }
    }
}

/** The tail in a Highest block, mono, wrapped (a log line is long); text stays selectable. */
@Composable
private fun TailBlock(page: SubagentPageState) {
    val tail = page.tail
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        if (tail?.truncated == true) SectionNote(stringResource(R.string.subagent_transcript_truncated))
        if (tail?.available == true) {
            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.fillMaxWidth()) {
                SelectionContainer {
                    Text(text = tail.text, style = AristotleTextStyles.toolOutput, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(horizontal = Spacing.m, vertical = 10.dp))
                }
            }
        } else {
            SectionNote(stringResource(if (tail == null) R.string.subagent_waiting else R.string.subagent_transcript_unavailable))
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text = text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.semantics { heading() })
}

@Composable
private fun SectionNote(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun subagentMessage(message: SubagentMessage): String = when (message) {
    SubagentMessage.StopRequested -> stringResource(R.string.subagent_stop_requested)
    SubagentMessage.Rejected -> stringResource(R.string.subagent_stop_rejected)
    SubagentMessage.NotSent -> stringResource(R.string.subagent_stop_not_sent)
}

private const val SECONDS_PER_MINUTE = 60L
private const val TICK_MS = 1_000L
