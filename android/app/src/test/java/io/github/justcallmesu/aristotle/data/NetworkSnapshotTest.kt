package io.github.justcallmesu.aristotle.data

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which network events count as a change for the link (T09 review 6). */
class NetworkSnapshotTest {
    private val wifiUnderVpn = Transports(wifi = true, vpn = true, notMetered = true)
    private fun ips(vararg text: String) = text.map(InetAddress::getByName)

    private val atHome = NetworkSnapshot().withDefault(handle = 900, wifiUnderVpn).withLocalLink(handle = 1, ips("192.168.1.5", "fe80::1"))

    @Test
    fun aWifiToWifiMoveUnderAVpnIsAChange() {
        val moved = atHome.withoutLocalLink(1).withLocalLink(2, ips("10.0.0.7"))
        assertEquals(atHome.handle, moved.handle) // the VPN stays the default network
        assertEquals(atHome.prefersLan, moved.prefersLan)
        assertNotEquals(atHome, moved)
    }

    @Test
    fun aRepeatedCapabilityOrAddressUpdateIsNoChange() {
        assertEquals(atHome, atHome.withDefault(900, wifiUnderVpn))
        assertEquals(atHome, atHome.withLocalLink(1, ips("fe80::1", "192.168.1.5")))
    }

    @Test
    fun onlyIpv4AddressesOfTheLocalLinkCount() {
        assertEquals(atHome, atHome.withLocalLink(1, ips("192.168.1.5", "2001:db8::77")))
        assertNotEquals(atHome, atHome.withLocalLink(1, ips("192.168.1.6")))
    }

    @Test
    fun theDefaultNetworkSaysWhetherAVpnIsUpAndWhetherLanGoesFirst() {
        assertTrue(atHome.vpn)
        assertTrue(atHome.prefersLan)
        val cellular = atHome.withDefault(901, Transports(cellular = true))
        assertFalse(cellular.vpn)
        assertFalse(cellular.prefersLan)
        assertFalse(atHome.withoutDefault().vpn)
        assertEquals(null, atHome.withoutDefault().handle)
    }
}
