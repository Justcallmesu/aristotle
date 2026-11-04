package io.github.justcallmesu.aristotle.ui.chat

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.justcallmesu.aristotle.data.LinkState
import io.github.justcallmesu.aristotle.data.chat.ChatFeedState
import io.github.justcallmesu.aristotle.data.chat.ChatItem
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

/**
 * A tool card's one-line summary as shown (TU1 C1, Q68): the part that tells the cards apart survives the ellipsis.
 * The PC's home directory reads `~`, long paths keep their first segment and the differing tail
 * (`~/…/scratch/safe.sh`), a summary that is one long path shows its file name first, and a shell command loses
 * its leading `VAR=…;` assignments. Display only: the data ([io.github.justcallmesu.aristotle.data.chat.ToolText])
 * stays as Hermes sent it. Pure.
 */
object ToolSummary {
    const val ELLIPSIS = "…"

    /** A path this long or shorter is shown whole; a longer one shows its file name first. */
    const val PATH_FIT = 32

    /** Paths with more segments than this hide the middle ones. */
    private const val MAX_SEGMENTS = 4
    private const val TAIL_SEGMENTS = 2

    private val HOME = Regex("(?<![\\w.~/-])(?:/home/[^/\\s'\"`]+|/Users/[^/\\s'\"`]+|/root(?![^/\\s'\"`])|\\$\\{HOME\\}|\\${'$'}HOME\\b)")
    private val ASSIGNMENT = Regex("^\\s*(?:export\\s+)?[A-Za-z_][A-Za-z0-9_]*=(?:'[^']*'|\"[^\"]*\"|[^\\s;&|]*)\\s*(?:;|&&)\\s*")
    private const val PATH_CHAR = "[^\\s'\"`;|&<>(){}\\[\\],=]"
    private val PATH = Regex("$PATH_CHAR*/$PATH_CHAR*")

    fun display(name: String, summary: String): String {
        var text = home(summary)
        if (shell(name)) text = dropAssignments(text)
        val whole = text.trim()
        if (isPath(whole)) return pathFirst(whole)
        return PATH.replace(text) { collapse(it.value) }
    }

    /** `/home/<user>`, `/Users/<user>`, `/root` and `$HOME` as `~`. */
    fun home(text: String): String = HOME.replace(text, "~")

    /** Leading `NAME=value;` (or `&&`) assignments go; a command that is nothing else stays as it is. */
    fun dropAssignments(command: String): String {
        var rest = command
        while (true) {
            val match = ASSIGNMENT.find(rest) ?: return rest
            rest = rest.substring(match.range.last + 1)
            if (rest.isBlank()) return command
        }
    }

    /** `a/b/c/d/e` as `a/…/d/e`: the first segment and the last [TAIL_SEGMENTS]; URLs and short paths unchanged. */
    fun collapse(path: String): String {
        val parts = split(path) ?: return path
        if (parts.names.size <= MAX_SEGMENTS) return path
        return parts.lead + parts.names.first() + "/" + ELLIPSIS + "/" + parts.names.takeLast(TAIL_SEGMENTS).joinToString("/") + parts.trail
    }

    /** A summary that is one path: whole when it fits, else `name · ~/…/dir`. */
    private fun pathFirst(path: String): String {
        if (path.length <= PATH_FIT) return path
        val parts = split(path) ?: return path
        if (parts.names.size < 2) return path
        val dir = parts.names.dropLast(1)
        val dirText = parts.lead + if (dir.size > 2) dir.first() + "/" + ELLIPSIS + "/" + dir.last() else dir.joinToString("/")
        return parts.names.last() + parts.trail + " · " + dirText
    }

    /** A shell tool: its summary is a command line (not code such as `execute_code`, where `x=1;` is a statement). */
    private fun shell(name: String): Boolean = name.lowercase(Locale.ROOT).let { lower -> SHELL_WORDS.any { it in lower } }

    private val SHELL_WORDS = listOf("terminal", "shell", "bash")

    private fun isPath(text: String): Boolean = text.isNotEmpty() && '/' in text && "://" !in text && PATH.matches(text)

    private class Parts(val lead: String, val names: List<String>, val trail: String)

