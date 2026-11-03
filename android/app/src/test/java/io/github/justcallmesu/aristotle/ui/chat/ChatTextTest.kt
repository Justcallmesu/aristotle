package io.github.justcallmesu.aristotle.ui.chat

import io.github.justcallmesu.aristotle.data.LinkState
import io.github.justcallmesu.aristotle.data.Network
import io.github.justcallmesu.aristotle.data.OfflineCause
import io.github.justcallmesu.aristotle.data.ServerEndpoint
import io.github.justcallmesu.aristotle.data.chat.ChatFeedState
import io.github.justcallmesu.aristotle.data.chat.ChatState
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatTextTest {
    private val mark = StreamCursor.MARK

    @Test
    fun theCursorFollowsTheLastCharacterWhileStreaming() {
        assertEquals(CursorText("one\ntwo$mark", CursorPlace.Inline), StreamCursor.place("one\ntwo\n", streaming = true))
        assertEquals(CursorText("**bold** text$mark", CursorPlace.Inline), StreamCursor.place("**bold** text", streaming = true))
    }

    @Test
    fun noCursorOnceTheTextIsComplete() {
        assertEquals(CursorText("done", CursorPlace.None), StreamCursor.place("done", streaming = false))
        assertEquals("agent text", StreamCursor.place("agent$mark text", streaming = false).content)
    }

    @Test
    fun insideACodeBlockOrTableTheCursorGoesBelow() {
        assertEquals(CursorPlace.Below, StreamCursor.place("Run:\n```sh\ngo test", streaming = true).place)
        assertEquals(CursorPlace.Inline, StreamCursor.place("```sh\ngo test\n```\nDone", streaming = true).place)
        assertEquals(CursorPlace.Below, StreamCursor.place("| a | b |\n| - | - |\n| 1", streaming = true).place)
        assertEquals(CursorPlace.Below, StreamCursor.place("Code:\n\n    indented", streaming = true).place)
        assertTrue(StreamCursor.openFence("~~~\nx"))
        assertFalse(StreamCursor.openFence("~~~\nx\n~~~"))
    }

    @Test
    fun durationsReadLikeTheMockup() {
        assertEquals("0.1", ChatFormat.seconds(0.1, Locale.US))
        assertEquals("6.8", ChatFormat.seconds(6.757521550000092, Locale.US))
        assertEquals("6,8", ChatFormat.seconds(6.757521550000092, Locale.forLanguageTag("id")))
        assertEquals("20", ChatFormat.seconds(20.487507931000437, Locale.US))
        assertEquals(1, ChatFormat.thoughtSeconds(10))
        assertEquals(4, ChatFormat.thoughtSeconds(3_200))
    }

    @Test
    fun toolIconsByName() {
        assertEquals(ToolIcon.Terminal, toolIcon("terminal"))
        assertEquals(ToolIcon.File, toolIcon("read_file"))
        assertEquals(ToolIcon.File, toolIcon("write_file"))
        assertEquals(ToolIcon.Generic, toolIcon("clarify"))
    }

    // design.md: no spinners or cursor while offline; T11 review MINOR-6/7: nor once the reattach was refused.
    @Test
    fun liveWorkShowsOnlyWhileOnlineAndAttached() {
        val online = LinkState.Online(ServerEndpoint("192.168.1.61", 8760, Network.Lan), 1)
        val offline = LinkState.Offline(0, OfflineCause.ConnectionLost, "", null, 1, null)
        val loaded = ChatFeedState(ChatState("s1"), loaded = true)
        assertTrue(ChatLive.of(loaded, online))
        assertFalse(ChatLive.of(loaded, offline))
        assertFalse(ChatLive.of(loaded, LinkState.Connecting))
        assertFalse(ChatLive.of(loaded.copy(problem = "session.resume failed: 4001"), online))
        assertFalse(ChatLive.of(ChatFeedState(ChatState("s1")), online))
        assertTrue(ChatLive.reattachFailed(loaded.copy(problem = "x")))
        assertFalse(ChatLive.reattachFailed(ChatFeedState(ChatState("s1"), problem = "x"))) // never loaded: the full error page
        assertFalse(ChatLive.reattachFailed(loaded))
    }

    // T11 review MINOR-4: a tap opens web pages only.
    @Test
    fun onlyHttpAndHttpsLinksOpen() {
        assertEquals("https://example.org/a?b=1#c", ChatLinks.webTarget("https://example.org/a?b=1#c"))
        assertEquals("http://192.168.1.61:8760", ChatLinks.webTarget(" http://192.168.1.61:8760 "))
        assertEquals("HTTPS://Example.org", ChatLinks.webTarget("HTTPS://Example.org"))
        for (other in listOf(
            "tel:+6281234", "sms:+6281234", "mailto:a@b.c", "market://details?id=x", "intent://scan/#Intent;scheme=zxing;end",
            "javascript:alert(1)", "file:///sdcard/x", "content://media/1", "aristotle://pair", "https://", "https:///path",
            "/relative", "example.org", "https://exa mple.org", "https://example.org\u0000x", "",
        )) {
            assertNull(other, ChatLinks.webTarget(other))
        }
    }
}
