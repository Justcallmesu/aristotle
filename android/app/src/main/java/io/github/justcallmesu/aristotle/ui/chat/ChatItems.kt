package io.github.justcallmesu.aristotle.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.justcallmesu.aristotle.R
import io.github.justcallmesu.aristotle.data.chat.ChatItem
import io.github.justcallmesu.aristotle.data.chat.NoticeKind
import io.github.justcallmesu.aristotle.data.chat.ToolStatus
import io.github.justcallmesu.aristotle.ui.components.RunningSpinner
import io.github.justcallmesu.aristotle.ui.components.reducedMotion
import io.github.justcallmesu.aristotle.ui.theme.AristotleTextStyles
import io.github.justcallmesu.aristotle.ui.theme.ButtonShape
import io.github.justcallmesu.aristotle.ui.theme.Motion
import io.github.justcallmesu.aristotle.ui.theme.Sizes
import io.github.justcallmesu.aristotle.ui.theme.Spacing

/** Tool output shows this many lines until "Show all" (T11 brief). */
const val OUTPUT_PREVIEW_LINES = 40

/** User bubbles take at most this share of the width (design.md §5). */
private const val BUBBLE_MAX_FRACTION = 0.82f
private const val TURNED = 180f

/** Expand state of the rows that fold (reasoning, tool cards), kept by the screen. */
interface FoldState {
    fun isOpen(key: String, default: Boolean): Boolean
    fun toggle(key: String, default: Boolean)
}

/** [live]: the cursor and tool spinners show only then ([ChatLive]). */
@Composable
fun ChatRow(item: ChatItem, folds: FoldState, lastFailedTool: String?, live: Boolean) {
    when (item) {
        is ChatItem.User -> UserBubble(item.text)
        is ChatItem.Assistant -> ChatMarkdown(item.text, streaming = item.streaming && live)
        is ChatItem.Reasoning -> ReasoningRow(item, folds)
        is ChatItem.Tool -> ToolCard(item, folds, openByDefault = item.key == lastFailedTool, live = live)
        is ChatItem.Notice -> NoticeRow(item)
    }
}

/** The user's own message (design.md §5): right-aligned `secondaryContainer` bubble, at most 82 % wide. */
@Composable
internal fun UserBubble(text: String) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp, bottomEnd = 4.dp, bottomStart = 14.dp),
            modifier = Modifier.align(Alignment.CenterEnd).widthIn(max = maxWidth * BUBBLE_MAX_FRACTION),
        ) {
            SelectionContainer {
                Text(text = text, style = AristotleTextStyles.chatText, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
            }
        }
    }
}

/** design.md §5: "Thought for N s" + chevron, collapsed by default; "Thinking…" while it streams. */
@Composable
private fun ReasoningRow(item: ChatItem.Reasoning, folds: FoldState) {
    val open = folds.isOpen(item.key, default = false)
    val label = when {
        item.streaming -> stringResource(R.string.chat_thinking)
        item.durationMillis != null -> stringResource(R.string.chat_thought_for, ChatFormat.thoughtSeconds(item.durationMillis))
        else -> stringResource(R.string.chat_reasoning)
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        FoldHeader(open = open, onToggle = { folds.toggle(item.key, default = false) }, minHeight = Sizes.minTouchTarget) {
            Text(text = label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Chevron(open)
        }
        Fold(open) {
            Text(
                text = item.text,
                style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = Spacing.xs),
            )
        }
    }
}

