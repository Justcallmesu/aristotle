package io.github.justcallmesu.aristotle.data.chat

import io.github.justcallmesu.aristotle.data.MemoryStore
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

/** T13 NIT: drafts are pruned only after 30 days untouched, never by session list membership. */
class ChatDraftsTest {
    @Test
    fun draftsMissingFromListButRecentlySurvivePruning() {
        val store = MemoryStore().apply { put("other", "not a draft") }
        val clock = { Instant.parse("2025-01-15T12:00:00Z") }
        val drafts = ChatDrafts(store, clock)
        drafts.put("present", "in list")
        drafts.put("absent", "missing from list but recent")
        
        // Prune with time at 2025-01-15, so both drafts (created at 2025-01-15) are recent.
        drafts.prune()
        
        assertEquals("in list", drafts.get("present"))
        assertEquals("missing from list but recent", drafts.get("absent"))
        assertEquals("not a draft", store.get("other"))
    }

    @Test
    fun draftsPrunedAfter30DaysUntouched() {
        val store = MemoryStore()
        
        // Create old draft at 2025-01-15 12:00:00Z
        val draftsAt0 = ChatDrafts(store) { Instant.parse("2025-01-15T12:00:00Z") }
        draftsAt0.put("old", "created at 2025-01-15")
        
        // Create new draft at 2025-02-14 12:00:00Z (30 days later)
        val draftsAt30 = ChatDrafts(store) { Instant.parse("2025-02-14T12:00:00Z") }
        draftsAt30.put("new", "created at 2025-02-14")
        
        // Prune at 2025-02-15 12:00:00Z (31 days after "old", 1 day after "new").
        val draftsAt31 = ChatDrafts(store) { Instant.parse("2025-02-15T12:00:00Z") }
        draftsAt31.prune()
        
        assertEquals("", draftsAt31.get("old")) // Pruned after 31 days
        assertEquals("created at 2025-02-14", draftsAt31.get("new")) // Still recent
    }
}
