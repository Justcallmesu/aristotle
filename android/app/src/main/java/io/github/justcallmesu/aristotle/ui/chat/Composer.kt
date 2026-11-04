package io.github.justcallmesu.aristotle.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.justcallmesu.aristotle.R
import io.github.justcallmesu.aristotle.data.chat.AttachmentRules
import io.github.justcallmesu.aristotle.data.chat.PickKind
import io.github.justcallmesu.aristotle.data.chat.SlashCommand
import io.github.justcallmesu.aristotle.data.chat.SlashMenu
import io.github.justcallmesu.aristotle.ui.components.reducedMotion
import io.github.justcallmesu.aristotle.ui.theme.AristotleTextStyles
import io.github.justcallmesu.aristotle.ui.theme.ButtonShape
import io.github.justcallmesu.aristotle.ui.theme.ComposerShape
import io.github.justcallmesu.aristotle.ui.theme.Sizes
import io.github.justcallmesu.aristotle.ui.theme.Spacing
import io.github.justcallmesu.aristotle.ui.theme.SquareIconButtonShape

/** What the composer's controls do; the ViewModel in the app. */
interface ComposerActions {
    fun edit(text: String)
    fun send()
    fun queue()
    fun steer()
    fun stop()
    fun cancelQueued(admissionId: String)

    /** A `/` menu row (T15): its name goes into the field. */
    fun pickCommand(command: SlashCommand)

    fun retryCommands()

    /** Items a picker or the camera returned (T16). */
    fun attach(uris: List<String>, kind: PickKind)

    fun removeAttachment(id: String)

    /** Where the camera writes (a `content://` URI string), null when it cannot. */
    fun cameraTarget(): String?

    fun cameraUnavailable()

    /** The camera returned without a picture: its empty file at [target] goes. */
    fun cameraCancelled(target: String)
}

/** The field never grows past this many lines; then it scrolls (T13 brief). */
private const val FIELD_MAX_LINES = 6

/** Queue, Steer and the hint fade in and out over this long (Q69). */
private const val EXTRAS_FADE_MS = 150

/** One line of text; the action row sits under it inside the same field. */
private val FIELD_MIN_HEIGHT = 40.dp

/** The model chip's label never grows past this (design-notes §5.2). */
private val CHIP_MAX_WIDTH = 250.dp

/** The floating field is inset 12 dp from the screen edge (design-notes §5.2: padding 12 / 6 / 12 / 8). */
private val OUTER_PADDING = PaddingValues(start = 12.dp, top = 6.dp, end = 12.dp, bottom = 8.dp)

/**
 * design-notes §5.2 composer (v2 board): on `surface`, one floating field (`surfaceContainerHigh`, [ComposerShape])
 * holding the text, attach, the model chip and the trailing action ([ComposerLogic.trailing]). Above it: the waiting
 * chip while a prompt waits for this phone, queued rows, the `/` menu. Busy (Q69): Stop alone while the field is
 * empty; with words or an attachment the chip hides and the row reads Stop · Queue · Steer, with one hint line under
 * the field. Offline: the text stays, nothing can be sent. A control is on only once it has meant the same thing for
 * [ComposerLogic.ARM_MS] ([ComposerState.armed]); the ViewModel drops an earlier tap anyway.
 * A draft starting with `/` is a command (T15): the `/` menu shows above the field while its name is typed, and Send
 * runs it, busy too; Queue and Steer are off for it.
 *
 * [modelChip] is [ComposerLogic.chipLabel] of the chat's model and effort (null hides the chip); [onOpenModels] opens
 * the model sheet; [onOpenPrompt] opens the prompt sheet from the waiting chip, whose words follow [waitingFor].
 */
