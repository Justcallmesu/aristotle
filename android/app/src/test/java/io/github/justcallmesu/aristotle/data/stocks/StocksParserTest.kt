package io.github.justcallmesu.aristotle.data.stocks

import io.github.justcallmesu.aristotle.data.ConnectionProblem
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Protocol §20 bodies as the server writes them: the fixtures in `test/resources/stocks/` are the real handler's output
 * (server/internal/stocks harness: fake Yahoo, Thu 2026-11-05 10:00 WIB), the numbers the server tests assert.
 */
class StocksParserTest {
    private fun fixture(name: String): String = javaClass.classLoader!!.getResource("stocks/$name")!!.readText()

    @Test
    fun watchlistInTheUsersOrder() {
        assertEquals(listOf(WatchEntry("BBCA", "Bank Central Asia Tbk"), WatchEntry("XYZA", "Xyz Abadi Tbk")), StocksParser.watchlist(fixture("watchlist.json")))
    }

    @Test
    fun quotesWithMarketSparkAndErrors() {
        val body = StocksParser.quotes(fixture("quotes.json"))!!
        assertEquals(MarketStatus("open", 1, "2026-11-05T12:00:00+07:00", null), body.market)
        assertTrue(body.market.isOpen)
        val bbca = body.quoteOf("BBCA")!!
        assertEquals(4930L, bbca.price)
        assertEquals(4840L, bbca.prevClose)
        assertEquals(90L, bbca.change)
        assertEquals(1.86, bbca.changePct!!, 0.0)
        assertEquals(listOf(4860L, 4930L), bbca.spark)
        assertEquals(10L, bbca.tick)
        assertEquals(6050L, bbca.ara)
        assertEquals(4510L, bbca.arb)
        // `spark` is null on the wire for a quote without bars: read as empty, the rest of the quote kept.
        val antm = body.quoteOf("ANTM")!!
        assertEquals(emptyList<Long>(), antm.spark)
        assertEquals(-100L, antm.change)
        assertNull(antm.high)
        assertEquals(listOf(QuoteError("XYZA", "unknown_symbol")), body.errors)
        // No market_time from the fake source: "as of" falls back to the server's time.
        assertEquals("2026-11-05T10:00:00+07:00", body.asOf)
    }

    @Test
    fun staleAndClosedQuotes() {
        val stale = StocksParser.quotes(fixture("quotes_stale.json"))!!
        assertTrue(stale.quoteOf("BBCA")!!.stale)
        assertEquals("upstream_blocked", stale.errors.single().error)
        val closed = StocksParser.quotes(fixture("quotes_closed_empty.json"))!!
        assertEquals(MarketStatus("closed", null, null, "2026-11-06T09:00:00+07:00"), closed.market)
        assertTrue(closed.quotes.isEmpty())
    }

    @Test
    fun holdingsWithPlAfterFees() {
        val body = StocksParser.holdings(fixture("holdings.json"))!!
        val bbca = body.rowOf("BBCA")!!
        assertEquals(HoldingRow("BBCA", "Bank Central Asia Tbk", 10, 4800.0, 4930, 4807200, 4917675, 110475, 2.3, 90000, false), bbca)
        assertEquals(-2400L, body.rowOf("ANTM")!!.pl)
        assertEquals(HoldingsTotal(5408100, 5516175, 108075, 2.0, 70000), body.total)
        assertEquals(listOf("BBCA", "ANTM"), body.byValue.map { it.code })
    }

    @Test
    fun holdingsWithoutAPriceKeepTheCost() {
        val body = StocksParser.holdings(fixture("holdings_no_price.json"))!!
        val row = body.holdings.single()
        assertNull(row.price)
        assertNull(row.value)
        assertNull(row.pl)
        assertEquals(600900L, row.cost)
        assertEquals(HoldingsTotal(cost = 600900), body.total)
    }

    @Test
    fun feesSearchAndNews() {
        assertEquals(StockFees(0.15, 0.25), StocksParser.fees(fixture("settings.json")))
        assertEquals(
            listOf(SearchResult("BBCA", "Bank Central Asia Tbk", true), SearchResult("BCAP", "MNC Kapital Indonesia Tbk", false)),
            StocksParser.search(fixture("search_bca.json")),
        )
        val news = StocksParser.news(fixture("news_matched.json"))!!
        assertEquals(3, news.sources.size)
        assertTrue(news.sources.all { it.ok && it.fetchedAt == "2026-11-05T10:00:00+07:00" })
        val matched = news.items.filter { it.codes.isNotEmpty() }
        assertEquals(2, matched.size)
        assertTrue(matched.all { it.codes == listOf("PGEO") && it.match == "strong" && it.source == "cnbc" })
        assertNull(news.items.first().match)
        val antm = StocksParser.news(fixture("news_antm.json"))!!.items.single()
        assertEquals("kontan", antm.source)
        assertNull(antm.imageUrl)
        assertEquals("2026-11-04T20:48:22+07:00", antm.publishedAt)
    }

