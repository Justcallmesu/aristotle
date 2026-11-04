package io.github.justcallmesu.aristotle.data.chat

/** An attachment a user message names: an image, a file or a folder. [sha256]: of a stored image, when its path tells. */
data class UserAttachment(val kind: Kind, val label: String, val value: String, val sha256: String? = null) {
    enum class Kind { Image, File, Folder }
}

/**
 * A user message's text split into what the bubble shows and its attachments, read the way the Desktop reads a stored
 * user row (`apps/desktop/src/lib/chat-messages/hydration.ts:510-521`). Pure.
 *
 * - Images: a native-vision row ends with one `[Image attached at: <path>]` (or `[Image attached: <url>]`) line per
 *   image, then one `[screenshot]` line per image (`hydration.ts:40-50`; written by `agent/image_routing.py:542-563`
 *   and `agent/session_persistence.py:122-141`); otherwise `@image:<path>` lines anywhere (`lib/embedded-images.ts:183-197`,
 *   `tui_gateway/session_history.py:51-60`), and then `[screenshot]` lines go too.
 * - Files: the leading run of `@file:` / `@folder:` lines (`components/assistant-ui/reference-kinds.ts:175-197`), the
 *   form `file.attach` hands out as `ref_text` and the prompt text carries (`AttachFrames.promptText`).
 *
 * The same applies to a turn's `pending` text, which carries only the `@file:` lines (images ride beside it).
 */
object AttachmentRefs {
    data class Split(val text: String, val attachments: List<UserAttachment>)

    fun split(raw: String): Split {
        val (body, images) = imageRefs(raw)
        val (text, files) = leadingFileRefs(body)
        val attachments = files + images
        return if (attachments.isEmpty()) Split(raw, emptyList()) else Split(text.trim(), attachments)
    }

    // ── images ────────────────────────────────────────────────────────────────────────────────────

    private fun imageRefs(text: String): Pair<String, List<UserAttachment>> {
        val suffix = NATIVE_SUFFIX.find(text) ?: return directiveRefs(text)
        val values = HINT_LINE.findAll(suffix.groupValues[1]).map { it.groupValues[1] }.toList()
        if (values.size != SCREENSHOT.findAll(suffix.groupValues[2]).count()) return directiveRefs(text)
        val (cleaned, directives) = directiveRefs(text.substring(0, suffix.range.first))
        return cleaned to directives + values.map(::image)
    }

    private fun directiveRefs(text: String): Pair<String, List<UserAttachment>> {
        val refs = IMAGE_REF_LINE.findAll(text).map { it.value.trim() }.toList()
        if (refs.isEmpty()) return text to emptyList()
        val cleaned = SCREENSHOT_LINE.replace(IMAGE_REF_LINE.replace(text, ""), "")
        return cleaned to refs.map { image(unquote(it.removePrefix("@image:"))) }
    }

    private fun image(value: String): UserAttachment {
        val segments = value.trimEnd('/').split('/', '\\')
        val sha = segments.getOrNull(segments.size - 2)?.takeIf { SHA256.matches(it) }
        return UserAttachment(UserAttachment.Kind.Image, segments.last().ifEmpty { value }, value, sha)
    }

    // ── files ─────────────────────────────────────────────────────────────────────────────────────

    private fun leadingFileRefs(text: String): Pair<String, List<UserAttachment>> {
        val lines = text.split('\n').toMutableList()
        val refs = mutableListOf<UserAttachment>()
        while (lines.isNotEmpty()) {
            val line = lines.first().trimEnd()
            val match = REFERENCE_LINE.matchEntire(line)?.takeIf { it.groupValues[1] == "file" || it.groupValues[1] == "folder" } ?: break
            val value = unwrap(match.groupValues[2])
            val kind = if (match.groupValues[1] == "folder") UserAttachment.Kind.Folder else UserAttachment.Kind.File
            refs += UserAttachment(kind, value.trimEnd('/').substringAfterLast('/').ifEmpty { value }, value)
            lines.removeAt(0)
        }
        return if (refs.isEmpty()) text to refs else lines.joinToString("\n").trim() to refs
    }

    /** Wire quotes off; a bare value also sheds trailing prose punctuation (`reference-kinds.ts:199-223`). */
    private fun unwrap(raw: String): String {
        val value = unquote(raw)
        return if (value == raw) value.replace(TRAILING_PUNCTUATION, "") else value
    }

    private fun unquote(raw: String): String {
        if (raw.length < 2) return raw
        val head = raw.first()
        return if ((head == '`' || head == '"' || head == '\'') && raw.last() == head) raw.substring(1, raw.length - 1) else raw
    }

    // Android compiles these with ICU, stricter than the JDK the unit tests run on: a literal brace outside a character
    // class is escaped (`\{`, `\}`) and a lookbehind has a bounded length. IcuRegexGuardTest holds every pattern to that.
    private val NATIVE_SUFFIX = Regex("""\n\n((?:\[Image attached(?: at)?: [^\n]+]\n)+)((?:\[screenshot](?:\n|$))+)\z""")
    private val HINT_LINE = Regex("""(?m)^\[Image attached(?: at)?: (.+)]$""")
    private val SCREENSHOT = Regex("""\[screenshot]""")
    private val IMAGE_REF_LINE = Regex("""(?m)^@image:[^\n]*\n?""")
    private val SCREENSHOT_LINE = Regex("""(?m)^\[screenshot]\n?""")
    private val REFERENCE_LINE = Regex("""@(file|folder|url|image|tool|line|terminal|session):(`[^`\n]+`|"[^"\n]+"|'[^'\n]+'|\S+)""")
    private val TRAILING_PUNCTUATION = Regex("""[,.;!?]+$""")
    private val SHA256 = Regex("[0-9a-f]{64}")
}
