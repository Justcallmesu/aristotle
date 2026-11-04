package io.github.justcallmesu.aristotle.ui.chat

import io.github.justcallmesu.aristotle.data.chat.Attachment
import io.github.justcallmesu.aristotle.data.chat.PickKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** TR4: the floating composer's trailing action (Q69) and the model chip's label. */
class ComposerTr4Test {
    private val empty = ComposerState()
    private val typed = ComposerState(draft = "hi")
    private val picked = ComposerState(attachments = listOf(Attachment("a", "a.png", PickKind.Photo)))

    @Test
    fun idleOrOfflineIsSend() {
        assertEquals(ComposerTrailing.Send, ComposerLogic.trailing(online = true, running = false, command = false, composer = typed))
        assertEquals(ComposerTrailing.Send, ComposerLogic.trailing(online = false, running = true, command = false, composer = typed))
    }

    @Test
    fun busyAndEmptyIsStopAlone() {
        assertEquals(ComposerTrailing.Stop, ComposerLogic.trailing(true, true, false, empty))
        assertEquals(ComposerTrailing.Stop, ComposerLogic.trailing(true, true, false, ComposerState(draft = "  ")))
    }

    @Test
    fun busyWithWordsOrAttachmentAddsQueueAndSteer() {
        assertEquals(ComposerTrailing.StopQueueSteer, ComposerLogic.trailing(true, true, false, typed))
        assertEquals(ComposerTrailing.StopQueueSteer, ComposerLogic.trailing(true, true, false, picked))
    }

    @Test
    fun busyCommandRunsWithSend() {
        assertEquals(ComposerTrailing.StopSend, ComposerLogic.trailing(true, true, true, ComposerState(draft = "/goal x")))
    }

    @Test
    fun chipHidesWhenTheRowIsFull() {
        assertTrue(ComposerLogic.showsModelChip(ComposerTrailing.Send))
        assertTrue(ComposerLogic.showsModelChip(ComposerTrailing.Stop))
        assertFalse(ComposerLogic.showsModelChip(ComposerTrailing.StopQueueSteer))
        assertFalse(ComposerLogic.showsModelChip(ComposerTrailing.StopSend))
    }

    @Test
    fun chipLabelJoinsModelAndEffort() {
        assertEquals("claude-opus-5-5 · Medium", ComposerLogic.chipLabel("claude-opus-5-5", "Medium"))
        assertEquals("gpt-6", ComposerLogic.chipLabel("gpt-6", null))
        assertEquals("gpt-6", ComposerLogic.chipLabel(" gpt-6 ", " "))
        assertNull(ComposerLogic.chipLabel(null, "Medium"))
        assertNull(ComposerLogic.chipLabel("  ", "Low"))
    }
}
