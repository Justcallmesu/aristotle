package io.github.justcallmesu.aristotle.data.stocks

import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** TBA2: the brief as the server writes it (§20.4), the home card's states, and the cited ids drawn from `refs` only. */
class BriefTest {
    private val today: LocalDate = LocalDate.parse("2026-11-05")
    private val morning = LocalTime.of(8, 0)
    private val afternoon = LocalTime.of(14, 0)
    private val body: BriefBody get() = StocksParser.decode(BRIEF_JSON, BriefBody.serializer())!!

    @Test
    fun `the brief body parses with its result, refs and headlines`() {
        val b = body
        assertEquals("2026-11-05", b.date)
        assertEquals(BriefBody.DONE, b.state)
        assertEquals("claude-opus-5-5", b.model)
        val r = b.result!!
        assertEquals("Banks lead into the open", r.headline)
        assertEquals(listOf("BBCA", "TLKM"), r.focus.map { it.code })
        assertEquals(BriefStance.WatchBuy, BriefStance.of(r.focus[0].stance))
        assertEquals(listOf("news:1"), r.news.single().sources)
        assertEquals("news:1", b.news.single().id)
        val list = StocksParser.decode("""{"briefs":[{"date":"2026-11-05","state":"done","late":false,"headline":"Banks"},{"date":"2026-11-04","state":"missed","late":false,"headline":null}]}""", BriefsBody.serializer())!!
        assertEquals(listOf("2026-11-05", "2026-11-04"), list.briefs.map { it.date })
        assertNull(list.briefs[1].headline)
    }

    @Test
    fun `cited ids render the stored numbers, never text from the model`() {
        val b = body
        assertEquals(BriefCite("lvl:BBCA:r1", "BBCA", BriefCiteKind.Resistance1, "9.950"), BriefRefs.cite("lvl:BBCA:r1", b))
        assertEquals("9.700", BriefRefs.cite("lvl:BBCA:s1", b).value)
        assertEquals("38,2", BriefRefs.cite("ind:BBCA:rsi14", b).value)
        assertEquals("\u22121,24\u00A0%", BriefRefs.cite("ind:BBCA:change_pct", b).value)
        assertEquals("+2,30\u00A0%", BriefRefs.cite("hold:BBCA:pl_pct", b).value)
        assertEquals(listOf("up"), BriefRefs.cite("ind:BBCA:trend", b).words)
        assertEquals("10.500", BriefRefs.cite("lvl:BBCA:ara", b).value)
        val verdict = BriefRefs.cite("vd:BBCA", b)
        assertEquals(BriefCiteKind.Verdict, verdict.kind)
        assertEquals(listOf("buy_today", "med", "auto"), verdict.words)
        val screen = BriefRefs.cite("scr:TLKM", b)
        assertEquals("5,2", screen.value)
        assertEquals(listOf("breakout:up", "volume_spike:up"), screen.words)
        assertEquals("BBCA rises", BriefRefs.cite("news:1", b).news?.title)
        assertNull("a headline the brief does not carry", BriefRefs.cite("news:9", b).news)
        // An id with no stored value shows its label only; an id this app does not know is kept as Other.
        assertNull(BriefRefs.cite("lvl:BBCA:pivot", b).value)
        assertEquals(BriefCiteKind.Other, BriefRefs.cite("ind:BBCA:atr14", b).kind)
        assertEquals(BriefCiteKind.Other, BriefRefs.cite("lvl:bad:r1", b).kind)
        assertEquals(listOf("lvl:BBCA:r1", "ind:BBCA:rsi14"), BriefRefs.cites(listOf("lvl:BBCA:r1", "ind:BBCA:rsi14", "lvl:BBCA:r1"), b).map { it.id })
    }

    @Test
    fun `the home card - ready, pending, running, failed, missed, no brief today`() {
        val b = body
        assertEquals(BriefCard.Ready("Banks lead into the open", late = false, running = false), BriefStates.card(b, false, today, morning, true, "08:30"))
        assertEquals(BriefCard.Ready("Banks lead into the open", late = false, running = true), BriefStates.card(b.copy(state = BriefBody.RUNNING), false, today, morning, true, "08:30"))
        assertTrue("a failed rerun keeps the day's brief", BriefStates.card(b.copy(state = BriefBody.FAILED, error = "busy"), false, today, morning, true, null) is BriefCard.Ready)
        val pending = BriefBody(date = "2026-11-05", state = BriefBody.PENDING)
        assertEquals(BriefCard.Pending("08:15"), BriefStates.card(pending, false, today, LocalTime.of(8, 29), true, "08:15"))
        assertEquals(BriefCard.Running, BriefStates.card(pending.copy(state = BriefBody.RUNNING), false, today, morning, true, null))
        assertEquals(BriefCard.Failed("invalid_brief"), BriefStates.card(pending.copy(state = BriefBody.FAILED, error = "invalid_brief"), false, today, morning, true, null))
        assertEquals(BriefCard.Missed, BriefStates.card(pending.copy(state = BriefBody.MISSED), false, today, afternoon, true, null))
        // An older latest: no brief today (a weekend, a holiday), or the job is off.
        val friday = b.copy(date = "2026-11-06")
        val saturday = LocalDate.parse("2026-11-07")
        assertEquals(BriefCard.NotTradingDay(LocalDate.parse("2026-11-06")), BriefStates.card(friday, false, saturday, morning, true, null))
        assertEquals(BriefCard.Off(LocalDate.parse("2026-11-06")), BriefStates.card(friday, false, saturday, morning, false, null))
        assertEquals(BriefCard.NotTradingDay(null), BriefStates.card(null, true, saturday, morning, true, null))
        assertEquals(BriefCard.Unknown, BriefStates.card(null, false, today, morning, null, null))
    }

