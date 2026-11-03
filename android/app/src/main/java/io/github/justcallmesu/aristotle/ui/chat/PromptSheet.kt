package io.github.justcallmesu.aristotle.ui.chat

import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.SecureFlagPolicy
import io.github.justcallmesu.aristotle.R
import io.github.justcallmesu.aristotle.data.chat.ApprovalChoice
import io.github.justcallmesu.aristotle.data.chat.ClarifyAnswer
import io.github.justcallmesu.aristotle.data.chat.ClarifyQuestion
import io.github.justcallmesu.aristotle.data.chat.Prompt
import io.github.justcallmesu.aristotle.data.chat.SecretKind
import io.github.justcallmesu.aristotle.ui.theme.AristotleTextStyles
import io.github.justcallmesu.aristotle.ui.theme.ButtonShape
import io.github.justcallmesu.aristotle.ui.theme.Sizes
import io.github.justcallmesu.aristotle.ui.theme.Spacing
import java.util.Locale
import kotlinx.coroutines.delay

/** What the prompt sheet can ask the ViewModel to do. */
interface PromptActions {
    fun hide()
    fun closeWithdrawn()
    fun choose(key: String, choice: ApprovalChoice)
    fun confirmAlways(key: String)
    fun cancelAlways()
    fun answerClarify(key: String, answers: Map<String, ClarifyAnswer>)
    fun answerSecret(key: String, field: SecretField)
    fun declineSecret(key: String, field: SecretField)
}

/**
 * design.md §5 approval / clarify sheet (Q41), the sudo / secret sheet (Q51) and the withdrawn state. Not swipe- or
 * scrim-dismissible while a prompt waits; Back lowers it to the waiting chip. [context] is "session · profile".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PromptSheet(prompt: Prompt?, sheet: PromptSheetState, context: String, actions: PromptActions, answerable: Boolean = true) {
    val withdrawn = sheet.withdrawn
    val shown = withdrawn ?: prompt ?: return
    val secret = withdrawn == null && shown is Prompt.Secret
    ModalBottomSheet(
        onDismissRequest = { if (withdrawn != null) actions.closeWithdrawn() else actions.hide() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetGesturesEnabled = false,
        properties = ModalBottomSheetProperties(
            // No screenshot or recents preview of the sheet's window while a password can be on it (Q51).
            securePolicy = if (secret) SecureFlagPolicy.SecureOn else SecureFlagPolicy.Inherit,
            shouldDismissOnBackPress = true,
            shouldDismissOnClickOutside = false,
        ),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = Spacing.xxl, end = Spacing.xxl, bottom = Spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            if (context.isNotEmpty()) {
                Text(text = context, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val busy = sheet.sending == shown.key
            // Answer buttons stay off until this prompt has been on screen for PromptSheetLogic.ARM_MS (T12b); the
            // ViewModel drops an earlier answer anyway.
            val armed = answerable && sheet.shown?.let { it.key == shown.key && it.armed } == true
            when {
                withdrawn != null -> Withdrawn(withdrawn, actions)
                shown is Prompt.Approval -> ApprovalBody(shown, confirming = sheet.confirmAlways == shown.key, busy = busy || !armed, actions = actions)
                shown is Prompt.Clarify -> ClarifyBody(shown, busy, armed, actions)
                shown is Prompt.Secret -> SecretBody(shown, busy, armed, actions)
                shown is Prompt.OnPc -> OnPcBody(shown, actions)
            }
        }
    }
}

@Composable
private fun Headline(text: String) {
    Text(text = text, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
}

/** The command or file action in a mono block that scrolls both ways; [dim] for the withdrawn state. */
@Composable
private fun CommandBlock(text: String, dim: Boolean = false) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = text,
            style = AristotleTextStyles.toolSummary,
            color = MaterialTheme.colorScheme.onSurface,
            softWrap = false,
            modifier = Modifier
                .heightIn(max = 220.dp)
                .verticalScroll(rememberScrollState())
                .horizontalScroll(rememberScrollState())
                .padding(Spacing.m)
                .alpha(if (dim) DIM else 1f),
        )
    }
}