    @Test
    fun notJsonIsNull() {
        assertNull(StocksParser.quotes("<html>"))
        assertNull(StocksParser.holdings("""{"holdings":"x"}"""))
    }

    @Test
    fun errorBodiesAndRetryAfter() {
        assertEquals(StocksError.Server(503, "upstream_blocked", 60), StocksParser.error(StocksReply.Http(503, fixture("error_upstream_blocked.json"))))
        assertEquals(StocksError.Server(404, "not_held", null), StocksParser.error(StocksReply.Http(404, fixture("error_not_held.json"))))
        // The header stands in when the body has no number; a body that is not JSON keeps the status.
        assertEquals(StocksError.Server(503, "upstream_blocked", 30), StocksParser.error(StocksReply.Http(503, """{"error":"upstream_blocked"}""", 30)))
        assertEquals(StocksError.Server(502, null, null), StocksParser.error(StocksReply.Http(502, "bad gateway")))
    }

    @Test
    fun apiBuildsTheRequestsOfTheContract() = runBlocking {
        val sent = mutableListOf<StocksRequest>()
        val api = StocksApi { request -> sent += request; StocksReply.Http(200, fixture("watchlist.json")) }
        api.watchlist()
        api.addToWatchlist("BBCA")
        api.removeFromWatchlist("BBCA")
        api.reorderWatchlist(listOf("TLKM", "BBCA"))
        api.quotes()
        api.quotes(listOf("BBCA", "TLKM"))
        api.search("bank bca")
        api.news()
        assertEquals(listOf("GET watchlist", "PUT watchlist/BBCA", "DELETE watchlist/BBCA", "PUT watchlist", "GET quotes", "GET quotes", "GET search", "GET news"), sent.map { "${it.method} ${it.path}" })
        assertEquals(listOf("TLKM", "BBCA"), Json.parseToJsonElement(sent[3].body!!).jsonObject["codes"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(emptyMap<String, String>(), sent[4].query)
        assertEquals(mapOf("codes" to "BBCA,TLKM"), sent[5].query)
        assertEquals(mapOf("q" to "bank bca"), sent[6].query)
        assertEquals(mapOf("limit" to "100"), sent[7].query)
    }

    @Test
    fun apiSendsHoldingAndFeeBodies() = runBlocking {
        val sent = mutableListOf<StocksRequest>()
        val api = StocksApi { request ->
            sent += request
            StocksReply.Http(200, if (request.path == "settings") fixture("settings.json") else fixture("holdings_put.json"))
        }
        val put = api.putHolding("BBCA", 10, 4812.51)
        assertEquals(110475L, (put as StocksResult.Ok).value.total.pl)
        val body = Json.parseToJsonElement(sent.single().body!!).jsonObject
        assertEquals("10", body["lots"]!!.jsonPrimitive.content)
        assertEquals("4812.51", body["avg_price"]!!.jsonPrimitive.content)
        api.putFees(StockFees(0.2, 0.0))
        val fees = Json.parseToJsonElement(sent.last().body!!).jsonObject
        assertEquals("0.2", fees["buy_fee_pct"]!!.jsonPrimitive.content)
        assertEquals("0.0", fees["sell_fee_pct"]!!.jsonPrimitive.content)
    }

    @Test
    fun apiMapsFailures() = runBlocking {
        val limit = StocksApi { StocksReply.Http(409, """{"error":"limit_reached"}""") }.addToWatchlist("BBCA")
        assertEquals(StocksResult.Failed(StocksError.Server(409, StocksError.LIMIT_REACHED)), limit)
        val down = StocksApi { StocksReply.Failed(ConnectionProblem.Unreachable) }.quotes()
        assertEquals(StocksResult.Failed(StocksError.Unreachable(ConnectionProblem.Unreachable)), down)
        val garbled = StocksApi { StocksReply.Http(200, "nope") }.holdings()
        assertEquals(StocksResult.Failed(StocksError.BadAnswer), garbled)
    }
}
