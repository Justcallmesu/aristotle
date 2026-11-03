package io.github.justcallmesu.aristotle.ui.sessions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.justcallmesu.aristotle.R
import io.github.justcallmesu.aristotle.data.Network
import io.github.justcallmesu.aristotle.ui.components.RunningSpinner
import io.github.justcallmesu.aristotle.ui.theme.AristotleTextStyles
import io.github.justcallmesu.aristotle.ui.theme.Sizes
import io.github.justcallmesu.aristotle.ui.theme.Spacing

@Composable
fun SessionsScreen(viewModel: SessionsViewModel, onPairAgain: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    SessionsContentView(state = state, onRetry = viewModel::retry, onPairAgain = onPairAgain)
}

@Composable
fun SessionsContentView(state: SessionsUiState, onRetry: () -> Unit, onPairAgain: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            TopBar(state)
            when (val content = state.content) {
                SessionsContent.Connecting -> ConnectingBody(state.address)
                is SessionsContent.Loaded ->
                    if (content.items.isEmpty()) EmptyBody() else SessionList(content.items)
                is SessionsContent.Problem -> ConnectionPage(content.issue, state.address, onRetry, onPairAgain)
            }
        }
    }
}

@Composable
private fun TopBar(state: SessionsUiState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Sizes.topBarHeight)
            .padding(horizontal = Spacing.l),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = stringResource(R.string.app_name), style = AristotleTextStyles.wordmark)
            Text(
                text = connectionLine(state),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        state.profileName?.let { ProfileLabel(it) }
    }
}

@Composable
private fun connectionLine(state: SessionsUiState): String = when (state.network) {
    Network.Tailscale -> stringResource(R.string.sessions_via_tailscale, state.address)
    Network.Lan -> stringResource(R.string.sessions_via_wifi, state.address)
    null -> state.address
}

/** The launch profile as text; the switcher chip comes with T14. */
@Composable
private fun ProfileLabel(name: String) {
    val description = stringResource(R.string.sessions_profile_description, name)
    Column(
        horizontalAlignment = Alignment.End,
        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = description },
    ) {
        Text(
            text = stringResource(R.string.sessions_profile_label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = name, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun ConnectingBody(address: String) {
    Text(
        text = stringResource(R.string.sessions_connecting, address),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Spacing.listGutter, vertical = Spacing.xxl),
    )
}

@Composable
private fun EmptyBody() {
    Column(modifier = Modifier.padding(horizontal = Spacing.xxl, vertical = Spacing.xxxl)) {
        Text(text = stringResource(R.string.sessions_empty_title), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = stringResource(R.string.sessions_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SessionList(items: List<SessionListItem>) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(items, key = ::itemKey) { item ->
            when (item) {
                is SessionListItem.Header -> SectionHeader(item.section)
                is SessionListItem.Row -> SessionRowItem(item.row)
            }
        }
    }
}

private fun itemKey(item: SessionListItem): String = when (item) {
    is SessionListItem.Header -> "header-${item.section}"
    is SessionListItem.Row -> "row-${item.row.key}"
}

@Composable
private fun SectionHeader(section: DaySection) {
    Text(
        text = stringResource(
            when (section) {
                DaySection.Today -> R.string.sessions_section_today
                DaySection.Yesterday -> R.string.sessions_section_yesterday
                DaySection.Earlier -> R.string.sessions_section_earlier
            },
        ),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .padding(start = Spacing.listGutter, end = Spacing.listGutter, top = Spacing.l, bottom = Spacing.s)
            .semantics { heading() },
    )
}

/** design.md §5 session row: 72 dp, title + one status line, trailing time. */
@Composable
private fun SessionRowItem(row: SessionRow) {
    ListItem(
        modifier = Modifier.heightIn(min = Sizes.sessionRowHeight),
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        headlineContent = {
            Text(
                text = row.title ?: stringResource(R.string.session_untitled),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = { RowStatus(row) },
        trailingContent = row.time?.let { time -> { RowTimeText(time) } },
    )
}

@Composable
private fun RowStatus(row: SessionRow) {
    if (row.running) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            RunningSpinner()
            Text(
                text = stringResource(R.string.session_running),
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.W600),
                color = MaterialTheme.colorScheme.tertiary,
                maxLines = 1,
            )
        }
    } else {
        Text(
            text = pluralStringResource(R.plurals.session_messages, row.messageCount, row.messageCount),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun RowTimeText(time: RowTime) {
    Text(
        text = when (time) {
            RowTime.Now -> stringResource(R.string.session_now)
            is RowTime.At -> time.text
        },
        style = AristotleTextStyles.tabular(MaterialTheme.typography.bodySmall),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
