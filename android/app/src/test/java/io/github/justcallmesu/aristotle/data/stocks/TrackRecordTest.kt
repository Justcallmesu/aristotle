package io.github.justcallmesu.aristotle.data.stocks

import io.github.justcallmesu.aristotle.data.ConnectionProblem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The AI track record on the phone (TR2, protocol §20.5, spec Q117-Q125, P16a): transport, rates, the card line, past calls, chips. */
class TrackRecordTest {
    private fun group(
        verdict: String,
        model: String = "opus",
        trigger: String = "manual",
        confidence: String = "med",
        n: Int = 0,
        win: Int = 0,
        loss: Int = 0,
        closed: Int = 0,
        notTriggered: Int = 0,
        right: Int = 0,
        wrong: Int = 0,
        wouldHave: Int = 0,
        noSession: Int = 0,
        unscorable: Int = 0,
        resultN: Int = 0,
        resultSum: Double = 0.0,
        info3dN: Int = 0,
        info3dSum: Double = 0.0,
    ) = TrackGroup(verdict, model, trigger, confidence, n, win, loss, closed, notTriggered, right, wrong, wouldHave, noSession, unscorable, resultN, resultSum, info3dN, info3dSum)

    private fun record(verdict: Verdict, vararg groups: TrackGroup, filters: TrackFilters = TrackFilters()): VerdictRecord =
        TrackRecords.byVerdict(TrackRecords.filter(groups.toList(), filters)).first { it.verdict == verdict }

    // ---- transport -------------------------------------------------------------------------------------------

    private val body = """
        {"summary":[{"verdict":"buy_today","model":"opus","trigger":"manual","confidence":"high","n":5,"win":2,"loss":1,"closed":1,"not_triggered":1,"right":0,"wrong":0,"would_have":0,"no_session":0,"unscorable":0,"result_n":4,"result_sum":3.5,"result_avg":0.88,"info_3d_n":2,"info_3d_sum":1.0,"info_3d_avg":0.5,"future_field":1}],
         "calls":[
          {"id":"vr_1","code":"BBCA","held":false,"analyzed_at":"2026-11-05T10:00:00+07:00","session_date":"2026-11-05","market_state":"open","model":"opus","trigger":"manual","verdict":"buy_today","confidence":"high","entry_low":4800,"entry_high":4850,"target":5000,"stop":4700,"price":4840,"flags":[],"score":{"rule":1,"source":"5m","outcome":"win","result_pct":3.09,"close":4990,"info_3d_pct":null,"scored_at":"2026-11-05T17:00:10+07:00"}},
          {"id":"vr_2","code":"BBCA","analyzed_at":"2026-11-06T09:30:00+07:00","session_date":"2026-11-06","model":"sonnet","trigger":"auto","verdict":"wait","confidence":"low","score":null},
          {"id":"vr_3","code":"BBCA","analyzed_at":"2026-11-04T09:30:00+07:00","session_date":"2026-11-04","model":"sonnet","trigger":"auto","verdict":"hold","confidence":"low","score":{"rule":2,"source":"daily","outcome":"something_new","result_pct":null,"scored_at":null}}
         ],
         "scored_through":"2026-11-05","pending":1}
    """.trimIndent()

    @Test
    fun `the answer is read - unknown fields ignored, missing fields safe, an unknown outcome is other`() {
        val parsed = StocksParser.decode(body, TrackRecordBody.serializer())!!
        assertEquals(1, parsed.summary.size)
        assertEquals(4, parsed.summary[0].resultN)
        assertEquals(3.5, parsed.summary[0].resultSum, 1e-9)
        assertEquals(3, parsed.calls.size)
        assertEquals("2026-11-05", parsed.scoredThrough)
        assertEquals(1, parsed.pending)
        assertEquals(TrackOutcome.Win, parsed.calls[0].score!!.outcomeKind)
        assertEquals(3.09, parsed.calls[0].score!!.resultPct!!, 1e-9)
        assertNull(parsed.calls[1].score)
        assertEquals(TrackOutcome.Other, parsed.calls[2].score!!.outcomeKind)
        assertTrue(parsed.calls[2].score!!.daily)
        assertFalse(parsed.calls[0].score!!.daily)
    }