@Composable
fun Composer(
    state: ComposerState,
    running: Boolean,
    queued: List<QueuedRow>,
    actions: ComposerActions,
    slash: SlashState,
    modifier: Modifier = Modifier,
    focusOnStart: Boolean = false,
    modelChip: String? = null,
    onOpenModels: () -> Unit = {},
    onOpenPrompt: () -> Unit = {},
    waitingFor: WaitingFor = WaitingFor.Approval,
) {
    val online = state.mode != ComposerMode.Offline
    val ready = state.armed && online
    val hasText = state.draft.isNotBlank()
    // Words or attachments, every attachment prepared (T16).
    val hasContent = ComposerLogic.hasContent(state)
    val command = SlashMenu.isCommand(state.draft)
    val query = SlashMenu.query(state.draft)
    val trailing = ComposerLogic.trailing(online, running, command, state)
    Surface(color = MaterialTheme.colorScheme.surface, modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                .padding(OUTER_PADDING),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            if (online && state.blocked) ComposerWaitingChip(waitingFor, onOpenPrompt)
            if (online) queued.forEachIndexed { index, row -> QueuedLine(index + 1, row, state, ready, actions) }
            if (online && query != null) SlashMenuPanel(slash, query, onPick = actions::pickCommand, onRetry = actions::retryCommands)
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = ComposerShape, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(start = Spacing.l, top = Spacing.s, end = Spacing.s, bottom = Spacing.s)) {
                    if (state.attachments.isNotEmpty()) AttachmentChips(state.attachments, onRemove = actions::removeAttachment)
                    ComposerField(state, enabled = online, onEdit = actions::edit, focusOnStart = focusOnStart, modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        AttachButton(enabled = online && state.attachments.size < AttachmentRules.MAX_ATTACHMENTS, actions = actions)
                        if (ComposerLogic.showsModelChip(trailing) && modelChip != null) {
                            ModelChip(modelChip, onOpenModels, modifier = Modifier.weight(1f, fill = false))
                        }
                        Spacer(Modifier.weight(1f))
                        TrailingActions(trailing, state, ready, hasText, hasContent, command, actions)
                    }
                }
            }
            // Q69: the busy hint fades in and out with Queue and Steer (TR MINOR-2); the other hints are steady.
            val busyExtras = online && running && ComposerLogic.showsBusyExtras(state)
            ExtrasFade(visible = busyExtras, horizontal = false) { BusyHint(state, command) }
            if (!busyExtras) {
                when {
                    !online -> HintLine(stringResource(R.string.composer_offline))
                    state.blocked && !command -> HintLine(stringResource(R.string.composer_blocked_idle_hint))
                    ComposerLogic.hasRefused(state) -> HintLine(stringResource(R.string.attach_refused_hint))
                }
            }
        }
    }
}

/**
 * The field. Its text is local while the user types; [ComposerState.draftEpoch] changes only when the ViewModel
 * changes the draft itself (sent, put back), and only then is the field reset, so a late state never undoes typing.
 */
@Composable
private fun ComposerField(state: ComposerState, enabled: Boolean, onEdit: (String) -> Unit, focusOnStart: Boolean, modifier: Modifier = Modifier) {
    var field by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(state.draft, TextRange(state.draft.length))) }
    // Focused once (a new session, T14); not again after a rotation, which would pop the keyboard back up.
    val focus = remember { FocusRequester() }
    var focused by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(focusOnStart, enabled) {
        if (focusOnStart && enabled && !focused) {
            focus.requestFocus()
            focused = true
        }
    }
    LaunchedEffect(state.draftEpoch) {
        if (field.text != state.draft) field = TextFieldValue(state.draft, TextRange(state.draft.length))
    }
    val description = stringResource(R.string.composer_field)
    val colors = MaterialTheme.colorScheme
    BasicTextField(
        value = field,
        onValueChange = {
            field = it
            onEdit(it.text)
        },
        enabled = enabled,
        maxLines = FIELD_MAX_LINES,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = if (enabled) colors.onSurface else colors.onSurfaceVariant),
        cursorBrush = SolidColor(colors.primary),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        modifier = modifier.heightIn(min = FIELD_MIN_HEIGHT).focusRequester(focus).semantics { contentDescription = description },
        decorationBox = { inner ->
            Box(modifier = Modifier.padding(end = Spacing.s, top = Spacing.xs, bottom = Spacing.xs), contentAlignment = Alignment.CenterStart) {
                if (field.text.isEmpty()) {
                    Text(
                        text = stringResource(if (enabled) R.string.composer_placeholder else R.string.composer_offline),
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.onSurfaceVariant,
                    )
                }
                inner()
            }
        },
    )
}

