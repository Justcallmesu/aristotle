package io.github.justcallmesu.aristotle.ui.sessions

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import io.github.justcallmesu.aristotle.R
import io.github.justcallmesu.aristotle.ui.theme.ButtonShape
import io.github.justcallmesu.aristotle.ui.theme.Spacing

/** Q64 / design-notes §5.1: one `surfaceContainerLow` notice line with Dismiss / See, once after an update. */
@Composable
fun WhatsNewCard(version: String, onOpen: () -> Unit, onDismiss: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.xs),
    ) {
        Row(
            modifier = Modifier.heightIn(min = NOTICE_MIN_HEIGHT).padding(start = Spacing.m, end = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.whats_new_card_title, version),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            val seeLabel = stringResource(R.string.whats_new_open)
            TextButton(onClick = onDismiss, shape = ButtonShape) { Text(stringResource(R.string.whats_new_dismiss)) }
            TextButton(onClick = onOpen, shape = ButtonShape, modifier = Modifier.semantics { contentDescription = seeLabel }) {
                Text(stringResource(R.string.whats_new_see))
            }
        }
    }
}
