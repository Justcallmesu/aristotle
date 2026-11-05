package io.github.justcallmesu.aristotle.data.stocks

import io.github.justcallmesu.aristotle.data.HermesJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TSC3: the screener bodies of protocol §20.2-§20.3 read from the server's own JSON. The fixtures are the values the
 * server tests assert (`server/internal/stocks/screener_test.go`, `screener_setups_test.go`), in the shape its structs
 * encode them.
 */
class ScreenerParserTest {
    @Test
    fun `an intraday scan with a candidate and a bearish flag (TestScreenerScanJob)`() {
        val body = StocksParser.screener(ScreenerFixtures.INTRADAY)!!
        assertEquals(ScreenerMode.Intraday, body.screenerMode)
        assertEquals("2026-11-05", body.sessionDate)
        assertEquals("2026-11-05T09:45:00+07:00", body.dataAsOf)
        assertFalse(body.stale)
        assertEquals(MarketStatus.OPEN, body.market.state)
        assertEquals(ScreenerUniverse(2, "file", "2027-01-29", false), body.universe)
        assertEquals("ok", body.scan.status)
        assertEquals(3, body.scan.codesOk)
        assertEquals("2026-11-05T10:05:00+07:00", body.scan.nextRun)
        assertEquals(ScreenerSkipped(illiquid = 1, noBars = 0), body.skipped)

        val bbca = body.candidates.single()
        assertEquals("BBCA", bbca.code)
        assertEquals("Bank Central Asia Tbk", bbca.name)
        assertEquals(4980L, bbca.price)
        assertEquals(1.01, bbca.changePct!!, 1e-9)
        assertEquals(49.3, bbca.val20, 1e-9)
        assertEquals(6.2, bbca.score, 1e-9)
        assertFalse(bbca.atAra)
        assertEquals(listOf("breakout", "volume_spike", "gap"), bbca.setups.map { it.id })
        val breakout = bbca.setups[0].values
        assertEquals(4940L, breakout.high)
        assertEquals(20, breakout.lookback)
        assertEquals(2.2, breakout.volRatio!!, 1e-9)
        val gap = bbca.setups[2]
        assertEquals(2.43, gap.values.gapPct!!, 1e-9)
        assertEquals(5050L, gap.values.open)
        assertEquals(false, gap.values.filled)
        assertEquals("2026-11-05T09:45:00+07:00", gap.firstAt)

        val antm = body.flags.single()
        assertEquals("ANTM", antm.code)
        assertTrue(antm.inWatchlist)
        assertTrue(antm.setups.single().bearish)
        assertEquals(0.0, antm.score, 0.0) // flags carry no score
    }

    @Test
    fun `before the first bars run (TestScreenerNoBarsBeforeFirstBarsRun)`() {
        val body = StocksParser.screener(ScreenerFixtures.NO_BARS)!!
        assertEquals(ScreenerMode.Eod, body.screenerMode)
        assertNull(body.sessionDate)
        assertNull(body.dataAsOf)
        assertEquals(ScreenerScan.NO_BARS, body.scan.error)
        assertEquals("2026-11-05T16:20:00+07:00", body.scan.nextRun)
        assertTrue(body.candidates.isEmpty())
    }

    @Test
    fun `an EOD screen with the MA cross both ways (TestScreenerEODAfterBarsJob)`() {
        val body = StocksParser.screener(ScreenerFixtures.EOD)!!
        assertEquals(ScreenerMode.Eod, body.screenerMode)
        val c = body.candidates.single()
        assertEquals(7.2, c.score, 1e-9)
        assertEquals(5.0, c.changePct!!, 1e-9)
        assertEquals(listOf("breakout:up", "volume_spike:up", "ma_cross:up"), c.setups.map { "${it.id}:${it.dir}" })
        assertEquals("ma_cross:down", body.flags.single().setups.single().let { "${it.id}:${it.dir}" })
    }

