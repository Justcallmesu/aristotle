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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.justcallmesu.aristotle.R
import io.github.justcallmesu.aristotle.data.chat.ChatItem
import io.github.justcallmesu.aristotle.data.chat.NoticeKind
import io.github.justcallmesu.aristotle.data.chat.ToolStatus
import io.github.justcallmesu.aristotle.data.chat.UserAttachment
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

/** Expand state of the rows that fold (steps lines, step rows, outputs, notification cards), kept by the screen. */
interface FoldState {
    fun isOpen(key: String, default: Boolean): Boolean
    fun toggle(key: String, default: Boolean)
}

/** The gap a [RowGap] stands for (Q67, design.md §4 chat rhythm). */
val RowGap.size: Dp
    get() = when (this) {
        RowGap.None -> 0.dp
        RowGap.Work -> Spacing.chatWorkGap
        RowGap.Message -> Spacing.chatMessageGap
        RowGap.Turn -> Spacing.chatTurnGap
    }

/** A transcript row ([ToolFolding]): one chat item, or a run of work items folded into one "N steps" line (Q75). */
@Composable
fun TranscriptRowView(
    row: TranscriptRow,
    folds: FoldState,
    lastFailedTool: String?,
    live: Boolean,
    attachmentsOf: (ChatItem.User) -> List<UserAttachment> = { it.attachments },
    onOpenSubagent: ((String) -> Unit)? = null,
) {
    when (row) {
        is TranscriptRow.Single -> ChatRow(row.item, folds, lastFailedTool, live, attachmentsOf, onOpenSubagent)
        is TranscriptRow.Folded -> StepsBlock(row, folds, lastFailedTool, live)
    }
}

/** [live]: the cursor and the running spinners show only then ([ChatLive]). */
@Composable
fun ChatRow(
    item: ChatItem,
    folds: FoldState,
    lastFailedTool: String?,
    live: Boolean,
    /** What a user row shows as attachments (its own, plus the images this phone sent with it, T16). */
    attachmentsOf: (ChatItem.User) -> List<UserAttachment> = { it.attachments },
    /** A child row of a `delegate_task` card was tapped (T17); null: the rows do not open. */
    onOpenSubagent: ((String) -> Unit)? = null,
) {
    when (item) {
        is ChatItem.User -> UserBubble(item.text, attachmentsOf(item).map { it.toBubble() })
        is ChatItem.Assistant -> ChatMarkdown(item.text, streaming = item.streaming && live)
        // A delegation reads as its children, not as a blob of JSON (the Desktop's delegate card, T17).
        is ChatItem.Tool -> if (item.subagents.isNotEmpty()) {
            DelegateCard(item, live, onOpenSubagent)
        } else {
            StepsBlock(TranscriptRow.Folded(listOf(item)), folds, lastFailedTool, live)
        }
        // [ToolFolding] folds every step; a lone one handed in here still reads as its own line.
        is ChatItem.Reasoning -> StepsBlock(TranscriptRow.Folded(listOf(item)), folds, lastFailedTool, live)
        is ChatItem.Notice -> NoticeRow(item)
        is ChatItem.Card -> NotificationCard(item, folds)
    }
}

/**
 * Q75, design-notes §5.2: a run of steps as one quiet 36 dp line on `surface` ("5 steps · execute_code"), collapsed by
 * default. While a step runs and the chat is live it reads "Running · N steps" in verdigris with the spinner and the
 * running call's name; failed calls add " · N failed" in red. Open, the steps follow in one `surfaceContainerLow`
 * container of 44 dp rows; a tool row opens its output below it, the reasoning row its text.
 */
