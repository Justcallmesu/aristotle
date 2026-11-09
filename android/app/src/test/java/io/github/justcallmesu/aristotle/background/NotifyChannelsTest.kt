package io.github.justcallmesu.aristotle.background

import androidx.core.app.NotificationManagerCompat
import io.github.justcallmesu.aristotle.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotifyChannelsTest {
    @Test
    fun channelsKeepTheirIdsImportanceAndNames() {
        // The ids are permanent on installed phones: a new id would leave the user's choices on the old channel.
        assertEquals(listOf("link", "turn_finished", "needs_you", "stock_alerts", "app_updates"), AppChannel.entries.map { it.id })
        assertEquals(
            listOf(
                NotificationManagerCompat.IMPORTANCE_LOW,
                NotificationManagerCompat.IMPORTANCE_DEFAULT,
                NotificationManagerCompat.IMPORTANCE_HIGH,
                NotificationManagerCompat.IMPORTANCE_HIGH,
                // UP2 (Q132): an update is news, not an alarm: low importance, no sound.
                NotificationManagerCompat.IMPORTANCE_LOW,
            ),
            AppChannel.entries.map { it.importance },
        )
        assertEquals(R.string.channel_stock_alerts, AppChannel.StockAlerts.title)
        assertEquals(R.string.channel_stock_alerts_description, AppChannel.StockAlerts.description)
        assertEquals(R.string.channel_turns, AppChannel.Turns.title)
        assertEquals(R.string.channel_turns_description, AppChannel.Turns.description)
        assertEquals(R.string.channel_needs, AppChannel.NeedsYou.title)
        assertEquals(R.string.channel_needs_description, AppChannel.NeedsYou.description)
        assertEquals(R.string.channel_updates, AppChannel.Updates.title)
        assertEquals(R.string.channel_updates_description, AppChannel.Updates.description)
        assertEquals(listOf(false, true, true, true, true), AppChannel.entries.map { it.showBadge })
    }

    @Test
    fun aSwitchIsOnOnlyWhenTheAppMayPostAndTheChannelIsNotBlocked() {
        assertTrue(ChannelSwitch.isOn(appAllowed = true, importance = NotificationManagerCompat.IMPORTANCE_HIGH))
        assertTrue(ChannelSwitch.isOn(appAllowed = true, importance = NotificationManagerCompat.IMPORTANCE_MIN))
        assertFalse(ChannelSwitch.isOn(appAllowed = true, importance = NotificationManagerCompat.IMPORTANCE_NONE))
        assertFalse(ChannelSwitch.isOn(appAllowed = true, importance = null))
        assertFalse(ChannelSwitch.isOn(appAllowed = false, importance = NotificationManagerCompat.IMPORTANCE_HIGH))
    }

    @Test
    fun withTheAppsNotificationsOffTheAppsPageOpens() {
        assertEquals(ChannelPage.Channel, ChannelSwitch.pageFor(appAllowed = true))
        assertEquals(ChannelPage.App, ChannelSwitch.pageFor(appAllowed = false))
    }
}
