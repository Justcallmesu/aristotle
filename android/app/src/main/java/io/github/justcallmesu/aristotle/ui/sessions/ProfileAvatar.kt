package io.github.justcallmesu.aristotle.ui.sessions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.justcallmesu.aristotle.R
import io.github.justcallmesu.aristotle.ui.theme.Sizes
import io.github.justcallmesu.aristotle.ui.theme.Spacing

/**
 * design-notes §5.6 + Q76: the Hermes profile at the top right of Home C, replacing the profile chip. The name sits
 * BESIDE the 40 dp mark on one row (user's call over the notes' "under"); the whole row is one 48 dp target opening
 * today's profile sheet. Enabled only while online (no sheet without a roster), dimmed to 38 % otherwise.
 */
@Composable
fun ProfileAvatarButton(name: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.sessions_profile_description, name)
    Row(
        modifier = modifier
            .heightIn(min = Sizes.minTouchTarget)
            .widthIn(min = Sizes.minTouchTarget)
            .clip(MaterialTheme.shapes.medium)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = stringResource(R.string.sessions_profile_change), onClick = onClick)
            .semantics { contentDescription = description }
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .padding(start = Spacing.m, end = Spacing.xs, top = Spacing.xs, bottom = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = NAME_MAX_WIDTH).clearAndSetSemantics { },
        )
        ProfileMark(name, size = AVATAR_SIZE, shape = MaterialTheme.shapes.medium, style = avatarInitialStyle())
    }
}

/** The profile's initial on a Soft square. Decorative: callers carry the name as text or contentDescription. */
@Composable
fun ProfileMark(name: String, size: Dp, shape: Shape, style: TextStyle, modifier: Modifier = Modifier) {
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = modifier.size(size).clearAndSetSemantics { },
    ) {
        Box(contentAlignment = Alignment.Center) { Text(text = profileInitial(name), style = style) }
    }
}

/** First character (whole code point) of the trimmed name, upper-cased; "?" for a blank name. */
fun profileInitial(name: String): String {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return "?"
    return trimmed.substring(0, trimmed.offsetByCodePoints(0, 1)).uppercase()
}

/** Literata 600 at 19 / 24 (titleLarge is 22 / 28), kept at the app's text size. */
@Composable
private fun avatarInitialStyle(): TextStyle {
    val base = MaterialTheme.typography.titleLarge
    return base.copy(fontSize = base.fontSize * INITIAL_SCALE, lineHeight = base.lineHeight * LINE_SCALE, fontWeight = FontWeight.SemiBold)
}

private val AVATAR_SIZE = 40.dp
private val NAME_MAX_WIDTH = 120.dp
private const val DISABLED_ALPHA = 0.38f
private const val INITIAL_SCALE = 19f / 22f
private const val LINE_SCALE = 24f / 28f