    @Test
    fun `an empty or bare answer is safe`() {
        val bare = StocksParser.decode("{}", TrackRecordBody.serializer())!!
        assertTrue(bare.summary.isEmpty() && bare.calls.isEmpty())
        assertNull(bare.scoredThrough)
        assertEquals(0, bare.pending)
        val nulls = StocksParser.decode("""{"summary":[],"calls":[],"scored_through":null,"pending":0}""", TrackRecordBody.serializer())!!
        assertNull(nulls.scoredThrough)
    }

    @Test
    fun `the call goes to track-record with the code only when given`() = runBlocking {
        val sent = mutableListOf<StocksRequest>()
        val api = StocksApi { request -> sent += request; StocksReply.Http(200, body) }
        assertTrue(api.trackRecord() is StocksResult.Ok)
        val one = api.trackRecord("BBCA")
        assertEquals(StocksRequest("GET", "track-record"), sent[0])
        assertEquals(StocksRequest("GET", "track-record", mapOf("code" to "BBCA")), sent[1])
        assertEquals(3, (one as StocksResult.Ok).value.calls.size)
    }

    @Test
    fun `a bad body and an unreachable PC are errors, not crashes`() = runBlocking {
        val bad = StocksApi { StocksReply.Http(200, "not json") }
        assertEquals(StocksResult.Failed(StocksError.BadAnswer), bad.trackRecord())
        val down = StocksApi { StocksReply.Failed(ConnectionProblem.Unreachable) }
        assertEquals(StocksResult.Failed(StocksError.Unreachable(ConnectionProblem.Unreachable)), down.trackRecord())
        val badCode = StocksApi { StocksReply.Http(400, """{"error":"bad_code"}""") }
        assertEquals(StocksResult.Failed(StocksError.Server(400, "bad_code")), badCode.trackRecord("x"))
    }

    // ---- rates -----------------------------------------------------------------------------------------------

    @Test
    fun `buy today - the rate is win over win plus loss plus closed, not triggered stays outside`() {
        val r = record(Verdict.BuyToday, group("buy_today", n = 20, win = 6, loss = 3, closed = 3, notTriggered = 8, resultN = 12, resultSum = 6.0))
        assertEquals(12, r.decided)
        assertEquals(6, r.hits)
        assertEquals(8, r.notTriggered)
        assertTrue(r.hasRate)
        assertEquals(50, r.ratePct)
    }

    @Test
    fun `add - same rule as buy today`() {
        val r = record(Verdict.Add, group("add", n = 14, win = 2, loss = 7, closed = 1, notTriggered = 4))
        assertEquals(10, r.decided)
        assertEquals(2, r.hits)
        assertEquals(20, r.ratePct)
    }

    @Test
    fun `hold - right plus win over win plus loss plus right`() {
        val r = record(Verdict.Hold, group("hold", n = 12, win = 2, loss = 2, right = 8))
        assertEquals(12, r.decided)
        assertEquals(10, r.hits)
        assertEquals(83, r.ratePct)
    }

    @Test
    fun `avoid and sell - right over right plus wrong`() {
        val avoid = record(Verdict.Avoid, group("avoid", n = 10, right = 7, wrong = 3))
        assertEquals(10, avoid.decided)
        assertEquals(7, avoid.hits)
        assertEquals(70, avoid.ratePct)
        val sell = record(Verdict.Sell, group("sell", n = 11, right = 4, wrong = 6, noSession = 1))
        assertEquals(10, sell.decided)
        assertEquals(40, sell.ratePct)
    }

    @Test
    fun `wait has an average and never a rate`() {
        val r = record(Verdict.Wait, group("wait", n = 12, wouldHave = 12, resultN = 12, resultSum = 2.4))
        assertEquals(0, r.decided)
        assertFalse(r.hasRate)
        assertNull(r.ratePct)
        assertEquals(0.2, r.resultAvg!!, 1e-9)
    }

