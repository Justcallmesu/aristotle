package io.github.justcallmesu.aristotle.ui.sessions

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.justcallmesu.aristotle.R
import io.github.justcallmesu.aristotle.data.Compatibility
import io.github.justcallmesu.aristotle.data.ServerVersions
import io.github.justcallmesu.aristotle.data.compatibilityOf
import io.github.justcallmesu.aristotle.ui.theme.Spacing

/** Q63: a persistent warning when app and PC server protocols do not meet; the app keeps working. */
@Composable
fun CompatibilityBanner(pcId: String) {
    val servers by ServerVersions.all.collectAsStateWithLifecycle()
    val text = when (servers[pcId]?.let { compatibilityOf(it) }) {
        Compatibility.ServerTooOld -> R.string.compat_server_older
        Compatibility.AppTooOld -> R.string.compat_app_older
        else -> return
    }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.m, vertical = Spacing.xs),
    ) {
        Row(modifier = Modifier.padding(Spacing.m), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(R.drawable.ic_error),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(end = Spacing.m),
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
