package io.github.justcallmesu.aristotle.ui.settings

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import io.github.justcallmesu.aristotle.R
import io.github.justcallmesu.aristotle.background.AppChannel
import io.github.justcallmesu.aristotle.data.LinkState
import io.github.justcallmesu.aristotle.data.Network

/** The categories of the Settings list (Q72, TS3); each opens its own sub-page. */
enum class SettingsPage(@StringRes val title: Int, @DrawableRes val icon: Int) {
    Connection(R.string.settings_section_connection, R.drawable.ic_devices),
    Notifications(R.string.settings_section_notifications, R.drawable.ic_notifications),
    Appearance(R.string.settings_section_appearance, R.drawable.ic_palette),
    Language(R.string.settings_section_language, R.drawable.ic_language),
    Security(R.string.settings_section_security, R.drawable.ic_verified_user),
    Stocks(R.string.settings_section_stocks, R.drawable.ic_show_chart),
    About(R.string.settings_section_about, R.drawable.ic_info),
}

/**
 * The Settings list's grouped blocks (design-notes §5.4): connection + notifications, look + language, stocks (TS2:
 * broker fees and broker app), security + about.
 */
val settingsBlocks: List<List<SettingsPage>> = listOf(
    listOf(SettingsPage.Connection, SettingsPage.Notifications),
    listOf(SettingsPage.Appearance, SettingsPage.Language),
    listOf(SettingsPage.Stocks),
    listOf(SettingsPage.Security, SettingsPage.About),
)

/** The link as one word of the Connection & PCs summary. */
enum class LinkWord { Connected, Connecting, Offline, HermesDown, HermesUpdated, Stopped, Revoked }

/** "On" / "Off" / "Some off" for the Notifications summary. */
enum class NotificationsSummary { On, Off, SomeOff }

/** Pure rules behind the one-line summaries of the Settings list (Q72, TS3). */
object SettingsSummaries {
    fun linkWord(link: LinkState): LinkWord = when (link) {
        is LinkState.Online -> LinkWord.Connected
        LinkState.Idle, LinkState.Connecting -> LinkWord.Connecting
        is LinkState.Offline -> LinkWord.Offline
        is LinkState.HermesDown -> LinkWord.HermesDown
        is LinkState.HermesUpdated -> LinkWord.HermesUpdated
        LinkState.Stopped -> LinkWord.Stopped
        LinkState.Revoked -> LinkWord.Revoked
    }

    /** Only a live link names its network; with no link, no address is "the" one. */
    fun linkNetwork(link: LinkState): Network? = (link as? LinkState.Online)?.endpoint?.network

    /** Off while the app's notifications are blocked or every channel is off; "Some off" for a mix. */
    fun notifications(appAllowed: Boolean, channels: Map<AppChannel, Boolean>): NotificationsSummary {
        val on = AppChannel.entries.count { channels[it] == true }
        return when {
            !appAllowed || on == 0 -> NotificationsSummary.Off
            on == AppChannel.entries.size -> NotificationsSummary.On
            else -> NotificationsSummary.SomeOff
        }
    }
}
