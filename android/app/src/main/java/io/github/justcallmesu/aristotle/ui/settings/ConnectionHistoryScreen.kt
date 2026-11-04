package io.github.justcallmesu.aristotle.ui.settings

import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.justcallmesu.aristotle.R
import io.github.justcallmesu.aristotle.data.AddressSource
import io.github.justcallmesu.aristotle.data.LogEntry
import io.github.justcallmesu.aristotle.data.LogKind
import io.github.justcallmesu.aristotle.data.Network
import io.github.justcallmesu.aristotle.data.OfflineReason
import io.github.justcallmesu.aristotle.ui.components.SubPageTopBar
import io.github.justcallmesu.aristotle.ui.theme.ButtonShape
import io.github.justcallmesu.aristotle.ui.theme.Spacing
import java.util.Date

/**
 * Settings › Connection & PCs › Connection history (Q73): what the link and discovery did on this phone, newest first,
 * one line each with its time and an icon for its kind. Empty state, and "Clear history" while there is any.
 */
@Composable
fun ConnectionHistoryScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val entries by viewModel.historyEntries.collectAsStateWithLifecycle()
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            SubPageTopBar(stringResource(R.string.settings_history), onBack)
            if (entries.isEmpty()) {
                Text(
                    text = stringResource(R.string.history_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.listGutter, vertical = Spacing.l),
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    item {
                        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.s), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = viewModel::clearHistory, shape = ButtonShape) { Text(stringResource(R.string.history_clear)) }
                        }
                    }
                    items(entries) { entry -> HistoryRow(entry) }
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(entry: LogEntry) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        leadingContent = {
            Icon(painterResource(iconOf(entry.kind)), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        headlineContent = { Text(lineOf(entry), style = MaterialTheme.typography.bodyMedium) },
        supportingContent = {
            Text(timeOf(entry.at), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
    )
}

@DrawableRes
private fun iconOf(kind: LogKind): Int = when (kind) {
    LogKind.Connected -> R.drawable.ic_check_circle
    LogKind.Switched -> R.drawable.ic_swap_horiz
    LogKind.Offline -> R.drawable.ic_cloud_off
    LogKind.Found, LogKind.NotFound, LogKind.CantScan -> R.drawable.ic_search
    LogKind.Saved -> R.drawable.ic_add
    LogKind.Deleted -> R.drawable.ic_delete
    LogKind.Demoted -> R.drawable.ic_arrow_downward
}

/** The time today, else the date and time. */
@Composable
private fun timeOf(at: Long): String {
    val context = LocalContext.current
    return if (DateUtils.isToday(at)) {
        DateFormat.getTimeFormat(context).format(Date(at))
    } else {
        DateUtils.formatDateTime(context, at, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_SHOW_TIME)
    }
}

@Composable
private fun lineOf(entry: LogEntry): String {
    val address = entry.address.orEmpty()
    return when (entry.kind) {
        LogKind.Connected -> stringResource(
            if (entry.network == Network.Tailscale) R.string.link_connected_tailscale else R.string.link_connected_wifi,
            address,
        )
        LogKind.Switched -> stringResource(R.string.history_switched, entry.from.orEmpty(), address)
        LogKind.Offline -> stringResource(R.string.history_offline, reasonOf(entry.reason ?: OfflineReason.ConnectionLost))
        LogKind.Found -> stringResource(if (entry.auto) R.string.history_found_auto else R.string.history_found_manual, address)
        LogKind.NotFound -> stringResource(R.string.history_not_found)
        LogKind.CantScan -> stringResource(R.string.history_cant_scan)
        LogKind.Saved -> stringResource(R.string.history_saved, address, sourceLabel(entry.source ?: AddressSource.Earlier))
        LogKind.Deleted -> stringResource(R.string.history_deleted, address)
        LogKind.Demoted -> stringResource(R.string.history_demoted, address)
    }
}

@Composable
private fun reasonOf(reason: OfflineReason): String = stringResource(
    when (reason) {
        OfflineReason.PcUnreachable -> R.string.history_reason_unreachable
        OfflineReason.TailscaleMayBeOff -> R.string.history_reason_tailscale_off
        OfflineReason.CertificateChanged -> R.string.history_reason_certificate
        OfflineReason.ConnectionLost -> R.string.history_reason_lost
        OfflineReason.HermesNotRunning -> R.string.history_reason_hermes_down
        OfflineReason.HermesUpdated -> R.string.history_reason_hermes_updated
        OfflineReason.Revoked -> R.string.history_reason_revoked
        OfflineReason.Superseded -> R.string.history_reason_superseded
        OfflineReason.AppError -> R.string.history_reason_app_error
    },
)
