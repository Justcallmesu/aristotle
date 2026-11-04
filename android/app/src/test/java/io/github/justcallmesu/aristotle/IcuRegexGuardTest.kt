package io.github.justcallmesu.aristotle

import io.github.justcallmesu.aristotle.ui.chat.ToolSummary
import java.io.File
import java.lang.reflect.Modifier
import java.util.jar.JarFile
import java.util.regex.Pattern
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TU4b: every regex in the app must compile on Android, whose `java.util.regex` is ICU, not the JDK engine these JVM tests
 * run on. The JDK accepts patterns ICU rejects with a `PatternSyntaxException` in the static initializer, so the app crashes
 * where the tests pass (`\$\{HOME}` in ToolSummary did, on the device). [IcuRegexRules] states the ICU rules we rely on; the
 * sweeps below hold every regex constant (reflection over the compiled app classes) and every inline regex literal (the
 * sources) to them. The rules were checked against ICU 78's `uregex_open` (the cases in [rulesMatchWhatIcuAccepts]).
 */
class IcuRegexGuardTest {
    @Test
    fun rulesMatchWhatIcuAccepts() {
        // Rejected by ICU 78 (uregex_open), accepted by the JDK, or JDK-only syntax.
        val rejected = listOf(
            """(?<![\w.~/-])(?:/home/[^/\s'"`]+|/root(?![^/\s'"`])|\$\{HOME}|\${'$'}HOME\b)""", // the device crash
            """\$\{HOME}""", "}", "{", "a{", "a{,3}", "a{1, 2}", "x{2}{3}", "a*{2}", "({1})",
            "(?<=a+)", "(?<=a*)", "(?<=a{2,})", "(?U)a", """[a-\w]""", """\b{g}""",
        )
        // Accepted by ICU 78.
        val accepted = listOf(
            """\$\{HOME\}""", "[^(){}]", "[{]", "[}]", """\d{1,2}""", "[0-9a-f]{4}", "a{2,}", "a{3}+", "a{1,3}?",
            """(?<![\w.~/-])x""", "(?<=a{1,3})", "(?<!a|bc)", """\p{L}+""", """\x{41}""", """\Q{}\E""", """[\w-.]""",
            "[]a]", "[^]a]", "[a-z&&[^b]]", "(?<name>a)\\k<name>", "(?i)a", "(?m)^a$", "(?i:a)", "a*+", "(?>a)", "]",
        )
        rejected.forEach { assertTrue("ICU rejects $it", IcuRegexRules.violations(it).isNotEmpty()) }
        accepted.forEach { assertEquals("ICU accepts $it", emptyList<String>(), IcuRegexRules.violations(it)) }
        assertTrue(IcuRegexRules.violations("a", Pattern.CANON_EQ).isNotEmpty())
    }

    @Test
    fun everyRegexConstantFollowsTheIcuRules() {
        val found = regexConstants()
        // The sweep must see the app's regexes, or it would pass by finding nothing.
        assertTrue("only ${found.size} regex constants found", found.size >= MIN_CONSTANTS)
        assertTrue(found.keys.any { it.startsWith(ToolSummary::class.java.name + ".") })
        dump(found.values)
        val bad = found.flatMap { (name, pattern) -> IcuRegexRules.violations(pattern.pattern(), pattern.flags()).map { "$name: $it" } }
        assertEquals("regexes Android's ICU engine rejects", emptyList<String>(), bad)
    }

