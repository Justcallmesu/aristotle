package io.github.justcallmesu.aristotle.ui.chat

import io.github.justcallmesu.aristotle.data.chat.ApprovalChoice
import io.github.justcallmesu.aristotle.data.chat.ChatState
import io.github.justcallmesu.aristotle.data.chat.Prompt
import io.github.justcallmesu.aristotle.data.chat.SecretKind
import io.github.justcallmesu.aristotle.data.chat.SettledPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the prompt sheet rises, moves on, hides and says "Answered elsewhere". */
class PromptSheetLogicTest {
    private val sudo = Prompt.Secret("srq-1", SecretKind.Sudo, "sudo ls", null)
    private val other = Prompt.OnPc("srq-2", "vault.code", null)

    private fun chat(vararg prompts: Prompt, settled: SettledPrompt? = null) = ChatState("s", prompts = prompts.toList(), lastSettled = settled)

    @Test
    fun aNewPromptRaisesItAndHidingKeepsItDownUntilTheNextOne() {
        var sheet = PromptSheetLogic.onChat(PromptSheetState(), chat(sudo))
        assertTrue(sheet.visible(chat(sudo)))
        sheet = PromptSheetLogic.onChat(sheet.copy(open = false), chat(sudo))
        assertFalse(sheet.visible(chat(sudo)))
        sheet = PromptSheetLogic.onChat(sheet, chat(sudo, other))
        assertTrue(sheet.open)
        assertEquals(sudo, sheet.current(chat(sudo, other)))
    }

    @Test
    fun nothingHappensBeforeTheFirstSnapshot() {
        val raised = PromptSheetState(open = true)
        assertEquals(raised, PromptSheetLogic.onChat(raised, chat(), loaded = false))
    }

    @Test
    fun aSettleOfTheShownPromptFromElsewhereIsAWithdrawal() {
        val shown = PromptSheetLogic.onChat(PromptSheetState(), chat(sudo))
        val after = PromptSheetLogic.onChat(shown, chat(settled = SettledPrompt(sudo, "resolved", 1)))
        assertEquals(sudo, after.withdrawn)
        assertTrue(after.visible(chat()))
        assertFalse(after.open)
    }

    @Test
    fun aSettleOfOurOwnAnswerIsNot() {
        val shown = PromptSheetLogic.onChat(PromptSheetState(), chat(sudo)).let { it.copy(answered = it.answered + sudo.key) }
        val after = PromptSheetLogic.onChat(shown, chat(settled = SettledPrompt(sudo, "resolved", 1)))
        assertNull(after.withdrawn)
        assertFalse(after.visible(chat()))
        assertTrue(after.answered.isEmpty())
    }

    @Test
    fun aSettleWhileTheSheetIsDownIsQuiet() {
        val down = PromptSheetLogic.onChat(PromptSheetState(), chat(sudo)).copy(open = false)
        assertNull(PromptSheetLogic.onChat(down, chat(settled = SettledPrompt(sudo, "timeout", 1))).withdrawn)
    }

    @Test
    fun theSameSettleIsHandledOnce() {
        val shown = PromptSheetLogic.onChat(PromptSheetState(), chat(sudo))
        val settled = chat(settled = SettledPrompt(sudo, null, 7))
        val once = PromptSheetLogic.onChat(shown, settled).copy(withdrawn = null)
        assertNull(PromptSheetLogic.onChat(once, settled).withdrawn)
    }

    // ── tap-through guard (T12b MAJOR-1) ──────────────────────────────────────────────────────────

    private val first = Prompt.Approval("p1", 4, "ls", "", listOf(ApprovalChoice.Once, ApprovalChoice.Deny))
    private val second = Prompt.Approval("p2", 4, "rm -rf ~", "", listOf(ApprovalChoice.Once, ApprovalChoice.Deny))

    @Test
    fun aPromptIsAnswerableOnlyAfterItWasOnScreenFor600Ms() {
        val chat = chat(first, second)
        var sheet = PromptSheetLogic.track(PromptSheetLogic.onChat(PromptSheetState(), chat), chat, now = 100)
        assertEquals(Shown("p1", confirming = false, at = 100), sheet.shown)
        assertFalse(PromptSheetLogic.armed(sheet, "p1", confirming = false, now = 699))
        assertTrue(PromptSheetLogic.armed(sheet, "p1", confirming = false, now = 700))
        assertFalse(PromptSheetLogic.armed(sheet, "p2", confirming = false, now = 10_000)) // not on screen
        assertFalse(PromptSheetLogic.armed(sheet, "p1", confirming = true, now = 10_000)) // no confirmation shown
        // Answered: the sheet swaps to p2 in place, which starts its own window.
        sheet = PromptSheetLogic.track(sheet.copy(answered = setOf("p1")), chat, now = 800)
        assertEquals(Shown("p2", confirming = false, at = 800), sheet.shown)
        assertFalse(PromptSheetLogic.armed(sheet, "p2", confirming = false, now = 800))
        // A later update of the same prompt keeps its time.
        assertEquals(sheet.shown, PromptSheetLogic.track(sheet, chat, now = 5_000).shown)
    }

    @Test
    fun hiddenOrWithdrawnSheetsShowNothingToAnswer() {
        val chat = chat(first)
        val up = PromptSheetLogic.track(PromptSheetLogic.onChat(PromptSheetState(), chat), chat, now = 0)
        assertNull(PromptSheetLogic.track(up.copy(open = false), chat, now = 1).shown)
        assertNull(PromptSheetLogic.track(up.copy(withdrawn = second), chat, now = 1).shown)
        assertEquals(Shown("p1", confirming = true, at = 2), PromptSheetLogic.track(up.copy(confirmAlways = "p1"), chat, now = 2).shown)
    }

    @Test
    fun aReconnectForgetsAnswersThatGotNoReply() {
        val chat = chat(sudo, other)
        val sent = PromptSheetState(answered = setOf("srq-1", "srq-2"), unconfirmed = mapOf("srq-1" to 1L))
        assertEquals(setOf("srq-2"), PromptSheetLogic.onReconnect(sent, generation = 2).answered)
        assertEquals(emptyMap<String, Long>(), PromptSheetLogic.onReconnect(sent, generation = 2).unconfirmed)
        assertEquals(sent, PromptSheetLogic.onReconnect(sent, generation = 1)) // sent on this very socket
        assertEquals(sudo, PromptSheetLogic.onReconnect(sent, generation = 2).current(chat))
    }
}
