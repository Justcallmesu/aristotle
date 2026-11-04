package io.github.justcallmesu.aristotle.ui.chat

import io.github.justcallmesu.aristotle.data.chat.ModelGroup
import io.github.justcallmesu.aristotle.ui.components.shouldCommit
import io.github.justcallmesu.aristotle.ui.components.sliderIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelFilterTest {
    private fun group(slug: String, vararg models: String) =
        ModelGroup(slug, slug.replaceFirstChar { it.uppercase() }, emptyList(), models.toList(), emptySet(), emptyMap(), null)

    private val groups = listOf(
        group("anthropic", "claude-opus-5-5", "claude-sonnet-5-5"),
        group("openai", "gpt-5", "o4-mini"),
        group("google", "gemini-3-pro"),
    )

    @Test fun noQueryOpensOnlyTheSessionsProvider() {
        val views = ModelFilter.views(groups, "  ", "openai", emptySet())
        assertEquals(listOf(false, true, false), views.map { it.expanded })
        assertTrue(views.none { it.filtered })
        assertEquals(listOf("gpt-5", "o4-mini"), views[1].shown)
    }

    @Test fun toggleFlipsTheDefault() {
        val views = ModelFilter.views(groups, "", "openai", setOf("openai", "google"))
        assertEquals(listOf(false, false, true), views.map { it.expanded })
    }

    @Test fun searchIsCaseInsensitiveAcrossProvidersAndOpensHits() {
        val views = ModelFilter.views(groups, "CLAUDE", "openai", emptySet())
        assertEquals(listOf("anthropic"), views.map { it.group.slug })
        assertTrue(views[0].expanded && views[0].filtered)
        assertEquals(2, views[0].shown.size)
        val some = ModelFilter.views(groups, "o", "google", emptySet())
        assertEquals(listOf("claude-opus-5-5", "o4-mini"), some.flatMap { it.shown }.filter { it in listOf("claude-opus-5-5", "o4-mini") })
        assertEquals(1, ModelFilter.views(groups, "o4", null, emptySet()).single().shown.size)
    }

    @Test fun noHitIsEmpty() {
        assertTrue(ModelFilter.views(groups, "zzz", "openai", emptySet()).isEmpty())
    }

    @Test fun matchRangeAndCount() {
        assertEquals(7..10, ModelFilter.matchRange("claude-opus-5-5", "OPUS"))
        assertNull(ModelFilter.matchRange("gpt-5", "opus"))
        assertNull(ModelFilter.matchRange("gpt-5", " "))
        assertEquals(5, ModelFilter.modelCount(groups))
    }

    @Test fun currentRowCountsHeadersOfOpenAndClosedGroups() {
        val views = ModelFilter.views(groups, "", "openai", emptySet())
        // anthropic header (0, closed), openai header (1), gpt-5 (2), o4-mini (3)
        assertEquals(3, ModelFilter.currentRow(views, "openai", "o4-mini"))
        assertNull(ModelFilter.currentRow(views, "anthropic", "claude-opus-5-5"))
        assertNull(ModelFilter.currentRow(views, "openai", null))
        val open = ModelFilter.views(groups, "", "openai", setOf("anthropic"))
        assertEquals(5, ModelFilter.currentRow(open, "openai", "o4-mini"))
    }

    @Test fun revealMovesLeastAmount() {
        assertNull(ModelFilter.revealIndex(0, 7, 8, 5))
        assertEquals(2, ModelFilter.revealIndex(4, 10, 7, 2))
        assertEquals(13, ModelFilter.revealIndex(0, 7, 8, 20))
    }

    @Test fun oneGestureCommitsOnePick() {
        val picks = mutableListOf<String>()
        val levels = listOf("off", "minimal", "low", "medium", "high")
        val current = "low"
        var index = levels.indexOf(current)
        // a drag over several ticks, then release: only the end commits
        for (v in listOf(2.4f, 2.9f, 3.2f, 3.6f, 4.0f)) index = sliderIndex(v, levels.size)
        if (shouldCommit(index, levels.indexOf(current))) picks += levels[index]
        assertEquals(listOf("high"), picks)
        // a drag that returns to where it began commits nothing
        index = sliderIndex(2.2f, levels.size)
        assertFalse(shouldCommit(index, levels.indexOf(current)))
    }
}
