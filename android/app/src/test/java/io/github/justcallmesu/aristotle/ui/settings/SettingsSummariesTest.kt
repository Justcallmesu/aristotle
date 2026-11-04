package io.github.justcallmesu.aristotle.ui.settings

import io.github.justcallmesu.aristotle.background.AppChannel
import io.github.justcallmesu.aristotle.data.LinkState
import io.github.justcallmesu.aristotle.data.Network
import io.github.justcallmesu.aristotle.data.ServerEndpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** TS3 (Q72): the one-line summaries of the Settings category list. */
class SettingsSummariesTest {
    private fun online(network: Network) = LinkState.Online(ServerEndpoint("192.168.1.5", 7777, network), 1)
    private fun channels(vararg on: AppChannel) = AppChannel.entries.associateWith { it in on }

    @Test
    fun `online names its network, others name none`() {
        assertEquals(LinkWord.Connected, SettingsSummaries.linkWord(online(Network.Lan)))
        assertEquals(Network.Lan, SettingsSummaries.linkNetwork(online(Network.Lan)))
        assertEquals(Network.Tailscale, SettingsSummaries.linkNetwork(online(Network.Tailscale)))
        assertEquals(LinkWord.Connecting, SettingsSummaries.linkWord(LinkState.Idle))
        assertEquals(LinkWord.Connecting, SettingsSummaries.linkWord(LinkState.Connecting))
        assertEquals(LinkWord.Stopped, SettingsSummaries.linkWord(LinkState.Stopped))
        assertEquals(LinkWord.Revoked, SettingsSummaries.linkWord(LinkState.Revoked))
        assertNull(SettingsSummaries.linkNetwork(LinkState.Connecting))
    }

    @Test
    fun `notifications on when all channels are on`() {
        assertEquals(NotificationsSummary.On, SettingsSummaries.notifications(true, channels(*AppChannel.entries.toTypedArray())))
    }

    @Test
    fun `notifications some off for a mix`() {
        assertEquals(NotificationsSummary.SomeOff, SettingsSummaries.notifications(true, channels(AppChannel.entries.first())))
    }

    @Test
    fun `notifications off when none is on or the app is blocked`() {
        assertEquals(NotificationsSummary.Off, SettingsSummaries.notifications(true, channels()))
        assertEquals(NotificationsSummary.Off, SettingsSummaries.notifications(false, channels(*AppChannel.entries.toTypedArray())))
    }
}
