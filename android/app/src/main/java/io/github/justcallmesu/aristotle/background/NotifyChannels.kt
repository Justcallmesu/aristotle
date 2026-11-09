package io.github.justcallmesu.aristotle.background

import androidx.annotation.StringRes
import androidx.core.app.NotificationManagerCompat
import io.github.justcallmesu.aristotle.R

/**
 * The app's notification channels (T10) and how Settings shows them (TS1). Android owns each channel's switch: the
 * app only mirrors it and opens the system page.
 */
enum class AppChannel(val id: String, val importance: Int, @param:StringRes val title: Int, @param:StringRes val description: Int) {
    Link("link", NotificationManagerCompat.IMPORTANCE_LOW, R.string.channel_link, R.string.channel_link_description),
    Turns("turn_finished", NotificationManagerCompat.IMPORTANCE_DEFAULT, R.string.channel_turns, R.string.channel_turns_description),
    NeedsYou("needs_you", NotificationManagerCompat.IMPORTANCE_HIGH, R.string.channel_needs, R.string.channel_needs_description),

    /** TBA2: price levels, setups, automatic analyses, strong news and the 08:30 brief (heads-up), one per stock. */
    StockAlerts("stock_alerts", NotificationManagerCompat.IMPORTANCE_HIGH, R.string.channel_stock_alerts, R.string.channel_stock_alerts_description),

    /** UP2 (Q132): a new app version on the PC, once per version. Low importance: no sound, no heads-up. */
    Updates("app_updates", NotificationManagerCompat.IMPORTANCE_LOW, R.string.channel_updates, R.string.channel_updates_description),
    ;

    /** The ongoing link notification is no news: no launcher badge for it. */
    val showBadge: Boolean get() = this != Link
}

/** Which system page a channel row opens. */
enum class ChannelPage { Channel, App }

/** Pure rules for the Settings rows of [AppChannel]. */
object ChannelSwitch {
    /** On when the app may post at all and the channel exists and is not blocked; a channel not created yet reads off. */
    fun isOn(appAllowed: Boolean, importance: Int?): Boolean =
        appAllowed && importance != null && importance != NotificationManagerCompat.IMPORTANCE_NONE

    /** With the app's notifications off, a channel's page cannot turn anything on: the app's page comes first. */
    fun pageFor(appAllowed: Boolean): ChannelPage = if (appAllowed) ChannelPage.Channel else ChannelPage.App
}
