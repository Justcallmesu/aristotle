package io.github.justcallmesu.aristotle.ui.settings

import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.justcallmesu.aristotle.R
import io.github.justcallmesu.aristotle.data.AddressSource
import io.github.justcallmesu.aristotle.data.FindPhase
import io.github.justcallmesu.aristotle.data.FindState
import io.github.justcallmesu.aristotle.data.LinkState
import io.github.justcallmesu.aristotle.data.Network
import io.github.justcallmesu.aristotle.data.PcCredentials
import io.github.justcallmesu.aristotle.ui.components.RunningSpinner
import io.github.justcallmesu.aristotle.ui.pair.FingerprintGrid
import io.github.justcallmesu.aristotle.ui.theme.AristotleTextStyles
import io.github.justcallmesu.aristotle.ui.theme.ButtonShape
import io.github.justcallmesu.aristotle.ui.theme.Spacing
import java.util.Date

/**
 * Connection & PCs (Q60): the paired PC (named by its address: the protocol has no PC name), the link now, the pinned
 * fingerprint as on pairing, the saved addresses with where each came from, when it last worked and a delete (Q73), the
 * connection history, "Find PC on this Wi-Fi" (Q65), the background link switch, then Pair again and Unpair. The top
 * bar names the page; no header repeats it (TS3).
 */
@Composable
internal fun ConnectionSection(viewModel: SettingsViewModel, pc: PcCredentials, onRepair: () -> Unit, onHistory: () -> Unit) {
    val link by viewModel.link.collectAsStateWithLifecycle()
    val keepConnected by viewModel.keepConnected.collectAsStateWithLifecycle()
    val find by viewModel.find.collectAsStateWithLifecycle()
    val pendingDelete by viewModel.pendingDelete.collectAsStateWithLifecycle()
    val rows = ConnectionRows.addresses(pc, link)
    val now = System.currentTimeMillis()
    val pin = runCatching { pc.pin }.getOrNull()
    // PC and fingerprint: one block (name + link state, then the pinned fingerprint, as on pairing).
    RowGroup(
        buildList<@Composable () -> Unit> {
            add { PcRow(ConnectionRows.pcLabel(pc), linkStatus(link)) }
            if (pin != null) add { FingerprintBlock(pin) }
        },
    )
    SubHeader(stringResource(R.string.settings_saved_addresses))
    RowGroup(rows.map { row -> { SavedAddressRow(row, now, onDelete = { viewModel.askDelete(row.endpoint) }) } })
    FindPcBlock(find, onFind = viewModel::findPc)
    RowGroup(
        listOf(
            {
                SettingRow(
                    title = stringResource(R.string.settings_history),
                    body = stringResource(R.string.settings_history_body),
                    modifier = Modifier.clickable(onClick = onHistory),
                )
            },
            {
                SwitchRow(
                    title = stringResource(R.string.settings_keep_connected),
                    body = stringResource(R.string.settings_keep_connected_body),
                    checked = keepConnected,
                    onChange = viewModel::setKeepConnected,
                )
            },
            {
                SettingRow(
                    title = stringResource(R.string.settings_repair),
                    body = stringResource(R.string.settings_repair_body),
                    modifier = Modifier.clickable(onClick = onRepair),
                )
            },
            {
                // The only red on the page.
                SettingRow(
                    title = stringResource(R.string.settings_unpair),
                    body = stringResource(R.string.settings_unpair_body),
                    titleColor = MaterialTheme.colorScheme.error,
                    modifier = Modifier.clickable { viewModel.show(SettingsSheet.Unpair) },
                )
            },
        ),
    )
    rows.firstOrNull { it.endpoint == pendingDelete }?.let { row ->
        DeleteAddressDialog(row, onConfirm = viewModel::confirmDelete, onDismiss = viewModel::cancelDelete)
    }
}

/** One saved address (Q73): the delete is disabled, with the reason in the line, when it is the only one. */
@Composable
private fun SavedAddressRow(row: AddressRow, now: Long, onDelete: () -> Unit) {
    val address = row.endpoint.displayAddress
    SettingRow(
        title = address,
        titleStyle = AristotleTextStyles.codeInline,
        body = addressLine(row, now),
        trailing = {
            IconButton(onClick = onDelete, enabled = row.deletable) {
                Icon(
                    painter = painterResource(R.drawable.ic_delete),
                    contentDescription = stringResource(R.string.settings_address_delete, address),
                )
            }
        },
    )
}

/**
 * Delete confirmation (design.md §5, as Unpair): what goes, whether the PC's list may bring it back (remembered only when
 * another address of its kind stays, until unpaired), and, for the address in use, that the link moves at once.
 */
@Composable
private fun DeleteAddressDialog(row: AddressRow, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_address_delete_title)) },
        text = {
            Column {
                Text(
                    stringResource(
                        if (row.remembered) R.string.settings_address_delete_remembered else R.string.settings_address_delete_may_return,
                        row.endpoint.displayAddress,
                    ),
                )
                if (row.inUse) {
                    Spacer(Modifier.height(Spacing.m))
                    Text(stringResource(R.string.settings_address_delete_in_use))
                }
            }
        },
        confirmButton = {
            OutlinedButton(
                onClick = onConfirm,
                shape = ButtonShape,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text(stringResource(R.string.settings_address_delete_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss, shape = ButtonShape) { Text(stringResource(R.string.settings_cancel)) } },
        shape = MaterialTheme.shapes.extraLarge,
    )
}

