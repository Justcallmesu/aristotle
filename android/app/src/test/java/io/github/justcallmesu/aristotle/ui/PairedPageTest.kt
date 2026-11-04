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
    }

    @Test
    fun `other pages still go back to the session list`() {
        assertEquals(PairedPage.Sessions, PairedPage.Chat.back)
        assertEquals(PairedPage.Sessions, PairedPage.WhatsNew.back)
        assertEquals(PairedPage.Sessions, PairedPage.Terminal.back)
    }
}
