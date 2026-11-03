package io.github.justcallmesu.aristotle.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** How the link's view of the PC's addresses is merged into the stored list (T09b). */
class PcAddressesTest {
    private val home = ServerEndpoint("192.168.1.61", 8760, Network.Lan)
    private val office = ServerEndpoint("10.20.0.15", 8760, Network.Lan)
    private val tailnet = ServerEndpoint("100.74.61.96", 8760, Network.Tailscale)

    @Test
    fun thePcsCurrentListGoesFirstAndNothingKnownIsDropped() {
        assertEquals(listOf(office, tailnet, home), PcAddresses.merge(fresh = listOf(office, tailnet), known = listOf(home, tailnet), keep = emptyList()))
        assertEquals(listOf(home, tailnet), PcAddresses.merge(fresh = emptyList(), known = listOf(home, tailnet), keep = emptyList()))
    }

    @Test
    fun oneEntryPerUrlAndTheFreshOneWins() {
        val samePort = ServerEndpoint("192.168.1.61", 8761, Network.Lan)
        assertEquals(listOf(home, samePort), PcAddresses.merge(listOf(home), listOf(samePort, home), keep = emptyList()))
    }

    @Test
    fun pastTheCapTheAddressUnseenLongestGoesFirstButKeptOnesStay() {
        val known = (1..5).map { ServerEndpoint("10.0.0.$it", 8760, Network.Lan) } + home
        val merged = PcAddresses.merge(listOf(office, tailnet), known, keep = listOf(home, null), cap = 4)
        assertEquals(listOf(office, tailnet, ServerEndpoint("10.0.0.1", 8760, Network.Lan), home), merged)
    }
}
