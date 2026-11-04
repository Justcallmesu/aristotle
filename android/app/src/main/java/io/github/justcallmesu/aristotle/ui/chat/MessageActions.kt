package io.github.justcallmesu.aristotle.ui.chat

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.justcallmesu.aristotle.R
import io.github.justcallmesu.aristotle.data.chat.SlashMenu
import io.github.justcallmesu.aristotle.ui.theme.AristotleTextStyles
import io.github.justcallmesu.aristotle.ui.theme.Sizes
import io.github.justcallmesu.aristotle.ui.theme.Spacing

/** What a long-press on a message offers (Q80a). */
enum class MessageAction { Copy, Share, SelectText, Quote, Steer }

/** Whose message it is: the user's own bubble or an assistant reply (Markdown source). */
enum class MessageRole { User, Assistant }

/** The message a sheet or an accessibility action works on. [text] is plain text for a user, Markdown source for a reply. */
@Immutable
data class MessageTarget(val role: MessageRole, val text: String)

/**
 * A block the chat screen asks the composer to put into its draft: [id] is new for each, so the same text can come twice;
 * [reply] (a quote) leaves a blank line after it for the answer.
 */
@Immutable
data class DraftEdit(val id: Long, val text: String, val reply: Boolean)

/** The result of putting a block into a draft: the new text and where the cursor goes. */
data class DraftInsert(val text: String, val cursor: Int)

/** Pure rules of the message actions (Q80a). */
object MessageActions {
    /** The actions to offer, in sheet order. A message with no words offers none; Steer only while it is allowed. */
    fun available(role: MessageRole, text: String, steerAllowed: Boolean): List<MessageAction> {
        if (clean(text).isBlank()) return emptyList()
        // Both roles offer the same set: a reply to correct the model with, or the user's own words to repeat or refine.
        return buildList {
            add(MessageAction.Copy)
            add(MessageAction.Share)
            add(MessageAction.SelectText)
            add(MessageAction.Quote)
            if (steerAllowed) add(MessageAction.Steer)
        }
    }

    /**
     * Steer with a message: while a turn runs, no prompt waits for this phone (a parked turn takes no steer), the field
     * holds no attachment (a steer carries words only) and the draft is not a `/` command (same rules as the Steer button).
     */
    fun steerAllowed(composer: ComposerState): Boolean =
        composer.mode == ComposerMode.Busy && !composer.blocked && composer.attachments.isEmpty() && !SlashMenu.isCommand(composer.draft)

    /** The text a message action works on: no streaming-cursor marker, and no surrounding blank lines. */
    fun clean(text: String): String = text.replace(StreamCursor.MARK.toString(), "").trim('\n', '\r')

    /** `> ` before every line; an empty line becomes a bare `>` so the quote stays one block. */
    fun quote(text: String): String =
        clean(text).replace("\r\n", "\n").trimEnd().lines().joinToString("\n") { if (it.isBlank()) ">" else "> $it" }

    /**
     * Puts [block] into [draft] at [cursor] (clamped), keeping everything else: a blank line before it when text comes
     * first, and a blank line after it so the next words start apart (when [reply] is false, only if text follows).
     * The new cursor sits after the block, where the user types the answer.
     */
    fun insert(draft: String, cursor: Int, block: String, reply: Boolean): DraftInsert {
        val at = cursor.coerceIn(0, draft.length)
        val before = draft.substring(0, at)
        val after = draft.substring(at)
        val lead = when {
            before.isEmpty() || before.endsWith("\n\n") -> ""
            before.endsWith("\n") -> "\n"
            else -> "\n\n"
        }
        val newlinesAfter = after.takeWhile { it == '\n' }.length
        val tail = if (after.isEmpty()) (if (reply) "\n\n" else "") else "\n".repeat((2 - newlinesAfter).coerceAtLeast(0))
        val head = before + lead + block + tail
        return DraftInsert(head + after, head.length)
    }
}

/** Pure code-block text extraction (Q80a): the code only, no fences and no language tag. */
object CodeBlocks {
    /** [source] is the node's Markdown: a fenced block (``` or ~~~, closing fence optional while streaming) or an indented one. */
    fun extract(source: String): String {
        val lines = source.replace("\r\n", "\n").split("\n")
        val first = lines.firstOrNull().orEmpty()
        val opening = FENCE.matchEntire(first.trimEnd())
        if (opening == null) {
            // Indented code: drop one level of indentation.
            return lines.joinToString("\n") { it.removePrefix("    ").removePrefix("\t") }.trimEnd('\n')
        }
        val fence = opening.groupValues[2]
        val body = lines.drop(1).toMutableList()
        val closing = Regex("^ {0,3}" + Regex.escape(fence[0].toString()) + "{" + fence.length + ",}\\s*$")
        if (body.isNotEmpty() && closing.matches(body.last())) body.removeAt(body.lastIndex)
        // The opening fence may be indented up to three spaces; so is its content.
        val indent = opening.groupValues[1].length
        return body.joinToString("\n") { line -> line.drop(line.take(indent).takeWhile { it == ' ' }.length) }
            .replace(StreamCursor.MARK.toString(), "")
    }

