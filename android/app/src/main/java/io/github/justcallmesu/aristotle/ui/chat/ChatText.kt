package io.github.justcallmesu.aristotle.ui.chat

import io.github.justcallmesu.aristotle.data.LinkState
import io.github.justcallmesu.aristotle.data.chat.ChatFeedState
import java.util.Locale

/** Where the streaming cursor goes in a Markdown block (design.md §5: a block cursor at the text end). */
enum class CursorPlace { None, Inline, Below }

/** The Markdown to render and where the cursor sits. [content] carries [StreamCursor.MARK] when inline. */
data class CursorText(val content: String, val place: CursorPlace)

/**
 * Puts the cursor at the end of streaming Markdown. Inline means a marker after the last character, which the
 * renderer swaps for the cursor block; inside an open code fence, an indented code block or a table row the
 * renderer does not run that swap, so the cursor goes below the block there. The marker is a word joiner
 * (invisible), so a missed swap shows nothing rather than a stray glyph. Pure.
 */
object StreamCursor {
    const val MARK = '\u2060'

    fun place(text: String, streaming: Boolean): CursorText {
        val clean = text.replace(MARK.toString(), "")
        if (!streaming) return CursorText(clean, CursorPlace.None)
        val body = clean.trimEnd()
        val lastLine = body.substringAfterLast('\n')
        val inBlock = openFence(body) || lastLine.startsWith("    ") || lastLine.startsWith("\t") || lastLine.trimStart().startsWith("|")
        return if (inBlock) CursorText(clean, CursorPlace.Below) else CursorText(body + MARK, CursorPlace.Inline)
    }

    /** An odd number of fence lines: the text ends inside a code block. */
    fun openFence(text: String): Boolean = text.lineSequence().count { line ->
        val start = line.trimStart()
        start.startsWith("```") || start.startsWith("~~~")
    } % 2 == 1
}

/**
 * Whether the chat shows live work: verdigris "Running", the streaming cursor, tool spinners (design.md §1:
 * verdigris only for live work; §5: no spinners while offline). Not while the link is down, and not after
 * Hermes refused to reattach the session: the transcript is then a still picture. Pure.
 */
object ChatLive {
    fun of(feed: ChatFeedState, link: LinkState): Boolean = feed.loaded && feed.problem == null && link is LinkState.Online

    /** A loaded chat whose resume on the current socket failed: it shows, but no longer follows the session. */
    fun reattachFailed(feed: ChatFeedState): Boolean = feed.loaded && feed.problem != null
}

/**
 * Which Markdown links a tap opens: web pages only (`http`, `https` with a host). Other schemes (`tel:`,
 * `sms:`, `market:`, `intent:`, another app's deep link) are ignored, since the link text can hide where it
 * goes (T11 review MINOR-4). Returns the address to open, or null. Pure.
 */
object ChatLinks {
    private val WEB = Regex("(?i)https?://[^/?#\\s]+([/?#]\\S*)?")

    fun webTarget(uri: String): String? = uri.trim().takeIf { WEB.matches(it) && it.none(Char::isISOControl) }
}

/** Number formats of the chat. Pure. */
object ChatFormat {
    /** A tool duration: one decimal under 10 s ("0.1", "6.8"), whole seconds above ("20"). */
    fun seconds(value: Double, locale: Locale): String =
        if (value < TENTHS_BELOW) String.format(locale, "%.1f", value) else String.format(locale, "%.0f", value)

    /** "Thought for N s": whole seconds, at least 1. */
    fun thoughtSeconds(millis: Long): Int = ((millis + 999) / 1000).toInt().coerceAtLeast(1)

    private const val TENTHS_BELOW = 10.0
}

/** Which icon a tool card shows; by name, since Hermes sends no category. */
enum class ToolIcon { Terminal, File, Generic }

fun toolIcon(name: String): ToolIcon {
    val lower = name.lowercase(Locale.ROOT)
    return when {
        TERMINAL_WORDS.any { it in lower } -> ToolIcon.Terminal
        FILE_WORDS.any { it in lower } -> ToolIcon.File
        else -> ToolIcon.Generic
    }
}

private val TERMINAL_WORDS = listOf("terminal", "shell", "bash", "exec", "command", "process")
private val FILE_WORDS = listOf("file", "read", "write", "edit", "patch", "search")