/** "Waits m:ss more" until [deadline] (from `timeout_s`); nothing when Hermes gave none. */
@Composable
private fun Countdown(deadline: Long?) {
    deadline ?: return
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(deadline) {
        while (now < deadline) {
            delay(TICK_MS)
            now = System.currentTimeMillis()
        }
    }
    val left = ((deadline - now).coerceAtLeast(0) + 999) / 1000
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Icon(painter = painterResource(R.drawable.ic_schedule), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(Sizes.chipIcon))
        Text(
            text = stringResource(R.string.prompt_waits, String.format(Locale.ROOT, "%d:%02d", left / 60, left % 60)),
            style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ── approval ──────────────────────────────────────────────────────────────────────────────────────

@Composable
private fun ApprovalBody(prompt: Prompt.Approval, confirming: Boolean, busy: Boolean, actions: PromptActions) {
    val action = prompt.command.startsWith("<") && prompt.command.endsWith(">")
    Headline(stringResource(if (action) R.string.prompt_approval_action_title else R.string.prompt_approval_title))
    if (prompt.command.isNotBlank()) CommandBlock(prompt.command)
    if (prompt.description.isNotBlank()) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(text = stringResource(R.string.prompt_reason), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(text = prompt.description, style = MaterialTheme.typography.bodyMedium)
        }
    }
    Countdown(prompt.deadlineMillis)
    val offers = prompt.choices.toSet()
    // Deny and Allow once side by side, equal width, in the thumb zone (Q41).
    // In the "Always allow" confirmation only Confirm / Back are left: Deny and Allow once would arm and then be dropped (D38).
    if (!confirming) Row(modifier = Modifier.fillMaxWidth().padding(top = Spacing.s), horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
        if (ApprovalChoice.Deny in offers) {
            OutlinedButton(
                onClick = { actions.choose(prompt.key, ApprovalChoice.Deny) },
                enabled = !busy,
                shape = ButtonShape,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.weight(1f).heightIn(min = Sizes.minTouchTarget),
            ) { Text(stringResource(R.string.prompt_deny)) }
        }
        if (ApprovalChoice.Once in offers) {
            Button(
                onClick = { actions.choose(prompt.key, ApprovalChoice.Once) },
                enabled = !busy,
                shape = ButtonShape,
                modifier = Modifier.weight(1f).heightIn(min = Sizes.minTouchTarget),
            ) { Text(stringResource(R.string.prompt_allow_once)) }
        }
    }
    if (confirming) {
        AlwaysConfirm(prompt.key, busy, actions)
    } else if (ApprovalChoice.Session in offers || ApprovalChoice.Always in offers) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            if (ApprovalChoice.Session in offers) {
                TextButton(onClick = { actions.choose(prompt.key, ApprovalChoice.Session) }, enabled = !busy, shape = ButtonShape) {
                    Text(stringResource(R.string.prompt_allow_session))
                }
            }
            if (ApprovalChoice.Always in offers) {
                TextButton(onClick = { actions.choose(prompt.key, ApprovalChoice.Always) }, enabled = !busy, shape = ButtonShape) {
                    Text(stringResource(R.string.prompt_allow_always))
                }
            }
        }
        if (ApprovalChoice.Always in offers) {
            Text(text = stringResource(R.string.prompt_always_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The second step of "Always allow": Hermes will not ask for this command again. */
@Composable
private fun AlwaysConfirm(key: String, busy: Boolean, actions: PromptActions) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Text(text = stringResource(R.string.prompt_always_confirm_title), style = MaterialTheme.typography.titleSmall)
            Text(text = stringResource(R.string.prompt_always_confirm_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = actions::cancelAlways, enabled = !busy, shape = ButtonShape) { Text(stringResource(R.string.prompt_cancel)) }
                Spacer(Modifier.size(Spacing.s))
                Button(onClick = { actions.confirmAlways(key) }, enabled = !busy, shape = ButtonShape) { Text(stringResource(R.string.prompt_allow_always)) }
            }
        }
    }
}

// ── clarify ───────────────────────────────────────────────────────────────────────────────────────

/** One question's draft: picked labels and the "Other answer" text (single select: [otherPicked] is the Other row). */
private class Draft {
    var picked by mutableStateOf(listOf<String>())
    var other by mutableStateOf("")
    var otherPicked by mutableStateOf(false)

    fun answer(question: ClarifyQuestion): ClarifyAnswer = when {
        question.choices.isEmpty() -> ClarifyAnswer(other = other)
        question.multiSelect -> ClarifyAnswer(picked = picked, other = other)
        otherPicked -> ClarifyAnswer(other = other)
        else -> ClarifyAnswer(picked = picked)
    }
}

@Composable
private fun ClarifyBody(prompt: Prompt.Clarify, busy: Boolean, armed: Boolean, actions: PromptActions) {
    val drafts: SnapshotStateMap<String, Draft> = remember(prompt.key) { mutableStateMapOf() }
    Headline(stringResource(R.string.prompt_clarify_title))
    val open = prompt.questions.filter { it.qid !in prompt.locked }
    open.forEach { question ->
        val draft = drafts.getOrPut(question.qid) { Draft() }
        Question(question, draft, busy)
    }
    Countdown(prompt.deadlineMillis)
    val answers = open.associate { it.qid to (drafts[it.qid] ?: Draft()).answer(it) }
    val ready = open.any { answers[it.qid]?.wire(it) != null }
    Row(modifier = Modifier.fillMaxWidth().padding(top = Spacing.s), horizontalArrangement = Arrangement.End) {
        Button(onClick = { actions.answerClarify(prompt.key, answers) }, enabled = ready && !busy && armed, shape = ButtonShape, modifier = Modifier.heightIn(min = Sizes.minTouchTarget)) {
            Text(stringResource(R.string.prompt_answer))
        }
    }
}

@Composable
private fun Question(question: ClarifyQuestion, draft: Draft, busy: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(text = question.question, style = MaterialTheme.typography.titleMedium)
        question.choices.forEach { choice ->
            val checked = choice in draft.picked && !(draft.otherPicked && !question.multiSelect)
            ChoiceRow(choice, checked, question.multiSelect, busy) {
                if (question.multiSelect) {
                    draft.picked = if (choice in draft.picked) draft.picked - choice else draft.picked + choice
                } else {
                    draft.picked = listOf(choice)
                    draft.otherPicked = false
                }
            }
        }
        if (question.choices.isNotEmpty() && !question.multiSelect) {
            ChoiceRow(stringResource(R.string.prompt_other), draft.otherPicked, multi = false, busy = busy, muted = true) {
                draft.otherPicked = true
                draft.picked = emptyList()
            }
        }
        val typing = question.choices.isEmpty() || question.multiSelect || draft.otherPicked
        if (typing) {
            OutlinedTextField(
                value = draft.other,
                onValueChange = { draft.other = it },
                enabled = !busy,
                label = { Text(stringResource(if (question.choices.isEmpty()) R.string.prompt_your_answer else R.string.prompt_other)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** A 56 dp radio or checkbox row (design.md §5 clarify sheet). */
@Composable
private fun ChoiceRow(label: String, checked: Boolean, multi: Boolean, busy: Boolean, muted: Boolean = false, onPick: () -> Unit) {
    val rowModifier = if (multi) {
        Modifier.toggleable(value = checked, enabled = !busy, role = Role.Checkbox, onValueChange = { onPick() })
    } else {
        Modifier.selectable(selected = checked, enabled = !busy, role = Role.RadioButton, onClick = onPick)
    }
    Row(modifier = Modifier.fillMaxWidth().heightIn(min = Sizes.listRowHeight).then(rowModifier), verticalAlignment = Alignment.CenterVertically) {
        if (multi) Checkbox(checked = checked, onCheckedChange = null) else RadioButton(selected = checked, onClick = null)
        Spacer(Modifier.size(Spacing.m))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
    }
}

// ── sudo / secret (Q51) ───────────────────────────────────────────────────────────────────────────

@Composable
private fun SecretBody(prompt: Prompt.Secret, busy: Boolean, armed: Boolean, actions: PromptActions) {
    // The value lives here only: `remember`, not `rememberSaveable`, so it is never written into saved state.
    val field = remember(prompt.key) { SecretField() }
    var visible by remember(prompt.key) { mutableStateOf(false) }
    SecureWindow()
    DisposableEffect(prompt.key) { onDispose { field.take() } }
    val sudo = prompt.kind == SecretKind.Sudo
    Headline(stringResource(if (sudo) R.string.prompt_sudo_title else R.string.prompt_secret_title))
    Text(
        text = stringResource(if (sudo) R.string.prompt_sudo_for else R.string.prompt_secret_for),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (prompt.label.isNotBlank()) CommandBlock(prompt.label)
    prompt.prompt?.let { Text(text = it, style = MaterialTheme.typography.bodyMedium) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        SecretInput(field, visible, enabled = !busy, label = stringResource(if (sudo) R.string.prompt_sudo_field else R.string.prompt_secret_field), modifier = Modifier.weight(1f))
        val toggle = stringResource(if (visible) R.string.prompt_hide_value else R.string.prompt_show_value)
        IconButton(onClick = { visible = !visible }, modifier = Modifier.semantics { contentDescription = toggle }) {
            Icon(painter = painterResource(if (visible) R.drawable.ic_visibility_off else R.drawable.ic_visibility), contentDescription = null)
        }
    }
    Text(text = stringResource(R.string.prompt_secret_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(modifier = Modifier.fillMaxWidth().padding(top = Spacing.s), horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
        OutlinedButton(
            onClick = { actions.declineSecret(prompt.key, field) },
            enabled = !busy && armed,
            shape = ButtonShape,
            modifier = Modifier.weight(1f).heightIn(min = Sizes.minTouchTarget),
        ) { Text(stringResource(R.string.prompt_cancel)) }
        Button(
            onClick = { actions.answerSecret(prompt.key, field) },
            enabled = !busy && armed && !field.isEmpty,
            shape = ButtonShape,
            modifier = Modifier.weight(1f).heightIn(min = Sizes.minTouchTarget),
        ) { Text(stringResource(R.string.prompt_send)) }
    }
}

/** FLAG_SECURE on the activity window too while the secret sheet is up (the sheet's own window has SecureOn). */
@Composable
private fun SecureWindow() {
    val window = LocalActivity.current?.window
    DisposableEffect(window) {
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

/**
 * A platform EditText, because Compose text fields cannot ask the keyboard not to learn
 * (`IME_FLAG_NO_PERSONALIZED_LEARNING`): password input type (no suggestions, no autocorrect), no copy or cut even
 * when shown ([SecretEditText]), no autofill, and no saved instance state. Cleared when [field] is emptied.
 */
@Composable
private fun SecretInput(field: SecretField, visible: Boolean, enabled: Boolean, label: String, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val textColor = colors.onSurface.toArgb()
    val hintColor = colors.onSurfaceVariant.toArgb()
    AndroidView(
        modifier = modifier.heightIn(min = Sizes.minTouchTarget),
        factory = { context ->
            // Copy, cut and share are never offered, also with "show" on (T12 review NIT).
            SecretEditText(context).apply {
                isSaveEnabled = false
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_DONE
                isSingleLine = true
                hint = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, FIELD_SP)
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                    override fun afterTextChanged(s: Editable?) {
                        field.text = s?.toString().orEmpty()
                    }
                })
            }
        },
        update = { edit ->
            val type = InputType.TYPE_CLASS_TEXT or
                (if (visible) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else InputType.TYPE_TEXT_VARIATION_PASSWORD) or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            if (edit.inputType != type) {
                val at = edit.selectionEnd
                edit.inputType = type
                edit.setSelection(at.coerceIn(0, edit.text.length))
            }
            edit.isEnabled = enabled
            edit.setTextColor(textColor)
            edit.setHintTextColor(hintColor)
            edit.hint = label
            if (field.isEmpty && edit.text.isNotEmpty()) edit.text.clear()
        },
    )
}

// ── vault prompts and the withdrawn state ─────────────────────────────────────────────────────────

@Composable
private fun OnPcBody(prompt: Prompt.OnPc, actions: PromptActions) {
    Headline(stringResource(R.string.prompt_on_pc_title))
    Text(text = stringResource(R.string.prompt_on_pc_body), style = MaterialTheme.typography.bodyMedium)
    prompt.detail?.takeIf { it.isNotBlank() }?.let { CommandBlock(it) }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Button(onClick = actions::hide, shape = ButtonShape) { Text(stringResource(R.string.prompt_close)) }
    }
}

/** Settled on another screen (or timed out, or the turn ended) while this sheet showed it; closes by itself. */
@Composable
private fun Withdrawn(prompt: Prompt, actions: PromptActions) {
    LaunchedEffect(prompt.key) {
        delay(WITHDRAWN_MS)
        actions.closeWithdrawn()
    }
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
        Icon(painter = painterResource(R.drawable.ic_task_alt), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Headline(stringResource(R.string.prompt_withdrawn_title))
            Text(text = stringResource(R.string.prompt_withdrawn_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    summary(prompt)?.let { CommandBlock(it, dim = true) }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Button(onClick = actions::closeWithdrawn, shape = ButtonShape) { Text(stringResource(R.string.prompt_close)) }
    }
}

/** What the withdrawn sheet dims: the command, the first question, or what a password was for. */
private fun summary(prompt: Prompt): String? = when (prompt) {
    is Prompt.Approval -> prompt.command
    is Prompt.Clarify -> prompt.questions.firstOrNull()?.question
    is Prompt.Secret -> prompt.label
    is Prompt.OnPc -> prompt.detail
}?.takeIf { it.isNotBlank() }

private const val DIM = 0.5f
private const val TICK_MS = 1_000L
private const val WITHDRAWN_MS = 3_000L
private const val FIELD_SP = 16f