@Composable
private fun StepsBlock(group: TranscriptRow.Folded, folds: FoldState, lastFailedTool: String?, live: Boolean) {
    val open = folds.isOpen(group.key, default = false)
    Column(modifier = Modifier.fillMaxWidth()) {
        StepsLine(group.summary(live), open, onToggle = { folds.toggle(group.key, default = false) })
        Fold(open) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.xs),
            ) {
                Column(modifier = Modifier.padding(vertical = Spacing.xs)) {
                    for (step in group.steps) {
                        key(step.key) {
                            when (step) {
                                is ChatItem.Tool -> ToolStep(step, folds, openByDefault = step.key == lastFailedTool, live = live)
                                is ChatItem.Reasoning -> ReasoningStep(step, folds)
                                else -> Unit
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StepsLine(summary: StepsSummary, open: Boolean, onToggle: () -> Unit) {
    val state = stringResource(if (open) R.string.chat_collapse else R.string.chat_expand)
    val label = if (summary.running) {
        pluralStringResource(R.plurals.chat_steps_running, summary.steps, summary.steps)
    } else {
        pluralStringResource(R.plurals.chat_steps, summary.steps, summary.steps)
    }
    val failed = summary.failed.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.chat_steps_failed, it, it) }
    val labelColor = if (summary.running) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant
    // Red on `surface` is 5.7:1 (design.md §1); the line has no container.
    val failedColor = MaterialTheme.colorScheme.error
    val toolSpan = AristotleTextStyles.toolSummary.toSpanStyle()
    val text = remember(label, failed, summary.tool, labelColor, failedColor, toolSpan) {
        buildAnnotatedString {
            withStyle(SpanStyle(color = labelColor)) { append(label) }
            // The failure before the tool name: a long name is what the ellipsis may cut.
            failed?.let {
                append(StatusText.SEPARATOR)
                withStyle(SpanStyle(color = failedColor)) { append(it) }
            }
            summary.tool?.let {
                append(StatusText.SEPARATOR)
                withStyle(toolSpan) { append(it) }
            }
        }
    }
    // 36 dp visible; Compose widens the touch area of a smaller clickable to 48 dp (Q67).
    Row(
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .clickable(role = Role.Button, onClickLabel = state, onClick = onToggle)
            .heightIn(min = STEPS_LINE_HEIGHT)
            .padding(end = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        if (summary.running) RunningSpinner() else Chevron(open)
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (summary.running) Chevron(open)
    }
}

/** One call in the open list: icon, name (mono 600), args (mono, shortened, TU1 C1), duration / Failed / spinner. */
@Composable
private fun ToolStep(item: ChatItem.Tool, folds: FoldState, openByDefault: Boolean, live: Boolean) {
    val open = folds.isOpen(item.key, openByDefault)
    Column(modifier = Modifier.fillMaxWidth()) {
        StepRow(open, onToggle = { folds.toggle(item.key, openByDefault) }) {
            Icon(
                painter = painterResource(toolIconRes(item.name)),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(Sizes.stepIcon),
            )
            Text(text = item.name, style = AristotleTextStyles.toolName, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
            val summary = remember(item.name, item.summary) { ToolSummary.display(item.name, item.summary) }
            Text(
                text = item.progress ?: summary,
                style = AristotleTextStyles.toolSummary,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            ToolTrailing(item, live)
        }
        Fold(open) { ToolOutput(item, folds) }
    }
}

/** design-notes §5.2: reasoning is a step row in italic ("Thought for 2 s", "Thinking…" while it streams); it opens its text. */
@Composable
private fun ReasoningStep(item: ChatItem.Reasoning, folds: FoldState) {
    val open = folds.isOpen(item.key, default = false)
    val label = when {
        item.streaming -> stringResource(R.string.chat_thinking)
        item.durationMillis != null -> stringResource(R.string.chat_thought_for, ChatFormat.thoughtSeconds(item.durationMillis))
        else -> stringResource(R.string.chat_reasoning)
    }
    val italic = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic)
    Column(modifier = Modifier.fillMaxWidth()) {
        StepRow(open, onToggle = { folds.toggle(item.key, default = false) }) {
            Icon(
                painter = painterResource(R.drawable.ic_psychology),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(Sizes.stepIcon),
            )
            Text(text = label, style = italic, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, modifier = Modifier.weight(1f))
        }
        Fold(open) {
            Text(
                text = item.text,
                style = italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = STEP_CONTENT_START, end = Spacing.m, bottom = Spacing.s),
            )
        }
    }
}

/** A 44 dp step row; tapping it opens what it holds (the state label says so: no chevron per row, as on the board). */
@Composable
private fun StepRow(open: Boolean, onToggle: () -> Unit, content: @Composable RowScope.() -> Unit) {
    val state = stringResource(if (open) R.string.chat_collapse else R.string.chat_expand)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = state, onClick = onToggle)
            .heightIn(min = STEP_ROW_HEIGHT)
            .padding(horizontal = Spacing.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(STEP_GAP),
        content = content,
    )
}

/**
 * A Hermes notification (a finished background process or delegation) as the Desktop's one-line row, never a user bubble (TC1):
 * `surfaceContainerLow`, `medium`, `onSurfaceVariant` text, no verdigris (it is not live work). Tap opens the whole text in mono.
 */
@Composable
private fun NotificationCard(item: ChatItem.Card, folds: FoldState) {
    val open = folds.isOpen(item.key, default = false)
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column {
            FoldHeader(
                open = open,
                onToggle = { folds.toggle(item.key, default = false) },
                minHeight = NOTIFICATION_HEIGHT,
                gap = STEP_GAP,
                modifier = Modifier.padding(horizontal = Spacing.m),
            ) {
                Icon(
                    painter = painterResource(if (item.process) R.drawable.ic_terminal else R.drawable.ic_task_alt),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(Sizes.stepIcon),
                )
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (item.text.isNotEmpty()) Chevron(open)
            }
            if (item.text.isNotEmpty()) {
                Fold(open) {
                    SelectionContainer {
                        Text(
                            text = item.text,
                            style = AristotleTextStyles.toolOutput,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            // The Desktop caps the opened log (max-h-80) and scrolls it.
                            modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState()).padding(start = Spacing.m, end = Spacing.m, bottom = Spacing.m),
                        )
                    }
                }
            }
        }
    }
}

/** The user's own message (design-notes §5.2): right-aligned `secondaryContainer` bubble in `shapes.large`, at most 82 % wide. */
@Composable
internal fun UserBubble(text: String, attachments: List<BubbleAttachment> = emptyList()) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val width = maxWidth * BUBBLE_MAX_FRACTION
        Column(modifier = Modifier.align(Alignment.CenterEnd).widthIn(max = width), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            // T16: the message's images and files above its words, as on the Desktop.
            if (attachments.isNotEmpty()) BubbleAttachments(attachments)
            if (text.isNotBlank() || attachments.isEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    shape = MaterialTheme.shapes.large,
                ) {
                    SelectionContainer {
                        Text(text = text, style = AristotleTextStyles.chatText, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolTrailing(item: ChatItem.Tool, live: Boolean) {
    val locale = LocalConfiguration.current.locales[0]
    val duration = item.durationSeconds?.let { stringResource(R.string.chat_tool_seconds, ChatFormat.seconds(it, locale)) }
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalAlignment = Alignment.CenterVertically) {
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

/** Output in a Highest block under its row, mono 11.5 / 17, no wrap, scrolls sideways; the first lines until "Show all". */
@Composable
private fun ToolOutput(item: ChatItem.Tool, folds: FoldState) {
    if (item.output.isEmpty() && item.status == ToolStatus.Running) return // nothing to show until it ends
    val lines = remember(item.output) { item.output.lines() }
    val allKey = "${item.key}#all"
    val showAll = folds.isOpen(allKey, default = false) || lines.size <= OUTPUT_PREVIEW_LINES
    val shown = remember(item.output, showAll) { if (showAll) item.output else lines.take(OUTPUT_PREVIEW_LINES).joinToString("\n") }
    // Under the row's name, past its icon (board: 40 dp in, 12 dp from the end, 8 dp below).
    Column(modifier = Modifier.fillMaxWidth().padding(start = STEP_CONTENT_START, end = Spacing.m, bottom = Spacing.s)) {
        Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.fillMaxWidth()) {
            val empty = item.output.isEmpty()
            SelectionContainer {
                Text(
                    text = if (empty) stringResource(R.string.chat_tool_no_output) else shown,
                    style = AristotleTextStyles.toolOutput,
                    color = if (empty) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    softWrap = false,
                    modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = Spacing.m, vertical = Spacing.s),
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

@Composable
private fun FoldHeader(
    open: Boolean,
    onToggle: () -> Unit,
    minHeight: Dp,
    modifier: Modifier = Modifier,
    gap: Dp = Spacing.m,
    content: @Composable RowScope.() -> Unit,
) {
    val state = stringResource(if (open) R.string.chat_collapse else R.string.chat_expand)
    Box(modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = state, onClick = onToggle)) {
        Row(
            modifier = modifier.fillMaxWidth().heightIn(min = minHeight),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(gap),
            content = content,
        )
    }
}

@Composable
internal fun Chevron(open: Boolean, size: Dp = Sizes.toolIcon) {
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
        modifier = Modifier.size(size).rotate(angle),
    )
}

/** design.md §4: expandVertically + fadeIn 300 ms emphasized-decelerate; out 200 ms standard-accelerate; instant if reduced. */
@Composable
internal fun Fold(open: Boolean, content: @Composable () -> Unit) {
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

/** The "N steps" line's visible height (design-notes §5.2). */
private val STEPS_LINE_HEIGHT = 36.dp

/** A step row in the open list (design-notes §5.2). */
private val STEP_ROW_HEIGHT = 44.dp

/** Icon-to-text gap in step rows and notification lines (board: 10 px). */
private val STEP_GAP = 10.dp

/** Where a step row's text starts: row padding + step icon + gap; outputs and reasoning text line up there. */
private val STEP_CONTENT_START = Spacing.m + Sizes.stepIcon + STEP_GAP

/** The notification line's visible height (board `.qline`: 40 dp). */
private val NOTIFICATION_HEIGHT = 40.dp

private const val EXPAND_MILLIS = 300
private const val COLLAPSE_MILLIS = 200
