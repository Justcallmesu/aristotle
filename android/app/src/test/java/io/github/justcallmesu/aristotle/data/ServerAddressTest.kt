package io.github.justcallmesu.aristotle.data

import org.junit.Assert.assertEquals
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
    fun tailscaleAddressUsesHttpsLikeTheLan() {
        val e = ok("100.101.102.103:8760")
        assertEquals(Network.Tailscale, e.network)
        assertEquals("https://100.101.102.103:8760", e.baseUrl)
        assertEquals(Network.Tailscale, ok("100.64.0.1").network)
        assertEquals(Network.Tailscale, ok("100.127.255.255").network)
    }

    @Test
    fun tailscaleIpv6NeedsBracketsForAPort() {
        val e = ok("[fd7a:115c:a1e0::1]:8760")
        assertEquals(Network.Tailscale, e.network)
        assertEquals("https://[fd7a:115c:a1e0:0:0:0:0:1]:8760", e.baseUrl)
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

    // D22: the IPv6 path is a pure parser. On Android, InetAddress.getByName resolves any string it cannot
    // parse, so each of these hostname-like inputs would have been a DNS query before.
    @Test
    fun hostnameLikeColonInputIsRefusedByPureParsing() {
        for (input in listOf(
            "1:2", "[cafe:babe]", "dead:beef:cafe", "192.168.1.5:8760:", "a:b:c:d:e:f:1:2:3", "1::2::3", ":::",
            ":1:2:3:4:5:6:7", "1:2:3:4:5:6:7:8::", "12345::1", "fd7a::115c::1", "fd7a:115c:a1e0::1.2.3", "100.64.0.1::1",
        )) {
            assertEquals(input, AddressError.NotAnIpAddress, refused(input))
        }
    }

    @Test
    fun ipv6LiteralsParseToTheirBytes() {
        val expected = ByteArray(16).also {
            it[0] = 0xFD.toByte(); it[1] = 0x7A; it[2] = 0x11; it[3] = 0x5C; it[4] = 0xA1.toByte(); it[5] = 0xE0.toByte(); it[15] = 1
        }
        assertTrue(expected.contentEquals(ServerAddress.parseIpLiteral("fd7a:115c:a1e0::1")))
        assertTrue(expected.contentEquals(ServerAddress.parseIpLiteral("FD7A:115C:A1E0:0:0:0:0:1")))
        assertTrue(expected.contentEquals(ServerAddress.parseIpLiteral("fd7a:115c:a1e0::0.0.0.1")))
        assertEquals(16, ServerAddress.parseIpLiteral("::")?.size)
        assertEquals(Network.Tailscale, ok("fd7a:115c:a1e0:1:2:3:4:5").network)
        // IPv4-mapped and compatible forms are not RFC1918 or tailnet addresses.
        assertEquals(AddressError.NotPrivate, refused("[::ffff:192.168.1.5]:8760"))
        assertEquals(AddressError.NotPrivate, refused("::100.64.0.1"))
    }

    @Test
    fun badPortsAreRefused() {
        assertEquals(AddressError.BadPort, refused("192.168.1.23:"))
        assertEquals(AddressError.BadPort, refused("192.168.1.23:0"))
        assertEquals(AddressError.BadPort, refused("192.168.1.23:65536"))
        assertEquals(AddressError.BadPort, refused("192.168.1.23:87a0"))
    }

    // Q49: no address, however it is typed or pasted, gives an http:// base URL.
    @Test
    fun everyAcceptedAddressGetsAnHttpsBaseUrl() {
        val inputs = listOf(
            "192.168.1.23", "http://192.168.1.23:8760/", "10.0.0.5:9000", "172.16.0.1",
            "100.101.102.103", "http://100.101.102.103:8760", "ws://100.64.0.1", "[fd7a:115c:a1e0::1]:8760", "fd7a:115c:a1e0::2",
        )
        for (input in inputs) {
            val url = ok(input).baseUrl
            assertTrue("$input -> $url", url.startsWith("https://"))
        }
    }
}