    @Test
    fun `the rate shows from 10 decided calls, not 9 (Q122)`() {
        val nine = record(Verdict.Avoid, group("avoid", n = 9, right = 6, wrong = 3))
        assertEquals(9, nine.decided)
        assertFalse(nine.hasRate)
        assertNull(nine.ratePct)
        assertNull("no average under the threshold", nine.resultAvg)
        val ten = record(Verdict.Avoid, group("avoid", n = 10, right = 6, wrong = 4, resultN = 10, resultSum = -5.0))
        assertTrue(ten.hasRate)
        assertEquals(60, ten.ratePct)
        assertEquals(-0.5, ten.resultAvg!!, 1e-9)
    }

    @Test
    fun `scored records that are not decided never count toward the threshold`() {
        // 9 decided + a lot of not triggered / unscorable / no session: still below 10.
        val r = record(Verdict.BuyToday, group("buy_today", n = 40, win = 3, loss = 3, closed = 3, notTriggered = 20, noSession = 6, unscorable = 5))
        assertEquals(9, r.decided)
        assertFalse(r.hasRate)
        assertEquals(11, r.notScored)
        val full = record(Verdict.BuyToday, group("buy_today", n = 40, win = 3, loss = 3, closed = 4, notTriggered = 20, noSession = 6, unscorable = 4))
        assertEquals(10, full.decided)
        assertEquals(30, full.ratePct)
    }

    @Test
    fun `filters sum the groups they keep`() {
        val groups = listOf(
            group("buy_today", model = "opus", trigger = "manual", confidence = "high", n = 6, win = 3, loss = 2, closed = 1, resultN = 6, resultSum = 3.0, info3dN = 4, info3dSum = 2.0),
            group("buy_today", model = "sonnet", trigger = "auto", confidence = "high", n = 8, win = 1, loss = 4, closed = 3, resultN = 8, resultSum = -4.0, info3dN = 2, info3dSum = -1.0),
            group("buy_today", model = "sonnet", trigger = "auto", confidence = "low", n = 5, win = 0, loss = 3, closed = 2, resultN = 5, resultSum = -5.0),
        )
        val all = record(Verdict.BuyToday, *groups.toTypedArray())
        assertEquals(19, all.scored)
        assertEquals(19, all.decided)
        assertEquals(4, all.hits)
        val sonnet = record(Verdict.BuyToday, *groups.toTypedArray(), filters = TrackFilters(model = "sonnet"))
        assertEquals(13, sonnet.decided)
        assertEquals(1, sonnet.hits)
        val sonnetHigh = record(Verdict.BuyToday, *groups.toTypedArray(), filters = TrackFilters(model = "sonnet", confidence = "high"))
        assertEquals(8, sonnetHigh.decided)
        assertFalse("8 decided is under the threshold", sonnetHigh.hasRate)
        assertTrue(TrackRecords.filter(groups, TrackFilters(model = "opus", trigger = "auto")).isEmpty())
    }

    @Test
    fun `averages are weighted by the records that have the value`() {
        val groups = arrayOf(
            group("buy_today", model = "opus", n = 10, win = 5, loss = 5, resultN = 10, resultSum = 10.0, info3dN = 2, info3dSum = 4.0),
            group("buy_today", model = "sonnet", n = 10, win = 5, loss = 5, notTriggered = 0, resultN = 2, resultSum = -2.0, info3dN = 8, info3dSum = 0.0),
        )
        val r = record(Verdict.BuyToday, *groups)
        // (10 - 2) / (10 + 2), not the mean of the two group means (1.0 and -1.0 -> 0.0).
        assertEquals(8.0 / 12.0, r.resultAvg!!, 1e-9)
        assertEquals(4.0 / 10.0, r.info3dAvg!!, 1e-9)
        val noValues = record(Verdict.BuyToday, group("buy_today", n = 10, win = 5, loss = 5))
        assertNull(noValues.resultAvg)
        assertNull(noValues.info3dAvg)
    }

