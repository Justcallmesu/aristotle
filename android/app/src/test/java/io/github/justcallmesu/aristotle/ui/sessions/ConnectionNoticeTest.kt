package io.github.justcallmesu.aristotle.ui.sessions

import io.github.justcallmesu.aristotle.data.LinkEnds
import io.github.justcallmesu.aristotle.data.LinkState
import io.github.justcallmesu.aristotle.data.Network
import io.github.justcallmesu.aristotle.data.OfflineCause
import io.github.justcallmesu.aristotle.data.ServerEndpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Home's Stocks view keeps the notice of a link that waits for the user, and only that (TS2b m1). */
class ConnectionNoticeTest {
    private val lan = ServerEndpoint("192.168.1.61", 8760, Network.Lan)

    @Test
    fun aLinkWaitingForTheUserHasANotice() {
        val certificate = LinkState.Offline(1_000, OfflineCause.CertificateChanged, LinkEnds.CERT_DETAILS, lan, 1, null)
        assertEquals(PageKind.CertificateChanged, noticeFor(certificate))
        assertEquals(PageKind.HermesUpdated, noticeFor(LinkState.HermesUpdated("close 4505", lan)))
    }

    @Test
    fun aLinkThatRecoversByItselfHasNone() {
        val unreachable = LinkState.Offline(1_000, OfflineCause.Unreachable, "unreachable", lan, 1, null)
        for (state in listOf(LinkState.Online(lan, 1), LinkState.HermesDown("close 4503", lan), unreachable, LinkState.Connecting)) {
            assertNull("$state", noticeFor(state))
        }
    }
}
