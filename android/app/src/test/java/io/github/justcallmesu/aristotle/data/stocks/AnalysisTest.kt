package io.github.justcallmesu.aristotle.data.stocks

import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The analyzer's phone side, pure (TA2): the server's JSON (fixtures shaped as `server/internal/stocks/analyzer.go`
 * marshals them, values from its `analyzer_test.go` / `verdict_test.go`), the section's state machine and stale-day rule,
 * the price percents, the error-code mapping and the language choice.
 */
class AnalysisTest {
    private fun fixture(name: String): String = javaClass.classLoader!!.getResource("stocks/$name")!!.readText()

    private val thursday = LocalDate.parse("2026-11-05")

    @Test
    fun aDoneJobParsesWithItsKeptVerdict() {
        val status = StocksParser.analysis(fixture("analysis_done.json"))!!
        assertEquals(AnalysisStatus.DONE, status.state)
        assertEquals("an_0123456789ab", status.jobId)
        assertNull(status.error)
        val r = status.result!!
        assertEquals(Verdict.BuyToday, Verdict.of(r.verdict))
        assertEquals(Confidence.Medium, Confidence.of(r.confidence))
        assertEquals(listOf(1595L, 1600L, 1650L, 1570L), listOf(r.entryLow, r.entryHigh, r.target, r.stop))
        assertEquals("2026-11-05", r.sessionDate)
        assertEquals("claude-opus-5-5", r.model)
        assertEquals(48_213L, r.durationMs)
        assertEquals(3, r.reasons.size)
        assertEquals(listOf("news:1", "ind:trend"), r.reasons[2].sources)
        assertEquals("TSTA laba naik", r.news.single().title)
        assertEquals(1600L, r.price)
        assertTrue(r.flags.isEmpty())
    }

    @Test
    fun aHeldFailedJobKeepsTheLastVerdict() {
        val status = StocksParser.analysis(fixture("analysis_held_failed.json"))!!
        assertEquals(AnalysisStatus.FAILED, status.state)
        assertEquals("invalid_verdict", status.error)
        val r = status.result!!
        assertTrue(r.held)
        assertTrue(r.repaired)
        assertEquals(Verdict.Hold, Verdict.of(r.verdict))
        assertNull(r.entryLow)
        assertNull(r.entryHigh)
        assertEquals(listOf("target_above_r1", "market_closed"), r.flags)
    }

    @Test
    fun runningIdleAndNullListsParse() {
        assertTrue(StocksParser.analysis(fixture("analysis_running.json"))!!.running)
        val idle = StocksParser.analysis(fixture("analysis_idle.json"))!!
        assertEquals(AnalysisStatus.IDLE, idle.state)
        assertNull(idle.result)
        // Go writes nil slices as null: they read as empty, the result is still read.
        val bare = StocksParser.analysis(fixture("analysis_bare.json"))!!.result!!
        assertTrue(bare.reasons.isEmpty() && bare.flags.isEmpty() && bare.news.isEmpty())
        assertNull(Verdict.of(bare.verdict))
        assertNull(StocksParser.analysis("not json"))
    }

    @Test
    fun aStartedJobParsesAndTheApiSendsLangAndAccepts202() = kotlinx.coroutines.runBlocking {
        val sent = mutableListOf<StocksRequest>()
        val api = StocksApi { request ->
            sent += request
            StocksReply.Http(202, """{"job_id":"an_0123456789ab","state":"running","started_at":"2026-11-05T10:00:00+07:00"}""")
        }
        val started = (api.analyze("TSTA", AnalysisLang.ID) as StocksResult.Ok).value
        assertEquals("an_0123456789ab", started.jobId)
        assertEquals("2026-11-05T10:00:00+07:00", started.startedAt)
        assertEquals(StocksRequest("POST", "analyze/TSTA", body = """{"lang":"id"}"""), sent.single())
    }

    @Test
    fun theStateMachine() {
        val done = StocksParser.analysis(fixture("analysis_done.json"))!!
        val failed = StocksParser.analysis(fixture("analysis_held_failed.json"))!!
        val running = StocksParser.analysis(fixture("analysis_running.json"))!!
        val idle = StocksParser.analysis(fixture("analysis_idle.json"))!!
        assertEquals(AnalysisPhase.Unknown, AnalysisStates.view(null, false, null, thursday).phase)
        assertEquals(AnalysisPhase.Idle, AnalysisStates.view(idle, false, null, thursday).phase)
        assertEquals(AnalysisPhase.Done, AnalysisStates.view(done, false, null, thursday).phase)
        assertNull(AnalysisStates.view(done, false, null, thursday).staleFrom)
        val run = AnalysisStates.view(running, false, null, thursday)
        assertEquals(AnalysisPhase.Running, run.phase)
        assertEquals("2026-11-05T10:00:00+07:00", run.startedAt)
        val fail = AnalysisStates.view(failed, false, null, thursday)
        assertEquals(AnalysisPhase.Failed, fail.phase)
        assertEquals(AnalysisProblem.InvalidVerdict, fail.problem)
        assertEquals("a failed job keeps the last verdict", Verdict.Hold.wire, fail.result!!.verdict)
        // A refused start shows over a kept verdict; a start on its way shows over everything.
        val refused = AnalysisStates.view(done, false, AnalysisProblem.Busy, thursday)
        assertEquals(AnalysisPhase.Failed, refused.phase)
        assertEquals(AnalysisProblem.Busy, refused.problem)
        assertEquals(done.result, refused.result)
        assertEquals(AnalysisPhase.Starting, AnalysisStates.view(done, true, AnalysisProblem.Busy, thursday).phase)
        // A done job without a result (cannot happen today) is idle, not an empty card.
        assertEquals(AnalysisPhase.Idle, AnalysisStates.view(done.copy(result = null), false, null, thursday).phase)
    }

