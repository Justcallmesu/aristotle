package io.github.justcallmesu.aristotle.ui.terminal

import android.view.KeyEvent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import io.github.justcallmesu.aristotle.R
import io.github.justcallmesu.aristotle.ui.theme.AristotleTextStyles
import io.github.justcallmesu.aristotle.ui.theme.ButtonShape
import io.github.justcallmesu.aristotle.ui.theme.Sizes
import io.github.justcallmesu.aristotle.ui.theme.Spacing

/** The extra-keys row's Ctrl and Alt: one-shot (held until the next key), shared by the row and the keyboard. */
class StickyModifiers {
    var ctrl: Boolean by mutableStateOf(false)
    var alt: Boolean by mutableStateOf(false)

    /** The modifiers for one key; releases them. */
    fun take(): Pair<Boolean, Boolean> {
        val taken = ctrl to alt
        ctrl = false
        alt = false
        return taken
    }
}

/** One key of the extra-keys row. */
sealed interface ExtraKey {
    val label: String

    /** A key Termux's KeyHandler turns into an escape sequence. [description]: the spoken name when [label] is a glyph. */
    data class Special(override val label: String, val keyCode: Int, val description: Int? = null) : ExtraKey
    data class Character(val char: Char) : ExtraKey {
        override val label: String get() = char.toString()
    }
    data object Ctrl : ExtraKey {
        override val label = "Ctrl"
    }
    data object Alt : ExtraKey {
        override val label = "Alt"
    }

    companion object {
        /** T19b brief: Esc, Tab, Ctrl, Alt, arrows, Home/End, PgUp/PgDn, `|`, `/`, `-`, `~`. */
        val row: List<ExtraKey> = listOf(
            Special("Esc", KeyEvent.KEYCODE_ESCAPE),
            Special("Tab", KeyEvent.KEYCODE_TAB),
            Ctrl,
            Alt,
            Special("↑", KeyEvent.KEYCODE_DPAD_UP, R.string.terminal_key_up),
            Special("↓", KeyEvent.KEYCODE_DPAD_DOWN, R.string.terminal_key_down),
            Special("←", KeyEvent.KEYCODE_DPAD_LEFT, R.string.terminal_key_left),
            Special("→", KeyEvent.KEYCODE_DPAD_RIGHT, R.string.terminal_key_right),
            Special("Home", KeyEvent.KEYCODE_MOVE_HOME),
            Special("End", KeyEvent.KEYCODE_MOVE_END),
            Special("PgUp", KeyEvent.KEYCODE_PAGE_UP),
            Special("PgDn", KeyEvent.KEYCODE_PAGE_DOWN),
            Character('|'),
            Character('/'),
            Character('-'),
            Character('~'),
        )
    }
}

/** The row under the terminal (design.md: tonal surface, 8 dp button shape, mono labels, ≥ 48 dp targets). */
@Composable
fun ExtraKeysRow(modifiers: StickyModifiers, onKey: (ExtraKey) -> Unit, modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier.fillMaxWidth()) {
        Row(modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = Spacing.xs)) {
            ExtraKey.row.forEach { key ->
                val on = (key == ExtraKey.Ctrl && modifiers.ctrl) || (key == ExtraKey.Alt && modifiers.alt)
                ExtraKeyButton(key, on) {
                    when (key) {
                        ExtraKey.Ctrl -> modifiers.ctrl = !modifiers.ctrl
                        ExtraKey.Alt -> modifiers.alt = !modifiers.alt
                        else -> onKey(key)
                    }
                }
            }
        }
    }
}

@Composable
private fun ExtraKeyButton(key: ExtraKey, on: Boolean, onClick: () -> Unit) {
    val colours = MaterialTheme.colorScheme
    val spoken = (key as? ExtraKey.Special)?.description?.let { stringResource(it) }
    val stateText = stringResource(if (on) R.string.terminal_key_on else R.string.terminal_key_off)
    val toggle = key == ExtraKey.Ctrl || key == ExtraKey.Alt
    TextButton(
        onClick = onClick,
        shape = ButtonShape,
        colors = if (on) {
            ButtonDefaults.textButtonColors(containerColor = colours.secondaryContainer, contentColor = colours.onSecondaryContainer)
        } else {
            ButtonDefaults.textButtonColors(contentColor = colours.onSurface)
        },
        modifier = Modifier
            .heightIn(min = Sizes.minTouchTarget)
            .widthIn(min = Sizes.minTouchTarget)
            .semantics {
                if (spoken != null) contentDescription = spoken
                if (toggle) {
                    selected = on
                    contentDescription = "${key.label}, $stateText"
                }
            },
    ) {
        Text(text = key.label, style = AristotleTextStyles.codeInline, maxLines = 1)
    }
}