    @Test
    fun `cards follow the verdict order, only for verdicts that have scored calls, and unknown verdicts are left out`() {
        val groups = listOf(group("sell", n = 1, right = 1), group("buy_today", n = 1, win = 1), group("future_verdict", n = 3), group("wait", n = 0))
        val cards = TrackRecords.byVerdict(groups)
        assertEquals(listOf(Verdict.BuyToday, Verdict.Sell), cards.map { it.verdict })
    }

    @Test
    fun `filter options come from what the groups hold and totals count every scored record`() {
        val groups = listOf(
            group("buy_today", model = "sonnet", trigger = "auto", confidence = "low", n = 3),
            group("avoid", model = "opus", trigger = "manual", confidence = "high", n = 4),
            group("wait", model = "opus", trigger = "manual", confidence = "med", n = 2),
        )
        val options = TrackRecords.options(groups)
        assertEquals(listOf("opus", "sonnet"), options.models)
        assertEquals(listOf("manual", "auto"), options.triggers)
        assertEquals(listOf("low", "med", "high"), options.confidences)
        assertEquals(9, TrackRecords.scored(groups))
        assertEquals(3, TrackRecords.scored(TrackRecords.filter(groups, TrackFilters(model = "sonnet"))))
    }

    @Test
    fun `model names are shown as the family`() {
        assertEquals("Opus", TrackRecords.modelName("claude-opus-4-8"))
        assertEquals("Sonnet", TrackRecords.modelName("Sonnet"))
        assertEquals("gpt-x", TrackRecords.modelName("gpt-x"))
        assertEquals(StockFormat.missing, TrackRecords.modelName(""))
    }

    // ---- the verdict-card line (Q124) ------------------------------------------------------------------------

    @Test
    fun `the card line counts a verdict type across every model and trigger`() {
        val groups = listOf(
            group("buy_today", model = "opus", trigger = "manual", n = 10, win = 3, loss = 4, closed = 1, notTriggered = 2),
            group("buy_today", model = "sonnet", trigger = "auto", n = 20, win = 5, loss = 9, closed = 2, notTriggered = 4),
        )
        assertEquals(RecordLine.Hits(Verdict.BuyToday, hit = 8, decided = 24), TrackRecords.line(groups, Verdict.BuyToday))
    }

    @Test
    fun `the card line per verdict type`() {
        val groups = listOf(
            group("add", n = 12, win = 4, loss = 6, closed = 2),
            group("hold", n = 22, win = 1, loss = 3, right = 18),
            group("avoid", n = 26, right = 16, wrong = 10),
            group("sell", n = 10, right = 5, wrong = 5),
            group("wait", n = 20, wouldHave = 20, resultN = 20, resultSum = 4.0),
        )
        assertEquals(RecordLine.Hits(Verdict.Add, 4, 12), TrackRecords.line(groups, Verdict.Add))
        assertEquals(RecordLine.Hits(Verdict.Hold, 19, 22), TrackRecords.line(groups, Verdict.Hold))
        assertEquals(RecordLine.Hits(Verdict.Avoid, 16, 26), TrackRecords.line(groups, Verdict.Avoid))
        assertEquals(RecordLine.Hits(Verdict.Sell, 5, 10), TrackRecords.line(groups, Verdict.Sell))
        assertEquals(RecordLine.WaitAverage(0.2), TrackRecords.line(groups, Verdict.Wait))
    }

    @Test
    fun `the card line is not enough calls below 10 decided, and none when the verdict has no scored call`() {
        val groups = listOf(group("avoid", n = 30, right = 4, wrong = 5, noSession = 21), group("wait", n = 9, wouldHave = 9, resultN = 9, resultSum = 1.0))
        assertEquals(RecordLine.Few(Verdict.Avoid, decided = 9), TrackRecords.line(groups, Verdict.Avoid))
        assertEquals(RecordLine.Few(Verdict.Wait, decided = 9), TrackRecords.line(groups, Verdict.Wait))
        assertEquals(RecordLine.None, TrackRecords.line(groups, Verdict.Sell))
        assertEquals(RecordLine.None, TrackRecords.line(emptyList(), Verdict.BuyToday))
    }