/** `surfaceContainerHighest`, no border, mono 13 "model · effort", opens the model sheet. */
@Composable
private fun ModelChip(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.composer_model_chip, label)
    AssistChip(
        onClick = onClick,
        label = { Text(text = label, style = AristotleTextStyles.toolSummary, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingIcon = { Icon(painter = painterResource(R.drawable.ic_expand_more), contentDescription = null, modifier = Modifier.size(Sizes.chipIcon)) },
        shape = MaterialTheme.shapes.small,
        colors = AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            labelColor = MaterialTheme.colorScheme.onSurface,
            trailingIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        border = null,
        modifier = modifier.widthIn(max = CHIP_MAX_WIDTH).semantics { contentDescription = description },
    )
}

/** A prompt waits for this phone: `errorContainer` chip that opens the prompt sheet again; "answer" unless it is an approval. */
@Composable
private fun ComposerWaitingChip(waitingFor: WaitingFor, onClick: () -> Unit) {
    val label = if (waitingFor == WaitingFor.Answer) R.string.composer_waiting_answer else R.string.composer_waiting_chip
    AssistChip(
        onClick = onClick,
        label = { Text(stringResource(label), style = MaterialTheme.typography.labelLarge) },
        leadingIcon = { Icon(painter = painterResource(R.drawable.ic_pan_tool), contentDescription = null, modifier = Modifier.size(Sizes.chipIcon)) },
        shape = MaterialTheme.shapes.small,
        colors = AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            labelColor = MaterialTheme.colorScheme.onErrorContainer,
            leadingIconContentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
        border = null,
    )
}

@Composable
private fun SendButton(enabled: Boolean, command: Boolean, onClick: () -> Unit) {
    FilledIconButton(onClick = onClick, enabled = enabled, shape = SquareIconButtonShape) {
        Icon(painter = painterResource(R.drawable.ic_send), contentDescription = stringResource(if (command) R.string.composer_run else R.string.composer_send), modifier = Modifier.size(Sizes.chipIcon))
    }
}

/** Stop: a square in `errorContainer`; always there while a turn runs (Q69), never asks to confirm. */
@Composable
private fun StopButton(enabled: Boolean, onClick: () -> Unit) {
    FilledIconButton(
        onClick = onClick,
        enabled = enabled,
        shape = SquareIconButtonShape,
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Icon(painter = painterResource(R.drawable.ic_stop), contentDescription = stringResource(R.string.composer_stop), modifier = Modifier.size(Sizes.chipIcon))
    }
}

/**
 * The right end of the action row, by [ComposerLogic.trailing]. While busy, Stop stays and Queue · Steer fade in and
 * out beside it over [EXTRAS_FADE_MS] (TR MINOR-2). Send follows [ComposerLogic.sendEnabled]: a command (alone, or
 * beside Stop while busy) runs idle or busy, never while the prompt sheet covers the composer (TR MINOR-3).
 */
@Composable
private fun TrailingActions(trailing: ComposerTrailing, state: ComposerState, ready: Boolean, hasText: Boolean, hasContent: Boolean, command: Boolean, actions: ComposerActions) {
    val busy = ready && state.mode == ComposerMode.Busy
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalAlignment = Alignment.CenterVertically) {
        if (trailing == ComposerTrailing.Send) {
            // A command runs idle, and while a prompt waits (it is not a message to the parked turn).
            SendButton(enabled = ComposerLogic.sendEnabled(state, ready, command, hasText, hasContent), command = command, onClick = actions::send)
        } else {
            StopButton(enabled = busy && !state.stopping, onClick = actions::stop)
            ExtrasFade(visible = trailing == ComposerTrailing.StopQueueSteer, horizontal = true) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalButton(onClick = actions::queue, enabled = busy && hasContent && !command, shape = ButtonShape) {
                        Text(stringResource(R.string.composer_queue))
                    }
                    // A steer carries words only: with attachments the message is queued (T16).
                    val steerable = hasText && !state.blocked && !command && state.attachments.isEmpty()
                    Button(onClick = actions::steer, enabled = busy && steerable, shape = ButtonShape) {
                        Text(stringResource(R.string.composer_steer))
                    }
                }
            }
            if (trailing == ComposerTrailing.StopSend) {
                SendButton(enabled = ComposerLogic.sendEnabled(state, ready, command = true, hasText, hasContent), command = true, onClick = actions::send)
            }
        }
    }
}

