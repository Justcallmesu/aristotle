package io.github.justcallmesu.aristotle.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import io.github.justcallmesu.aristotle.R

/** Row height of a Settings category (design-notes §5.4). */
private val CategoryRowHeight = 64.dp

/** One category of the Settings list: icon, title, one-line summary, chevron (Q72). */
@Composable
internal fun CategoryRow(page: SettingsPage, summary: String, onOpen: () -> Unit) {
    ListItem(
        modifier = Modifier.heightIn(min = CategoryRowHeight).clickable(onClick = onOpen),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = {
            Icon(painterResource(page.icon), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        headlineContent = { Text(stringResource(page.title), style = MaterialTheme.typography.bodyLarge) },
        supportingContent = {
            Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        trailingContent = {
            Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        },
    )
}