    private fun split(path: String): Parts? {
        if ("://" in path) return null
        val lead = if (path.startsWith("/")) "/" else ""
        val trail = if (path.length > 1 && path.endsWith("/")) "/" else ""
        val body = path.removePrefix(lead).removeSuffix(trail)
        val names = body.split('/')
        if (names.any { it.isEmpty() }) return null
        return Parts(lead, names, trail)
    }
}

/** What a transcript row is, for the gap above the next one (Q67 chat rhythm). */
enum class RowKind { User, Message, Work }

/** The gap above a transcript row: none for the first, then work / message / turn (design.md §4). */
enum class RowGap { None, Work, Message, Turn }

/**
 * Q67: the gap above a row is chosen by the row before it. 4 dp inside a run of work (tool calls, reasoning,
 * notices), 12 dp between a message and work, 20 dp before a user bubble that starts a turn; a user bubble right
 * after another one is the same turn (12 dp). Pure.
 */
object ChatRhythm {
    fun gap(previous: RowKind?, current: RowKind): RowGap = when {
        previous == null -> RowGap.None
        current == RowKind.User -> if (previous == RowKind.User) RowGap.Message else RowGap.Turn
        previous == RowKind.Work && current == RowKind.Work -> RowGap.Work
        else -> RowGap.Message
    }

    fun kindOf(item: ChatItem): RowKind = when (item) {
        is ChatItem.User -> RowKind.User
        is ChatItem.Assistant -> RowKind.Message
        is ChatItem.Reasoning, is ChatItem.Tool, is ChatItem.Notice, is ChatItem.Card -> RowKind.Work
    }

    fun kindOf(row: TranscriptRow): RowKind = when (row) {
        is TranscriptRow.Single -> kindOf(row.item)
        is TranscriptRow.Folded -> RowKind.Work
    }
}

/**
 * A status line in one text (TU1 C3): the live or coloured [lead] first, then the details, all joined by " · " so
 * every separator is the same. [leadEnd] is where the lead's span ends (0: no lead). Pure.
 */
data class StatusText(val text: String, val leadEnd: Int) {
    companion object {
        const val SEPARATOR = " · "

        fun of(lead: String?, details: List<String?>): StatusText {
            val rest = details.filterNotNull().filter { it.isNotBlank() }
            val head = lead?.takeIf { it.isNotBlank() }
            val text = listOfNotNull(head).plus(rest).joinToString(SEPARATOR)
            return StatusText(text, head?.length ?: 0)
        }
    }
}

/**
 * Markdown tables in the chat (TU1 C6): cells wrap instead of being cut to one line, each column [CELL_MIN]–[CELL_MAX]
 * wide, the table scrolls sideways when the columns do not fit; a table of more than [MAX_COLUMNS] columns shows as
 * its source in a mono block. Pure.
 */
object ChatTables {
    const val MAX_COLUMNS = 3
    val CELL_MIN: Dp = 120.dp
    val CELL_MAX: Dp = 240.dp

    fun asMono(columns: Int): Boolean = columns > MAX_COLUMNS

    /** Columns share [available] evenly within the bounds. */
    fun cellWidth(available: Dp, columns: Int): Dp = (available / columns.coerceAtLeast(1)).coerceIn(CELL_MIN, CELL_MAX)
}

/**
 * Inline code without the renderer's space padding (TU1 C7): the code span's text without its backtick fences, at
 * most one thin space each side inside the background. CommonMark: equal backtick runs fence the span; one space
 * just inside each fence is dropped when both are there; line ends read as spaces. Pure.
 */
object InlineCode {
    const val PAD = "\u2009"

    fun text(raw: String): String {
        val fence = raw.takeWhile { it == '`' }.length
        val closing = raw.takeLastWhile { it == '`' }.length
        if (fence == 0 || fence != closing || raw.length < fence * 2) return raw
        var inner = raw.substring(fence, raw.length - fence).replace('\n', ' ')
        if (inner.length >= 2 && inner.startsWith(' ') && inner.endsWith(' ') && inner.isNotBlank()) inner = inner.substring(1, inner.length - 1)
        return inner
    }

    fun padded(raw: String): String = PAD + text(raw) + PAD
}
