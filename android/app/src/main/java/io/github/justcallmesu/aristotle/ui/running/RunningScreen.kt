package io.github.justcallmesu.aristotle.ui.running

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.justcallmesu.aristotle.R
import io.github.justcallmesu.aristotle.data.ProfileOption
import io.github.justcallmesu.aristotle.data.RunningGroup
import io.github.justcallmesu.aristotle.data.RunningRow
import io.github.justcallmesu.aristotle.data.RunningStatus
import io.github.justcallmesu.aristotle.ui.components.RunningSpinner
import io.github.justcallmesu.aristotle.ui.components.SubPageTopBar
import io.github.justcallmesu.aristotle.ui.components.groupedRowShape
import io.github.justcallmesu.aristotle.ui.components.GroupedRowGap
import io.github.justcallmesu.aristotle.ui.theme.AristotleTextStyles
import io.github.justcallmesu.aristotle.ui.theme.ButtonShape
import io.github.justcallmesu.aristotle.ui.theme.Sizes
import io.github.justcallmesu.aristotle.ui.theme.Spacing

/** What a row tap opens: the session's chat in its own profile (null = launch), as Home's rows do. */
data class RunningOpen(val sessionId: String, val title: String?, val profile: String?)

/**
 * "Running" (TY1, Q82 D1): what runs across every Hermes profile, grouped by profile, waiting first. Reads only while
 * this page is resumed and online ([RunningViewModel.setVisible]); pull to refresh reads at once. A row opens its chat,
 * where the status stack shows the subagents and processes and offers Stop.
 */
@Composable
fun RunningScreen(viewModel: RunningViewModel, onBack: () -> Unit, onOpen: (RunningOpen) -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel) {
        viewModel.setVisible(true)
        onPauseOrDispose { viewModel.setVisible(false) }
    }
    val nav = LocalRunningNav.current
    LaunchedEffect(state.snapshot) { state.snapshot?.let { nav?.report(it.count) } }
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
            SubPageTopBar(stringResource(R.string.running_title), onBack)
            if (!state.online) Banner(stringResource(R.string.running_offline))
            val snapshot = state.snapshot
            val problem = state.problem
            when {
                problem != null && state.online && snapshot == null -> ProblemBody(problem, viewModel::retry)
                snapshot == null -> QuietLine(stringResource(R.string.running_loading), Modifier.padding(horizontal = Spacing.listGutter, vertical = Spacing.xxl))
                else -> RunningList(state, viewModel, onOpen)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RunningList(state: RunningUiState, viewModel: RunningViewModel, onOpen: (RunningOpen) -> Unit) {
    val snapshot = state.snapshot ?: return
    val bottom = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom).asPaddingValues()
    PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = viewModel::refresh, modifier = Modifier.fillMaxSize()) {
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottom.calculateBottomPadding() + Spacing.l)) {
            state.problem?.let { item(key = "problem") { Banner(stringResource(R.string.running_problem, it)) } }
            if (snapshot.groups.isEmpty()) item(key = "empty") { EmptyBody() }
            snapshot.groups.forEach { group -> groupItems(group, onOpen) }
            if (snapshot.failed.isNotEmpty()) {
                item(key = "failed") {
                    QuietLine(
                        stringResource(R.string.running_failed, snapshot.failed.joinToString(", ")),
                        Modifier.padding(horizontal = Spacing.listGutter, vertical = Spacing.l),
                    )
                }
            }
        }
    }
}

private fun LazyListScope.groupItems(group: RunningGroup, onOpen: (RunningOpen) -> Unit) {
    item(key = "header-${group.profile.name}") { GroupHeader(group.profile) }
    itemsIndexed(group.rows, key = { _, row -> "row-${group.profile.name}-${row.sessionId}" }) { index, row ->
        Surface(
            shape = groupedRowShape(index, group.rows.size),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.listGutter).padding(bottom = GroupedRowGap),
        ) { RowItem(row, onOpen) }
    }
}

/** `labelMedium` `onSurfaceVariant` on the 16 dp gutter, as Home's section headers. */
@Composable
private fun GroupHeader(profile: ProfileOption) {
    Text(
        text = profile.displayName.ifEmpty { profile.name }.ifEmpty { stringResource(R.string.running_profile_launch) },
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(start = Spacing.listGutter, end = Spacing.listGutter, top = Spacing.l, bottom = Spacing.s).semantics { heading() },
    )
}

/** Title; status (spinner + "Working" in tertiary, "Waiting for you" in red) · N subagents · N background. */
@Composable
private fun RowItem(row: RunningRow, onOpen: (RunningOpen) -> Unit) {
    val title = row.title ?: if (row.live) stringResource(R.string.session_untitled) else stringResource(R.string.running_session_id, row.sessionId.take(SHORT_ID))
    ListItem(
        modifier = Modifier
            .clickable(role = Role.Button) { onOpen(RunningOpen(row.sessionId, row.title, row.wireProfile)) }
            .heightIn(min = Sizes.listRowHeight),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { StatusLine(row) },
        trailingContent = {
            Icon(painter = painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(Sizes.toolIcon))
        },
    )
}

@Composable
private fun StatusLine(row: RunningRow) {
    val parts = buildList {
        if (row.subagents > 0) add(pluralStringResource(R.plurals.running_subagents, row.subagents, row.subagents))
        if (row.background > 0) add(pluralStringResource(R.plurals.running_background, row.background, row.background))
    }
    val lead = when (row.status) {
        RunningStatus.Waiting -> stringResource(R.string.running_status_waiting)
        RunningStatus.Working -> stringResource(R.string.running_status_working)
        RunningStatus.Starting -> stringResource(R.string.running_status_starting)
        RunningStatus.Idle -> if (row.live) null else stringResource(R.string.running_not_live)
    }
    val leadColor = when (row.status) {
        RunningStatus.Waiting -> MaterialTheme.colorScheme.error
        RunningStatus.Working, RunningStatus.Starting -> MaterialTheme.colorScheme.tertiary
        RunningStatus.Idle -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        if (row.status == RunningStatus.Working) RunningSpinner()
        if (row.status == RunningStatus.Waiting) {
            Icon(painter = painterResource(R.drawable.ic_pan_tool), contentDescription = null, tint = leadColor, modifier = Modifier.size(Sizes.lineIcon))
        }
        lead?.let {
            Text(it, style = AristotleTextStyles.liveStatusSmall, color = leadColor, maxLines = 1)
        }
        if (parts.isNotEmpty()) {
            Text(
                text = (if (lead != null) SEPARATOR else "") + parts.joinToString(SEPARATOR),
                style = AristotleTextStyles.tabular(MaterialTheme.typography.bodySmall),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** design.md §5 banner: `surfaceContainerHighest`, `medium`, `onSurface` text. */
@Composable
private fun Banner(text: String) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.listGutter, vertical = Spacing.s),
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(Spacing.m))
    }
}

@Composable
private fun ProblemBody(problem: String, onRetry: () -> Unit) {
    Column(modifier = Modifier.padding(horizontal = Spacing.listGutter, vertical = Spacing.xxl), verticalArrangement = Arrangement.spacedBy(Spacing.l)) {
        Text(stringResource(R.string.running_problem, problem), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = onRetry, shape = ButtonShape) { Text(stringResource(R.string.running_retry)) }
    }
}

@Composable
private fun EmptyBody() {
    Box(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = Spacing.listGutter, vertical = Spacing.l)) {
            Text(stringResource(R.string.running_empty), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(Spacing.xs))
            Text(stringResource(R.string.running_empty_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun QuietLine(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

private const val SEPARATOR = " · "
private const val SHORT_ID = 8