/** design.md §5 tool card: header ≥ 48 dp (icon, name, args, duration or Failed, chevron); output below. */
@Composable
private fun ToolCard(item: ChatItem.Tool, folds: FoldState, openByDefault: Boolean, live: Boolean) {
    val open = folds.isOpen(item.key, openByDefault)
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column {
            FoldHeader(
                open = open,
                onToggle = { folds.toggle(item.key, openByDefault) },
                minHeight = Sizes.minTouchTarget,
                modifier = Modifier.padding(horizontal = Spacing.m, vertical = 6.dp),
            ) {
                Icon(
                    painter = painterResource(toolIconRes(item.name)),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(Sizes.toolIcon),
                )
                Text(text = item.name, style = AristotleTextStyles.toolName, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                Text(
                    text = item.progress ?: item.summary,
                    style = AristotleTextStyles.toolSummary,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                ToolTrailing(item, live)
                Chevron(open)
            }
            Fold(open) { ToolOutput(item, folds) }
        }
    }
}

@Composable
private fun ToolTrailing(item: ChatItem.Tool, live: Boolean) {
    val locale = LocalConfiguration.current.locales[0]
    val duration = item.durationSeconds?.let { stringResource(R.string.chat_tool_seconds, ChatFormat.seconds(it, locale)) }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        when (item.status) {
            // No spinner while the chat is not live (offline, or the session could not be reattached).
            ToolStatus.Running -> if (live) {
                val running = stringResource(R.string.chat_tool_running)
                RunningSpinner(modifier = Modifier.semantics { stateDescription = running })
            }
            // Red only on surfaceContainerLow (design.md §1 contrast trap), never on the Highest output block.
            ToolStatus.Failed -> Text(
                text = stringResource(R.string.chat_tool_failed),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
            )
            ToolStatus.Done -> Unit
        }
        duration?.let {
            Text(text = it, style = AristotleTextStyles.tabular(MaterialTheme.typography.bodySmall), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Output in a Highest block, mono 11.5 / 17, no wrap, scrolls sideways; the first lines until "Show all". */
@Composable
private fun ToolOutput(item: ChatItem.Tool, folds: FoldState) {
    if (item.output.isEmpty() && item.status == ToolStatus.Running) return // nothing to show until it ends
    val lines = remember(item.output) { item.output.lines() }
    val allKey = "${item.key}#all"
    val showAll = folds.isOpen(allKey, default = false) || lines.size <= OUTPUT_PREVIEW_LINES
    val shown = remember(item.output, showAll) { if (showAll) item.output else lines.take(OUTPUT_PREVIEW_LINES).joinToString("\n") }
    Column(modifier = Modifier.fillMaxWidth().padding(start = Spacing.m, end = Spacing.m, bottom = 6.dp)) {
        Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.fillMaxWidth()) {
            val empty = item.output.isEmpty()
            SelectionContainer {
                Text(
                    text = if (empty) stringResource(R.string.chat_tool_no_output) else shown,
                    style = AristotleTextStyles.toolOutput,
                    color = if (empty) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    softWrap = false,
                    modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = Spacing.m, vertical = 10.dp),
                )
            }
        }
        if (!showAll) {
            TextButton(onClick = { folds.toggle(allKey, default = false) }, shape = ButtonShape) {
                Text(pluralStringResource(R.plurals.chat_tool_show_all, lines.size, lines.size))
            }
        }
    }
}

@Composable
private fun NoticeRow(item: ChatItem.Notice) {
    when (item.kind) {
        NoticeKind.Stopped -> Text(
            text = stringResource(R.string.chat_turn_stopped),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        NoticeKind.TurnFailed, NoticeKind.Error -> Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Icon(
                painter = painterResource(R.drawable.ic_error),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(Sizes.chipIcon),
            )
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(
                    text = stringResource(if (item.kind == NoticeKind.TurnFailed) R.string.chat_turn_failed else R.string.chat_session_error),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (item.text.isNotBlank()) {
                    Text(text = item.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        NoticeKind.Info -> Text(text = item.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** "Thinking…" while the model works and nothing streams yet. */
@Composable
fun ThinkingLine() {
    Text(text = stringResource(R.string.chat_thinking), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** An approval, clarify or password prompt is open (design.md §5): a tap brings the prompt sheet up. */
@Composable
fun WaitingChip(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Row(
            modifier = Modifier.heightIn(min = Sizes.minTouchTarget).padding(start = 10.dp, end = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            Icon(painter = painterResource(R.drawable.ic_pan_tool), contentDescription = null, modifier = Modifier.size(Sizes.chipIcon))
            Text(text = stringResource(R.string.chat_waiting), style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun FoldHeader(
    open: Boolean,
    onToggle: () -> Unit,
    minHeight: Dp,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val state = stringResource(if (open) R.string.chat_collapse else R.string.chat_expand)
    Box(modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = state, onClick = onToggle)) {
        Row(
            modifier = modifier.fillMaxWidth().heightIn(min = minHeight),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}

@Composable
private fun Chevron(open: Boolean) {
    val instant = reducedMotion()
    val angle by animateFloatAsState(
        targetValue = if (open) TURNED else 0f,
        animationSpec = tween(durationMillis = if (instant) 0 else if (open) EXPAND_MILLIS else COLLAPSE_MILLIS),
        label = "chevron",
    )
    Icon(
        painter = painterResource(R.drawable.ic_expand_more),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(Sizes.toolIcon).rotate(angle),
    )
}

/** design.md §4: expandVertically + fadeIn 300 ms emphasized-decelerate; out 200 ms standard-accelerate; instant if reduced. */
@Composable
private fun Fold(open: Boolean, content: @Composable () -> Unit) {
    val instant = reducedMotion()
    val enter = if (instant) 0 else EXPAND_MILLIS
    val exit = if (instant) 0 else COLLAPSE_MILLIS
    AnimatedVisibility(
        visible = open,
        enter = expandVertically(tween(enter, easing = Motion.EmphasizedDecelerate)) + fadeIn(tween(enter, easing = Motion.EmphasizedDecelerate)),
        exit = shrinkVertically(tween(exit, easing = Motion.StandardAccelerate)) + fadeOut(tween(exit, easing = Motion.StandardAccelerate)),
    ) { content() }
}

private fun toolIconRes(name: String): Int = when (toolIcon(name)) {
    ToolIcon.Terminal -> R.drawable.ic_terminal
    ToolIcon.File -> R.drawable.ic_description
    ToolIcon.Generic -> R.drawable.ic_build
}

private const val EXPAND_MILLIS = 300
private const val COLLAPSE_MILLIS = 200
