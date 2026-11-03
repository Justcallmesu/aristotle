package io.github.justcallmesu.aristotle.data

import java.io.File
import java.net.URLEncoder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pairing QR payload (protocol §11.4), against the string the server's own test writes. */
class PairPayloadTest {
    /** Shared with server/internal/pairing/payload_test.go: the server builds exactly this from the same inputs. */
    private val fixture = File("../../server/internal/pairing/testdata/pair-payload-v1.txt").readText().trim()

    private val fingerprint = "7E54 D87B 0C1F 9A42 6B3E 11D0 8F5C 2A97 4E06 B1C8 D3F2 5A69 0E7B C4D1 BCA7 EAA7"
    private val fp = fingerprint.replace(" ", "")
    private val base = "aristotle://pair?v=1&c=K7Q2M9XD&fp=$fp"

    private fun ok(text: String): PairPayload {
        val check = PairPayloads.parse(text)
        assertTrue("refused $check: $text", check is PayloadCheck.Ok)
        return (check as PayloadCheck.Ok).payload
    }

    private fun refused(error: PayloadError, text: String) =
        assertEquals(text, PayloadCheck.Refused(error), PairPayloads.parse(text))

    private fun a(url: String) = "&a=" + URLEncoder.encode(url, "UTF-8")

    @Test
    fun acceptsWhatTheServerWrites() {
        val payload = ok(fixture)
        assertEquals("K7Q2M9XD", payload.code)
        assertArrayEquals(Fingerprint.parse(fingerprint), payload.pin)
        assertEquals(
            listOf(
                ServerEndpoint("192.168.1.61", 8760, Network.Lan),
                ServerEndpoint("10.0.0.2", 8760, Network.Lan),
                ServerEndpoint("100.74.61.96", 8760, Network.Tailscale),
                (ServerAddress.parse("[fd7a:115c:a1e0::d628:3d61]:8760") as AddressCheck.Ok).endpoint,
            ),
            payload.addresses,
        )
    }

    @Test
    fun acceptsUnescapedUrlsAndALowercaseFingerprint() {
        val payload = ok("aristotle://pair?v=1&c=K7Q2M9XD&fp=${fp.lowercase()}&a=https://192.168.1.61:8760")
        assertEquals("https://192.168.1.61:8760", payload.addresses.single().baseUrl)
        assertArrayEquals(Fingerprint.parse(fingerprint), payload.pin)
    }

    @Test
    fun refusesAnotherSchemeOrHost() {
        for (text in listOf(
            "",
            "https://example.com/pair?v=1",
            fixture.replaceFirst("aristotle://", "Aristotle://"),
            fixture.replaceFirst("aristotle://pair?", "aristotle://pairing?"),
            fixture.replaceFirst("aristotle://pair?", "aristotle://pair/?"),
            fixture.replaceFirst("aristotle://pair?", "aristotle:pair?"),
            "K7Q2M9XD",
        )) {
            refused(PayloadError.NotPairing, text)
        }
    }

    @Test
    fun refusesAnotherVersion() {
        refused(PayloadError.Version, fixture.replaceFirst("v=1", "v=2"))
        refused(PayloadError.Version, fixture.replaceFirst("v=1", "v=01"))
        refused(PayloadError.Version, fixture.replaceFirst("v=1", "v=2") + "&x=new-in-v2")
        refused(PayloadError.Malformed, fixture.replaceFirst("v=1&", ""))
        refused(PayloadError.Malformed, fixture + "&v=1")
    }

    @Test
    fun refusesACodeTheServerDoesNotIssue() {
        for (code in listOf("K7Q2M9X", "K7Q2M9XDD", "k7q2m9xd", "K7Q2 M9XD", "K7Q2-M9XD", "K7Q2M9XU", "K7Q2M9XO")) {
            refused(PayloadError.Code, fixture.replaceFirst("c=K7Q2M9XD", "c=" + URLEncoder.encode(code, "UTF-8")))
        }
    }

    @Test
    fun refusesAFingerprintThatIsNot64Hex() {
        for (bad in listOf(fp.dropLast(1), fp + "A", fp.replaceFirst('E', 'G'), fingerprint.replace(" ", "+"), "")) {
            refused(PayloadError.Fingerprint, fixture.replaceFirst("fp=$fp", "fp=$bad"))
        }
    }

    @Test
    fun refusesAnAddressTheAppWouldNotUse() {
        for (url in listOf(
            "http://192.168.1.61:8760",
            "HTTPS://192.168.1.61:8760",
            "https://192.168.1.61",
            "https://fd7a:115c:a1e0::d628:3d61",
            "https://[fd7a:115c:a1e0::d628:3d61]",
            "https://192.168.1.61:8760/v1/pair",
            "https://192.168.1.61:8760?x=1",
            "https://user@192.168.1.61:8760",
            "https://laptop.local:8760",
            "https://8.8.8.8:8760",
            "https://127.0.0.1:8760",
            "https://192.168.1.61:0",
            "https://192.168.1.61:65536",
            "192.168.1.61:8760",
        )) {
            refused(PayloadError.Address, base + a("https://10.0.0.2:8760") + a(url))
        }
    }

    @Test
    fun refusesMalformedOrOversizedInput() {
        refused(PayloadError.Malformed, base) // no address
        refused(PayloadError.Malformed, base + (1..9).joinToString("") { a("https://10.0.0.$it:8760") })
        ok(base + (1..8).joinToString("") { a("https://10.0.0.$it:8760") })
        refused(PayloadError.Malformed, fixture + "&token=abc")
        refused(PayloadError.Malformed, fixture + "&c=K7Q2M9XD")
        refused(PayloadError.Malformed, fixture + "&")
        refused(PayloadError.Malformed, fixture.replaceFirst("&c=", "&&c="))
        refused(PayloadError.Malformed, fixture + "&a")
        refused(PayloadError.Malformed, fixture + "&a=%zz")
        refused(PayloadError.Malformed, fixture + "&=x")
        refused(PayloadError.Malformed, fixture + "&pad=" + "x".repeat(PairPayloads.MAX_LENGTH))
        refused(PayloadError.Malformed, "aristotle://pair?" + "x".repeat(100_000))
    }

    @Test
    fun theSameAddressTwiceIsKeptOnce() {
        assertEquals(1, ok(base + a("https://10.0.0.2:8760") + a("https://10.0.0.2:8760")).addresses.size)
    }
}
