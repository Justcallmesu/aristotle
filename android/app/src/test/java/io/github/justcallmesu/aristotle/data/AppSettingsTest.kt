package io.github.justcallmesu.aristotle.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSettingsTest {
    @Test
    fun keepConnectedIsOnByDefaultAndRemembered() {
        val store = MemoryStore()
        assertTrue(AppSettings(store).keepConnected.value)
        AppSettings(store).setKeepConnected(false)
        assertFalse(AppSettings(store).keepConnected.value)
    }

    @Test
    fun theGuideIsShownOnceAndItsTicksAreKept() {
        val store = MemoryStore()
        val settings = AppSettings(store)
        assertFalse(settings.batteryGuideShown)
        settings.markBatteryGuideShown()
        settings.markGuide(GuideStep.Autostart, true)
        settings.markGuide(GuideStep.BatterySaver, true)
        settings.markGuide(GuideStep.BatterySaver, false)
        val again = AppSettings(store)
        assertTrue(again.batteryGuideShown)
        assertEquals(setOf(GuideStep.Autostart), again.guideMarks.value)
    }

    @Test
    fun aDamagedValueFallsBackToTheDefault() {
        val store = MemoryStore().apply { put("keep_connected", "yes") }
        assertTrue(AppSettings(store).keepConnected.value)
    }

    @Test
    fun appearanceAndLanguageDefaultToTheSystemAndAreRemembered() {
        val store = MemoryStore()
        val fresh = AppSettings(store)
        assertEquals(ThemeChoice.System, fresh.theme.value)
        assertEquals(TextSize.Default, fresh.textSize.value)
        assertEquals(AppLanguage.System, fresh.language.value)
        fresh.setTheme(ThemeChoice.Dark)
        fresh.setTextSize(TextSize.Large)
        fresh.setLanguage(AppLanguage.Indonesian)
        // Applied at once (the flows the activity collects), and still there after a restart.
        assertEquals(ThemeChoice.Dark, fresh.theme.value)
        assertEquals(TextSize.Large, fresh.textSize.value)
        val again = AppSettings(store)
        assertEquals(ThemeChoice.Dark, again.theme.value)
        assertEquals(TextSize.Large, again.textSize.value)
        assertEquals(AppLanguage.Indonesian, again.language.value)
        assertEquals(mapOf("theme" to "dark", "text_size" to "large", "language" to "id"), store.map)
    }

    @Test
    fun damagedAppearanceValuesFallBackToTheDefaults() {
        val store = MemoryStore().apply {
            put("theme", "sepia")
            put("text_size", "huge")
            put("language", "fr")
        }
        val settings = AppSettings(store)
        assertEquals(ThemeChoice.System, settings.theme.value)
        assertEquals(TextSize.Default, settings.textSize.value)
        assertEquals(AppLanguage.System, settings.language.value)
    }

    @Test
    fun themeChoiceMapsToDarkOrLight() {
        assertTrue(ThemeChoice.System.isDark(systemDark = true))
        assertFalse(ThemeChoice.System.isDark(systemDark = false))
        assertFalse(ThemeChoice.Light.isDark(systemDark = true))
        assertTrue(ThemeChoice.Dark.isDark(systemDark = false))
    }

    @Test
    fun languageTagsAndLocaleCodesMap() {
        assertNull(AppLanguage.System.tag)
        assertEquals("en", AppLanguage.English.tag)
        assertEquals("id", AppLanguage.Indonesian.tag)
        // Android reports Indonesian with its legacy code "in"; both mean the same choice.
        assertEquals(AppLanguage.Indonesian, AppLanguage.ofLanguage("in"))
        assertEquals(AppLanguage.Indonesian, AppLanguage.ofLanguage("id"))
        assertEquals(AppLanguage.English, AppLanguage.ofLanguage("EN"))
        assertEquals(AppLanguage.System, AppLanguage.ofLanguage("fr"))
        assertEquals(AppLanguage.System, AppLanguage.ofLanguage(null))
    }
}
