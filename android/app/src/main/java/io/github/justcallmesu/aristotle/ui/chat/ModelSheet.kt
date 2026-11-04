package io.github.justcallmesu.aristotle.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import io.github.justcallmesu.aristotle.R
import io.github.justcallmesu.aristotle.data.chat.ChatState
import io.github.justcallmesu.aristotle.data.chat.ModelGroup
import io.github.justcallmesu.aristotle.data.chat.ReasoningLevels
import io.github.justcallmesu.aristotle.ui.components.AristotleSlider
import io.github.justcallmesu.aristotle.ui.theme.AristotleTextStyles
import io.github.justcallmesu.aristotle.ui.theme.ButtonShape
import io.github.justcallmesu.aristotle.ui.theme.FieldShape
import io.github.justcallmesu.aristotle.ui.theme.Sizes
import io.github.justcallmesu.aristotle.ui.theme.Spacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

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

/** Superseded by [ModelFilter.currentRow] (the v2 sheet has no head or chip items in its list); kept for its existing test. */
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
 * The model and reasoning sheet (T15, v2 §5.3), opened from the chat's top bar: a search field over every provider's
 * models, the providers as collapsible sections in Hermes' order (the session's open), the current model checked, and
 * the reasoning slider in a footer when the model takes levels. A pick closes the sheet; its outcome shows as a snackbar.
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
        var query by remember { mutableStateOf("") }
        var toggled by remember { mutableStateOf(emptySet<String>()) }
        val groups = catalog?.groups.orEmpty()
        val views = ModelFilter.views(groups, query, provider, toggled)
        val searching = ModelFilter.normalize(query).isNotEmpty()
        val footerShown = choices.isNotEmpty() && !searching
        val listState = rememberLazyListState()
        // S5: on open, scroll by the least amount that reveals the current row, so collapsed providers above stay visible.
        LaunchedEffect(catalog != null) {
            if (catalog == null) return@LaunchedEffect
            val row = ModelFilter.currentRow(ModelFilter.views(catalog.groups, "", provider, emptySet()), provider, model) ?: return@LaunchedEffect
            val visible = snapshotFlow { listState.layoutInfo.visibleItemsInfo }.first { it.isNotEmpty() }
            val to = ModelFilter.revealIndex(visible.first().index, visible.last().index, visible.size, row)
            if (to != null) listState.scrollToItem(to)
        }
        Column(modifier = Modifier.fillMaxWidth()) {
            SheetHead(model, provider, running = chat.running && !footerShown)
            if (groups.isNotEmpty()) {
                SearchField(query, ModelFilter.modelCount(groups), onChange = { query = it })
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = Spacing.l),
            ) {
                when {
                    catalog == null && sheet.problem != null -> item(key = "failed") { LoadFailed(sheet.problem, actions::retry) }
                    catalog == null -> item(key = "loading") { Quiet(stringResource(R.string.model_loading)) }
                    groups.isEmpty() -> item(key = "empty") { Quiet(stringResource(R.string.model_empty)) }
                    views.isEmpty() -> item(key = "none") { Quiet(stringResource(R.string.model_search_none, ModelFilter.normalize(query))) }
                    else -> views.forEach { view ->
                        item(key = "g-${view.group.slug}") {
                            GroupHead(view, query, onToggle = { toggled = if (view.group.slug in toggled) toggled - view.group.slug else toggled + view.group.slug })
                        }
                        if (view.expanded) {
                            items(view.shown, key = { "m-${view.group.slug}-$it" }) { name ->
                                ModelRow(view.group, name, query, current = name == model && view.group.matches(provider), enabled = !sheet.sending, onPick = actions::pickModel)
                            }
                        }
                    }
                }
            }
            if (footerShown) {
                EffortFooter(choices, chat, enabled = !sheet.sending, onPick = actions::pickReasoning)
            }
        }
    }
}

@Composable
private fun SheetHead(model: String?, provider: String?, running: Boolean) {
    Column(
        modifier = Modifier.padding(horizontal = Spacing.xxl).padding(bottom = Spacing.m),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
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

/** Local search over the catalog: a [FieldShape] field with a leading search icon and a clear button. */
@Composable
private fun SearchField(query: String, count: Int, onChange: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val hint = stringResource(R.string.model_search_hint, count)
    BasicTextField(
        value = query,
        onValueChange = onChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
        cursorBrush = SolidColor(colors.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        modifier = Modifier
            .padding(horizontal = Spacing.xxl)
            .padding(bottom = Spacing.s)
            .fillMaxWidth()
            .heightIn(min = Sizes.listRowHeight)
            .background(colors.surfaceContainerHighest, FieldShape)
            .semantics { contentDescription = hint },
        decorationBox = { inner ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = Spacing.l)) {
                Icon(painter = painterResource(R.drawable.ic_search), contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(Sizes.fieldIcon))
                Box(contentAlignment = Alignment.CenterStart, modifier = Modifier.weight(1f).padding(horizontal = Spacing.m)) {
                    if (query.isEmpty()) Text(hint, style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant, maxLines = 1)
                    inner()
                }
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onChange("") }) {
                        Icon(painter = painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.sessions_search_clear), tint = colors.onSurfaceVariant)
                    }
                } else {
                    Spacer(Modifier.width(Spacing.l))
                }
            }
        },
    )
}

