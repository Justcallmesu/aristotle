package io.github.justcallmesu.aristotle.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
