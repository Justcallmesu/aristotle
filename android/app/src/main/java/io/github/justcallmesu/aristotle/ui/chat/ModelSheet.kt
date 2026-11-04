package io.github.justcallmesu.aristotle.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import io.github.justcallmesu.aristotle.R
import io.github.justcallmesu.aristotle.data.chat.ChatState
import io.github.justcallmesu.aristotle.data.chat.ModelGroup
import io.github.justcallmesu.aristotle.data.chat.ReasoningLevels
import io.github.justcallmesu.aristotle.ui.theme.AristotleTextStyles
import io.github.justcallmesu.aristotle.ui.theme.ButtonShape
import io.github.justcallmesu.aristotle.ui.theme.Sizes
import io.github.justcallmesu.aristotle.ui.theme.Spacing

/** What the model sheet and its confirmation can ask of the ViewModel. */
interface ModelActions {
    fun open()
    fun close()
    fun retry()
    fun pickModel(group: ModelGroup, model: String)
    fun pickReasoning(effort: String)
    fun confirm()
    fun cancelConfirm()
    fun messageShown()
}

/** Where the sheet's LazyColumn puts the current model: item 0 is the head, 1 the reasoning chips when there are any. */
object ModelSheetRows {
    /** [models] of one provider; [current] is the session's model when this provider is the session's, else null. */
    data class Group(val models: List<String>, val current: String?)

    /** The LazyColumn index of the current model's row (each group adds a head item), null when it is not listed. */
    fun currentRow(hasReasoning: Boolean, groups: List<Group>): Int? {
        var index = if (hasReasoning) 2 else 1
        for (group in groups) {
            index += 1
            val at = group.current?.let { group.models.indexOf(it) } ?: -1
            if (at >= 0) return index + at
            index += group.models.size
        }
        return null
    }
}

/** The label of a reasoning level (Hermes' value when it is not one of its known levels). */
@Composable
fun reasoningLabel(level: String): String = when (level) {
    ReasoningLevels.OFF -> stringResource(R.string.reasoning_none)
    "minimal" -> stringResource(R.string.reasoning_minimal)
    "low" -> stringResource(R.string.reasoning_low)
    "medium" -> stringResource(R.string.reasoning_medium)
    "high" -> stringResource(R.string.reasoning_high)
    "xhigh" -> stringResource(R.string.reasoning_xhigh)
    "max" -> stringResource(R.string.reasoning_max)
    "ultra" -> stringResource(R.string.reasoning_ultra)
    else -> level
}

/**
 * The model and reasoning sheet (T15), opened from the chat's top bar: the reasoning levels the current model takes
 * (none when the catalog says it has no reasoning), then the models Hermes offers this session, grouped by provider in
 * Hermes' order, the current one selected. A pick closes the sheet; its outcome shows as a snackbar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSheet(sheet: ModelSheetState, chat: ChatState, actions: ModelActions) {
    ModalBottomSheet(
        onDismissRequest = actions::close,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        val catalog = sheet.catalog
        val model = chat.model ?: catalog?.model
        val provider = chat.provider ?: catalog?.provider
        val choices = ReasoningLevels.choices(catalog?.capabilities(provider, model))
        // S5: the current model's row is brought to the top once, when the catalog has arrived.
        val listState = rememberLazyListState()
        val currentRow = ModelSheetRows.currentRow(
            hasReasoning = choices.isNotEmpty(),
            groups = catalog?.groups.orEmpty().map { ModelSheetRows.Group(it.models, if (it.matches(provider)) model else null) },
        )
        LaunchedEffect(currentRow != null) {
            currentRow?.let { listState.scrollToItem(it) }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(start = Spacing.xxl, end = Spacing.xxl, bottom = Spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            item(key = "head") { SheetHead(model, provider, running = chat.running) }
            if (choices.isNotEmpty()) {
                item(key = "reasoning") { ReasoningChoices(choices, chat, enabled = !sheet.sending, onPick = actions::pickReasoning) }
            }
            when {
                catalog == null && sheet.problem != null -> item(key = "failed") { LoadFailed(sheet.problem, actions::retry) }
                catalog == null -> item(key = "loading") { Quiet(stringResource(R.string.model_loading)) }
                catalog.groups.isEmpty() -> item(key = "empty") { Quiet(stringResource(R.string.model_empty)) }
                else -> catalog.groups.forEach { group ->
                    item(key = "g-${group.slug}") { GroupHead(group) }
                    items(group.models, key = { "m-${group.slug}-$it" }) { name ->
                        ModelRow(group, name, current = name == model && group.matches(provider), enabled = !sheet.sending, onPick = actions::pickModel)
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetHead(model: String?, provider: String?, running: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(text = stringResource(R.string.model_sheet_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        val now = listOfNotNull(model, provider).joinToString(" · ")
        if (now.isNotEmpty()) {
            Text(text = stringResource(R.string.model_current, now), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (running) {
            Text(text = stringResource(R.string.model_running_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The levels as chips (design.md §3: small shape); the session's level selected; a clamped level says what is sent. */
