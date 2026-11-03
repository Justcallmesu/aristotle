package io.github.justcallmesu.aristotle.ui.pair

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.github.justcallmesu.aristotle.R
import io.github.justcallmesu.aristotle.data.Fingerprint
import io.github.justcallmesu.aristotle.ui.theme.AristotleTextStyles
import io.github.justcallmesu.aristotle.ui.theme.ButtonShape
import io.github.justcallmesu.aristotle.ui.theme.Spacing

private const val GROUPS_PER_ROW = 4

/** design.md §5: medium card on surfaceContainerLow, 16 groups in a 4 × 4 mono grid. */
@Composable
fun FingerprintCard(
    address: String,
    fingerprint: ByteArray,
    busy: Boolean,
    onMatch: () -> Unit,
    onMismatch: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(Spacing.l)) {
            Text(text = stringResource(R.string.fp_title), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = stringResource(R.string.fp_body, address),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Spacing.m))
            FingerprintGrid(fingerprint)
            Spacer(Modifier.height(Spacing.l))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.s, Alignment.End),
            ) {
                TextButton(onClick = onMismatch, enabled = !busy, shape = ButtonShape) {
                    Text(stringResource(R.string.fp_no_match))
                }
                Button(onClick = onMatch, enabled = !busy, shape = ButtonShape) {
                    Text(stringResource(if (busy) R.string.pair_pairing else R.string.fp_match))
                }
            }
        }
    }
}

@Composable
private fun FingerprintGrid(fingerprint: ByteArray) {
    val groups = Fingerprint.groups(fingerprint)
    Column(
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = groups.joinToString(", ") },
    ) {
        groups.chunked(GROUPS_PER_ROW).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.l)) {
                row.forEach { group ->
                    Text(
                        text = group,
                        style = AristotleTextStyles.tabular(AristotleTextStyles.fingerprint),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

/** After "They match": a compact card, so a refused code does not ask for the comparison again. */
@Composable
fun FingerprintConfirmed(fingerprint: ByteArray) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.m),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_verified_user),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column {
                Text(text = stringResource(R.string.fp_matched), style = MaterialTheme.typography.titleSmall)
                Text(
                    text = Fingerprint.abbreviated(fingerprint),
                    style = AristotleTextStyles.fingerprintShort,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
