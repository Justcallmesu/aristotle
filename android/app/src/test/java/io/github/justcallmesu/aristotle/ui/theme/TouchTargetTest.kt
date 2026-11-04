package io.github.justcallmesu.aristotle.ui.theme

import androidx.compose.ui.unit.dp
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Q67: Material controls keep their visible size (40 dp buttons, 32 dp chips) and get their 48 dp touch area from
 * `minimumInteractiveComponentSize`. This scans the sources so a control can neither be stretched back to 48 dp nor
 * have that touch area capped by a fixed size.
 */
class TouchTargetTest {
    private val sources: List<File> = File("src/main/java").walkTopDown().filter { it.extension == "kt" }.toList()

    private val controls = listOf(
        "Button", "OutlinedButton", "FilledTonalButton", "TextButton", "ElevatedButton", "FilterChip", "AssistChip",
        "InputChip", "SuggestionChip", "IconButton", "FilledIconButton", "IconToggleButton", "RadioButton", "Checkbox", "Switch",
    )
    private val call = Regex("""(?<![\w.])(${controls.joinToString("|")})\(""")

    // Terminal extra keys are key caps: their 48 dp is the visible key, on purpose (T19b).
    private val stretchAllowed = setOf("ExtraKeys.kt")

    @Test
    fun theThemeKeepsMaterialsFortyEightDpMinimum() {
        assertTrue(Sizes.minTouchTarget >= 48.dp)
        val theme = sources.single { it.name == "Theme.kt" }.readText()
        assertTrue(theme.contains("LocalMinimumInteractiveComponentSize provides Sizes.minTouchTarget"))
        val overrides = sources.filter { it.name != "Theme.kt" && it.readText().contains("LocalMinimumInteractive") }
        assertEquals("only the theme sets the minimum touch size", emptyList<String>(), overrides.map { it.name })
    }

    @Test
    fun noControlIsStretchedOrHasItsTouchAreaCapped() {
        assertTrue("no sources found from ${File(".").absolutePath}", sources.size > 20)
        val bad = mutableListOf<String>()
        for (file in sources) {
            val text = file.readText()
            for (match in call.findAll(text)) {
                if (text.lineStartingAt(match.range.first).trimStart().startsWith("import ")) continue
                val modifier = topLevelArgs(text, match.range.last + 1).firstOrNull { it.startsWith("modifier") } ?: continue
                val line = text.substring(0, match.range.first).count { it == '\n' } + 1
                val where = "${file.name}:$line ${match.groupValues[1]}"
                if (modifier.contains("heightIn(min = Sizes.minTouchTarget)") && file.name !in stretchAllowed) bad += "$where stretched: $modifier"
                if (CAPS.any { modifier.contains(it) }) bad += "$where capped: $modifier"
            }
        }
        assertEquals(emptyList<String>(), bad)
    }

    private fun String.lineStartingAt(index: Int): String = substring(lastIndexOf('\n', index) + 1, index)

    /** The comma-separated arguments of the call whose `(` is just before [open], at nesting depth 0. */
    private fun topLevelArgs(text: String, open: Int): List<String> {
        val args = mutableListOf<String>()
        var depth = 0
        var start = open
        var i = open
        while (i < text.length) {
            when (text[i]) {
                '(', '{', '[' -> depth++
                ')', '}', ']' -> {
                    if (depth == 0) {
                        args += text.substring(start, i).trim()
                        return args.filter { it.isNotEmpty() }
                    }
                    depth--
                }
                ',' -> if (depth == 0) {
                    args += text.substring(start, i).trim()
                    start = i + 1
                }
            }
            i++
        }
        return args
    }

    private companion object {
        /** Modifiers that fix or cap a control's size, which would shrink its touch area under 48 dp. */
        val CAPS = listOf(".height(", ".size(", ".requiredHeight(", ".requiredSize(", "heightIn(max", "sizeIn(max")
    }
}
