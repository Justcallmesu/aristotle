package io.github.justcallmesu.aristotle.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/** TR3: the chat top bar's subtitle (design-notes §5.2, §5.6) and the 16 dp profile mark's letter. */
class ChatSubtitleTest {
    @Test
    fun idleShowsOnlyTheProfile() {
        assertEquals(SubtitleLead.None, ChatSubtitle.lead(linkDown = false, live = true, waiting = false, running = false))
    }

    @Test
    fun aRunningTurnLeadsOnlyWhileLive() {
        assertEquals(SubtitleLead.Running, ChatSubtitle.lead(linkDown = false, live = true, waiting = false, running = true))
        // Not reattached: no verdigris (design.md §1, live work only).
        assertEquals(SubtitleLead.None, ChatSubtitle.lead(linkDown = false, live = false, waiting = false, running = true))
    }

    @Test
    fun aWaitingPromptComesBeforeTheRunningTurn() {
        assertEquals(SubtitleLead.Waiting, ChatSubtitle.lead(linkDown = false, live = true, waiting = true, running = true))
        assertEquals(SubtitleLead.None, ChatSubtitle.lead(linkDown = false, live = false, waiting = true, running = false))
    }

    @Test
    fun aDownLinkNamesItselfFirst() {
        assertEquals(SubtitleLead.Link, ChatSubtitle.lead(linkDown = true, live = false, waiting = true, running = true))
        assertEquals(SubtitleLead.Link, ChatSubtitle.lead(linkDown = true, live = false, waiting = false, running = false))
    }

    @Test
    fun theMarkIsTheFirstLetterInUpperCase() {
        assertEquals("P", ChatSubtitle.initial("pm"))
        assertEquals("B", ChatSubtitle.initial("  bkm-reports-bot"))
        assertEquals("?", ChatSubtitle.initial(" "))
        assertEquals("?", ChatSubtitle.initial(""))
    }
}