    private val FENCE = Regex("^( {0,3})(`{3,}|~{3,})[^`]*$")
}

/**
 * What a message's long-press and accessibility actions call. [steerAllowed] is read when the row composes, so a row's
 * TalkBack actions follow the composer; the lambdas are the screen's, remembered, so this stays equal until it flips.
 */
@Immutable
data class MessageActionHost(
    val steerAllowed: Boolean,
    val open: (MessageTarget) -> Unit,
    val perform: (MessageAction, MessageTarget) -> Unit,
    /** Text went to the clipboard: Android below 13 shows no confirmation of its own, so the screen shows a snackbar. */
    val copied: () -> Unit,
)

/** The chat's host; null (a subagent page, "What's new") means rows are not long-pressable. */
val LocalMessageActions = compositionLocalOf<MessageActionHost?> { null }

/** Copies [text] and tells the user where Android does not: returns true when the system showed "Copied" itself (Android 13+). */
fun copyToClipboard(context: Context, text: String): Boolean {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
    manager.setPrimaryClip(ClipData.newPlainText("", text))
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
}

/** Copy with feedback: the system's on Android 13+, the chat's snackbar below, a toast where there is no chat. */
fun copyWithFeedback(context: Context, text: String, host: MessageActionHost?, toast: String) {
    if (copyToClipboard(context, text)) return
    if (host != null) host.copied() else Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
}

/** The Android share sheet with [text]. */
fun shareText(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    val chooser = Intent.createChooser(send, null)
    if (context !is android.app.Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(chooser)
    } catch (_: ActivityNotFoundException) {
        // Nothing can take text: the share does nothing.
    }
}

private fun MessageAction.label(): Int = when (this) {
    MessageAction.Copy -> R.string.msg_copy
    MessageAction.Share -> R.string.msg_share
    MessageAction.SelectText -> R.string.msg_select
    MessageAction.Quote -> R.string.msg_quote
    MessageAction.Steer -> R.string.msg_steer
}

private fun MessageAction.icon(): Int = when (this) {
    MessageAction.Copy -> R.drawable.ic_content_copy
    MessageAction.Share -> R.drawable.ic_share
    MessageAction.SelectText -> R.drawable.ic_select_all
    MessageAction.Quote -> R.drawable.ic_format_quote
    MessageAction.Steer -> R.drawable.ic_bolt
}

/**
 * Long-press on a message opens its action sheet (haptic); TalkBack gets the long-click and one custom action per
 * item. Taps and links under it still work: the long-press only reacts to a press that no child consumed.
 * Nothing happens without a host ([LocalMessageActions]) or without words.
 */
@Composable
fun Modifier.messageActions(role: MessageRole, text: String): Modifier {
    val host = LocalMessageActions.current ?: return this
    val target = MessageTarget(role, MessageActions.clean(text))
    val actions = MessageActions.available(role, target.text, host.steerAllowed)
    if (actions.isEmpty()) return this
    val haptic = LocalHapticFeedback.current
    val openLabel = stringResource(R.string.msg_actions_open)
    val custom = actions.map { action ->
        CustomAccessibilityAction(stringResource(action.label())) {
            host.perform(action, target)
            true
        }
    }
    return this
        .pointerInput(host, target) {
            detectTapGestures(onLongPress = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                host.open(target)
            })
        }
        .semantics {
            onLongClick(label = openLabel) {
                host.open(target)
                true
            }
            customActions = custom
        }
}

/** The sheet (Q80a): one 56 dp row per action on `surfaceContainerLow`, like the other sheets. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageActionSheet(target: MessageTarget, steerAllowed: Boolean, onDismiss: () -> Unit, onAction: (MessageAction) -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.m)) {
            for (action in MessageActions.available(target.role, target.text, steerAllowed)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button) { onAction(action) }
                        .heightIn(min = Sizes.listRowHeight)
                        .padding(horizontal = Spacing.l),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.l),
                ) {
                    Icon(
                        painter = painterResource(action.icon()),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(Sizes.fieldIcon),
                    )
                    Text(text = stringResource(action.label()), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

/** "Select text": the whole message full screen, selectable (plain text; a reply shows its Markdown source). */
@Composable
fun SelectTextScreen(target: MessageTarget, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = Sizes.topBarHeight).padding(horizontal = Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onClose) {
                        Icon(painter = painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.msg_select_close))
                    }
                    Text(
                        text = stringResource(R.string.msg_select_title),
                        style = AristotleTextStyles.chatTitle,
                        modifier = Modifier.padding(horizontal = Spacing.xs).semantics { heading() },
                    )
                }
                SelectionContainer(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    Text(
                        text = target.text,
                        style = AristotleTextStyles.chatText,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.l, vertical = Spacing.s),
                    )
                }
            }
        }
    }
}

/** Applies a [DraftEdit] to the field's [value]: the block goes in at the cursor (end of the selection). */
internal fun applyDraftEdit(value: TextFieldValue, edit: DraftEdit): TextFieldValue {
    val result = MessageActions.insert(value.text, value.selection.end, edit.text, edit.reply)
    return TextFieldValue(result.text, TextRange(result.cursor))
}
