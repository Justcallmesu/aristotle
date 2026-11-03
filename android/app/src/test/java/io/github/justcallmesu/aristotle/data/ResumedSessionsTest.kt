package io.github.justcallmesu.aristotle.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumedSessionsTest {
    @Test
    fun aSessionIsClaimedOncePerSocket() {
        val resumed = ResumedSessions()
        assertTrue(resumed.claim(1, "a"))
        assertFalse(resumed.claim(1, "a"))
        assertTrue(resumed.release(1, "a"))
        assertTrue(resumed.claim(1, "a"))
    }

    @Test
    fun aNewSocketStartsEmptyAndAnOlderOneCanNoLongerClaim() {
        val resumed = ResumedSessions()
        resumed.claim(1, "a")
        assertTrue(resumed.claim(2, "a"))
        assertEquals(setOf("a"), resumed.snapshot(2))
        assertFalse(resumed.claim(1, "b"))
        assertFalse(resumed.release(1, "a"))
        assertEquals(setOf("a"), resumed.snapshot(2))
    }

    @Test
    fun overlappingChatScreensAreCounted() {
        val resumed = ResumedSessions()
        resumed.openChat("a")
        resumed.openChat("a")
        resumed.closeChat("a")
        assertTrue(resumed.chatOpen("a"))
        resumed.closeChat("a")
        assertFalse(resumed.chatOpen("a"))
        resumed.closeChat("a")
        assertFalse(resumed.chatOpen("a"))
    }

    @Test
    fun theSameSessionIdInTwoProfilesIsTwoSubscriptions() {
        val resumed = ResumedSessions()
        assertTrue(resumed.claim(1, "a"))
        assertTrue(resumed.claim(1, "a", profile = "pm"))
        assertFalse(resumed.claim(1, "a", profile = "pm"))
        assertEquals(setOf("a"), resumed.snapshot(1)) // the watcher's launch-profile view
        assertTrue(resumed.release(1, "a", profile = "pm"))
        assertFalse(resumed.release(1, "a", profile = "pm"))
        assertEquals(setOf("a"), resumed.snapshot(1))
    }

    @Test
    fun aRequestBelongsToAProfileOnlyWhenNoOtherProfileHasThatSessionIdOnTheSocket() {
        val resumed = ResumedSessions()
        assertFalse(resumed.requestFor(1, "a")) // not attached here: Hermes would not send it to this socket for us
        resumed.claim(1, "a")
        assertTrue(resumed.requestFor(1, "a"))
        assertFalse(resumed.requestFor(1, "a", profile = "pm"))
        resumed.claim(1, "a", profile = "pm")
        assertFalse(resumed.requestFor(1, "a"))
        assertFalse(resumed.requestFor(1, "a", profile = "pm"))
        resumed.release(1, "a")
        assertTrue(resumed.requestFor(1, "a", profile = "pm"))
        assertFalse(resumed.requestFor(2, "a", profile = "pm")) // a newer socket starts empty
    }
}
