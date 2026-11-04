package io.github.justcallmesu.aristotle.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
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
import io.github.justcallmesu.aristotle.ui.theme.ButtonShape
import io.github.justcallmesu.aristotle.ui.theme.Sizes
import io.github.justcallmesu.aristotle.ui.theme.Spacing

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

/** The field is one compact line tall when empty; a Material button is 40 dp, so the row stays near that. */
private val FIELD_MIN_HEIGHT = 44.dp

/**
 * design.md §5 composer (mockup screen 3): `surfaceContainer`, top corners `extraLarge`. Idle: field + Send. Busy
 * ([running]): queued rows above the field, then Stop (outlined, error) left, Queue (tonal) and Steer (filled) right,
 * and one hint line. Offline: the text stays, nothing can be sent. A control is on only once it has meant the same
 * thing for [ComposerLogic.ARM_MS] ([ComposerState.armed]); the ViewModel drops an earlier tap anyway.
 * A draft starting with `/` is a command (T15): the `/` menu shows above the field while its name is typed, and Send
 * (shown while busy too) runs it; Queue and Steer are off for it.
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
) {
    val online = state.mode != ComposerMode.Offline
    val ready = state.armed && online
    val hasText = state.draft.isNotBlank()
    // Words or attachments, every attachment prepared (T16).
    val hasContent = ComposerLogic.hasContent(state)
    val command = SlashMenu.isCommand(state.draft)
    val query = SlashMenu.query(state.draft)
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                .padding(horizontal = Spacing.m, vertical = Spacing.s),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            if (online) queued.forEachIndexed { index, row -> QueuedLine(index + 1, row, state, ready, actions) }
            if (online && query != null) SlashMenuPanel(slash, query, onPick = actions::pickCommand, onRetry = actions::retryCommands)
            if (state.attachments.isNotEmpty()) AttachmentChips(state.attachments, onRemove = actions::removeAttachment)
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                AttachButton(enabled = online && state.attachments.size < AttachmentRules.MAX_ATTACHMENTS, actions = actions)
                ComposerField(state, enabled = online, onEdit = actions::edit, focusOnStart = focusOnStart, modifier = Modifier.weight(1f))
                if (!running || !online || command) {
                    // A command runs idle or busy, and while a prompt waits (it is not a message to the parked turn).
                    val canSend = if (command) state.mode == ComposerMode.Idle || state.mode == ComposerMode.Busy else state.mode == ComposerMode.Idle && !state.blocked
                    SendButton(enabled = ready && canSend && (if (command) hasText else hasContent), command = command, onClick = actions::send)
                }
            }
            when {
                !online -> HintLine(stringResource(R.string.composer_offline))
                running -> BusyActions(state, ready, hasText, hasContent, command, actions)
                state.blocked && !command -> HintLine(stringResource(R.string.composer_blocked_idle_hint))
                ComposerLogic.hasRefused(state) -> HintLine(stringResource(R.string.attach_refused_hint))
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
            Box(
                modifier = Modifier
                    .background(colors.surfaceContainerLowest, MaterialTheme.shapes.large)
                    .padding(horizontal = Spacing.l, vertical = Spacing.s),
                contentAlignment = Alignment.CenterStart,
            ) {
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

@Composable
private fun SendButton(enabled: Boolean, command: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = ButtonShape,
        contentPadding = ButtonDefaults.ContentPadding,
    ) {
        Icon(painter = painterResource(R.drawable.ic_send), contentDescription = stringResource(if (command) R.string.composer_run else R.string.composer_send), modifier = Modifier.size(Sizes.chipIcon))
    }
}

/**
 * Busy (Q69): Stop left, always there while the turn runs. Queue and Steer on the right and the one-line hint appear
 * only while the field holds something ([ComposerLogic.showsBusyExtras]), fading over [EXTRAS_FADE_MS].
 */
@Composable
private fun BusyActions(state: ComposerState, ready: Boolean, hasText: Boolean, hasContent: Boolean, command: Boolean, actions: ComposerActions) {
    val busy = ready && state.mode == ComposerMode.Busy
    val extras = ComposerLogic.showsBusyExtras(state)
    val fadeIn = fadeIn(tween(EXTRAS_FADE_MS))
    val fadeOut = fadeOut(tween(EXTRAS_FADE_MS))
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        OutlinedButton(
            onClick = actions::stop,
            enabled = busy && !state.stopping,
            shape = ButtonShape,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) {
            Icon(painter = painterResource(R.drawable.ic_stop), contentDescription = null, modifier = Modifier.size(Sizes.chipIcon))
            Spacer(Modifier.size(Spacing.s))
            Text(stringResource(R.string.composer_stop))
        }
        Spacer(Modifier.weight(1f))
        AnimatedVisibility(visible = extras, enter = fadeIn, exit = fadeOut) {
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
    }
    val hint = when {
        command -> R.string.composer_command_hint
        ComposerLogic.hasRefused(state) -> R.string.attach_refused_hint
        state.blocked -> R.string.composer_blocked_hint
        else -> R.string.composer_steer_hint
    }
    AnimatedVisibility(visible = extras, enter = fadeIn + expandVertically(tween(EXTRAS_FADE_MS)), exit = fadeOut + shrinkVertically(tween(EXTRAS_FADE_MS))) {
        HintLine(stringResource(hint))
    }
}

/** "Queued · N — text" with ✕ (Hermes' queue: the Desktop shows and cancels the same rows). */
@Composable
private fun QueuedLine(number: Int, row: QueuedRow, state: ComposerState, ready: Boolean, actions: ComposerActions) {
    Row(modifier = Modifier.fillMaxWidth().padding(start = Spacing.xs), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
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

@Composable
private fun HintLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = Spacing.xs, top = Spacing.xs),
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
