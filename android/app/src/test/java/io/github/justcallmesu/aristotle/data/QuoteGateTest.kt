package io.github.justcallmesu.aristotle.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuoteGateTest {
    private val reading = GateInput(reading = true, paired = false, linkPending = true)
    private val connecting = GateInput(reading = false, paired = true, linkPending = true)
    private val online = GateInput(reading = false, paired = true, linkPending = false)
    private val unpaired = GateInput(reading = false, paired = false, linkPending = false)

    @Test
    fun showsAtStartWhileThePairingIsRead() {
        assertTrue(QuoteGate().update(reading, 0).visible)
    }

    @Test
    fun holdsUntilTheMinimumThenGivesWayWhenOnline() {
        val gate = QuoteGate()
        gate.update(reading, 0)
        gate.update(connecting, 100)
        val early = gate.update(online, 300)
        assertTrue(early.visible)
        assertEquals(900, early.recheckAfterMillis)
        assertTrue(gate.update(online, 1199).visible)
        assertFalse(gate.update(online, 1200).visible)
    }

    @Test
    fun staysWhileTheLinkIsStillConnectingPastTheMinimum() {
        val gate = QuoteGate()
        gate.update(reading, 0)
        assertTrue(gate.update(connecting, 60_000).visible)
        assertFalse(gate.update(online, 61_000).visible)
    }

    @Test
    fun reconnectingBeforeAnyContentShowsTheQuote() {
        val gate = QuoteGate()
        gate.update(online, 0) // a pairing already known and online at once: never shown, no content yet marked
        assertFalse(gate.update(online, 10).visible)
        // content was shown now; a later reconnect must not cover it
        assertFalse(gate.update(connecting, 5_000).visible)
    }

    @Test
    fun reconnectingAfterContentWasShownKeepsTheBanner() {
        val gate = QuoteGate()
        gate.update(reading, 0)
        assertFalse(gate.update(online, 2_000).visible)
        assertFalse(gate.update(connecting, 3_000).visible)
        assertFalse(gate.update(online, 4_000).visible)
    }

    @Test
    fun aFirstConnectNotYetOnlineShowsTheQuoteEvenAfterTheStartReadFinished() {
        val gate = QuoteGate()
        assertTrue(gate.update(connecting, 0).visible)
        assertFalse(gate.update(online, 2_000).visible)
    }

    @Test
    fun notPairedGivesWayAtOnceIgnoringTheMinimum() {
        val gate = QuoteGate()
        assertTrue(gate.update(reading, 0).visible)
        assertFalse(gate.update(unpaired, 50).visible)
    }

    @Test
    fun anOfflineLinkIsNotPendingSoTheListTakesOver() {
        val gate = QuoteGate()
        gate.update(connecting, 0)
        // Offline maps to linkPending = false (ui): definitive, shown with the banner after the minimum
        assertFalse(gate.update(online, 1_300).visible)
    }
}