    @Test
    fun everyInlineRegexLiteralFollowsTheIcuRules() {
        val constants = regexConstants().keys.map { it.substringAfterLast('.') }.toSet()
        val bad = mutableListOf<String>()
        val literals = mutableListOf<Pattern>()
        sources().forEach { file ->
            val text = file.readText()
            CALL.findAll(text).forEach { call ->
                val where = "${file.name}:${text.substring(0, call.range.first).count { it == '\n' } + 1}"
                val literal = KotlinLiteral.at(text, call.range.last + 1)
                when {
                    literal == null -> bad += "$where: the pattern is not a string literal; make it one, or a `val` constant"
                    literal.templated -> {
                        // A templated pattern is checked as the constant it builds (the sweep above), never inline.
                        val name = DECLARED.find(text.substring(text.lastIndexOf('\n', call.range.first) + 1, call.range.first))?.groupValues?.get(1)
                        if (name == null || name !in constants) bad += "$where: a templated pattern must be a regex constant (`val NAME = Regex(…)`)"
                    }
                    else -> {
                        literals += Pattern.compile(literal.value)
                        IcuRegexRules.violations(literal.value).forEach { bad += "$where: $it" }
                    }
                }
            }
            // `"…".toRegex()`: the literal is the receiver.
            TO_REGEX.findAll(text).forEach { call ->
                val where = "${file.name}:${text.substring(0, call.range.first).count { it == '\n' } + 1}"
                val literal = KotlinLiteral.at(text, call.range.first)
                if (literal == null || literal.templated) {
                    bad += "$where: use a plain string literal or a regex constant"
                } else {
                    literals += Pattern.compile(literal.value)
                    IcuRegexRules.violations(literal.value).forEach { bad += "$where: $it" }
                }
            }
        }
        assertTrue("no inline regex literal found: the scan is broken", literals.size >= MIN_LITERALS)
        dump(literals)
        assertEquals("regex literals Android's ICU engine rejects", emptyList<String>(), bad)
    }

    /** Every static `Regex` / `Pattern` field of the compiled app classes (objects and companions keep theirs static). */
    private fun regexConstants(): Map<String, Pattern> {
        val root = File(ToolSummary::class.java.protectionDomain.codeSource.location.toURI())
        val loader = ToolSummary::class.java.classLoader
        val found = sortedMapOf<String, Pattern>()
        // The app classes come as a directory or as a jar, depending on the build.
        val names = if (root.isDirectory) {
            root.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).path.replace(File.separatorChar, '/') }.toList()
        } else {
            JarFile(root).use { jar -> jar.entries().asSequence().map { it.name }.toList() }
        }
        names.filter { it.endsWith(".class") && it.startsWith(APP_PATH) }.forEach { entry ->
            val name = entry.removeSuffix(".class").replace('/', '.')
            val fields = runCatching { Class.forName(name, false, loader).declaredFields }.getOrNull() ?: return@forEach
            fields.filter { Modifier.isStatic(it.modifiers) && (it.type == Regex::class.java || it.type == Pattern::class.java) }.forEach { field ->
                field.isAccessible = true
                // Reading it runs the class's static initializer: a pattern the JDK rejects fails here too.
                val pattern = when (val value = field.get(null)) {
                    is Regex -> value.toPattern()
                    is Pattern -> value
                    else -> return@forEach
                }
                found["$name.${field.name}"] = pattern
            }
        }
        return found
    }

    private fun sources(): List<File> {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val main = File(dir, "app/src/main/java").takeIf { it.isDirectory } ?: File(dir, "src/main/java").takeIf { it.isDirectory }
            if (main != null) return main.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
            dir = dir.parentFile
        }
        error("app sources not found above ${System.getProperty("user.dir")}")
    }

    /** `ICU_REGEX_DUMP=<file>`: append `flags<TAB>pattern` lines, to run them through a real ICU (`uregex_open`) by hand. */
    private fun dump(patterns: Collection<Pattern>) {
        val target = System.getenv("ICU_REGEX_DUMP") ?: return
        val lines = patterns.joinToString("") { p ->
            val flags = (if (p.flags() and Pattern.CASE_INSENSITIVE != 0) "i" else "") + (if (p.flags() and Pattern.MULTILINE != 0) "m" else "") +
                (if (p.flags() and Pattern.DOTALL != 0) "s" else "") + (if (p.flags() and Pattern.COMMENTS != 0) "x" else "")
            "$flags\t${p.pattern().replace("\n", "\\n")}\n"
        }
        File(target).appendText(lines)
    }

    private companion object {
        const val MIN_CONSTANTS = 20
        const val MIN_LITERALS = 20
        val APP_PATH = "io/github/justcallmesu/aristotle/"
        val CALL = Regex("""\b(?:Regex|Pattern\.compile)\(\s*""")
        val DECLARED = Regex("""\bval\s+([A-Za-z_][A-Za-z0-9_]*)\s*=\s*$""")
        val TO_REGEX = Regex(""""(?:[^"\\\n]|\\.)*"\.toRegex\(""")
    }
}