    // ---- past calls (collapse to 3 + Show more) ---------------------------------------------------------------

    private fun calls(n: Int) = (1..n).map { TrackCall(id = "vr_$it", code = "BBCA", analyzedAt = "2026-11-0${it}T10:00:00+07:00", verdict = "avoid") }

    @Test
    fun `past calls collapse to three and say how many are hidden`() {
        val view = PastCalls.view(calls(8), expanded = false)
        assertEquals(listOf("vr_1", "vr_2", "vr_3"), view.shown.map { it.id })
        assertEquals(5, view.hidden)
        assertTrue(view.canToggle)
        assertEquals(8, view.total)
    }

    @Test
    fun `expanding shows all and the toggle stays to collapse again`() {
        val view = PastCalls.view(calls(8), expanded = true)
        assertEquals(8, view.shown.size)
        assertEquals(0, view.hidden)
        assertTrue(view.canToggle)
    }

    @Test
    fun `three or fewer calls need no toggle, and none is an empty view`() {
        assertFalse(PastCalls.view(calls(3), expanded = false).canToggle)
        assertEquals(3, PastCalls.view(calls(3), expanded = true).shown.size)
        assertFalse(PastCalls.view(calls(3), expanded = true).canToggle)
        val none = PastCalls.view(emptyList(), expanded = false)
        assertTrue(none.shown.isEmpty())
        assertFalse(none.canToggle)
    }

    // ---- result chips -----------------------------------------------------------------------------------------

    private fun chip(verdict: String, outcome: String?, pct: Double? = null, source: String = "5m") =
        ResultChips.of(verdict, outcome?.let { TrackScore(rule = 1, source = source, outcome = it, resultPct = pct) })

    @Test
    fun `buy today and add chips`() {
        assertEquals(ResultChip(ChipKind.TargetHit, ChipTone.Gain, 3.7), chip("buy_today", "win", 3.7))
        assertEquals(ResultChip(ChipKind.StopHit, ChipTone.Loss, -2.4), chip("add", "loss", -2.4))
        assertEquals(ResultChip(ChipKind.ClosedFlat, ChipTone.Neutral, 0.4), chip("buy_today", "closed", 0.4))
        assertEquals(ResultChip(ChipKind.NotTriggered, ChipTone.Neutral, null), chip("buy_today", "not_triggered"))
    }

    @Test
    fun `avoid and sell chips read right or wrong, with the price move`() {
        assertEquals(ResultChip(ChipKind.Right, ChipTone.Gain, -2.5), chip("avoid", "right", -2.5))
        assertEquals(ResultChip(ChipKind.Wrong, ChipTone.Loss, 2.3), chip("sell", "wrong", 2.3))
    }

    @Test
    fun `hold, wait, pending and the rest`() {
        assertEquals(ResultChip(ChipKind.Held, ChipTone.Gain, null), chip("hold", "right", 0.8))
        assertEquals(ResultChip(ChipKind.StopHit, ChipTone.Loss, -3.0), chip("hold", "loss", -3.0))
        assertEquals(ResultChip(ChipKind.TargetHit, ChipTone.Gain, 4.0), chip("hold", "win", 4.0))
        assertEquals(ResultChip(ChipKind.IfBought, ChipTone.Neutral, -2.4), chip("wait", "would_have", -2.4))
        assertEquals(ResultChip(ChipKind.Pending, ChipTone.Neutral, null), chip("avoid", null))
        assertEquals(ResultChip(ChipKind.NoSession, ChipTone.Neutral, null), chip("avoid", "no_session"))
        assertEquals(ResultChip(ChipKind.Unscorable, ChipTone.Neutral, null), chip("avoid", "unscorable"))
        assertEquals(ResultChip(ChipKind.Other, ChipTone.Neutral, null), chip("avoid", "brand_new_outcome", 1.0))
    }

    @Test
    fun `a missing result percent leaves the chip with its word only`() {
        assertEquals(ResultChip(ChipKind.TargetHit, ChipTone.Gain, null), chip("buy_today", "win", null))
    }
}
