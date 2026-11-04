package io.github.justcallmesu.aristotle.ui.chat

import io.github.justcallmesu.aristotle.data.chat.Attachment
import io.github.justcallmesu.aristotle.data.chat.PickKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** TU5: busy composer extras (Q69), slash description, model-sheet scroll target. */
class ComposerTu5Test {
    @Test
    fun busyExtrasNeedWordsOrAnAttachment() {
        assertFalse(ComposerLogic.showsBusyExtras(ComposerState()))
        assertFalse(ComposerLogic.showsBusyExtras(ComposerState(draft = "  \n")))
        assertTrue(ComposerLogic.showsBusyExtras(ComposerState(draft = "hi")))
        assertTrue(ComposerLogic.showsBusyExtras(ComposerState(attachments = listOf(Attachment("a", "a.png", PickKind.Photo)))))
    }

    @Test
    fun slashDescriptionDropsTheUsageTail() {
        assertEquals("Set a goal", SlashDescription.display("Set a goal (usage: /goal <text>)"))
        assertEquals("Set a goal", SlashDescription.display("Set a goal [usage: /goal]"))
        assertEquals("Plain", SlashDescription.display("Plain"))
        assertEquals("Use (usage) carefully", SlashDescription.display("Use (usage) carefully"))
        assertEquals("", SlashDescription.display(""))
    }

    @Test
    fun modelRowIndexCountsHeadsAndChips() {
        val groups = listOf(
            ModelSheetRows.Group(listOf("a", "b"), null),
            ModelSheetRows.Group(listOf("c", "d", "e"), "d"),
        )
        // head, chips, group head, a, b, group head, c, d
        assertEquals(7, ModelSheetRows.currentRow(hasReasoning = true, groups = groups))
        assertEquals(6, ModelSheetRows.currentRow(hasReasoning = false, groups = groups))
        assertNull(ModelSheetRows.currentRow(true, listOf(ModelSheetRows.Group(listOf("a"), "zz"))))
        assertNull(ModelSheetRows.currentRow(true, emptyList()))
    }
}
