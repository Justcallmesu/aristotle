package io.github.justcallmesu.aristotle.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsBlocksTest {
    @Test
    fun threeBlocksAsInTheDesignNotes() {
        assertEquals(
            listOf(
                listOf(SettingsPage.Connection, SettingsPage.Notifications),
                listOf(SettingsPage.Appearance, SettingsPage.Language),
                listOf(SettingsPage.Security, SettingsPage.About),
            ),
            settingsBlocks,
        )
    }

    @Test
    fun everyPageIsInExactlyOneBlock() {
        assertEquals(SettingsPage.entries.toSet(), settingsBlocks.flatten().toSet())
        assertEquals(SettingsPage.entries.size, settingsBlocks.flatten().size)
    }
}
