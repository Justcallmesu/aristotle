package io.github.justcallmesu.aristotle.data

import io.github.justcallmesu.aristotle.BuildConfig
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Q64: "What's new" once after an update; a fresh install counts as seen. */
class WhatsNewCardTest {
    @Test
    fun aFreshInstallShowsNothingAndRecordsTheVersion() {
        val store = MemoryStore()
        val card = WhatsNewCard(AppSettings(store), 10000)
        assertFalse(card.visible.value)
        assertEquals(10000, AppSettings(store).lastSeenVersionCode)
        // The next launch of the same build: still nothing.
        assertFalse(WhatsNewCard(AppSettings(store), 10000).visible.value)
    }

    @Test
    fun anUpdateShowsTheCardUntilDismissedThenNeverAgain() {
        val store = MemoryStore().apply { put("last_seen_version_code", "10000") }
        val card = WhatsNewCard(AppSettings(store), 10100)
        assertTrue(card.visible.value)
        // Not dismissed: it shows on the next launch too.
        assertTrue(WhatsNewCard(AppSettings(store), 10100).visible.value)
        card.dismiss()
        assertFalse(card.visible.value)
        assertEquals(10100, AppSettings(store).lastSeenVersionCode)
        assertFalse(WhatsNewCard(AppSettings(store), 10100).visible.value)
    }

    @Test
    fun theRuleIsDueOnlyBelowTheCurrentVersion() {
        assertFalse(WhatsNewCard.due(null, 10000))
        assertFalse(WhatsNewCard.due(10000, 10000))
        assertFalse(WhatsNewCard.due(10100, 10000))
        assertTrue(WhatsNewCard.due(9999, 10000))
    }

    @Test
    fun aDamagedStoredValueIsTreatedAsNone() {
        val store = MemoryStore().apply { put("last_seen_version_code", "abc") }
        assertNull(AppSettings(store).lastSeenVersionCode)
        assertFalse(WhatsNewCard(AppSettings(store), 10000).visible.value)
    }

    /** The build's own version must have a released entry in CHANGELOG.md (the asset is cut from it). */
    @Test
    fun changelogHasAnUnreleasedHeadingAndThisVersion() {
        val text = File("../../CHANGELOG.md").readText()
        assertTrue(text.lines().any { it.trim() == "## [Unreleased]" })
        assertTrue(text.lines().any { it.startsWith("## [${BuildConfig.VERSION_NAME}] - ") })
        assertTrue(text.lines().any { it.startsWith("- Phone: ") })
    }
}
