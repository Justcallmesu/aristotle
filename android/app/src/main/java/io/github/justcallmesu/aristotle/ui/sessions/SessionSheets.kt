package io.github.justcallmesu.aristotle.ui.sessions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import io.github.justcallmesu.aristotle.R
import io.github.justcallmesu.aristotle.data.HistoryGrouping
import io.github.justcallmesu.aristotle.data.HistorySort
import io.github.justcallmesu.aristotle.data.ProfileOption
import io.github.justcallmesu.aristotle.ui.theme.ButtonShape
import io.github.justcallmesu.aristotle.ui.theme.Sizes
import io.github.justcallmesu.aristotle.ui.theme.Spacing

/** What the list's sheets and the rename dialog can ask of the ViewModel. */
interface SheetActions {
    fun closeProfiles()
    fun retryProfiles()
    fun selectProfile(option: ProfileOption)
    fun closeActions()
    fun togglePin(row: SessionRow)
    fun startRename(row: SessionRow)
    fun cancelRename()
    fun rename(title: String)
    fun closeView()
    fun setGrouping(grouping: HistoryGrouping)
    fun setSort(sort: HistorySort)
}

/** The profile switcher, a row's actions and the rename dialog, whichever [state] has open. */
@Composable
fun SessionSheets(state: SessionsUiState, actions: SheetActions) {
    state.picker?.let { ProfileSheet(it, state.profile, actions) }
    state.actionsFor?.let { RowActionsSheet(it, actions) }
    state.renaming?.let { RenameDialog(it, state.renameBusy, actions) }
    if (state.viewSheet) ViewSheet(state.view.grouping, state.view.sort, state.costSort, actions)
}

