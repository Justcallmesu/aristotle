package io.github.justcallmesu.aristotle.ui.sessions

import io.github.justcallmesu.aristotle.data.LinkState
import io.github.justcallmesu.aristotle.data.Network
import io.github.justcallmesu.aristotle.data.OfflineCause
import io.github.justcallmesu.aristotle.data.ServerEndpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionsUiStateTest {
    private val lan = ServerEndpoint("192.168.1.61", 8760, Network.Lan)
    private val listed = SessionsUiState(address = lan, items = emptyList(), listedAt = "22:31")

    // T09 review 12: running rows are live (spinner) only while online; otherwise they read "Was running at".
    @Test
    fun theListIsLiveOnlyWhileOnline() {
        assertNull(listed.copy(link = LinkState.Online(lan, generation = 1)).staleAt)
        val notLive = listOf(
            LinkState.Connecting,
            LinkState.Offline(0, OfflineCause.Unreachable, "", lan, attempt = 1, nextAttemptAtMillis = null),
        )
        for (link in notLive) assertEquals("$link", "22:31", listed.copy(link = link).staleAt)
    }
}