/** A Kotlin string literal in source: its value, and whether it has a template other than `${'$'}`. */
private class KotlinLiteral(val value: String, val templated: Boolean) {
    companion object {
        fun at(text: String, start: Int): KotlinLiteral? = when {
            text.startsWith("\"\"\"", start) -> raw(text, start + 3)
            text.startsWith("\"", start) -> escaped(text, start + 1)
            else -> null
        }

        private fun raw(text: String, from: Int): KotlinLiteral? {
            val end = text.indexOf("\"\"\"", from).takeIf { it >= 0 } ?: return null
            return templates(text.substring(from, end))
        }

        private fun escaped(text: String, from: Int): KotlinLiteral? {
            val out = StringBuilder()
            var i = from
            while (i < text.length) {
                val c = text[i]
                when {
                    c == '"' -> return templates(out.toString())
                    c == '\\' -> {
                        val n = text[i + 1]
                        when (n) {
                            'n' -> out.append('\n')
                            't' -> out.append('\t')
                            'r' -> out.append('\r')
                            'b' -> out.append('\b')
                            'u' -> { out.append(text.substring(i + 2, i + 6).toInt(16).toChar()); i += 4 }
                            // `\$` is a literal dollar: keep it apart from a template.
                            '$' -> out.append(DOLLAR)
                            else -> out.append(n)
                        }
                        i += 2
                    }
                    else -> { out.append(c); i++ }
                }
            }
            return null
        }

        private fun templates(body: String): KotlinLiteral {
            val plain = body.replace("\${'$'}", DOLLAR.toString())
            val templated = Regex("""\$(?:\{|[A-Za-z_])""").containsMatchIn(plain)
            return KotlinLiteral(plain.replace(DOLLAR, '$'), templated)
        }

        private const val DOLLAR = '\uE000'
    }
}

/**
 * The ICU regex rules the app relies on, where Android's ICU engine and the JDK's differ (checked against ICU 78):
 * - `{` outside a character class only opens a quantifier `{n}`, `{n,}` or `{n,m}` (no spaces) that follows something to
 *   repeat; `}` only closes one. A literal brace is escaped: `\{`, `\}`. Inside a class, `{` and `}` are literals.
 * - one quantifier per atom: `x{2}{3}` and `a*{2}` are errors.
 * - a lookbehind has a bounded length: no `*`, `+` or `{n,}` inside `(?<=…)` / `(?<!…)`.
 * - no JDK-only syntax: the `(?U)` flag, `\b{…}`, a class range ending in a class escape (`[a-\w]`), and the CANON_EQ /
 *   UNICODE_CHARACTER_CLASS options.
 */
internal object IcuRegexRules {
    private val QUANTIFIER = Regex("""\{(\d+)(,(\d*))?\}""")
    private const val CLASS_ESCAPES = "dDsSwWhHvVpP"
    private const val ZERO_WIDTH_ESCAPES = "bBAzZG"