    @Test
    fun theStaleDayRule() {
        val done = StocksParser.analysis(fixture("analysis_done.json"))!! // session 2026-11-05, made Thu 10:00
        val friday = LocalDate.parse("2026-11-06")
        val stale = AnalysisStates.view(done, false, null, friday)
        assertEquals(AnalysisPhase.Done, stale.phase)
        assertEquals("the day it was made", thursday, stale.staleFrom)
        // Which session is current: open / break = today; closed = the next open's day; unknown = today.
        val open = MarketStatus(MarketStatus.OPEN, 1, "2026-11-05T12:00:00+07:00")
        assertEquals(thursday, AnalysisStates.currentSession(open, thursday))
        assertEquals(thursday, AnalysisStates.currentSession(MarketStatus(MarketStatus.BREAK, nextOpen = "2026-11-05T13:30:00+07:00"), thursday))
        val evening = MarketStatus(MarketStatus.CLOSED, nextOpen = "2026-11-06T09:00:00+07:00")
        assertEquals(friday, AnalysisStates.currentSession(evening, thursday))
        assertTrue("Thursday's verdict is stale on Thursday evening", AnalysisStates.isStale(done.result!!, AnalysisStates.currentSession(evening, thursday)))
        val earlyMorning = MarketStatus(MarketStatus.CLOSED, nextOpen = "2026-11-05T09:00:00+07:00")
        assertEquals(thursday, AnalysisStates.currentSession(earlyMorning, thursday))
        assertEquals(thursday, AnalysisStates.currentSession(null, thursday))
        assertEquals("a next open in the past is not trusted", thursday, AnalysisStates.currentSession(MarketStatus(MarketStatus.CLOSED, nextOpen = "2026-11-04T09:00:00+07:00"), thursday))
        // A verdict made after the close is for the next session: not stale that evening nor the next day.
        val forFriday = done.result!!.copy(sessionDate = "2026-11-06", analyzedAt = "2026-11-05T17:00:00+07:00")
        assertFalse(AnalysisStates.isStale(forFriday, friday))
        assertTrue("an unreadable date is never shown as today's", AnalysisStates.isStale(forFriday.copy(sessionDate = ""), friday))
    }

    @Test
    fun elapsedAndDuration() {
        val start = "2026-11-05T10:00:00+07:00"
        val at = OffsetDateTime.parse(start).toInstant().toEpochMilli()
        assertEquals(42L, AnalysisStates.elapsedSeconds(start, at + 42_900))
        assertEquals("a PC clock ahead of the phone is never negative", 0L, AnalysisStates.elapsedSeconds(start, at - 5_000))
        assertNull(AnalysisStates.elapsedSeconds(null, at))
        assertEquals("0:42", AnalysisStates.elapsedText(42))
        assertEquals("1:05", AnalysisStates.elapsedText(65))
        assertEquals("12:00", AnalysisStates.elapsedText(720))
        assertEquals(48L, AnalysisStates.durationSeconds(48_213))
        assertEquals(49L, AnalysisStates.durationSeconds(48_500))
        assertEquals(1L, AnalysisStates.durationSeconds(0))
    }

    @Test
    fun pricePercents() {
        assertEquals(3.125, VerdictLevels.pctFrom(1650, 1600)!!, 1e-9)
        assertEquals("+3,13\u00A0%", VerdictLevels.pctText(1650, 1600))
        assertEquals("\u22121,88\u00A0%", VerdictLevels.pctText(1570, 1600))
        assertEquals("0,00\u00A0%", VerdictLevels.pctText(1600, 1600))
        assertNull(VerdictLevels.pctFrom(1600, 0))
        assertEquals("1.595\u20131.600", VerdictLevels.zone(1595, 1600))
        assertEquals("1.600", VerdictLevels.zone(1600, 1600))
        assertNull(VerdictLevels.zone(null, 1600))
        assertEquals(listOf("\u22120,31\u00A0%", "0,00\u00A0%"), VerdictLevels.zonePct(1595, 1600, 1600))
        assertEquals(listOf("0,00\u00A0%"), VerdictLevels.zonePct(1600, 1600, 1600))
        val r = StocksParser.analysis(fixture("analysis_done.json"))!!.result!!
        assertEquals("the live quote's price first", 1620L, VerdictLevels.reference(r, StockQuote("TSTA", price = 1620)))
        assertEquals(1600L, VerdictLevels.reference(r, null))
    }