@Composable
private fun ReasoningChoices(choices: List<String>, chat: ChatState, enabled: Boolean, onPick: (String) -> Unit) {
    val current = ReasoningLevels.current(chat.reasoningEffort)
    Column(modifier = Modifier.padding(top = Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Text(
            text = stringResource(R.string.model_reasoning),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { heading() },
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
            verticalArrangement = Arrangement.spacedBy(Spacing.s),
            modifier = Modifier.selectableGroup(),
        ) {
            choices.forEach { level ->
                FilterChip(
                    selected = level == current,
                    onClick = { if (level != current) onPick(level) },
                    enabled = enabled,
                    label = { Text(reasoningLabel(level)) },
                )
            }
        }
        ReasoningLevels.clampedTo(chat.reasoningEffort, chat.reasoningWire)?.let { sent ->
            Text(text = stringResource(R.string.model_reasoning_sends, reasoningLabel(sent)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun GroupHead(group: ModelGroup) {
    Column(modifier = Modifier.padding(top = Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(
            text = group.name,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { heading() },
        )
        group.warning?.let { Text(text = it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** A 56 dp radio row (design.md §4 list/choice row); a locked model is shown, not selectable. */
@Composable
private fun ModelRow(group: ModelGroup, name: String, current: Boolean, enabled: Boolean, onPick: (ModelGroup, String) -> Unit) {
    val locked = name in group.unavailable
    val active = enabled && !locked
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Sizes.listRowHeight)
            .selectable(selected = current, enabled = active, role = Role.RadioButton) { if (!current) onPick(group, name) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        RadioButton(selected = current, onClick = null, enabled = active)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = AristotleTextStyles.codeInline,
                color = if (locked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (locked) Text(text = stringResource(R.string.model_locked), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Quiet(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Spacing.l))
}

@Composable
private fun LoadFailed(problem: String, onRetry: () -> Unit) {
    Column(modifier = Modifier.padding(top = Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Text(text = stringResource(R.string.model_failed, problem), style = MaterialTheme.typography.bodyMedium)
        Button(onClick = onRetry, shape = ButtonShape) { Text(stringResource(R.string.model_retry)) }
    }
}

/** Hermes asked before a guarded switch (cost, data policy, large context): its own words, then the user decides. */
@Composable
fun ModelConfirmDialog(confirm: ModelConfirm, actions: ModelActions) {
    // The dialog just appeared: "Switch anyway" ignores taps for the arming time (a tap meant for what was under it).
    var armed by remember(confirm) { mutableStateOf(false) }
    LaunchedEffect(confirm) {
        delay(ComposerLogic.ARM_MS)
        armed = true
    }
    AlertDialog(
        onDismissRequest = actions::cancelConfirm,
        title = { Text(stringResource(R.string.model_confirm_title, confirm.model)) },
        text = { Text(confirm.message) },
        confirmButton = { Button(onClick = actions::confirm, enabled = armed, shape = ButtonShape) { Text(stringResource(R.string.model_confirm_switch)) } },
        dismissButton = { TextButton(onClick = actions::cancelConfirm, shape = ButtonShape) { Text(stringResource(R.string.model_confirm_cancel)) } },
        shape = MaterialTheme.shapes.extraLarge,
    )
}