    fun violations(pattern: String, flags: Int = 0): List<String> {
        val out = mutableListOf<String>()
        if (flags and Pattern.CANON_EQ != 0) out += "CANON_EQ is not supported on Android"
        if (flags and Pattern.UNICODE_CHARACTER_CLASS != 0) out += "UNICODE_CHARACTER_CLASS is not supported on Android"
        var i = 0
        var depth = 0 // character class nesting
        var classBody = -1 // where the outer class's body starts: a `]` there is a literal
        var atom = false // something a quantifier may repeat comes just before
        val groups = ArrayDeque<Boolean>() // open groups; true for a lookbehind
        while (i < pattern.length) {
            val c = pattern[i]
            if (c == '\\') {
                val n = pattern.getOrNull(i + 1) ?: return out + "a trailing backslash"
                if (n == 'Q') {
                    val end = pattern.indexOf("\\E", i + 2)
                    i = if (end < 0) pattern.length else end + 2
                    atom = true
                    continue
                }
                if (n in "pPxN" && pattern.getOrNull(i + 2) == '{') {
                    val end = pattern.indexOf('}', i + 3)
                    if (end < 0) return out + "an unclosed \\$n{ at $i"
                    i = end + 1
                    atom = true
                    continue
                }
                if (n == 'b' && depth == 0 && pattern.getOrNull(i + 2) == '{') out += "\\b{…} at $i is JDK-only"
                i += 2
                atom = n !in ZERO_WIDTH_ESCAPES
                continue
            }
            if (depth > 0) {
                when {
                    c == '[' -> depth++
                    c == ']' && i != classBody -> depth--
                    c == '-' && i != classBody && pattern.getOrNull(i + 1) == '\\' && (pattern.getOrNull(i + 2) ?: ' ') in CLASS_ESCAPES ->
                        out += "a class range ending in a class escape at $i"
                }
                i++
                if (depth == 0) atom = true
                continue
            }
            when (c) {
                '[' -> {
                    depth = 1
                    classBody = if (pattern.getOrNull(i + 1) == '^') i + 2 else i + 1
                    i = classBody
                    continue
                }
                '(' -> {
                    i = openGroup(pattern, i, groups, out)
                    atom = false
                    continue
                }
                ')' -> { groups.removeLastOrNull(); atom = true }
                '|' -> atom = false
                '{' -> {
                    val match = QUANTIFIER.matchAt(pattern, i)
                    when {
                        match == null -> out += "a bare '{' at $i: escape it as \\{"
                        !atom -> out += "a quantifier with nothing to repeat at $i (escape a literal '{')"
                        groups.any { it } && match.groupValues[2].isNotEmpty() && match.groupValues[3].isEmpty() -> out += "an unbounded lookbehind at $i"
                    }
                    i = (match?.range?.last ?: i) + 1
                    i = skipModifier(pattern, i)
                    atom = false
                    continue
                }
                '}' -> out += "a bare '}' at $i: escape it as \\}"
                '*', '+' -> {
                    if (groups.any { it }) out += "an unbounded lookbehind at $i"
                    i = skipModifier(pattern, i + 1)
                    atom = false
                    continue
                }
                '?' -> {
                    i = skipModifier(pattern, i + 1)
                    atom = false
                    continue
                }
                else -> atom = c != '^' && c != '$'
            }
            i++
        }
        return out
    }

    /** `(`, `(?:`, `(?=`, `(?!`, `(?>`, `(?<=`, `(?<!`, `(?<name>`, `(?flags)`, `(?flags:`; returns where the group's body starts. */
    private fun openGroup(pattern: String, at: Int, groups: ArrayDeque<Boolean>, out: MutableList<String>): Int {
        if (pattern.getOrNull(at + 1) != '?') {
            groups.addLast(false)
            return at + 1
        }
        val rest = pattern.substring(at + 2)
        return when {
            rest.startsWith("<=") || rest.startsWith("<!") -> { groups.addLast(true); at + 4 }
            rest.startsWith("<") -> { groups.addLast(false); pattern.indexOf('>', at).let { if (it < 0) pattern.length else it + 1 } }
            rest.firstOrNull() in listOf(':', '=', '!', '>') -> { groups.addLast(false); at + 3 }
            else -> {
                val letters = rest.takeWhile { it.isLetter() || it == '-' }
                if ('U' in letters) out += "the (?U) flag at $at is JDK-only"
                val end = at + 2 + letters.length
                if (pattern.getOrNull(end) == ':') groups.addLast(false)
                end + 1
            }
        }
    }

    /** A lazy `?` or possessive `+` after a quantifier. */
    private fun skipModifier(pattern: String, at: Int): Int = if (pattern.getOrNull(at) == '?' || pattern.getOrNull(at) == '+') at + 1 else at
}