    @Test
    fun errorCodes() {
        fun post(status: Int, code: String?) = AnalysisProblem.ofPost(StocksError.Server(status, code))
        assertEquals(AnalysisProblem.HermesUnavailable, post(503, "hermes_unavailable"))
        assertEquals(AnalysisProblem.NotSetUp, post(503, "analyzer_not_set_up"))
        assertEquals(AnalysisProblem.ShuttingDown, post(503, "shutting_down"))
        assertEquals(AnalysisProblem.NoData, post(422, "no_data"))
        assertEquals(AnalysisProblem.AlreadyRunning, post(409, "analysis_running"))
        assertEquals(AnalysisProblem.Busy, post(429, "busy"))
        assertEquals(AnalysisProblem.AlreadyRunning, post(409, null))
        assertEquals(AnalysisProblem.Busy, post(429, null))
        val blocked = StocksError.Server(503, StocksError.UPSTREAM_BLOCKED, 60)
        assertEquals(AnalysisProblem.Request(blocked), AnalysisProblem.ofPost(blocked))
        assertEquals(AnalysisProblem.Request(StocksError.BadAnswer), AnalysisProblem.ofPost(StocksError.BadAnswer))
        val jobs = mapOf(
            "timeout" to AnalysisProblem.Timeout,
            "invalid_verdict" to AnalysisProblem.InvalidVerdict,
            "verdict_rejected" to AnalysisProblem.InvalidVerdict,
            "model_error" to AnalysisProblem.ModelError,
            "tools_present" to AnalysisProblem.ToolsPresent,
            "unexpected_prompt" to AnalysisProblem.UnexpectedPrompt,
            "interrupted" to AnalysisProblem.Interrupted,
            "hermes_lost" to AnalysisProblem.HermesUnavailable,
            "hermes_refused" to AnalysisProblem.HermesRefused,
            "data_unavailable" to AnalysisProblem.NoData,
            "analyzer_not_set_up" to AnalysisProblem.NotSetUp,
            "something_new" to AnalysisProblem.Job("something_new"),
        )
        jobs.forEach { (code, want) -> assertEquals(code, want, AnalysisProblem.ofJob(code)) }
        assertEquals(AnalysisProblem.Job(null), AnalysisProblem.ofJob(null))
    }

    @Test
    fun everyServerFlagIsKnown() {
        // protocol §20.1 `flags`, TA1b added news_only.
        val server = listOf("low_reward_risk", "wide_stop", "entry_far", "target_above_r1", "market_closed", "limits_unknown", "news_only")
        assertEquals(server, VerdictFlag.entries.map { it.wire })
        assertEquals(VerdictFlag.NewsOnly, VerdictFlag.of("news_only"))
        assertNull(VerdictFlag.of("something_new"))
        val newsOnly = StocksParser.analysis(fixture("analysis_news_only.json"))!!.result!!
        assertEquals(listOf(VerdictFlag.NewsOnly), newsOnly.flags.map(VerdictFlag::of))
        assertEquals("the server caps it", Confidence.Medium, Confidence.of(newsOnly.confidence))
    }

    @Test
    fun langFollowsThePhone() {
        assertEquals("id", AnalysisLang.of(Locale.forLanguageTag("id-ID")))
        assertEquals("id", AnalysisLang.of(Locale("in", "ID")))
        assertEquals("en", AnalysisLang.of(Locale.US))
        assertEquals("en", AnalysisLang.of(Locale.forLanguageTag("ms-MY")))
        assertEquals("en", AnalysisLang.of(Locale.ROOT))
    }

    @Test
    fun sourcesResolve() {
        val news = listOf(AnalysisNews("news:1", "cnbc", "TSTA laba naik", "https://x/1", "2026-11-05T08:00:00+07:00"))
        assertEquals(VerdictSource.News("news:1", news.single()), VerdictSources.of("news:1", news))
        assertEquals(VerdictSource.News("news:9", null), VerdictSources.of("news:9", news))
        assertEquals(VerdictSource.Known("ind:rsi14", SourceLabel.Rsi14), VerdictSources.of("ind:rsi14", news))
        assertEquals(VerdictSource.Known("lvl:s2", SourceLabel.Support2), VerdictSources.of("lvl:s2", news))
        assertEquals(VerdictSource.Known("hold:pl_pct", SourceLabel.HoldProfit), VerdictSources.of("hold:pl_pct", news))
        assertEquals("macd", (VerdictSources.of("ind:macd", news) as VerdictSource.Other).text)
        assertEquals("every label id is distinct", SourceLabel.entries.size, SourceLabel.entries.map { it.id }.toSet().size)
    }
}