/** Fades [content] in and out over [EXTRAS_FADE_MS], growing it sideways or downwards; instant with reduced motion. */
@Composable
private fun ExtrasFade(visible: Boolean, horizontal: Boolean, content: @Composable () -> Unit) {
    val millis = if (reducedMotion()) 0 else EXTRAS_FADE_MS
    val enter = fadeIn(tween(millis)) + if (horizontal) expandHorizontally(tween(millis)) else expandVertically(tween(millis))
    val exit = fadeOut(tween(millis)) + if (horizontal) shrinkHorizontally(tween(millis)) else shrinkVertically(tween(millis))
    AnimatedVisibility(visible = visible, enter = enter, exit = exit) { content() }
}

/** The one hint line under the field while Queue and Steer show (Q69). */
@Composable
private fun BusyHint(state: ComposerState, command: Boolean) {
    val hint = when {
        command -> R.string.composer_command_hint
        ComposerLogic.hasRefused(state) -> R.string.attach_refused_hint
        state.blocked -> R.string.composer_blocked_hint
        else -> R.string.composer_steer_hint
    }
    HintLine(stringResource(hint))
}

/** "Queued · N — text" with ✕ (Hermes' queue: the Desktop shows and cancels the same rows), in `surfaceContainerLow`. */
@Composable
private fun QueuedLine(number: Int, row: QueuedRow, state: ComposerState, ready: Boolean, actions: ComposerActions) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(start = Spacing.l), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Text(
                text = if (row.admissionId == null) stringResource(R.string.composer_sending) else stringResource(R.string.composer_queued, number),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = row.text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            val admission = row.admissionId
            IconButton(
                onClick = { admission?.let(actions::cancelQueued) },
                enabled = ready && admission != null && admission !in state.cancelling,
            ) {
                Icon(painter = painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.composer_queue_remove), modifier = Modifier.size(Sizes.toolIcon))
            }
        }
    }
}

@Composable
private fun HintLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = Spacing.l, top = Spacing.xs),
    )
}

/**
 * A message of ours that Hermes does not show itself yet (design.md §5 user bubble): "Sending…" under it, or why it
 * failed with Retry (when a retry cannot deliver twice) and ✕ (the words go back to the field).
 */
@Composable
fun EchoRow(item: Outgoing, onRetry: (String) -> Unit, onDismiss: (String) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        if (item.kind == OutgoingKind.Steer && item.status is OutgoingStatus.Sent) {
            Text(text = stringResource(R.string.echo_steered), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        UserBubble(item.text, item.attachments.map { it.toBubble() })
        when (val status = item.status) {
            OutgoingStatus.Sending -> Text(text = stringResource(R.string.composer_sending), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            is OutgoingStatus.Failed -> FailedLine(item, status.failure, onRetry, onDismiss)
            is OutgoingStatus.Sent -> Unit
        }
    }
}

@Composable
private fun attachFailure(failure: SendFailure.AttachFailed): String = when (failure.cause) {
    AttachCause.NotReached -> stringResource(R.string.echo_attach_not_reached, failure.name)
    AttachCause.Refused -> failure.reason?.let { stringResource(R.string.echo_attach_refused, failure.name, it) }
        ?: stringResource(R.string.echo_attach_refused_plain, failure.name)
    AttachCause.Gone -> stringResource(R.string.echo_attach_gone, failure.name)
    AttachCause.TooLarge -> stringResource(R.string.echo_attach_too_large, failure.name)
}

@Composable
private fun FailedLine(item: Outgoing, failure: SendFailure, onRetry: (String) -> Unit, onDismiss: (String) -> Unit) {
    val reason = when (failure) {
        SendFailure.NotSent -> stringResource(R.string.echo_not_sent)
        SendFailure.NoAnswer -> stringResource(R.string.echo_no_answer)
        is SendFailure.Refused -> stringResource(R.string.echo_refused, failure.reason)
        SendFailure.TurnEnded -> stringResource(R.string.echo_turn_ended)
        SendFailure.NotTaken -> stringResource(R.string.echo_not_taken)
        is SendFailure.AttachFailed -> attachFailure(failure)
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(
            text = reason,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (item.retryable) {
            TextButton(onClick = { onRetry(item.id) }, shape = ButtonShape) { Text(stringResource(R.string.echo_retry)) }
        }
        IconButton(onClick = { onDismiss(item.id) }) {
            Icon(painter = painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.echo_dismiss), modifier = Modifier.size(Sizes.toolIcon))
        }
    }
}
