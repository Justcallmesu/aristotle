package io.github.justcallmesu.aristotle.ui.chat

import io.github.justcallmesu.aristotle.data.chat.Attachment
import io.github.justcallmesu.aristotle.data.chat.PickKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** TM2 (Q80a): quote formatting, draft insertion, code extraction, which actions show when. */
class MessageActionsTest {
    private fun composer(mode: ComposerMode, blocked: Boolean = false, draft: String = "", attachments: List<Attachment> = emptyList()) =
        ComposerState(draft = draft, shown = ComposerShown(ComposerKey(mode, blocked, emptyList()), at = 0), attachments = attachments)

    @Test
    fun quotePrefixesEveryLineAndKeepsBlankOnes() {
        assertEquals("> one", MessageActions.quote("one"))
        assertEquals("> a\n>\n> b", MessageActions.quote("a\n\nb"))
        assertEquals("> a\n> b", MessageActions.quote("\na\r\nb\n\n"))
    }

    @Test
    fun quoteIntoEmptyDraftLeavesRoomToReply() {
        val r = MessageActions.insert("", 0, "> hi", reply = true)
        assertEquals("> hi\n\n", r.text)
        assertEquals(r.text.length, r.cursor)
    }

    @Test
    fun quoteKeepsExistingDraftAndSeparatesIt() {
        val atEnd = MessageActions.insert("my words", 8, "> q", reply = true)
        assertEquals("my words\n\n> q\n\n", atEnd.text)
        assertEquals(atEnd.text.length, atEnd.cursor)
        val after = MessageActions.insert("tail\n", 5, "> q", reply = true)
        assertEquals("tail\n\n> q\n\n", after.text)
    }

    @Test
    fun quoteAtCursorInTheMiddleKeepsBothSides() {
        val r = MessageActions.insert("abcdef", 3, "> q", reply = true)
        assertEquals("abc\n\n> q\n\ndef", r.text)
        assertEquals("abc\n\n> q\n\n".length, r.cursor)
        // Newlines already after the cursor are counted, not doubled.
        assertEquals("ab\n\n> q\n\ncd", MessageActions.insert("ab\n\ncd", 2, "> q", reply = true).text.replace("\n\n\n", "\n\n"))
    }

    @Test
    fun cursorIsClamped() {
        assertEquals("x", MessageActions.insert("", 99, "x", reply = false).text)
        assertEquals("x\n\nab", MessageActions.insert("ab", -5, "x", reply = false).text)
    }

    @Test
    fun steerTextGoesInPlainAndWithoutTrailingBlankLine() {
        val r = MessageActions.insert("", 0, "use rg", reply = false)
        assertEquals("use rg", r.text)
        assertEquals(6, r.cursor)
    }

    @Test
    fun fencedCodeIsExtractedWithoutFencesOrLanguage() {
        assertEquals("fun a() {}\nb()", CodeBlocks.extract("```kotlin\nfun a() {}\nb()\n```"))
        assertEquals("x\n\ny", CodeBlocks.extract("~~~\nx\n\ny\n~~~"))
        assertEquals("a", CodeBlocks.extract("````\na\n````"))
    }

    @Test
    fun openFenceWhileStreamingKeepsWhatIsThere() {
        assertEquals("a\nb", CodeBlocks.extract("```sh\na\nb"))
        assertEquals("", CodeBlocks.extract("```"))
    }

    @Test
    fun fenceInsideCodeStaysAndShorterFenceDoesNotClose() {
        assertEquals("```\ninner\n```", CodeBlocks.extract("````md\n```\ninner\n```\n````"))
    }

    @Test
    fun indentedCodeLosesOneIndent() {
        assertEquals("a\n  b", CodeBlocks.extract("    a\n      b"))
        assertEquals("a", CodeBlocks.extract("\ta"))
    }

    @Test
    fun indentedFenceContentLosesTheFencesIndent() {
        assertEquals("a\n b", CodeBlocks.extract("  ```\n  a\n   b\n  ```"))
    }

    @Test
    fun everyRoleOffersTheSameActionsAndSteerOnlyWhenAllowed() {
        for (role in MessageRole.values()) {
            assertEquals(
                listOf(MessageAction.Copy, MessageAction.Share, MessageAction.SelectText, MessageAction.Quote),
                MessageActions.available(role, "hi", steerAllowed = false),
            )
            assertEquals(MessageAction.Steer, MessageActions.available(role, "hi", steerAllowed = true).last())
        }
    }

    @Test
    fun aMessageWithoutWordsOffersNothing() {
        assertTrue(MessageActions.available(MessageRole.User, " \n", steerAllowed = true).isEmpty())
        assertTrue(MessageActions.available(MessageRole.Assistant, "${StreamCursor.MARK}", steerAllowed = true).isEmpty())
    }

    @Test
    fun steerFollowsTheComposerRules() {
        assertTrue(MessageActions.steerAllowed(composer(ComposerMode.Busy)))
        assertTrue(MessageActions.steerAllowed(composer(ComposerMode.Busy, draft = "words")))
        assertFalse(MessageActions.steerAllowed(composer(ComposerMode.Idle)))
        assertFalse(MessageActions.steerAllowed(composer(ComposerMode.Offline)))
        assertFalse(MessageActions.steerAllowed(composer(ComposerMode.Covered)))
        assertFalse(MessageActions.steerAllowed(composer(ComposerMode.Busy, blocked = true)))
        assertFalse(MessageActions.steerAllowed(composer(ComposerMode.Busy, draft = "/model")))
        assertFalse(MessageActions.steerAllowed(composer(ComposerMode.Busy, attachments = listOf(Attachment("a", "a.png", PickKind.Photo)))))
        assertFalse(MessageActions.steerAllowed(ComposerState()))
    }
}
