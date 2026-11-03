package io.github.justcallmesu.aristotle.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T09 endpoint rule: the kind that fits the network first, the other kind after, last-good first within a kind. */
class EndpointChoiceTest {
    private val wifi = ServerEndpoint("192.168.1.61", 8760, Network.Lan)
    private val eth = ServerEndpoint("10.0.0.2", 8760, Network.Lan)
    private val ts4 = ServerEndpoint("100.74.61.96", 8760, Network.Tailscale)
    private val ts6 = ServerEndpoint("fd7a:115c:a1e0:0:0:0:d628:3d61", 8760, Network.Tailscale)
    private val stored = listOf(wifi, eth, ts4, ts6)

    @Test
    fun orderTable() {
        data class Row(val preferLan: Boolean, val lastGood: ServerEndpoint?, val want: List<ServerEndpoint>)
        val table = listOf(
            Row(preferLan = true, lastGood = null, want = listOf(wifi, eth, ts4, ts6)),
            Row(preferLan = false, lastGood = null, want = listOf(ts4, ts6, wifi, eth)),
            Row(preferLan = true, lastGood = eth, want = listOf(eth, wifi, ts4, ts6)),
            Row(preferLan = false, lastGood = eth, want = listOf(ts4, ts6, eth, wifi)),
            Row(preferLan = true, lastGood = ts6, want = listOf(wifi, eth, ts6, ts4)),
            Row(preferLan = false, lastGood = ts6, want = listOf(ts6, ts4, wifi, eth)),
        )
        for (row in table) {
            val got = EndpointChoice.order(stored, row.lastGood, row.preferLan)
            assertEquals("preferLan=${row.preferLan} lastGood=${row.lastGood}", row.want, got)
        }
    }

    @Test
    fun theOtherKindIsAlwaysTheFallbackAndDuplicatesGo() {
        assertEquals(listOf(ts4), EndpointChoice.order(listOf(ts4, ts4), null, preferLan = true))
        assertEquals(listOf(wifi), EndpointChoice.order(listOf(wifi), null, preferLan = false))
        assertEquals(emptyList<ServerEndpoint>(), EndpointChoice.order(emptyList(), wifi, preferLan = true))
    }

    @Test
    fun lanFirstOnWifiEthernetOrAnotherUnmeteredNetwork() {
        assertTrue(EndpointChoice.prefersLan(wifi = true, ethernet = false, cellular = false, notMetered = false))
        assertTrue(EndpointChoice.prefersLan(wifi = false, ethernet = true, cellular = false, notMetered = false))
        assertTrue(EndpointChoice.prefersLan(wifi = false, ethernet = false, cellular = false, notMetered = true))
        assertFalse(EndpointChoice.prefersLan(wifi = false, ethernet = false, cellular = true, notMetered = true))
        assertFalse(EndpointChoice.prefersLan(wifi = false, ethernet = false, cellular = true, notMetered = false))
        assertFalse(EndpointChoice.prefersLan(wifi = false, ethernet = false, cellular = false, notMetered = false))
    }
}