    @Test
    fun `settings defaults as the server writes them, and a full PUT body`() {
        val s = StocksParser.screenerSettings(ScreenerFixtures.SETTINGS_DEFAULTS)!!
        assertEquals(ScreenerSettings(), s)
        // The PUT must carry every key (the server refuses a missing one): same key tree as the server's own encoding.
        val sent = HermesJson.parseToJsonElement(ScreenerSettingsJson.encode(s)).jsonObject
        val server = HermesJson.parseToJsonElement(ScreenerFixtures.SETTINGS_DEFAULTS).jsonObject
        assertEquals(keys(server), keys(sent))
        assertEquals(s, StocksParser.screenerSettings(ScreenerSettingsJson.encode(s)))
        // The server test's "good" body round-trips.
        val good = StocksParser.screenerSettings(ScreenerFixtures.SETTINGS_GOOD)!!
        assertEquals(0.0, good.minValueBn, 0.0)
        assertEquals(60, good.setups.breakout.lookback)
        assertFalse(good.setups.gap.down)
        assertEquals(keys(HermesJson.parseToJsonElement(ScreenerFixtures.SETTINGS_GOOD).jsonObject), keys(HermesJson.parseToJsonElement(ScreenerSettingsJson.encode(good)).jsonObject))
    }

    @Test
    fun `jobs, universe and the scan start`() {
        val jobs = StocksParser.jobs(ScreenerFixtures.JOBS)!!
        val scan = jobs.single { it.id == JobView.SCAN }
        assertEquals(10, scan.everyMin)
        assertNull(scan.at)
        assertEquals(listOf("on", "every_min"), scan.editable)
        val u = StocksParser.universe(ScreenerFixtures.UNIVERSE)!!
        assertEquals(UniverseBody.FILE, u.source)
        assertEquals(listOf("BBCA", "TLKM"), u.idx80)
        assertEquals(2, u.count)
        assertNull(u.fileError)
        val started = StocksParser.decode("""{"mode":"intraday","scan":{"running":true,"last_run":null,"next_run":null,"status":null,"error":null,"codes_ok":0,"codes_failed":0,"codes_stale":0}}""", ScanStarted.serializer())!!
        assertEquals("intraday", started.mode)
        assertTrue(started.scan.running)
    }

    @Test
    fun `calls go out as protocol 20_2 and 20_3 say`() = runBlocking {
        val sent = mutableListOf<StocksRequest>()
        val api = StocksApi { r ->
            sent += r
            when (r.path) {
                "screener/scan" -> StocksReply.Http(429, """{"error":"too_soon","retry_after_s":120}""", retryAfterSeconds = 120)
                "jobs/screener.scan" -> StocksReply.Http(200, """{"id":"screener.scan","on":true,"every_min":15}""")
                "universe" -> StocksReply.Http(200, ScreenerFixtures.UNIVERSE)
                "screener/settings" -> StocksReply.Http(200, ScreenerFixtures.SETTINGS_DEFAULTS)
                else -> StocksReply.Http(200, ScreenerFixtures.INTRADAY)
            }
        }
        api.screener()
        api.screener(ScreenerMode.Eod)
        val refused = api.scanNow() as StocksResult.Failed
        assertEquals(StocksError.Server(429, StocksError.TOO_SOON, 120), refused.error)
        assertEquals(15, (api.putJobEvery(JobView.SCAN, 15) as StocksResult.Ok).value.everyMin)
        api.putUniverse(listOf("BBCA"), listOf("TLKM"), "2027-01-29", null)
        api.resetUniverse()
        api.putScreenerSettings(ScreenerSettings())
        assertEquals(StocksRequest("GET", "screener"), sent[0])
        assertEquals(StocksRequest("GET", "screener", mapOf("mode" to "eod")), sent[1])
        assertEquals(StocksRequest("POST", "screener/scan"), sent[2])
        assertEquals(StocksRequest("PUT", "jobs/screener.scan", body = """{"every_min":15}"""), sent[3])
        assertEquals(StocksRequest("PUT", "universe", body = """{"lq45":["BBCA"],"idx80":["TLKM"],"valid_to":"2027-01-29"}"""), sent[4])
        assertEquals(StocksRequest("DELETE", "universe"), sent[5])
        assertEquals("PUT", sent[6].method)
        assertEquals("screener/settings", sent[6].path)
    }

