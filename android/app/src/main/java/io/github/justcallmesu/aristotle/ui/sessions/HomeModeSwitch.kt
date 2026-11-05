package io.github.justcallmesu.aristotle.ui.sessions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import io.github.justcallmesu.aristotle.R
import io.github.justcallmesu.aristotle.data.HomeMode
import io.github.justcallmesu.aristotle.ui.theme.Spacing

/**
 * Q92 title words: "Chats  Stocks" in place of the wordmark, Literata; the selected word in ink at the wordmark's size,
 * the other muted at `titleLarge`. A tab row for assistive tech (role Tab, selected state).
 *
 * No extra height (Q92): each word is laid out at its own line height (36 / 28), so the row is the wordmark's 36 dp line.
 * The 48 dp touch area comes from Compose's minimum-touch-target expansion of a smaller clickable, not from layout
 * height (Q67, as the chat's "N steps" line); the words are wider than 48 dp, so only the height is widened, into the
 * head's own top padding and the line under it, and the two words never compete for a touch (16 dp apart).
 */
@Composable
fun HomeModeSwitch(mode: HomeMode, onMode: (HomeMode) -> Unit, selectedStyle: TextStyle, modifier: Modifier = Modifier) {
    Row(modifier = modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(Spacing.l)) {
        HomeMode.entries.forEach { option ->
            val selected = option == mode
            Text(
                text = stringResource(if (option == HomeMode.Chats) R.string.home_mode_chats else R.string.home_mode_stocks),
                style = if (selected) selectedStyle else MaterialTheme.typography.titleLarge,
                color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier
                    .alignByBaseline()
                    .selectable(selected = selected, role = Role.Tab, onClick = { if (!selected) onMode(option) }),
            )
        }
    }
}
