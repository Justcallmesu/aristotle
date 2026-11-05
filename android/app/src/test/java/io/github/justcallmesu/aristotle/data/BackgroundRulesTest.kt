package io.github.justcallmesu.aristotle.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundRulesTest {
    private val lan = ServerEndpoint("192.168.1.61", 8760, Network.Lan)
    private val tailnet = ServerEndpoint("100.74.61.96", 8760, Network.Tailscale)
    private val pc = PcCredentials(
        pcId = "dev_1",
        deviceId = "dev_1",
        addresses = listOf(lan, tailnet),
        fingerprint = "7E54 D87B 19C0 A4F2 5B3E 0D96 C1A8 72FE 3E09 B6D4 8A15 F0C3 62D9 4E7A BCA7 EAA7",
        token = DeviceToken("t"),
    )
    private val paired = CredentialState.Paired(pc)
    private val online = LinkState.Online(lan, 1)
    private val offline = LinkState.Offline(1_000, OfflineCause.Unreachable, "", lan, 2, null)

    private fun keep(
        credentials: CredentialState = paired,
        link: LinkState = online,
        keepConnected: Boolean = true,
        stopped: Boolean = false,
    ) = BackgroundPolicy.keepRunning(credentials, link, keepConnected, stopped)

    @Test
    fun runsWhilePairedAndTheLinkCanStillRetry() {
        for (link in listOf(LinkState.Idle, LinkState.Connecting, online, offline, LinkState.HermesDown("close 4503", lan))) {
            assertTrue("$link", keep(link = link))
        }
    }

    @Test
    fun stopsForTheSwitchTheStopActionAndDeadEnds() {
        assertFalse(keep(keepConnected = false))
        assertFalse(keep(stopped = true))
        assertFalse(keep(link = LinkState.Revoked))
        assertFalse(keep(link = LinkState.HermesUpdated("close 4505", lan)))
        assertFalse(keep(link = LinkState.Stopped))
        assertFalse(keep(credentials = CredentialState.Unpaired))
        assertFalse(keep(credentials = CredentialState.Unavailable))
    }

    @Test
    fun aRestartedProcessKeepsTheServiceWhileThePairingIsRead() {
        assertTrue(keep(credentials = CredentialState.Loading, link = LinkState.Idle))
        assertFalse(keep(credentials = CredentialState.Loading, keepConnected = false))
    }

    @Test
    fun serviceStateMapsToTheNotificationLine() {
        assertEquals(ConnectionNotice.Connected("192.168.1.61", Network.Lan), ConnectionNotice.of(online, "pc"))
        assertEquals(ConnectionNotice.Connected("100.74.61.96", Network.Tailscale), ConnectionNotice.of(LinkState.Online(tailnet, 2), "pc"))
        assertEquals(ConnectionNotice.Reconnecting("pc"), ConnectionNotice.of(LinkState.Connecting, "pc"))
        assertEquals(ConnectionNotice.Reconnecting("pc"), ConnectionNotice.of(LinkState.Idle, "pc"))
        assertEquals(ConnectionNotice.OfflineSince(1_000), ConnectionNotice.of(offline, "pc"))
        assertEquals(ConnectionNotice.OfflineSince(1_000), ConnectionNotice.of(offline.copy(nextAttemptAtMillis = 9_000, attempt = 3), "pc"))
        assertEquals(ConnectionNotice.HermesDown("pc"), ConnectionNotice.of(LinkState.HermesDown("x", lan), "pc"))
        assertNull(ConnectionNotice.of(LinkState.Revoked, "pc"))
        assertNull(ConnectionNotice.of(LinkState.Stopped, "pc"))
        assertNull(ConnectionNotice.of(LinkState.HermesUpdated("x", lan), "pc"))
    }

    @Test
    fun thePcIsNamedByTheAddressThatLastWorked() {
        assertEquals("192.168.1.61", ConnectionNotice.pcLabel(pc))
        assertEquals("100.74.61.96", ConnectionNotice.pcLabel(pc.copy(lastGood = tailnet)))
        val v6 = ServerEndpoint("fd7a:115c:a1e0::1", 8760, Network.Tailscale)
        assertEquals("[fd7a:115c:a1e0::1]", ConnectionNotice.pcLabel(pc.copy(lastGood = v6)))
    }

    @Test
    fun xiaomiRedmiAndPocoAreOneFamily() {
        assertTrue(DeviceMaker.isXiaomiFamily("Xiaomi", "POCO"))
        assertTrue(DeviceMaker.isXiaomiFamily("Xiaomi", "Redmi"))
        assertTrue(DeviceMaker.isXiaomiFamily("XIAOMI", "xiaomi"))
        assertTrue(DeviceMaker.isXiaomiFamily(null, " poco "))
        assertFalse(DeviceMaker.isXiaomiFamily("Google", "google"))
        assertFalse(DeviceMaker.isXiaomiFamily("samsung", "samsung"))
        assertFalse(DeviceMaker.isXiaomiFamily(null, null))
        assertFalse(DeviceMaker.isXiaomiFamily("Xiaomi-ish", "pocophone"))
    }
}
