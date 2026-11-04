package io.github.justcallmesu.aristotle.data.terminal

import io.github.justcallmesu.aristotle.data.CredentialState
import io.github.justcallmesu.aristotle.data.DeviceToken
import io.github.justcallmesu.aristotle.data.LinkState
import io.github.justcallmesu.aristotle.data.Network
import io.github.justcallmesu.aristotle.data.PcCredentials
import io.github.justcallmesu.aristotle.data.ServerEndpoint
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Terminal entry's visibility (protocol §14 Switch and grant): `/v1/me` on every connect, hidden after a 4403. */
class TerminalAccessTest {
    private val lan = ServerEndpoint("192.168.1.61", 8760, Network.Lan)
    private val tailnet = ServerEndpoint("100.74.61.96", 8760, Network.Tailscale)
    private val pc = PcCredentials("pc", "dev", listOf(lan, tailnet), "F".repeat(64), DeviceToken("t"))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val link = MutableStateFlow<LinkState>(LinkState.Connecting)
    private val credentials = MutableStateFlow<CredentialState>(CredentialState.Paired(pc))

    private class FakeDialer(var answer: Boolean?) : TerminalDialer {
        val asked = CopyOnWriteArrayList<ServerEndpoint>()
        override fun openTerminal(pc: PcCredentials, endpoint: ServerEndpoint): TerminalChannel = error("not used")
        override suspend fun terminalAllowed(pc: PcCredentials, endpoint: ServerEndpoint): Boolean? {
            asked += endpoint
            return answer
        }
    }

    @After
    fun stop() = scope.cancel()

    private fun access(dialer: FakeDialer) = TerminalAccess(scope, link, credentials, dialer).also { it.start() }

    private fun TerminalAccess.awaitTarget(): TerminalTarget = runBlocking { withTimeout(5_000) { target.first { it != null }!! } }

    @Test
    fun theRuleNeedsBothFlags() {
        assertTrue(TerminalAccess.visible(enabled = true, granted = true))
        assertFalse(TerminalAccess.visible(enabled = true, granted = false))
        assertFalse(TerminalAccess.visible(enabled = false, granted = true))
        assertFalse(TerminalAccess.visible(enabled = false, granted = false))
    }

    @Test
    fun shownOnlyWhileOnlineAndAllowed() {
        val dialer = FakeDialer(answer = true)
        val access = access(dialer)
        assertNull(access.target.value)
        assertTrue(dialer.asked.isEmpty())
        link.value = LinkState.Online(lan, generation = 1)
        assertEquals(TerminalTarget(pc, lan, 1), access.awaitTarget())
        link.value = LinkState.Connecting
        assertNull(access.target.value)
    }

    @Test
    fun meIsReadAgainOnEveryConnectAtTheLinksAddress() {
        val dialer = FakeDialer(answer = true)
        val access = access(dialer)
        link.value = LinkState.Online(lan, 1)
        access.awaitTarget()
        link.value = LinkState.Online(tailnet, 2)
        assertEquals(TerminalTarget(pc, tailnet, 2), access.awaitTarget())
        dialer.answer = false
        link.value = LinkState.Online(lan, 3)
        assertNull(access.target.value)
        assertEquals(listOf(lan, tailnet, lan), dialer.asked)
    }

    @Test
    fun notAllowedOrUnreadableStaysHidden() {
        val dialer = FakeDialer(answer = false)
        val access = access(dialer)
        link.value = LinkState.Online(lan, 1)
        assertNull(access.target.value)
        dialer.answer = null
        link.value = LinkState.Online(lan, 2)
        assertNull(access.target.value)
        assertEquals(2, dialer.asked.size)
    }

    @Test
    fun aSwitchOffHidesItUntilTheNextConnect() {
        val dialer = FakeDialer(answer = true)
        val access = access(dialer)
        link.value = LinkState.Online(lan, 1)
        access.awaitTarget()
        access.turnedOff()
        assertNull(access.target.value)
        link.value = LinkState.Online(lan, 2)
        assertEquals(TerminalTarget(pc, lan, 2), access.awaitTarget())
    }
}
