package io.github.justcallmesu.aristotle.ui.sessions

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.justcallmesu.aristotle.R
import io.github.justcallmesu.aristotle.data.HistoryGrouping
import io.github.justcallmesu.aristotle.data.HistorySort
import io.github.justcallmesu.aristotle.data.StatusBucket
import io.github.justcallmesu.aristotle.ui.theme.AristotleTextStyles
import io.github.justcallmesu.aristotle.ui.theme.ButtonShape
import io.github.justcallmesu.aristotle.ui.theme.Spacing

/**
 * Q81d: one quiet line above the history, at the end of the row: the sort icon and "Date · Updated". Tapping opens
 * the "Group and sort" sheet, so Home keeps a single control for it.
 */
@Composable
internal fun ViewBarRow(bar: LedgerItem.ViewBar, onOpen: () -> Unit) {
    val summary = stringResource(R.string.sessions_view_summary, groupingLabel(bar.grouping), sortLabel(bar.sort))
    val label = stringResource(R.string.sessions_view_open)
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = Spacing.listGutter, end = Spacing.s, top = Spacing.xs),
        horizontalArrangement = Arrangement.End,
    ) {
        TextButton(
            onClick = onOpen,
            shape = ButtonShape,
            contentPadding = VIEW_BAR_PADDING,
            modifier = Modifier.semantics { contentDescription = "$label: $summary" },
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_sort),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(VIEW_BAR_ICON),
            )
            Text(
                text = summary,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = Spacing.xs),
            )
        }
    }
}

/**
 * A project section header: chevron, name and Hermes' session count. Tap folds or opens it (kept on this phone); a long
 * press offers this phone's order (move up / down, back to the PC's order).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ProjectHeaderRow(header: LedgerItem.ProjectHeader, actions: ListActions) {
    var menu by remember { mutableStateOf(false) }
    val name = if (header.home) stringResource(R.string.sessions_project_home) else header.label
    val count = pluralStringResource(R.plurals.sessions_project_sessions, header.count, header.count)
    val toggle = stringResource(if (header.collapsed) R.string.sessions_project_expand else R.string.sessions_project_collapse)
    val orderLabel = stringResource(R.string.sessions_project_actions)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = { actions.toggleProject(header.id) }, onLongClick = { menu = true }, role = Role.Button)
                .semantics(mergeDescendants = true) {
                    heading()
                    contentDescription = "$name, $count"
                    onClick(label = toggle) { actions.toggleProject(header.id); true }
                    onLongClick(label = orderLabel) { menu = true; true }
                }
                .heightIn(min = PROJECT_HEADER_HEIGHT)
                .padding(start = Spacing.listGutter, end = Spacing.listGutter, top = Spacing.s),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            Icon(
                painter = painterResource(if (header.collapsed) R.drawable.ic_chevron_right else R.drawable.ic_expand_more),
                contentDescription = null,
                tint = muted,
                modifier = Modifier.size(PROJECT_CHEVRON),
            )
            Text(
                text = name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Text(text = header.count.toString(), style = AristotleTextStyles.tabular(MaterialTheme.typography.labelMedium), color = muted, maxLines = 1)
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, shape = MaterialTheme.shapes.medium, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
            if (!header.home) {
                DropdownMenuItem(text = { Text(stringResource(R.string.sessions_project_move_up)) }, onClick = { menu = false; actions.moveProject(header.id, -1) })
                DropdownMenuItem(text = { Text(stringResource(R.string.sessions_project_move_down)) }, onClick = { menu = false; actions.moveProject(header.id, 1) })
            }
            DropdownMenuItem(text = { Text(stringResource(R.string.sessions_project_order_reset)) }, onClick = { menu = false; actions.resetProjectOrder() })
        }
    }
}

/** Under a project's rows: "Load more", "Loading…", or the failure with Retry. */
@Composable
internal fun ProjectFooterRow(footer: LedgerItem.ProjectFooter, actions: ListActions) {
    when (footer.more) {
        ProjectMore.More -> Box(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.s)) {
            TextButton(onClick = { actions.moreInProject(footer.id) }, shape = ButtonShape) { Text(stringResource(R.string.sessions_load_more)) }
        }
        ProjectMore.Loading -> QuietLine(stringResource(R.string.sessions_loading_more))
        ProjectMore.Failed -> RetryLine(stringResource(R.string.sessions_project_failed)) { actions.moreInProject(footer.id) }
    }
}

/** `projects.tree` failed: the reason stays in the log; the line offers Retry. */
@Composable
internal fun ProjectsFailedRow(onRetry: () -> Unit) = RetryLine(stringResource(R.string.sessions_projects_failed), onRetry)

@Composable
private fun RetryLine(text: String, onRetry: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(start = Spacing.listGutter, end = Spacing.s), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).padding(vertical = Spacing.l),
        )
        TextButton(onClick = onRetry, shape = ButtonShape) { Text(stringResource(R.string.sessions_history_retry)) }
    }
}

@Composable
internal fun groupingLabel(grouping: HistoryGrouping): String = stringResource(
    when (grouping) {
        HistoryGrouping.Date -> R.string.sessions_group_date
        HistoryGrouping.Project -> R.string.sessions_group_project
        HistoryGrouping.Status -> R.string.sessions_group_status
    },
)

@Composable
internal fun sortLabel(sort: HistorySort): String = stringResource(
    when (sort) {
        HistorySort.Updated -> R.string.sessions_sort_updated
        HistorySort.Created -> R.string.sessions_sort_created
        HistorySort.Status -> R.string.sessions_sort_status
        HistorySort.Tokens -> R.string.sessions_sort_tokens
        HistorySort.Cost -> R.string.sessions_sort_cost
    },
)

@Composable
internal fun statusLabel(bucket: StatusBucket): String = stringResource(
    when (bucket) {
        StatusBucket.NeedsInput -> R.string.sessions_status_needs_input
        StatusBucket.Working -> R.string.sessions_status_working
        StatusBucket.Unread -> R.string.sessions_status_unread
        StatusBucket.Draft -> R.string.sessions_status_draft
        StatusBucket.Idle -> R.string.sessions_status_idle
    },
)

private val VIEW_BAR_ICON = 18.dp
private val VIEW_BAR_PADDING = PaddingValues(horizontal = Spacing.s, vertical = 0.dp)
private val PROJECT_HEADER_HEIGHT = 48.dp
private val PROJECT_CHEVRON = 20.dp