/** The paired PC: its name in `titleMedium` and the link state under it. */
@Composable
private fun PcRow(name: String, status: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.m)) {
        Text(text = name, style = MaterialTheme.typography.titleMedium)
        Text(text = status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The pinned fingerprint, the same 4 × 4 grid as on pairing, inside a grouped row. The pin is public. */
@Composable
private fun FingerprintBlock(pin: ByteArray) {
    Column(modifier = Modifier.fillMaxWidth().padding(Spacing.l)) {
        Text(text = stringResource(R.string.settings_fingerprint), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = stringResource(R.string.settings_fingerprint_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Spacing.m))
        FingerprintGrid(pin)
    }
}

/**
 * "Find PC on this Wi-Fi" (Q65): a `FilledTonalButton` and one line under it. Only on a tap. The line is the progress,
 * then the result, announced as it changes.
 */
@Composable
private fun FindPcBlock(find: FindState, onFind: () -> Unit) {
    val looking = find is FindState.Looking
    val body = when (find) {
        FindState.Idle -> stringResource(R.string.settings_find_pc_body)
        is FindState.Looking -> stringResource(if (find.phase == FindPhase.Scan) R.string.settings_find_pc_scanning else R.string.settings_find_pc_looking)
        is FindState.Found -> stringResource(R.string.settings_find_pc_found, find.endpoint.host)
        FindState.NotFound -> stringResource(R.string.settings_find_pc_not_found)
        FindState.CantScan -> stringResource(R.string.settings_find_pc_cant_scan)
    }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        FilledTonalButton(onClick = onFind, enabled = !looking, shape = ButtonShape) {
            if (looking) {
                RunningSpinner()
                Spacer(Modifier.width(Spacing.s))
            }
            Text(stringResource(R.string.settings_find_pc))
        }
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

@Composable
private fun SubHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier
            .padding(horizontal = Spacing.xs)
            .semantics { heading() },
    )
}

/**
 * "Wi-Fi (LAN) · typed · worked 5 minutes ago · worked last · in use now": text only, no colour (verdigris is for live
 * work).
 */
@Composable
private fun addressLine(row: AddressRow, now: Long): String {
    val parts = buildList {
        add(stringResource(if (row.endpoint.network == Network.Lan) R.string.settings_address_lan else R.string.settings_address_tailscale))
        add(sourceLabel(row.source))
        when (ConnectionRows.workedAgo(row.lastSuccessAt, now)) {
            ConnectionRows.Worked.JustNow -> add(stringResource(R.string.settings_address_worked_now))
            ConnectionRows.Worked.Ago -> add(
                stringResource(
                    R.string.settings_address_worked,
                    DateUtils.getRelativeTimeSpanString(row.lastSuccessAt ?: now, now, DateUtils.MINUTE_IN_MILLIS).toString(),
                ),
            )
            null -> Unit
        }
        if (row.lastWorked) add(stringResource(R.string.settings_address_last_worked))
        if (row.inUse) add(stringResource(R.string.settings_address_in_use))
        if (!row.deletable) add(stringResource(R.string.settings_address_only_one))
    }
    return parts.joinToString(" · ")
}

@Composable
internal fun sourceLabel(source: AddressSource): String = stringResource(
    when (source) {
        AddressSource.Typed -> R.string.settings_address_source_typed
        AddressSource.Pairing -> R.string.settings_address_source_pairing
        AddressSource.ServerList -> R.string.settings_address_source_server
        AddressSource.FoundOnWifi -> R.string.settings_address_source_found
        AddressSource.Earlier -> R.string.settings_address_source_earlier
    },
)

@Composable
private fun linkStatus(link: LinkState): String = when (link) {
    is LinkState.Online -> stringResource(
        if (link.endpoint.network == Network.Tailscale) R.string.link_connected_tailscale else R.string.link_connected_wifi,
        link.endpoint.displayAddress,
    )
    LinkState.Idle, LinkState.Connecting -> stringResource(R.string.settings_link_connecting)
    is LinkState.Offline -> stringResource(R.string.link_offline_since, DateFormat.getTimeFormat(LocalContext.current).format(Date(link.sinceMillis)))
    is LinkState.HermesDown -> stringResource(R.string.settings_link_hermes_down)
    is LinkState.HermesUpdated -> stringResource(R.string.conn_4505_title)
    LinkState.Stopped -> stringResource(R.string.settings_link_stopped)
    LinkState.Revoked -> stringResource(R.string.settings_link_revoked)
}

/**
 * Unpair confirmation (design.md §5 confirmation, as Delete on Scheduled): what this phone forgets, and the exact
 * command that removes this phone on the PC too, selectable for copying. The token is never shown.
 */
@Composable
internal fun UnpairDialog(deviceId: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_unpair_title)) },
        text = {
            Column {
                Text(stringResource(R.string.settings_unpair_text))
                Spacer(Modifier.height(Spacing.m))
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.fillMaxWidth()) {
                    SelectionContainer {
                        Text(
                            text = ConnectionRows.revokeCommand(deviceId),
                            style = AristotleTextStyles.codeInline,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(Spacing.m),
                        )
                    }
                }
            }
        },
        confirmButton = {
            OutlinedButton(
                onClick = onConfirm,
                shape = ButtonShape,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text(stringResource(R.string.settings_unpair_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss, shape = ButtonShape) { Text(stringResource(R.string.settings_cancel)) } },
        shape = MaterialTheme.shapes.extraLarge,
    )
}
