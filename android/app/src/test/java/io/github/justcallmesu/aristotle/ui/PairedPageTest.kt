package io.github.justcallmesu.aristotle.ui

import io.github.justcallmesu.aristotle.ui.settings.SettingsPage
import org.junit.Assert.assertEquals
import org.junit.Test

/** TS3 (Q72): back from a Settings sub-page, and the page a category opens. */
class PairedPageTest {
    @Test
    fun `every category has its own page and goes back to the list`() {
        SettingsPage.entries.forEach { category ->
            val page = PairedPage.of(category)
            assertEquals(category, page.settingsPage)
            assertEquals(PairedPage.Settings, page.back)
        }
        assertEquals(PairedPage.Sessions, PairedPage.Settings.back)
    }

    @Test
    fun `battery guide, pair again and what's new go back to their sub-page`() {
        assertEquals(PairedPage.SettingsNotifications, PairedPage.SettingsGuide.back)
        assertEquals(PairedPage.SettingsConnection, PairedPage.Repair.back)
        assertEquals(PairedPage.SettingsAbout, PairedPage.SettingsWhatsNew.back)
        assertEquals(PairedPage.SettingsConnection, PairedPage.SettingsHistory.back) // Q73
    }

    @Test
    fun `other pages still go back to the session list`() {
        assertEquals(PairedPage.Sessions, PairedPage.Chat.back)
        assertEquals(PairedPage.Sessions, PairedPage.WhatsNew.back)
        assertEquals(PairedPage.Sessions, PairedPage.Terminal.back)
    }

    @Test
    fun `Running opened from a chat goes back to that chat, otherwise to Home`() {
        assertEquals(PairedPage.Chat, PairedPage.Running.backFrom(PairedPage.Chat))
        assertEquals(PairedPage.Sessions, PairedPage.Running.backFrom(PairedPage.Sessions))
        assertEquals(PairedPage.Sessions, PairedPage.Chat.backFrom(PairedPage.Chat)) // only Running is redirected
        assertEquals(PairedPage.Settings, PairedPage.SettingsAbout.backFrom(PairedPage.Chat))
    }
}
