package io.github.justcallmesu.aristotle.ui.chat

import io.github.justcallmesu.aristotle.data.chat.ChatItem
import io.github.justcallmesu.aristotle.data.chat.ToolStatus

/** One row of the transcript: a chat item as it is, or a run of work items folded into one "N steps" line (Q75). */
sealed interface TranscriptRow {
    val key: String

    data class Single(val item: ChatItem) : TranscriptRow {
        override val key: String get() = item.key
    }

    /**
     * [items]: from the run's first step (reasoning or tool call) to its last, in order, with any empty assistant text
     * between them. Keyed by the first step, so the line keeps its place and its open state while the run grows.
     */
    data class Folded(val items: List<ChatItem>) : TranscriptRow {
        override val key: String = ToolFolding.KEY_PREFIX + items.first().key

        /** The steps the open list shows: reasoning rows and tool calls, without the empty assistant text. */
        val steps: List<ChatItem> = items.filter(ToolFolding::isStep)
        val tools: List<ChatItem.Tool> = items.filterIsInstance<ChatItem.Tool>()

        /** The run's last tool call; null for a run of reasoning only. */
        val lastTool: ChatItem.Tool? get() = tools.lastOrNull()

        /** A step still runs: a call, or reasoning that streams. The line shows it as running only while the chat is live. */
        val running: Boolean
            get() = tools.any { it.status == ToolStatus.Running } || items.any { it is ChatItem.Reasoning && it.streaming }

        val failedCount: Int get() = tools.count { it.status == ToolStatus.Failed }

        /** A call failed: the line says so. */
        val failed: Boolean get() = failedCount > 0

        /** What the collapsed line says ([live]: the chat shows live work, [ChatLive]). */
        fun summary(live: Boolean): StepsSummary {
            val runningNow = live && running
            val named = if (runningNow) tools.lastOrNull { it.status == ToolStatus.Running } ?: lastTool else lastTool
            return StepsSummary(steps = steps.size, running = runningNow, tool = named?.name, failed = failedCount)
        }
    }
}

/**
 * The "N steps" line (design-notes §5.2): [steps] counted, [running] = "Running · N steps" in verdigris with the
 * spinner (live only), [tool] = the running call or else the last one (null: reasoning only), [failed] calls in red.
 */
data class StepsSummary(val steps: Int, val running: Boolean, val tool: String?, val failed: Int)

/**
 * Q75 (replaces Q68's fold of 3+ calls): every run of consecutive work items, reasoning rows and tool calls, folds into
 * one "N steps" line that opens to the step rows; one call or one reasoning row is a run too. A message, a notice, a
 * notification card or a delegation (it reads as its children, T17) ends a run; assistant text that is still empty
 * does not, and stays outside the line when it comes before the first step or after the last (its streaming cursor
 * stays in view). Pure.
 */
object ToolFolding {
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

    /** A step of a run: a reasoning row or a tool call that is not a delegation. */
    fun isStep(item: ChatItem): Boolean = when (item) {
        is ChatItem.Tool -> item.subagents.isEmpty()
        is ChatItem.Reasoning -> true
        is ChatItem.User, is ChatItem.Assistant, is ChatItem.Notice, is ChatItem.Card -> false
    }

    private fun inRun(item: ChatItem): Boolean = isStep(item) || (item is ChatItem.Assistant && item.text.isBlank())

    private fun flush(run: MutableList<ChatItem>, out: MutableList<TranscriptRow>) {
        if (run.isEmpty()) return
        val first = run.indexOfFirst(::isStep)
        if (first < 0) {
            run.mapTo(out) { TranscriptRow.Single(it) }
        } else {
            val last = run.indexOfLast(::isStep)
            run.subList(0, first).mapTo(out) { TranscriptRow.Single(it) }
            out += TranscriptRow.Folded(run.subList(first, last + 1).toList())
            run.subList(last + 1, run.size).mapTo(out) { TranscriptRow.Single(it) }
        }
        run.clear()
    }
}
