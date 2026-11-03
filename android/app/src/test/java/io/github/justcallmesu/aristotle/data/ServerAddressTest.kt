package io.github.justcallmesu.aristotle.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerAddressTest {
    private fun ok(input: String): ServerEndpoint = (ServerAddress.parse(input) as AddressCheck.Ok).endpoint
    private fun refused(input: String): AddressError = (ServerAddress.parse(input) as AddressCheck.Refused).error

    @Test
    fun lanAddressUsesHttpsAndDefaultPort() {
        val e = ok("192.168.1.23")
        assertEquals(ServerEndpoint("192.168.1.23", 8760, Network.Lan), e)
        assertEquals("https://192.168.1.23:8760", e.baseUrl)
    }

    @Test
    fun explicitPortIsKept() {
        assertEquals(9000, ok("10.0.0.5:9000").port)
        assertEquals(Network.Lan, ok("172.16.0.1").network)
        assertEquals(Network.Lan, ok("172.31.255.254:1").network)
    }

    @Test
    fun tailscaleAddressUsesPlainHttp() {
        val e = ok("100.101.102.103:8760")
        assertEquals(Network.Tailscale, e.network)
        assertEquals("http://100.101.102.103:8760", e.baseUrl)
        assertEquals(Network.Tailscale, ok("100.64.0.1").network)
        assertEquals(Network.Tailscale, ok("100.127.255.255").network)
    }

    @Test
    fun tailscaleIpv6NeedsBracketsForAPort() {
        val e = ok("[fd7a:115c:a1e0::1]:8760")
        assertEquals(Network.Tailscale, e.network)
        assertEquals("http://[fd7a:115c:a1e0:0:0:0:0:1]:8760", e.baseUrl)
        assertEquals(8760, ok("fd7a:115c:a1e0:ab12::3").port)
    }

    @Test
    fun pastedUrlSchemeIsIgnoredTheRangeDecides() {
        assertEquals(Network.Lan, ok("http://192.168.1.23:8760/").network)
        assertEquals(Network.Tailscale, ok("https://100.100.1.1:8760").network)
    }

    @Test
    fun publicAndOtherRangesAreRefused() {
        assertEquals(AddressError.NotPrivate, refused("8.8.8.8"))
        assertEquals(AddressError.NotPrivate, refused("100.63.255.255"))
        assertEquals(AddressError.NotPrivate, refused("100.128.0.1"))
        assertEquals(AddressError.NotPrivate, refused("172.32.0.1"))
        assertEquals(AddressError.NotPrivate, refused("127.0.0.1"))
        assertEquals(AddressError.NotPrivate, refused("169.254.1.1"))
        assertEquals(AddressError.NotPrivate, refused("[2001:db8::1]:8760"))
        assertEquals(AddressError.NotPrivate, refused("fd7a:115c:a1e1::1"))
    }

    @Test
    fun namesAndMalformedInputAreRefusedWithoutDns() {
        assertEquals(AddressError.Empty, refused("   "))
        assertEquals(AddressError.NotAnIpAddress, refused("little-boy"))
        assertEquals(AddressError.NotAnIpAddress, refused("little-boy.tail1234.ts.net:8760"))
        assertEquals(AddressError.NotAnIpAddress, refused("192.168.1"))
        assertEquals(AddressError.NotAnIpAddress, refused("192.168.1.256"))
        assertEquals(AddressError.NotAnIpAddress, refused("192.168.01.2"))
        assertEquals(AddressError.NotAnIpAddress, refused("[fd7a::1"))
        assertEquals(AddressError.NotAnIpAddress, refused("fe80::1%wlan0"))
    }

    @Test
    fun badPortsAreRefused() {
        assertEquals(AddressError.BadPort, refused("192.168.1.23:"))
        assertEquals(AddressError.BadPort, refused("192.168.1.23:0"))
        assertEquals(AddressError.BadPort, refused("192.168.1.23:65536"))
        assertEquals(AddressError.BadPort, refused("192.168.1.23:87a0"))
    }

    @Test
    fun tailnetHostCheckForTheCleartextGuard() {
        assertTrue(ServerAddress.isTailnetHost("100.101.102.103"))
        assertTrue(ServerAddress.isTailnetHost("fd7a:115c:a1e0::1"))
        assertFalse(ServerAddress.isTailnetHost("192.168.1.23"))
        assertFalse(ServerAddress.isTailnetHost("127.0.0.1"))
        assertFalse(ServerAddress.isTailnetHost("example.com"))
        assertNull(ServerAddress.networkOfHost("8.8.8.8"))
    }
}
