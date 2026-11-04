package io.github.justcallmesu.aristotle.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.github.justcallmesu.aristotle.R
import io.github.justcallmesu.aristotle.data.chat.BackgroundProcess
import io.github.justcallmesu.aristotle.data.chat.ProcessKill
import io.github.justcallmesu.aristotle.data.chat.Subagent
import io.github.justcallmesu.aristotle.data.chat.SubagentStatus
import io.github.justcallmesu.aristotle.ui.components.RunningSpinner
import io.github.justcallmesu.aristotle.ui.running.LocalRunningNav
import io.github.justcallmesu.aristotle.ui.theme.AristotleTextStyles
import io.github.justcallmesu.aristotle.ui.theme.ButtonShape
import io.github.justcallmesu.aristotle.ui.theme.Sizes
import io.github.justcallmesu.aristotle.ui.theme.Spacing
import kotlinx.coroutines.delay

/** What the status stack and its Stop dialog can do (TB1). */
interface BackgroundActions {
    /** Stop on a process row: opens the dialog only. */
    fun askStop(processId: String)

    /** The dialog's red Stop: the one place `process.kill` is sent. */
    fun confirmStop()

    /** Cancel, back or a tap outside the dialog. */
    fun cancelStop()

    fun resultShown()
}

/**
 * Q77, the Desktop's composer status stack (`apps/desktop/src/app/chat/composer/status-stack`) above the composer: "N
 * Subagents" (this session's running children: goal, current step; a tap opens the child's page) and "N Background"
 * (its running processes: command, status, uptime, Stop). Each section folds on its header; the open/closed state is
 * per session and survives a rotation. Hidden when both are empty. In the composer's language: one
 * `surfaceContainerLow` `medium` block inset 12 dp like the field, headers like the "N steps" line, at most
 * [BackgroundLogic.MAX_SHARE] of the room left after the composer, scrolling inside; while the keyboard is up it folds to
 * its headers (they stay as a hint, and nothing there is worth hiding the field for).
 */
@Composable
internal fun StatusStack(
    sessionId: String,
    children: List<Subagent>,
    background: BackgroundState,
    live: Boolean,
    onOpenSubagent: ((String) -> Unit)?,
    onStop: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val subagents = BackgroundLogic.subagentRows(children)
    val processes = background.processes
    if (!BackgroundLogic.stackVisible(subagents, processes)) return
    var subagentsOpen by rememberSaveable(sessionId) { mutableStateOf(true) }
    var processesOpen by rememberSaveable(sessionId) { mutableStateOf(true) }
    val now = wallClockTicking(live)
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    // TY1: the first header links to the Running page (every profile's work).
    val seeAll: (() -> Unit)? = LocalRunningNav.current?.open
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = STACK_INSET, end = STACK_INSET, top = Spacing.xs)
            .layout { measurable, constraints ->
                // The room the parent left after the composer, not the window (TB1b MAJOR-1).
                val cap = if (constraints.hasBoundedHeight) BackgroundLogic.stackLayout(imeVisible, constraints.maxHeight).maxHeight else constraints.maxHeight
                val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = minOf(constraints.maxHeight, cap)))
                layout(placeable.width, placeable.height) { placeable.place(0, 0) }
            },
    ) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(vertical = Spacing.xs)) {
            if (subagents.isNotEmpty()) {
                val running = live && subagents.any { it.status == SubagentStatus.Running }
                SectionHeader(pluralStringResource(R.plurals.status_subagents, subagents.size, subagents.size), subagentsOpen && !imeVisible, running, seeAll) { subagentsOpen = !subagentsOpen }
                Fold(subagentsOpen && !imeVisible) {
                    Column { subagents.forEach { child -> SubagentStatusRow(child, live, now, onOpenSubagent) } }
                }
            }
            if (processes.isNotEmpty()) {
                SectionHeader(
                    pluralStringResource(R.plurals.status_background, processes.size, processes.size),
                    processesOpen && !imeVisible,
                    running = live,
                    seeAll = seeAll.takeIf { subagents.isEmpty() },
                ) { processesOpen = !processesOpen }
                Fold(processesOpen && !imeVisible) {
                    Column {
                        processes.forEach { process ->
                            ProcessRow(process, background.polledAt, now, live, enabled = live && background.stopping == null, onStop)
                        }
                    }
                }
            }
        }
    }
}