/**
 * Q81d "Group and sort": the Desktop's groupings (date, project, status) and sort keys (updated, created, status, tokens,
 * cost) as two `FilterChip` rows. Cost is offered only when some session reports spend (`sidebar-archive.ts:144-148`).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ViewSheet(grouping: HistoryGrouping, sort: HistorySort, costShown: Boolean, actions: SheetActions) {
    ModalBottomSheet(
        onDismissRequest = actions::closeView,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = Spacing.xxl, end = Spacing.xxl, bottom = Spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            Text(text = stringResource(R.string.sessions_view_open), style = MaterialTheme.typography.titleLarge)
            Text(
                text = stringResource(R.string.sessions_view_scope),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ChipGroup(stringResource(R.string.sessions_view_group), HistoryGrouping.entries, grouping, { groupingLabel(it) }, actions::setGrouping)
            val sorts = HistorySort.entries.filter { it != HistorySort.Cost || costShown }
            ChipGroup(stringResource(R.string.sessions_view_sort), sorts, sort, { sortLabel(it) }, actions::setSort)
            if (!costShown) {
                Text(
                    text = stringResource(R.string.sessions_view_cost_hidden),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** A titled row of single-choice `FilterChip`s (32 dp, `shapes.small`; Q67), wrapping on narrow screens. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChipGroup(title: String, options: List<T>, selected: T, label: @Composable (T) -> String, onPick: (T) -> Unit) {
    Text(text = title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = Spacing.s).semantics { heading() })
    FlowRow(modifier = Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        options.forEach { option ->
            val text = label(option)
            FilterChip(
                selected = option == selected,
                onClick = { onPick(option) },
                label = { Text(text) },
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.semantics { role = Role.RadioButton },
            )
        }
    }
}

/**
 * design.md §5 profile chip -> `ModalBottomSheet` with a radio list of the profiles Hermes serves (mockup 2b); picking
 * one re-lists the sessions. [current]: the listed profile (null = the launch profile, the roster's own row).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileSheet(picker: ProfilePicker, current: String?, actions: SheetActions) {
    ModalBottomSheet(
        onDismissRequest = actions::closeProfiles,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        // §5.6: rows on a 12 dp column inset with 12 dp inside (radio stays on the 24 dp line); text on the 24 dp line.
        val text = Modifier.padding(horizontal = Spacing.m)
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = Spacing.m, end = Spacing.m, bottom = Spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Text(text = stringResource(R.string.sessions_profiles_title), style = MaterialTheme.typography.titleLarge, modifier = text)
            Text(
                text = stringResource(R.string.sessions_profiles_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = text,
            )
            val options = picker.options
            when {
                options != null -> Column(modifier = Modifier.padding(top = Spacing.s).selectableGroup()) {
                    options.forEach { option ->
                        val selected = if (option.own) current == null || current == option.name else current == option.name
                        ProfileRow(option, selected) { actions.selectProfile(option) }
                    }
                }
                picker.problem != null -> {
                    Text(
                        text = stringResource(R.string.sessions_profiles_failed, picker.problem),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = text.padding(top = Spacing.s),
                    )
                    Button(onClick = actions::retryProfiles, shape = ButtonShape, modifier = text) { Text(stringResource(R.string.conn_retry_now)) }
                }
                else -> Text(
                    text = stringResource(R.string.sessions_profiles_loading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = text.padding(top = Spacing.s),
                )
            }
        }
    }
}

/** A 56 dp radio row (design.md §4 list/choice row); pressed state is a 16 dp Soft shape (design-notes §5.6). */
@Composable
private fun ProfileRow(option: ProfileOption, selected: Boolean, onPick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Sizes.listRowHeight)
            .clip(MaterialTheme.shapes.medium)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onPick)
            .padding(horizontal = Spacing.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(modifier = Modifier.weight(1f)) {
            Text(text = option.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (option.displayName != option.name) {
                Text(
                    text = option.displayName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Long press on a row: rename (in Hermes) and pin (on this phone only, and it says so). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RowActionsSheet(row: SessionRow, actions: SheetActions) {
    ModalBottomSheet(
        onDismissRequest = actions::closeActions,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.xxl)) {
            Text(
                text = row.title ?: stringResource(R.string.session_untitled),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = Spacing.xxl, end = Spacing.xxl, top = Spacing.s),
            )
            // Q75: the one-line ledger drops the message count; the long-press sheet keeps it.
            Text(
                text = pluralStringResource(R.plurals.session_messages, row.messageCount, row.messageCount),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = Spacing.xxl, end = Spacing.xxl, bottom = Spacing.s),
            )
            ActionRow(R.drawable.ic_edit, stringResource(R.string.session_rename), null) { actions.startRename(row) }
            ActionRow(
                R.drawable.ic_push_pin,
                stringResource(if (row.pinned) R.string.session_unpin else R.string.session_pin),
                stringResource(R.string.session_pin_scope),
            ) { actions.togglePin(row) }
        }
    }
}

@Composable
private fun ActionRow(icon: Int, label: String, detail: String?, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Sizes.listRowHeight)
            .selectable(selected = false, role = Role.Button, onClick = onClick)
            .padding(horizontal = Spacing.xxl),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.l),
    ) {
        Icon(painter = painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(Sizes.fieldIcon))
        Column {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
            detail?.let { Text(text = it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/** Rename in Hermes (`session.mutate rename`); the new title shows on the PC too. */
@Composable
private fun RenameDialog(row: SessionRow, busy: Boolean, actions: SheetActions) {
    var title by rememberSaveable(row.key) { mutableStateOf(row.title.orEmpty()) }
    AlertDialog(
        onDismissRequest = actions::cancelRename,
        title = { Text(stringResource(R.string.session_rename_title)) },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                enabled = !busy,
                singleLine = true,
                label = { Text(stringResource(R.string.session_rename_field)) },
                shape = MaterialTheme.shapes.extraSmall,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { actions.rename(title) }),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            Button(onClick = { actions.rename(title) }, enabled = !busy && title.isNotBlank(), shape = ButtonShape) {
                Text(stringResource(if (busy) R.string.session_rename_busy else R.string.session_rename_save))
            }
        },
        dismissButton = {
            TextButton(onClick = actions::cancelRename, enabled = !busy, shape = ButtonShape) { Text(stringResource(R.string.session_rename_cancel)) }
        },
        shape = MaterialTheme.shapes.extraLarge,
    )
}