    @Test
    fun `Q115 - until the jobs answer, an older brief is Unknown, never not a trading day`() {
        val older = body.copy(date = "2026-11-04")
        assertEquals(BriefCard.Unknown, BriefStates.card(older, false, today, morning, null, null))
        assertEquals(BriefCard.Unknown, BriefStates.card(null, true, today, morning, null, null))
        assertEquals("once the job is known the words follow", BriefCard.NotTradingDay(LocalDate.parse("2026-11-04")), BriefStates.card(older, false, today, morning, true, "08:30"))
        assertEquals(BriefCard.Off(LocalDate.parse("2026-11-04")), BriefStates.card(older, false, today, morning, false, "08:30"))
    }

    @Test
    fun `Q115 - a pending brief at or after 09-00 is no brief today (the job was turned on after its slot)`() {
        val pending = BriefBody(date = "2026-11-05", state = BriefBody.PENDING)
        assertEquals(BriefCard.Pending("08:30"), BriefStates.card(pending, false, today, LocalTime.of(8, 59), true, "08:30"))
        assertEquals(BriefCard.NoneToday, BriefStates.card(pending, false, today, LocalTime.of(9, 0), true, "08:30"))
        assertEquals(BriefCard.NoneToday, BriefStates.card(pending, false, today, afternoon, true, "08:30"))
        assertEquals("a brief that is there stays ready", BriefCard.Ready("Banks lead into the open", late = true, running = false), BriefStates.card(body.copy(late = true), false, today, afternoon, true, "08:30"))
        assertEquals("a missed day keeps its own words", BriefCard.Missed, BriefStates.card(pending.copy(state = BriefBody.MISSED), false, today, afternoon, true, "08:30"))
        assertEquals(BriefCard.Running, BriefStates.card(pending.copy(state = BriefBody.RUNNING), false, today, afternoon, true, "08:30"))
    }

    @Test
    fun `stances read their wire words only`() {
        assertEquals(BriefStance.Avoid, BriefStance.of("avoid"))
        assertNull(BriefStance.of("buy"))
        assertFalse(BriefStance.entries.any { it.wire.isEmpty() })
    }

    companion object {
        const val BRIEF_JSON = """{"date":"2026-11-05","state":"done","late":false,"trigger":"scheduled","error":null,
            "started_at":"2026-11-05T08:30:02+07:00","finished_at":"2026-11-05T08:31:10+07:00","model":"claude-opus-5-5","lang":"en","repaired":false,
            "result":{"headline":"Banks lead into the open","summary":"BBCA holds above support; TLKM screened strong yesterday.",
              "focus":[{"code":"BBCA","stance":"watch_buy","note":"Near resistance with RSI mid-range.","sources":["lvl:BBCA:r1","ind:BBCA:rsi14"]},
                       {"code":"TLKM","stance":"watch","note":"Two setups fired at the close.","sources":["scr:TLKM"]}],
              "news":[{"text":"BBCA dividend talk.","sources":["news:1"]}],
              "risks":[{"text":"Rupiah weakness.","sources":["ind:BBCA:change_pct"]}]},
            "refs":{"ind:BBCA:close":9875,"ind:BBCA:change_pct":-1.24,"ind:BBCA:rsi14":38.24,"ind:BBCA:trend":"up",
              "lvl:BBCA:s1":9700,"lvl:BBCA:r1":9950,"lvl:BBCA:ara":10500,"lvl:BBCA:arb":9200,"hold:BBCA:pl_pct":2.3,
              "vd:BBCA":{"verdict":"buy_today","confidence":"med","session_date":"2026-11-05","analyzed_at":"2026-11-04T16:30:00+07:00","trigger":"auto"},
              "scr:TLKM":{"score":5.2,"setups":["breakout:up","volume_spike:up"]},"news:1":"BBCA rises"},
            "news":[{"id":"news:1","source":"cnbc","title":"BBCA rises","link":"https://example.com/a","published_at":"2026-11-05T07:10:00+07:00"}]}"""
    }
}