/** The reasoning slider: only the model's levels, "Off" / "Ultra" under the ends, one note line. Hidden by the caller when there are none. */
@Composable
private fun EffortFooter(choices: List<String>, chat: ChatState, enabled: Boolean, onPick: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val current = ReasoningLevels.current(chat.reasoningEffort) ?: choices.first()
    val labels = choices.associateWith { reasoningLabel(it) }
    Column {
        HorizontalDivider(color = colors.outlineVariant)
        Column(
            modifier = Modifier.padding(start = Spacing.xxl, end = Spacing.xxl, top = Spacing.m, bottom = Spacing.l),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Text(
                text = stringResource(R.string.model_reasoning),
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant,
                modifier = Modifier.semantics { heading() },
            )
            AristotleSlider(
                levels = choices,
                current = current,
                label = labels::getValue,
                onPick = onPick,
                enabled = enabled,
                startLabel = labels.getValue(choices.first()),
                endLabel = labels.getValue(choices.last()),
            )
            val sent = ReasoningLevels.clampedTo(chat.reasoningEffort, chat.reasoningWire)
            val note = when {
                sent != null -> stringResource(R.string.model_reasoning_sends, reasoningLabel(sent))
                chat.running -> stringResource(R.string.model_running_note)
                else -> null
            }
            note?.let { Text(text = it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
        }
    }
}

/** Provider header, 56 dp: name, "· 44 models" (or "3 of 13" while searching), chevron; the provider's warning under it when open. */
@Composable
private fun GroupHead(view: ProviderView, query: String, onToggle: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val count = if (view.filtered) stringResource(R.string.model_count_of, view.shown.size, view.total) else stringResource(R.string.model_count, view.total)
    val state = stringResource(if (view.expanded) R.string.model_group_open else R.string.model_group_closed)
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Sizes.listRowHeight)
                .clickable(enabled = !view.filtered, role = Role.Button, onClick = onToggle)
                .padding(horizontal = Spacing.xxl)
                .semantics(mergeDescendants = true) { stateDescription = state; heading() },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            Text(text = view.group.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Text(text = "· $count", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1)
            Spacer(Modifier.weight(1f))
            if (!view.filtered) {
                Icon(
                    painter = painterResource(R.drawable.ic_expand_more),
                    contentDescription = null,
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.rotate(if (view.expanded) 180f else 0f),
                )
            }
        }
        if (view.expanded) {
            view.group.warning?.let {
                Text(text = it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, modifier = Modifier.padding(horizontal = Spacing.xxl).padding(bottom = Spacing.s))
            }
        }
    }
}

/** A 48 dp mono row; the current one is a secondaryContainer row inset 12 dp with a trailing check; a locked model is shown, not selectable. */
@Composable
private fun ModelRow(group: ModelGroup, name: String, query: String, current: Boolean, enabled: Boolean, onPick: (ModelGroup, String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val locked = name in group.unavailable
    val active = enabled && !locked
    val text = buildAnnotatedString {
        append(name)
        ModelFilter.matchRange(name, query)?.let { addStyle(SpanStyle(textDecoration = TextDecoration.Underline), it.first, it.last + 1) }
    }
    Row(
        modifier = Modifier
            .padding(horizontal = Spacing.m)
            .fillMaxWidth()
            .heightIn(min = ROW_HEIGHT)
            .clip(MaterialTheme.shapes.medium)
            .background(if (current) colors.secondaryContainer else Color.Transparent)
            .selectable(selected = current, enabled = active, role = Role.RadioButton) { if (!current) onPick(group, name) }
            .padding(horizontal = Spacing.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = text,
                style = AristotleTextStyles.codeInline,
                color = when {
                    locked -> colors.onSurfaceVariant
                    current -> colors.onSecondaryContainer
                    else -> colors.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (locked) Text(text = stringResource(R.string.model_locked), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        if (current) {
            Icon(painter = painterResource(R.drawable.ic_check), contentDescription = null, tint = colors.onSecondaryContainer, modifier = Modifier.size(Sizes.fieldIcon))
        }
    }
}

/** Model rows are 48 dp (design-notes §5.3), the touch-target minimum. */
private val ROW_HEIGHT = Sizes.minTouchTarget

@Composable
private fun Quiet(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = Spacing.xxl, vertical = Spacing.l))
}

@Composable
private fun LoadFailed(problem: String, onRetry: () -> Unit) {
    Column(modifier = Modifier.padding(horizontal = Spacing.xxl, vertical = Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
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
