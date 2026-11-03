package io.github.justcallmesu.aristotle.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Close codes and failures, mapped to what the link does next (protocol §12). */
class LinkEndsTest {
    private val lan = ServerEndpoint("192.168.1.61", 8760, Network.Lan)
    private val tailnet = ServerEndpoint("100.74.61.96", 8760, Network.Tailscale)

    @Test
    fun closeCodesWithTheirOwnState() {
        assertEquals(RoundEnd.Revoked, LinkEnds.ofClose(4401, "revoked", lan))
        assertEquals(RoundEnd.HermesDown("close 4503 hermes_not_running"), LinkEnds.ofClose(4503, "hermes_not_running", lan))
        assertEquals(
            RoundEnd.HermesUpdated("close 4505 hermes_protocol_unsupported (unsupported hermes runtime protocol: 2)"),
            LinkEnds.ofClose(4505, "hermes_protocol_unsupported (unsupported hermes runtime protocol: 2)", lan),
        )
    }

    @Test
    fun everyOtherCloseIsALostConnectionRetriedWithBackoff() {
        listOf(1000 to "", 1001 to "going_away", 1011 to "pong_timeout", 1013 to "try_again", 4429 to "rate_limited",
            4430 to "too_many_connections", 4502 to "hermes_unavailable").forEach { (code, reason) ->
            assertEquals(
                RoundEnd.Lost(OfflineCause.ConnectionLost, LinkEnds.closeDetails(code, reason), lan),
                LinkEnds.ofClose(code, reason, lan),
            )
        }
        assertEquals("close 1000", LinkEnds.closeDetails(1000, ""))
    }

    @Test
    fun failuresBeforeTheRelayOpenedAreMissesExceptARefusedToken() {
        assertNull(LinkEnds.missOf(lan, 401, ConnectionProblem.Unreachable))
        assertEquals(Miss(lan, MissKind.Refused, "/v1/ws HTTP 403"), LinkEnds.missOf(lan, 403, ConnectionProblem.Unreachable))
        assertEquals(MissKind.CertificateChanged, LinkEnds.missOf(lan, null, ConnectionProblem.CertificateChanged)?.kind)
        assertEquals(MissKind.Unreachable, LinkEnds.missOf(lan, null, ConnectionProblem.Unreachable)?.kind)
        assertEquals(RoundEnd.Revoked, LinkEnds.ofFailure(401, ConnectionProblem.Unreachable, lan))
        assertEquals(OfflineCause.ConnectionLost, (LinkEnds.ofFailure(null, ConnectionProblem.Unreachable, lan) as RoundEnd.Lost).cause)
    }

    // D28: "Tailscale may be off" only when the tailnet was the only hope (not on Wi-Fi, or no LAN address).
    @Test
    fun offlineCauseAfterARoundWithoutAnAnswer() {
        val bothUnreachable = listOf(Miss(lan, MissKind.Unreachable, "unreachable"), Miss(tailnet, MissKind.Unreachable, "unreachable"))
        assertEquals(OfflineCause.Unreachable, LinkEnds.offline(bothUnreachable, pcHasLan = true, preferLan = true, vpnUp = false).cause)
        assertEquals(OfflineCause.TailscaleMayBeOff, LinkEnds.offline(bothUnreachable, pcHasLan = true, preferLan = false, vpnUp = false).cause)
        val tailnetOnly = listOf(Miss(tailnet, MissKind.Unreachable, "unreachable"))
        assertEquals(OfflineCause.TailscaleMayBeOff, LinkEnds.offline(tailnetOnly, pcHasLan = false, preferLan = true, vpnUp = false).cause)
        val refused = listOf(Miss(tailnet, MissKind.Refused, "/v1/ws HTTP 403"))
        assertEquals(OfflineCause.Unreachable, LinkEnds.offline(refused, pcHasLan = false, preferLan = false, vpnUp = false).cause)
        // T09 review 7: a VPN is up, so Tailscale is probably on; the laptop does not answer.
        assertEquals(OfflineCause.Unreachable, LinkEnds.offline(bothUnreachable, pcHasLan = true, preferLan = false, vpnUp = true).cause)
        assertEquals(OfflineCause.Unreachable, LinkEnds.offline(tailnetOnly, pcHasLan = false, preferLan = true, vpnUp = true).cause)

        val certificate = LinkEnds.offline(
            listOf(Miss(lan, MissKind.CertificateChanged, LinkEnds.CERT_DETAILS), Miss(tailnet, MissKind.Unreachable, "unreachable")),
            pcHasLan = true,
            preferLan = false,
            vpnUp = false,
        )
        assertEquals(OfflineCause.CertificateChanged, certificate.cause)
        assertEquals(lan, certificate.address)
        assertEquals("192.168.1.61:8760 ${LinkEnds.CERT_DETAILS}\n100.74.61.96:8760 unreachable", certificate.details)
    }
}
