package io.github.justcallmesu.aristotle.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileSelectionTest {
    // T14b: a profile the PC stopped serving is forgotten (the next start does not repeat it) and announced once.
    @Test
    fun fallingBackForgetsTheProfileAndNoticesItOnce() {
        val store = MemoryStore()
        val selection = ProfileSelection(store).apply { follow("pc"); select("gone") }
        assertTrue(selection.fallBack("gone"))
        assertNull(selection.selected.value)
        assertEquals("gone", selection.fellBackFrom.value)
        assertNull(ProfileSelection(store).apply { follow("pc") }.selected.value)
        selection.noticeShown()
        assertNull(selection.fellBackFrom.value)
    }

    @Test
    fun fallingBackFromAProfileThatIsNoLongerSelectedDoesNothing() {
        val selection = ProfileSelection(MemoryStore()).apply { follow("pc"); select("pm") }
        assertFalse(selection.fallBack("gone"))
        assertEquals("pm", selection.selected.value)
        assertNull(selection.fellBackFrom.value)
    }
}