    private fun keys(o: JsonObject, prefix: String = ""): Set<String> = o.entries.flatMap { (k, v) ->
        listOf(prefix + k) + ((v as? JsonObject)?.let { keys(it, "$prefix$k.") } ?: emptySet())
    }.toSet()
}

/** Server-shaped screener bodies (values from the server tests). */
internal object ScreenerFixtures {
    private const val SCAN_OK = """{"running":false,"last_run":"2026-11-05T09:55:10+07:00","next_run":"2026-11-05T10:05:00+07:00","status":"ok","error":null,"codes_ok":3,"codes_failed":0,"codes_stale":0}"""
    const val BBCA = """{"code":"BBCA","name":"Bank Central Asia Tbk","in_watchlist":false,"held":false,"price":4980,"change_pct":1.01,"val20":49.3,"score":6.2,"at_ara":false,"setups":[""" +
        """{"id":"breakout","dir":"up","date":"2026-11-05","first_at":"2026-11-05T09:45:00+07:00","values":{"high":4940,"lookback":20,"vol_ratio":2.2}},""" +
        """{"id":"volume_spike","dir":"up","date":"2026-11-05","first_at":"2026-11-05T09:45:00+07:00","values":{"vol_ratio":2.2}},""" +
        """{"id":"gap","dir":"up","date":"2026-11-05","first_at":"2026-11-05T09:45:00+07:00","values":{"gap_pct":2.43,"open":5050,"filled":false}}]}"""
    const val ANTM = """{"code":"ANTM","name":"Aneka Tambang Tbk","in_watchlist":true,"held":false,"price":3010,"change_pct":-2.9,"setups":[""" +
        """{"id":"gap","dir":"down","date":"2026-11-05","first_at":"2026-11-05T09:45:00+07:00","values":{"gap_pct":-3.23,"open":3000,"filled":false}}]}"""
    const val INTRADAY = """{"at":"2026-11-05T09:56:00+07:00","mode":"intraday","session_date":"2026-11-05","data_as_of":"2026-11-05T09:45:00+07:00","stale":false,""" +
        """"market":{"state":"open","session":1,"closes_at":"2026-11-05T12:00:00+07:00"},"universe":{"count":2,"source":"file","valid_to":"2027-01-29","expired":false},""" +
        """"scan":$SCAN_OK,"skipped":{"illiquid":1,"no_bars":0},"candidates":[$BBCA],"flags":[$ANTM]}"""
    /** A held code in both lists (screener_setups.go `rank`): a volume spike on a gap-down day, a candidate and a flag at once. */
    const val BOTH_LISTS = """{"at":"2026-11-05T10:06:00+07:00","mode":"intraday","session_date":"2026-11-05","data_as_of":"2026-11-05T09:55:00+07:00","stale":false,""" +
        """"market":{"state":"open","session":1,"closes_at":"2026-11-05T12:00:00+07:00"},"universe":{"count":2,"source":"file","valid_to":"2027-01-29","expired":false},""" +
        """"scan":$SCAN_OK,"skipped":{"illiquid":0,"no_bars":0},"candidates":[{"code":"BBRI","name":"Bank Rakyat Indonesia Tbk","in_watchlist":false,"held":true,""" +
        """"price":3900,"change_pct":-3.7,"val20":410.2,"score":2.5,"at_ara":false,"setups":[""" +
        """{"id":"volume_spike","dir":"up","date":"2026-11-05","first_at":"2026-11-05T09:55:00+07:00","values":{"vol_ratio":2.3}}]}],""" +
        """"flags":[{"code":"BBRI","name":"Bank Rakyat Indonesia Tbk","in_watchlist":false,"held":true,"price":3900,"change_pct":-3.7,"setups":[""" +
        """{"id":"gap","dir":"down","date":"2026-11-05","first_at":"2026-11-05T09:55:00+07:00","values":{"gap_pct":-2.94,"open":3930,"filled":false}}]}]}"""
    const val NO_BARS = """{"at":"2026-11-05T09:55:10+07:00","mode":"eod","session_date":null,"data_as_of":null,"stale":false,""" +
        """"market":{"state":"open","session":1,"closes_at":"2026-11-05T12:00:00+07:00"},"universe":{"count":2,"source":"file","valid_to":"2027-01-29","expired":false},""" +
        """"scan":{"running":false,"last_run":null,"next_run":"2026-11-05T16:20:00+07:00","status":null,"error":"no_bars","codes_ok":0,"codes_failed":0,"codes_stale":0},""" +
        """"skipped":{"illiquid":0,"no_bars":0},"candidates":[],"flags":[]}"""
    const val EOD = """{"at":"2026-11-05T16:21:00+07:00","mode":"eod","session_date":"2026-11-05","data_as_of":"2026-11-05T16:00:00+07:00","stale":false,""" +
        """"market":{"state":"closed","next_open":"2026-11-06T09:00:00+07:00"},"universe":{"count":2,"source":"file","valid_to":"2027-01-29","expired":false},""" +
        """"scan":{"running":false,"last_run":"2026-11-05T16:20:05+07:00","next_run":"2026-11-06T16:20:00+07:00","status":"ok","error":null,"codes_ok":3,"codes_failed":0,"codes_stale":0},""" +
        """"skipped":{"illiquid":0,"no_bars":0},"candidates":[{"code":"BBCA","name":"Bank Central Asia Tbk","in_watchlist":false,"held":false,"price":1050,"change_pct":5,"val20":100.5,"score":7.2,"at_ara":false,"setups":[""" +
        """{"id":"breakout","dir":"up","date":"2026-11-05","first_at":"2026-11-05T16:00:00+07:00","values":{"high":1010,"lookback":20,"vol_ratio":2}},""" +
        """{"id":"volume_spike","dir":"up","date":"2026-11-05","first_at":"2026-11-05T16:00:00+07:00","values":{"vol_ratio":2}},""" +
        """{"id":"ma_cross","dir":"up","date":"2026-11-05","first_at":"2026-11-05T16:00:00+07:00","values":{"ma20":1002.5,"ma50":1001}}]}],""" +
        """"flags":[{"code":"ANTM","name":"Aneka Tambang Tbk","in_watchlist":true,"held":false,"price":2900,"change_pct":-3.33,"setups":[""" +
        """{"id":"ma_cross","dir":"down","date":"2026-11-05","first_at":"2026-11-05T16:00:00+07:00","values":{"ma20":2995,"ma50":2998}}]}]}"""
    const val SETTINGS_DEFAULTS = """{"min_value_bn":5,"setups":{"breakout":{"lookback":20,"on":true,"vol_mult":1.5},"gap":{"down":true,"min_pct":2,"on":true,"up":true},""" +
        """"ma_cross":{"on":true},"rsi_rebound":{"level":30,"on":true},"volume_spike":{"mult":2,"on":true}}}"""
    const val SETTINGS_GOOD = """{"min_value_bn":0,"setups":{"breakout":{"on":false,"lookback":60,"vol_mult":5},"volume_spike":{"on":true,"mult":1.5},""" +
        """"ma_cross":{"on":false},"rsi_rebound":{"on":true,"level":45},"gap":{"on":true,"min_pct":0.5,"up":true,"down":false}}}"""
    const val JOBS = """{"jobs":[{"id":"bars.daily","on":true,"at":"16:20","every_min":null,"running":false,"last_due":null,"last_run":null,"last_status":null,"last_error":null,"late":false,"next_run":"2026-11-05T16:20:00+07:00","editable":["on","at"]},""" +
        """{"id":"screener.scan","on":true,"at":null,"every_min":10,"running":false,"last_due":"2026-11-05T09:55:00+07:00","last_run":"2026-11-05T09:55:10+07:00","last_status":"ok","last_error":null,"late":false,"next_run":"2026-11-05T10:05:00+07:00","editable":["on","every_min"]}]}"""
    const val UNIVERSE = """{"source":"file","valid_from":null,"valid_to":"2027-01-29","expired":false,"count":2,"lq45":[],"idx80":["BBCA","TLKM"],"file_error":null}"""
}
