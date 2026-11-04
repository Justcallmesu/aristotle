package io.github.justcallmesu.aristotle.ui.chat

import io.github.justcallmesu.aristotle.data.chat.ApprovalChoice
import io.github.justcallmesu.aristotle.data.chat.Prompt
import io.github.justcallmesu.aristotle.data.chat.SecretKind
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/** TB1: the TR review minors 1, 3, 4 and 5 as pure rules. */
class TrMinorsTest {
    // ── MINOR-1: the model chip without a model ───────────────────────────────────────────────────

    @Test
    fun withoutAModelTheChipSaysModelSoTheSheetStaysReachable() {
        assertEquals("Model", ComposerLogic.chipLabel(null, null, fallback = "Model"))
        assertEquals("Model", ComposerLogic.chipLabel("  ", null, fallback = "Model"))
        assertEquals("Model · High", ComposerLogic.chipLabel(null, "High", fallback = "Model"))
        assertEquals("gpt-6 · High", ComposerLogic.chipLabel("gpt-6", "High", fallback = "Model"))
        assertNull(ComposerLogic.chipLabel(null, null))
    }

    // ── MINOR-3: Send is off while the prompt sheet covers the composer ───────────────────────────

    private fun composer(mode: ComposerMode, blocked: Boolean = false, draft: String = "/goal x") =
        ComposerState(draft = draft, shown = ComposerShown(ComposerKey(mode, blocked, emptyList()), at = 0, armed = true))

    @Test
    fun aBusyCommandSendIsOffWhileCovered() {
        assertTrue(ComposerLogic.sendEnabled(composer(ComposerMode.Busy), ready = true, command = true, hasText = true, hasContent = true))
        assertTrue(ComposerLogic.sendEnabled(composer(ComposerMode.Idle, blocked = true), ready = true, command = true, hasText = true, hasContent = true))
        assertFalse(ComposerLogic.sendEnabled(composer(ComposerMode.Covered, blocked = true), ready = true, command = true, hasText = true, hasContent = true))
        assertFalse(ComposerLogic.sendEnabled(composer(ComposerMode.Offline), ready = true, command = true, hasText = true, hasContent = true))
        assertFalse(ComposerLogic.sendEnabled(composer(ComposerMode.Busy), ready = false, command = true, hasText = true, hasContent = true))
        assertFalse(ComposerLogic.sendEnabled(composer(ComposerMode.Busy, draft = ""), ready = true, command = true, hasText = false, hasContent = false))
    }

    @Test
    fun aMessageSendsOnlyIdleWithNothingWaiting() {
        assertTrue(ComposerLogic.sendEnabled(composer(ComposerMode.Idle, draft = "hi"), ready = true, command = false, hasText = true, hasContent = true))
        assertFalse(ComposerLogic.sendEnabled(composer(ComposerMode.Idle, blocked = true, draft = "hi"), ready = true, command = false, hasText = true, hasContent = true))
        assertFalse(ComposerLogic.sendEnabled(composer(ComposerMode.Busy, draft = "hi"), ready = true, command = false, hasText = true, hasContent = true))
        assertFalse(ComposerLogic.sendEnabled(composer(ComposerMode.Covered, draft = "hi"), ready = true, command = false, hasText = true, hasContent = true))
    }

    // ── MINOR-4: "approval" only for an approval ──────────────────────────────────────────────────

    @Test
    fun theWaitingWordsFollowThePromptKind() {
        assertNull(ChatSubtitle.waitingFor(null))
        assertEquals(WaitingFor.Approval, ChatSubtitle.waitingFor(Prompt.Approval("a", 1, "rm -rf x", "", listOf(ApprovalChoice.Once))))
        assertEquals(WaitingFor.Answer, ChatSubtitle.waitingFor(Prompt.Clarify("c", 1, emptyList())))
        assertEquals(WaitingFor.Answer, ChatSubtitle.waitingFor(Prompt.Secret("s", SecretKind.Sudo, "apt install", null)))
        assertEquals(WaitingFor.Answer, ChatSubtitle.waitingFor(Prompt.Secret("t", SecretKind.Secret, "API_KEY", "key")))
        assertEquals(WaitingFor.Answer, ChatSubtitle.waitingFor(Prompt.OnPc("v", "vault.unlock", null)))
    }

    // ── MINOR-5: the model count is a plural in both locales ──────────────────────────────────────

    private fun resources(locale: String): Element {
        val file = listOf(File("src/main/res/$locale/strings.xml"), File("app/src/main/res/$locale/strings.xml")).first { it.exists() }
        return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
    }

    private fun Element.named(tag: String, name: String): List<Element> {
        val nodes = getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }.filter { it.getAttribute("name") == name }
    }

    private fun Element.quantities(): Map<String, String> {
        val items = getElementsByTagName("item")
        return (0 until items.length).map { items.item(it) as Element }.associate { it.getAttribute("quantity") to it.textContent }
    }

    @Test
    fun modelCountIsAPlural() {
        val en = resources("values")
        assertTrue(en.named("string", "model_count").isEmpty())
        assertEquals(mapOf("one" to "%1\$d model", "other" to "%1\$d models"), en.named("plurals", "model_count").single().quantities())
        val id = resources("values-in")
        assertTrue(id.named("string", "model_count").isEmpty())
        assertEquals(mapOf("other" to "%1\$d model"), id.named("plurals", "model_count").single().quantities())
    }
}
