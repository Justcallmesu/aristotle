package io.github.justcallmesu.aristotle.ui.chat

import io.github.justcallmesu.aristotle.data.chat.ChatItem
import io.github.justcallmesu.aristotle.data.chat.ToolStatus

/** One row of the transcript: a chat item as it is, or a run of tool calls folded into one card (Q68). */
sealed interface TranscriptRow {
    val key: String

    data class Single(val item: ChatItem) : TranscriptRow {
        override val key: String get() = item.key
    }

    /**
     * [items]: from the run's first tool call to its last, with the reasoning rows between them, in order. Keyed by
     * the first call, so the card keeps its place and its open state while the run grows.
     */
    data class Folded(val items: List<ChatItem>) : TranscriptRow {
        override val key: String = ToolFolding.KEY_PREFIX + items.first().key
        val tools: List<ChatItem.Tool> = items.filterIsInstance<ChatItem.Tool>()
        val last: ChatItem.Tool get() = tools.last()

        /** A call still runs: the card keeps the spinner while the chat is live (verdigris only for live work). */
        val running: Boolean get() = tools.any { it.status == ToolStatus.Running }

        /** A call failed: the folded header says so. */
        val failed: Boolean get() = tools.any { it.status == ToolStatus.Failed }
    }
}

/**
 * Q68: [MIN_RUN] or more tool calls with no message between them fold into one "N tool calls · last: <name>" card that
 * opens to the single cards. Reasoning rows between the calls fold with them; reasoning before the first call or after
 * the last stays outside (a streaming "Thinking…" stays in view). A message, a notice, a notification card or a
 * delegation (it reads as its children, T17) ends a run; assistant text that is still empty does not. Pure.
 */
object ToolFolding {
    const val MIN_RUN = 3
    const val KEY_PREFIX = "fold-"

    fun rows(items: List<ChatItem>): List<TranscriptRow> {
        val out = ArrayList<TranscriptRow>(items.size)
        val run = ArrayList<ChatItem>()
        for (item in items) {
            if (inRun(item)) {
                run += item
            } else {
                flush(run, out)
                out += TranscriptRow.Single(item)
            }
        }
        flush(run, out)
        return out
    }

    private fun inRun(item: ChatItem): Boolean = when (item) {
        is ChatItem.Tool -> item.subagents.isEmpty()
        is ChatItem.Reasoning -> true
        is ChatItem.Assistant -> item.text.isBlank()
        is ChatItem.User, is ChatItem.Notice, is ChatItem.Card -> false
    }

    private fun flush(run: MutableList<ChatItem>, out: MutableList<TranscriptRow>) {
        if (run.isEmpty()) return
        val first = run.indexOfFirst { it is ChatItem.Tool }
        val last = run.indexOfLast { it is ChatItem.Tool }
        if (run.count { it is ChatItem.Tool } < MIN_RUN) {
            run.mapTo(out) { TranscriptRow.Single(it) }
        } else {
            run.subList(0, first).mapTo(out) { TranscriptRow.Single(it) }
            out += TranscriptRow.Folded(run.subList(first, last + 1).toList())
            run.subList(last + 1, run.size).mapTo(out) { TranscriptRow.Single(it) }
        }
        run.clear()
    }
}
