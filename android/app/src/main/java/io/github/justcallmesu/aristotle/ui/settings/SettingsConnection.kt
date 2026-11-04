package io.github.justcallmesu.aristotle.ui.settings

import android.text.format.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.justcallmesu.aristotle.R
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
 * fingerprint as on pairing, every stored address, "Find PC on this Wi-Fi" (Q65), the background link switch, then
 * Pair again and Unpair.
 */
@Composable
internal fun ConnectionSection(viewModel: SettingsViewModel, pc: PcCredentials, onRepair: () -> Unit) {
    val link by viewModel.link.collectAsStateWithLifecycle()
    val keepConnected by viewModel.keepConnected.collectAsStateWithLifecycle()
    val find by viewModel.find.collectAsStateWithLifecycle()
    SectionHeader(stringResource(R.string.settings_section_connection))
    SettingRow(title = ConnectionRows.pcLabel(pc), body = linkStatus(link))
    FingerprintBlock(pc)
    SubHeader(stringResource(R.string.settings_addresses))
    ConnectionRows.addresses(pc, link).forEach { row ->
        SettingRow(title = row.endpoint.displayAddress, body = addressLine(row))
    }
    FindPcRow(find, onFind = viewModel::findPc)
    SwitchRow(
        title = stringResource(R.string.settings_keep_connected),
        body = stringResource(R.string.settings_keep_connected_body),
        checked = keepConnected,
        onChange = viewModel::setKeepConnected,
    )
    SettingRow(
        title = stringResource(R.string.settings_repair),
        body = stringResource(R.string.settings_repair_body),
        modifier = Modifier.clickable(onClick = onRepair),
    )
    SettingRow(
        title = stringResource(R.string.settings_unpair),
        body = stringResource(R.string.settings_unpair_body),
        modifier = Modifier.clickable { viewModel.show(SettingsSheet.Unpair) },
    )
}

/** design.md §5 fingerprint card: `medium`, `surfaceContainerLow`, the same 4 × 4 grid as on pairing. The pin is public. */
@Composable
private fun FingerprintBlock(pc: PcCredentials) {
    val pin = runCatching { pc.pin }.getOrNull() ?: return
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.listGutter, vertical = Spacing.s),
    ) {
        Column(modifier = Modifier.padding(Spacing.l)) {
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
}

/** "Find PC on this Wi-Fi" (Q65): only on a tap. The body is the progress, then the result, announced as it changes. */
@Composable
private fun FindPcRow(find: FindState, onFind: () -> Unit) {
    val looking = find is FindState.Looking
    val body = when (find) {
        FindState.Idle -> stringResource(R.string.settings_find_pc_body)
        is FindState.Looking -> stringResource(if (find.phase == FindPhase.Scan) R.string.settings_find_pc_scanning else R.string.settings_find_pc_looking)
        is FindState.Found -> stringResource(R.string.settings_find_pc_found, find.endpoint.host)
        FindState.NotFound -> stringResource(R.string.settings_find_pc_not_found)
        FindState.CantScan -> stringResource(R.string.settings_find_pc_cant_scan)
    }
    SettingRow(
        title = stringResource(R.string.settings_find_pc),
        body = body,
        modifier = Modifier
            .clickable(enabled = !looking, onClick = onFind)
            .semantics { liveRegion = LiveRegionMode.Polite },
        trailing = if (looking) {
            { RunningSpinner() }
        } else {
            null
        },
    )
}

@Composable
private fun SubHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier
            .padding(start = Spacing.listGutter, end = Spacing.listGutter, top = Spacing.l, bottom = Spacing.xs)
            .semantics { heading() },
    )
}

/** "Wi-Fi (LAN) · worked last · in use now": text only, no colour (verdigris is for live work). */
@Composable
private fun addressLine(row: AddressRow): String {
    val parts = buildList {
        add(stringResource(if (row.endpoint.network == Network.Lan) R.string.settings_address_lan else R.string.settings_address_tailscale))
        if (row.lastWorked) add(stringResource(R.string.settings_address_last_worked))
        if (row.inUse) add(stringResource(R.string.settings_address_in_use))
    }
    return parts.joinToString(" · ")
}

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
                Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.fillMaxWidth()) {
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
