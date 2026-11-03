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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.justcallmesu.aristotle.R
import io.github.justcallmesu.aristotle.data.LinkState
import io.github.justcallmesu.aristotle.data.Network
import io.github.justcallmesu.aristotle.data.OfflineCause
import io.github.justcallmesu.aristotle.ui.components.RunningSpinner
import io.github.justcallmesu.aristotle.ui.theme.AristotleTextStyles
import io.github.justcallmesu.aristotle.ui.theme.Sizes
import io.github.justcallmesu.aristotle.ui.theme.Spacing

@Composable
fun SessionsScreen(viewModel: SessionsViewModel, onPairAgain: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    SessionsContentView(state = state, timeText = viewModel::timeText, onRetry = viewModel::retry, onPairAgain = onPairAgain)
}

@Composable
fun SessionsContentView(state: SessionsUiState, timeText: (Long) -> String, onRetry: () -> Unit, onPairAgain: () -> Unit) {
    val address = state.address?.hostForUrl.orEmpty()
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            TopBar(state, address)
            val page = pageFor(state)
            if (page != null) {
                ConnectionPage(page.kind, page.details, address, onRetry, onPairAgain)
            } else {
                val offline = state.link as? LinkState.Offline
                offline?.let { OfflineBanner(it, address, state.listedAt, timeText, onRetry) }
                ListBody(state, address)
            }
        }
    }
}

private class Page(val kind: PageKind, val details: String)

/** design.md §5: Hermes down / updated and a changed certificate replace the list; everything else keeps it. */
private fun pageFor(state: SessionsUiState): Page? = when (val link = state.link) {
    is LinkState.HermesDown -> Page(PageKind.HermesDown, link.details)
    is LinkState.HermesUpdated -> Page(PageKind.HermesUpdated, link.details)
    is LinkState.Offline -> if (link.cause == OfflineCause.CertificateChanged) Page(PageKind.CertificateChanged, link.details) else null
    is LinkState.Online -> state.listProblem?.let { Page(PageKind.ListFailed, it) }
    else -> null
}

@Composable
private fun ListBody(state: SessionsUiState, address: String) {
    val items = state.items
    when {
        items == null -> if (state.link !is LinkState.Offline) ConnectingBody(address)
        items.isEmpty() -> EmptyBody()
        else -> SessionList(items, state.staleAt)
    }
}

@Composable
private fun TopBar(state: SessionsUiState, address: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Sizes.topBarHeight)
            .padding(horizontal = Spacing.l),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = stringResource(R.string.app_name), style = AristotleTextStyles.wordmark)
            ConnectionLine(state.link, address)
        }
        state.profileName?.let { ProfileLabel(it) }
    }
}

/** "192.168.1.23 · via Wi-Fi", "… · via Tailscale", or "… · offline" with the cloud-off icon. */
@Composable
private fun ConnectionLine(link: LinkState, address: String) {
    val offline = link is LinkState.Offline
    val text = when {
        link is LinkState.Online && link.endpoint.network == Network.Tailscale -> stringResource(R.string.sessions_via_tailscale, address)
        link is LinkState.Online -> stringResource(R.string.sessions_via_wifi, address)
        offline -> stringResource(R.string.sessions_offline_line, address)
        else -> address
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        if (offline) {
            Icon(
                painter = painterResource(R.drawable.ic_cloud_off),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(Sizes.lineIcon),
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
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

/** [staleAt]: the list is from that time and the link is not up, so nothing reads as live (design.md §5). */
@Composable
private fun SessionList(items: List<SessionListItem>, staleAt: String?) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(items, key = ::itemKey) { item ->
            when (item) {
                is SessionListItem.Header -> SectionHeader(item.section)
                is SessionListItem.Row -> SessionRowItem(item.row, staleAt)
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
private fun SessionRowItem(row: SessionRow, staleAt: String?) {
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
        supportingContent = { RowStatus(row, staleAt) },
        trailingContent = row.time?.let { time -> { RowTimeText(time, staleAt) } },
    )
}

@Composable
private fun RowStatus(row: SessionRow, staleAt: String?) {
    if (row.running && staleAt != null) {
        Text(
            text = stringResource(R.string.session_was_running, staleAt),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    } else if (row.running) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            RunningSpinner()
            Text(
                text = stringResource(R.string.session_running),
                style = AristotleTextStyles.liveStatus,
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
private fun RowTimeText(time: RowTime, staleAt: String?) {
    Text(
        text = when (time) {
            RowTime.Now -> staleAt ?: stringResource(R.string.session_now)
            is RowTime.At -> time.text
        },
        style = AristotleTextStyles.tabular(MaterialTheme.typography.bodySmall),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
