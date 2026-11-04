package io.github.justcallmesu.aristotle.data.chat

import io.github.justcallmesu.aristotle.data.HermesJson
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Claude plan limits from `model.options` usage (TY6; `inventory.py::_apply_usage`). */
class PlanUsageTest {
    private fun answer(usage: String?) = HermesJson.parseToJsonElement(
        """{"model":"m","provider":"anthropic","providers":[
            {"slug":"openai-codex","name":"Codex","models":["x"],"usage":{"windows":[{"label":"Current week","used_percent":99}]}},
            {"slug":"anthropic","name":"Anthropic","models":["claude-opus"]${usage?.let { ",\"usage\":$it" } ?: ""}}]}""",
    )

    private val win = """{"windows":[
        {"label":"Sonnet week","used_percent":12.5,"resets_at":"2026-11-09T09:00:00+07:00","scope":"model"},
        {"label":"Current session","used_percent":42,"resets_at":"2026-11-04T19:10:00+00:00","scope":"account"},
        {"label":"Fable week","used_percent":80,"scope":"model"},
        {"label":"Current week","used_percent":130,"resets_at":null},
        {"label":"Opus week","used_percent":"bad"}]}"""

    @Test
    fun windowsAreTheFourKnownOnesInOrderAndClamped() {
        val plan = PlanUsage.of(answer(win))
        assertTrue(plan.present)
        assertEquals(listOf("Current session", "Current week", "Sonnet week"), plan.windows.map { it.label })
        assertEquals(42.0, plan.windows[0].usedPercent, 0.0)
        assertEquals(100.0, plan.windows[1].usedPercent, 0.0)
        assertNull(plan.windows[1].resetsAt)
        assertEquals(Instant.parse("2026-11-09T02:00:00Z"), plan.windows[2].resetsAt)
        assertEquals("model", plan.windows[2].scope)
    }

    @Test
    fun otherProvidersUsageIsNotRead() {
        assertTrue(PlanUsage.of(answer(null)).windows.isEmpty())
        assertTrue(PlanUsage.of(answer(null)).present)
        assertFalse(PlanUsage.of(HermesJson.parseToJsonElement("""{"providers":[{"slug":"openai","models":["x"]}]}""")).present)
        assertFalse(PlanUsage.of(null).present)
    }

    @Test
    fun catalogCarriesThePlan() {
        assertEquals(3, ModelCatalog.of(answer(win)).plan.windows.size)
    }

    @Test
    fun poolShowsTheFirstReadyAccountWithNumbers() {
        val plan = PlanUsage.of(answer("""{"windows":[],"accounts":[
            {"id":"a","label":"","state":"unknown","windows":[]},
            {"id":"b","label":"Work","state":"limited","windows":[{"label":"Current week","used_percent":100}]},
            {"id":"c","label":"Home","state":"ready","windows":[{"label":"Current week","used_percent":10}]}]}"""))
        assertEquals("Home", plan.account)
        assertEquals(3, plan.accounts)
        assertEquals(10.0, plan.windows.single().usedPercent, 0.0)
        val none = PlanUsage.of(answer("""{"accounts":[{"id":"a","state":"unknown","windows":[]}]}"""))
        assertTrue(none.windows.isEmpty())
        assertEquals(1, none.accounts)
    }

    private val now = Instant.parse("2026-11-04T10:00:00Z")
    private val zone = ZoneId.of("Asia/Jakarta")

    private fun reset(at: String?) = PlanLimitsLogic.reset(at?.let(Instant::parse), now, zone, Locale.ENGLISH)

    @Test
    fun resetFormatting() {
        assertEquals(PlanReset.Unknown, reset(null))
        assertEquals(PlanReset.Passed, reset("2026-11-04T10:00:00Z"))
        assertEquals(PlanReset.In(2, 10), reset("2026-11-04T12:10:00Z"))
        assertEquals(PlanReset.In(0, 45), reset("2026-11-04T10:45:00Z"))
        assertEquals(PlanReset.In(0, 1), reset("2026-11-04T10:00:20Z"))
        assertEquals(PlanReset.In(23, 59), reset("2026-11-05T09:59:00Z"))
        // 2026-11-09 is a Monday; 02:00Z = 09:00 in Jakarta.
        assertEquals(PlanReset.At("Mon 09:00"), reset("2026-11-09T02:00:00Z"))
    }

    @Test
    fun staleAndEmptyHandling() {
        val full = PlanUsage.of(answer(win))
        assertTrue(PlanLimitsLogic.visible(full, 0, PlanUsage.STALE_MS - 1))
        assertFalse(PlanLimitsLogic.visible(full, 0, PlanUsage.STALE_MS))
        val empty = PlanUsage.of(answer(null))
        assertFalse(PlanLimitsLogic.visible(empty, 0, 0))
        assertTrue(PlanLimitsLogic.loading(empty))
        assertFalse(PlanLimitsLogic.loading(full))
        assertFalse(PlanLimitsLogic.loading(PlanUsage.NONE))
        assertTrue(PlanLimitsLogic.shouldRetry(empty, retried = false))
        assertFalse(PlanLimitsLogic.shouldRetry(empty, retried = true))
        assertFalse(PlanLimitsLogic.shouldRetry(full, retried = false))
    }
}
