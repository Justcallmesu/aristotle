package io.github.justcallmesu.aristotle.data.terminal

import io.github.justcallmesu.aristotle.data.AuthPurpose
import io.github.justcallmesu.aristotle.data.FreshAuth
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** TS2: the Terminal entry's visibility (Q62) and its fresh check (Q61). */
class TerminalEntryTest {
    @Test
    fun thePhoneSwitchHidesTheEntryWhateverThePcSays() {
        assertTrue(TerminalEntry.shown(serverAllows = true, showOnPhone = true))
        assertFalse(TerminalEntry.shown(serverAllows = true, showOnPhone = false))
        assertFalse(TerminalEntry.shown(serverAllows = false, showOnPhone = true))
        assertFalse(TerminalEntry.shown(serverAllows = false, showOnPhone = false))
    }

    @Test
    fun aFailedOrCancelledCheckOpensNothing() = runBlocking {
        val asked = mutableListOf<AuthPurpose>()
        var opened = 0
        val no = FreshAuth { asked += it; false }
        assertFalse(TerminalEntry.open(no, shown = { true }) { opened++ })
        assertEquals(0, opened)
        assertEquals(listOf(AuthPurpose.Terminal), asked)

        assertTrue(TerminalEntry.open(FreshAuth { true }, shown = { true }) { opened++ })
        assertEquals(1, opened)
    }

    @Test
    fun aHiddenEntryAsksNothingAndOpensNothing() = runBlocking {
        var asked = 0
        var opened = 0
        assertFalse(TerminalEntry.open(FreshAuth { asked++; true }, shown = { false }) { opened++ })
        assertEquals(0, asked)
        assertEquals(0, opened)
    }

    @Test
    fun anEntryThatWentAwayDuringTheCheckOpensNothing() = runBlocking {
        var shown = true
        var opened = 0
        val auth = FreshAuth {
            shown = false // the PC switched the terminal off meanwhile
            true
        }
        assertFalse(TerminalEntry.open(auth, shown = { shown }) { opened++ })
        assertEquals(0, opened)
    }
}
