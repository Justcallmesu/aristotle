package io.github.justcallmesu.aristotle.ui.chat

import io.github.justcallmesu.aristotle.data.chat.ChatItem
import io.github.justcallmesu.aristotle.data.chat.DelegateRow
import io.github.justcallmesu.aristotle.data.chat.DelegateStatus
import io.github.justcallmesu.aristotle.data.chat.NoticeKind
import io.github.justcallmesu.aristotle.data.chat.ToolStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolFoldingTest {
    private fun tool(key: String, name: String = "terminal", status: ToolStatus = ToolStatus.Done) =
        ChatItem.Tool(key = key, toolId = key, name = name, summary = "", status = status)

    private fun reasoning(key: String) = ChatItem.Reasoning(key, "thinking")
    private fun said(key: String, text: String = "Done.") = ChatItem.Assistant(key, text)
    private fun user(key: String) = ChatItem.User(key, "go")

    /** Each row as a short label: a key, or `[k1,k2,…]` for a folded run. */
    private fun shape(items: List<ChatItem>): List<String> = ToolFolding.rows(items).map { row ->
        when (row) {
            is TranscriptRow.Single -> row.item.key
            is TranscriptRow.Folded -> row.items.joinToString(",", "[", "]") { it.key }
        }
    }

    @Test
    fun threeCallsInARowFoldIntoOneCard() {
        assertEquals(listOf("u", "[t1,t2,t3]", "a"), shape(listOf(user("u"), tool("t1"), tool("t2"), tool("t3"), said("a"))))
    }

    @Test
    fun twoCallsStayAsTheyAre() {
        assertEquals(listOf("t1", "t2", "a", "t3"), shape(listOf(tool("t1"), tool("t2"), said("a"), tool("t3"))))
    }

    @Test
    fun assistantTextBetweenCallsBreaksTheRun() {
        assertEquals(listOf("t1", "t2", "a", "t3", "t4"), shape(listOf(tool("t1"), tool("t2"), said("a"), tool("t3"), tool("t4"))))
    }

    @Test
    fun reasoningBetweenCallsFoldsWithThemButNotAroundThem() {
        val items = listOf(reasoning("r0"), tool("t1"), reasoning("r1"), tool("t2"), tool("t3"), reasoning("r2"))
        assertEquals(listOf("r0", "[t1,r1,t2,t3]", "r2"), shape(items))
    }

    @Test
    fun emptyAssistantTextDoesNotBreakTheRun() {
        assertEquals(listOf("[t1,a,t2,t3]"), shape(listOf(tool("t1"), said("a", " "), tool("t2"), tool("t3"))))
    }

    @Test
    fun noticesCardsAndDelegationsEndARun() {
        val delegate = tool("d").copy(subagents = listOf(DelegateRow(key = "c", goal = "child", status = DelegateStatus.Running)))
        val notice = ChatItem.Notice("n", NoticeKind.Stopped, "")
        val items = listOf(tool("t1"), tool("t2"), delegate, tool("t3"), tool("t4"), notice, tool("t5"), tool("t6"), tool("t7"))
        assertEquals(listOf("t1", "t2", "d", "t3", "t4", "n", "[t5,t6,t7]"), shape(items))
    }

    @Test
    fun theFoldedCardKeepsItsKeyWhileTheRunGrows() {
        val three = ToolFolding.rows(listOf(tool("t1"), tool("t2"), tool("t3"))).single()
        val four = ToolFolding.rows(listOf(tool("t1"), tool("t2"), tool("t3"), tool("t4"))).single()
        assertEquals(three.key, four.key)
        assertEquals("fold-t1", four.key)
    }

    @Test
    fun theHeaderNamesTheLastCallAndCarriesRunningAndFailed() {
        val group = ToolFolding.rows(
            listOf(tool("t1", status = ToolStatus.Failed), tool("t2", "read_file"), tool("t3", "patch", ToolStatus.Running)),
        ).single() as TranscriptRow.Folded
        assertEquals(3, group.tools.size)
        assertEquals("patch", group.last.name)
        assertTrue(group.running)
        assertTrue(group.failed)
        val done = ToolFolding.rows(listOf(tool("t1"), tool("t2"), tool("t3"))).single() as TranscriptRow.Folded
        assertFalse(done.running)
        assertFalse(done.failed)
    }

    @Test
    fun anEmptyTranscriptHasNoRows() {
        assertEquals(emptyList<TranscriptRow>(), ToolFolding.rows(emptyList()))
    }
}
