package io.github.justcallmesu.aristotle.ui.sessions

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.justcallmesu.aristotle.R
import io.github.justcallmesu.aristotle.data.Compatibility
import io.github.justcallmesu.aristotle.data.ServerVersions
import io.github.justcallmesu.aristotle.data.compatibilityOf
import io.github.justcallmesu.aristotle.ui.theme.Spacing

/** Q63: the warning text while app and PC server protocols do not meet (the app keeps working); null when they do. */
@StringRes
@Composable
fun compatibilityText(pcId: String): Int? {
    val servers by ServerVersions.all.collectAsStateWithLifecycle()
    return when (servers[pcId]?.let { compatibilityOf(it) }) {
        Compatibility.ServerTooOld -> R.string.compat_server_older
        Compatibility.AppTooOld -> R.string.compat_app_older
        else -> null
    }
}

/** design-notes §5.1: one notice line with the error icon (non-text, so `error` on Highest is fine), no action. */
@Composable
fun CompatibilityNotice(@StringRes text: Int) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.xs),
    ) {
        Row(
            modifier = Modifier.heightIn(min = NOTICE_MIN_HEIGHT).padding(start = Spacing.m, end = Spacing.s, top = Spacing.s, bottom = Spacing.s),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_error),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(NOTICE_ICON),
            )
            Text(
                text = stringResource(text),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

private val NOTICE_ICON = 20.dp
