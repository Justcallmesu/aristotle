package io.github.justcallmesu.aristotle

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TL2 (Q80b): the app logs only through `AppLog` (redacted, bounded, on this phone), and its calls name events, never
 * content. Sweeps the main sources: no `android.util.Log` outside the one logcat bridge, and no `AppLog` call that
 * puts a content-bearing variable (message text, prompt, reasoning, tool output, server details, secrets) or an
 * exception message into its line.
 */
class AppLogSourceGuardTest {
    @Test
    fun onlyTheBridgeTalksToLogcat() {
        val files = sources()
        assertTrue("only ${files.size} sources found", files.size > 50)
        val direct = files.filter { file ->
            val text = file.readText()
            ("import android.util.Log\n" in text || DIRECT_CALL.containsMatchIn(text)) && relative(file) !in LOGCAT_ALLOWED
        }.map(::relative)
        assertEquals("route these through AppLog", emptyList<String>(), direct)
    }

    @Test
    fun appLogCallsNeverTemplateContent() {
        val bad = mutableListOf<String>()
        var calls = 0
        sources().forEach { file ->
            file.readLines().forEachIndexed { index, line ->
                if (!APP_LOG_CALL.containsMatchIn(line)) return@forEachIndexed
                calls++
                val templates = TEMPLATE.findAll(line).map { it.groupValues[1].ifEmpty { it.groupValues[2] } }.toList()
                templates.filter { CONTENT.containsMatchIn(it) }.forEach { bad += "${relative(file)}:${index + 1}: \${$it}" }
            }
        }
        assertTrue("only $calls AppLog calls found", calls >= 4)
        assertEquals("AppLog lines name events and ids, never content", emptyList<String>(), bad)
    }

    @Test
    fun theSweepCatchesWhatItShould() {
        val samples = listOf(
            "AppLog.d(\"sent \${frame.text}\")",
            "AppLog.w(\"failed \${e.message}\")",
            "AppLog.i(\"prompt \$prompt\")",
            "AppLog.i(\"server said \${state.details}\")",
            "AppLog.e(\"x \${e.localizedMessage}\")",
        )
        samples.forEach { sample ->
            assertTrue(sample, TEMPLATE.findAll(sample).any { m -> CONTENT.containsMatchIn(m.groupValues[1].ifEmpty { m.groupValues[2] }) })
        }
        assertTrue(TEMPLATE.findAll("AppLog.i(\"link state=\${state.cause} n=\$count\")").none { m -> CONTENT.containsMatchIn(m.groupValues[1].ifEmpty { m.groupValues[2] }) })
    }

    private fun relative(file: File): String = file.invariantSeparatorsPath.substringAfter("/aristotle/")

    private fun sources(): List<File> {
        val root = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    private companion object {
        /**
         * `background/DebugShare.kt` is the logcat bridge (debug builds only, redacted lines). `ui/theme/Theme.kt` logs
         * a constant line when a font fails to load; it belongs to the design ticket's files and is left as it is.
         */
        val LOGCAT_ALLOWED = setOf("background/DebugShare.kt", "ui/theme/Theme.kt")
        val DIRECT_CALL = Regex("""(?<![A-Za-z.])Log\.(?:v|d|i|w|e|wtf|println)\(""")
        val APP_LOG_CALL = Regex("""AppLog\.(?:d|i|w|e)\(""")

        // `${expr}` (group 1) or `$name` (group 2).
        val TEMPLATE = Regex("""\$\{([^}]*)\}|\$([A-Za-z_][A-Za-z0-9_.]*)""")
        val CONTENT = Regex(
            """(?i)(?:text|content|message|prompt|reasoning|output|body|details|title|token|password|secret|answer|args|arguments|draft|stdout|stderr|preview)""",
        )
    }
}