/**
 * "2 Subagents": 36 dp like the "N steps" line (48 dp touch), chevron, spinner while something there runs; [seeAll]
 * adds a "See all" link to the Running page at the end (TY1).
 */
@Composable
private fun SectionHeader(label: String, open: Boolean, running: Boolean, seeAll: (() -> Unit)? = null, onToggle: () -> Unit) {
    val action = stringResource(if (open) R.string.chat_collapse else R.string.chat_expand)
    val state = stringResource(if (open) R.string.status_section_open else R.string.status_section_closed)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(role = Role.Button, onClickLabel = action, onClick = onToggle)
            .heightIn(min = HEADER_HEIGHT)
            .padding(horizontal = Spacing.m)
            .semantics {
                heading()
                stateDescription = state
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        Chevron(open)
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (running) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (running) RunningSpinner()
        seeAll?.let { go ->
            val description = stringResource(R.string.running_see_all_label)
            TextButton(onClick = go, shape = ButtonShape, modifier = Modifier.semantics { contentDescription = description }) {
                Text(stringResource(R.string.running_see_all), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/** A running child: goal, then its current step in mono (Hermes' "[set k · i/n] tools…" line), time on the right. */
@Composable
private fun SubagentStatusRow(child: Subagent, live: Boolean, now: Long, onOpen: ((String) -> Unit)?) {
    val openLabel = stringResource(R.string.subagent_open)
    val clickable = onOpen?.let { open -> Modifier.clickable(role = Role.Button, onClickLabel = openLabel) { open(child.id) } } ?: Modifier
    val step = child.lastLine?.text?.takeIf { it.isNotBlank() }
        ?: stringResource(if (child.status == SubagentStatus.Queued) R.string.subagent_status_queued else R.string.subagent_waiting)
    Row(
        modifier = Modifier.fillMaxWidth().then(clickable).heightIn(min = Sizes.listRowHeight).padding(horizontal = Spacing.m, vertical = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = child.goal, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                text = step,
                style = AristotleTextStyles.toolSummary,
                color = if (child.lastLine?.error == true) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (live) {
            Text(
                text = elapsedText((now - child.startedAtMillis).coerceAtLeast(0) / 1000),
                style = AristotleTextStyles.tabular(MaterialTheme.typography.bodySmall),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (onOpen != null) {
            Icon(painter = painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(Sizes.toolIcon))
        }
    }
}

/** A running process: its command on one mono line (the middle elides, so the start and the end stay), status · uptime, Stop. */
@Composable
private fun ProcessRow(process: BackgroundProcess, polledAt: Long, now: Long, live: Boolean, enabled: Boolean, onStop: (String) -> Unit) {
    val status = processStatus(process)
    val uptime = uptimeText(BackgroundLogic.uptime(process, polledAt, if (live) now else polledAt))
    val leadColor = if (live) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant
    val leadStyle = if (live) AristotleTextStyles.liveStatusSmall.toSpanStyle().copy(color = leadColor) else SpanStyle(color = leadColor)
    val meta = remember(status, uptime, leadStyle) {
        buildAnnotatedString {
            withStyle(leadStyle) { append(status) }
            append(StatusText.SEPARATOR)
            append(uptime)
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = Sizes.listRowHeight).padding(start = Spacing.m, end = Spacing.xs, top = Spacing.xs, bottom = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        Icon(painter = painterResource(R.drawable.ic_terminal), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(Sizes.chipIcon))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = process.command.lineSequence().firstOrNull()?.trim().orEmpty().ifEmpty { stringResource(R.string.background_unnamed) },
                style = AristotleTextStyles.toolSummary,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
            Text(text = meta, style = AristotleTextStyles.tabular(MaterialTheme.typography.bodySmall), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(
            onClick = { onStop(process.id) },
            enabled = enabled,
            colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) {
            Icon(painter = painterResource(R.drawable.ic_stop), contentDescription = stringResource(R.string.background_stop), modifier = Modifier.size(Sizes.chipIcon))
        }
    }
}

/**
 * Q77's confirmation before `process.kill`: the full command (as Hermes lists it, mono, scrolls when long), how long it
 * has run, a red Stop and Cancel. Cancel, back and a tap outside close it and send nothing. Stop ignores taps for
 * [BackgroundLogic.ARM_MS] after the dialog appears (the ViewModel checks it too).
 */
@Composable
internal fun StopProcessDialog(confirm: StopConfirm, actions: BackgroundActions) {
    var armed by remember(confirm) { mutableStateOf(false) }
    LaunchedEffect(confirm) {
        delay(BackgroundLogic.ARM_MS)
        armed = true
    }
    val now = wallClockTicking(true)
    val uptime = uptimeText(BackgroundLogic.uptime(confirm.process, confirm.polledAt, now))
    AlertDialog(
        onDismissRequest = actions::cancelStop,
        title = { Text(stringResource(R.string.background_stop_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
                Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.fillMaxWidth()) {
                    Box(modifier = Modifier.heightIn(max = COMMAND_MAX_HEIGHT).verticalScroll(rememberScrollState())) {
                        SelectionContainer {
                            Text(
                                text = confirm.process.command.ifBlank { stringResource(R.string.background_unnamed) },
                                style = AristotleTextStyles.toolSummary,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(horizontal = Spacing.m, vertical = 10.dp),
                            )
                        }
                    }
                }
                Text(
                    text = stringResource(R.string.background_running_for, uptime),
                    style = AristotleTextStyles.tabular(MaterialTheme.typography.bodyMedium),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = actions::confirmStop,
                enabled = armed,
                shape = ButtonShape,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
            ) { Text(stringResource(R.string.background_stop_confirm)) }
        },
        dismissButton = { TextButton(onClick = actions::cancelStop, shape = ButtonShape) { Text(stringResource(R.string.background_stop_cancel)) } },
        shape = MaterialTheme.shapes.extraLarge,
    )
}

/** The result of a Stop, one short line (snackbar). */
@Composable
internal fun processResult(result: ProcessKill): String = when (result) {
    ProcessKill.Stopped -> stringResource(R.string.background_result_stopped)
    ProcessKill.AlreadyExited -> stringResource(R.string.background_result_exited)
    ProcessKill.NotFound -> stringResource(R.string.background_result_not_found)
    is ProcessKill.Failed -> if (result.reason.isBlank()) stringResource(R.string.background_result_failed_plain) else stringResource(R.string.background_result_failed, result.reason)
    ProcessKill.NotSent -> stringResource(R.string.background_result_not_sent)
    ProcessKill.Unknown -> stringResource(R.string.background_result_unknown)
}

@Composable
private fun processStatus(process: BackgroundProcess): String =
    if (process.status == "running") stringResource(R.string.subagent_status_running) else process.status

/** "12 s", "3 m 05 s", or "2 h 05 m" from an hour on (a dev server runs for hours). */
@Composable
private fun uptimeText(seconds: Long): String =
    if (seconds < SECONDS_PER_HOUR) {
        elapsedText(seconds)
    } else {
        stringResource(R.string.background_hours, (seconds / SECONDS_PER_HOUR).toInt(), ((seconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE).toInt())
    }

private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 3_600L

/** Inset like the composer's floating field (design-notes §5.2). */
private val STACK_INSET = 12.dp

/** The headers' visible height: the "N steps" line's (design-notes §5.2). */
private val HEADER_HEIGHT = 36.dp

/** The dialog's command block scrolls past this. */
private val COMMAND_MAX_HEIGHT = 200.dp
