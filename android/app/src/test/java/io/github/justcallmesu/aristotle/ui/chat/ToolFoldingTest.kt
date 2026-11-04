package io.github.justcallmesu.aristotle.ui.chat

import io.github.justcallmesu.aristotle.data.chat.ChatItem
import io.github.justcallmesu.aristotle.data.chat.DelegateRow
import io.github.justcallmesu.aristotle.data.chat.DelegateStatus
import io.github.justcallmesu.aristotle.data.chat.NoticeKind
import io.github.justcallmesu.aristotle.data.chat.ToolStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Q75: every run of work items (reasoning + tool calls, even one) folds into one "N steps" line. */
class ToolFoldingTest {
    private fun tool(key: String, name: String = "terminal", status: ToolStatus = ToolStatus.Done) =
        ChatItem.Tool(key = key, toolId = key, name = name, summary = "", status = status)

    private fun reasoning(key: String, streaming: Boolean = false) = ChatItem.Reasoning(key, "thinking", streaming = streaming)
    private fun said(key: String, text: String = "Done.") = ChatItem.Assistant(key, text)
    private fun user(key: String) = ChatItem.User(key, "go")

    /** Each row as a short label: a key, or `[k1,k2,…]` for a folded run. */
    private fun shape(items: List<ChatItem>): List<String> = ToolFolding.rows(items).map { row ->
        when (row) {
            is TranscriptRow.Single -> row.item.key
            is TranscriptRow.Folded -> row.items.joinToString(",", "[", "]") { it.key }
        }
    }

    private fun fold(vararg items: ChatItem) = ToolFolding.rows(items.toList()).single() as TranscriptRow.Folded

    @Test
    fun threeCallsInARowFoldIntoOneLine() {
        assertEquals(listOf("u", "[t1,t2,t3]", "a"), shape(listOf(user("u"), tool("t1"), tool("t2"), tool("t3"), said("a"))))
    }

    @Test
    fun oneOrTwoCallsFoldToo() {
        assertEquals(listOf("[t1,t2]", "a", "[t3]"), shape(listOf(tool("t1"), tool("t2"), said("a"), tool("t3"))))
    }

    @Test
    fun assistantTextBetweenCallsBreaksTheRun() {
        assertEquals(listOf("[t1,t2]", "a", "[t3,t4]"), shape(listOf(tool("t1"), tool("t2"), said("a"), tool("t3"), tool("t4"))))
    }

    @Test
    fun reasoningFoldsWithTheCallsOnBothSides() {
        val items = listOf(user("u"), reasoning("r0"), tool("t1"), reasoning("r1"), tool("t2"), tool("t3"), reasoning("r2"), said("a"))
        assertEquals(listOf("u", "[r0,t1,r1,t2,t3,r2]", "a"), shape(items))
    }

    @Test
    fun reasoningAloneIsARunOfOneStep() {
        val rows = ToolFolding.rows(listOf(user("u"), reasoning("r"), said("a")))
        assertEquals(listOf("u", "[r]", "a"), shape(listOf(user("u"), reasoning("r"), said("a"))))
        val line = rows[1] as TranscriptRow.Folded
        assertNull(line.lastTool)
        assertEquals(StepsSummary(steps = 1, running = false, tool = null, failed = 0), line.summary(live = true))
    }

    @Test
    fun emptyAssistantTextDoesNotBreakTheRunAndStaysOutsideItsEnds() {
        assertEquals(listOf("[t1,a,t2,t3]"), shape(listOf(tool("t1"), said("a", " "), tool("t2"), tool("t3"))))
        // A blank streaming answer after the last step keeps its cursor in view; one before the first step stays too.
        assertEquals(listOf("a0", "[t1]", "a1"), shape(listOf(said("a0", ""), tool("t1"), said("a1", ""))))
        assertEquals(listOf("a"), shape(listOf(said("a", ""))))
    }

    @Test
    fun noticesCardsAndDelegationsEndARun() {
        val delegate = tool("d").copy(subagents = listOf(DelegateRow(key = "c", goal = "child", status = DelegateStatus.Running)))
        val notice = ChatItem.Notice("n", NoticeKind.Stopped, "")
        val card = ChatItem.Card("k", process = true, title = "done", text = "")
        val items = listOf(tool("t1"), tool("t2"), delegate, tool("t3"), tool("t4"), notice, tool("t5"), card, tool("t6"))
        assertEquals(listOf("[t1,t2]", "d", "[t3,t4]", "n", "[t5]", "k", "[t6]"), shape(items))
    }

    @Test
    fun theLineKeepsItsKeyWhileTheRunGrows() {
        val one = ToolFolding.rows(listOf(reasoning("r1"))).single()
        val more = ToolFolding.rows(listOf(reasoning("r1"), tool("t1"), tool("t2"))).single()
        assertEquals(one.key, more.key)
        assertEquals("fold-r1", more.key)
        assertEquals("fold-t1", ToolFolding.rows(listOf(tool("t1"))).single().key)
    }

    @Test
    fun theLineCountsStepsAndNamesTheLastCall() {
        val line = fold(reasoning("r"), tool("t1"), said("blank", ""), tool("t2", "patch"), tool("t3", "execute_code"))
        assertEquals(listOf("r", "t1", "t2", "t3"), line.steps.map { it.key })
        assertEquals(StepsSummary(steps = 4, running = false, tool = "execute_code", failed = 0), line.summary(live = true))
    }

    @Test
    fun aRunningStepReadsAsRunningOnlyWhileLive() {
        val line = fold(tool("t1"), tool("t2", "vision_analyze", ToolStatus.Running), tool("t3", "read_file"))
        assertTrue(line.running)
        // Live: the running call is the one named.
        assertEquals(StepsSummary(steps = 3, running = true, tool = "vision_analyze", failed = 0), line.summary(live = true))
        // Offline or not reattached: no live state, the last call.
        assertEquals(StepsSummary(steps = 3, running = false, tool = "read_file", failed = 0), line.summary(live = false))
    }

    @Test
    fun streamingReasoningIsARunningStep() {
        val line = fold(tool("t1"), reasoning("r", streaming = true))
        assertTrue(line.running)
        assertEquals(StepsSummary(steps = 2, running = true, tool = "terminal", failed = 0), line.summary(live = true))
        assertFalse(fold(tool("t1"), reasoning("r")).running)
    }

    @Test
    fun failedCallsShowInTheLine() {
        val line = fold(tool("t1", status = ToolStatus.Failed), tool("t2"), tool("t3", "patch", ToolStatus.Failed))
        assertTrue(line.failed)
        assertEquals(2, line.failedCount)
        assertEquals(StepsSummary(steps = 3, running = false, tool = "patch", failed = 2), line.summary(live = true))
        assertFalse(fold(tool("t1")).failed)
    }

    @Test
    fun anEmptyTranscriptHasNoRows() {
        assertEquals(emptyList<TranscriptRow>(), ToolFolding.rows(emptyList()))
    }
}
