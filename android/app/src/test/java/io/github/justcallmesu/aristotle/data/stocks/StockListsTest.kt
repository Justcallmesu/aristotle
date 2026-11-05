package io.github.justcallmesu.aristotle.data.stocks

import io.github.justcallmesu.aristotle.data.AppSettings
import io.github.justcallmesu.aristotle.data.HomeMode
import io.github.justcallmesu.aristotle.data.MemoryStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchlistOrderTest {
    private val codes = listOf("BBCA", "BBRI", "TLKM", "ADRO")

    @Test
    fun moveUpAndDown() {
        assertEquals(listOf("BBRI", "BBCA", "TLKM", "ADRO"), WatchlistOrder.move(codes, "BBRI", -1))
        assertEquals(listOf("BBCA", "BBRI", "ADRO", "TLKM"), WatchlistOrder.move(codes, "TLKM", 1))
        assertEquals(listOf("BBRI", "TLKM", "ADRO", "BBCA"), WatchlistOrder.move(codes, "BBCA", 10))
    }

    @Test
    fun theEndsAndUnknownCodesChangeNothing() {
        assertEquals(codes, WatchlistOrder.move(codes, "BBCA", -1))
        assertEquals(codes, WatchlistOrder.move(codes, "ADRO", 1))
        assertEquals(codes, WatchlistOrder.move(codes, "GOTO", 1))
        assertFalse(WatchlistOrder.canMove(codes, "BBCA", -1))
        assertFalse(WatchlistOrder.canMove(codes, "ADRO", 1))
        assertTrue(WatchlistOrder.canMove(codes, "BBRI", -1))
        assertFalse(WatchlistOrder.canMove(codes, "GOTO", 1))
    }

    @Test
    fun theSameSetIsSent() {
        // The server refuses any other set (400 bad_request): a move only reorders.
        assertEquals(codes.toSet(), WatchlistOrder.move(codes, "TLKM", -2).toSet())
        assertEquals(codes.size, WatchlistOrder.move(codes, "TLKM", -2).size)
    }

    @Test
    fun arrangeKeepsEntriesTheOrderDoesNotName() {
        val entries = listOf(WatchEntry("BBCA"), WatchEntry("BBRI"), WatchEntry("GOTO"))
        assertEquals(listOf("BBRI", "BBCA", "GOTO"), WatchlistOrder.arrange(entries, listOf("BBRI", "BBCA")).map { it.code })
    }
}

/** The holding sheet's checks (§20) and its P/L preview against the server's own numbers. */
class HoldingInputTest {
    @Test
    fun lotsAreWholeNumbersInRange() {
        assertEquals(10L, HoldingInput.lots("10").value)
        assertEquals(1000L, HoldingInput.lots("1.000").value)
        assertEquals(10_000_000L, HoldingInput.lots("10000000").value)
        assertEquals(FieldProblem.Empty, HoldingInput.lots(" ").problem)
        assertEquals(FieldProblem.TooSmall, HoldingInput.lots("0").problem)
        assertEquals(FieldProblem.TooLarge, HoldingInput.lots("10000001").problem)
        assertEquals(FieldProblem.NotWhole, HoldingInput.lots("1,5").problem)
        assertEquals(FieldProblem.NotWhole, HoldingInput.lots("1.5").problem)
        assertEquals(FieldProblem.NotANumber, HoldingInput.lots("ten").problem)
        assertEquals(FieldProblem.NotANumber, HoldingInput.lots("-3").problem)
    }

    @Test
    fun averagePricesTheIndonesianWayOrWithAPoint() {
        assertEquals(4800.0, HoldingInput.avgPrice("4800").value!!, 0.0)
        assertEquals(4800.0, HoldingInput.avgPrice("4.800").value!!, 0.0)
        assertEquals(4812.51, HoldingInput.avgPrice("4.812,51").value!!, 0.0)
        assertEquals(4812.51, HoldingInput.avgPrice("4812,51").value!!, 0.0)
        assertEquals(4812.5, HoldingInput.avgPrice("4812.5").value!!, 0.0)
        assertEquals(68.0, HoldingInput.avgPrice("Rp 68").value!!, 0.0)
        assertEquals(1_250_000.0, HoldingInput.avgPrice("1.250.000").value!!, 0.0)
        // An average of several buys may sit off the IDX tick: accepted, as the server does.
        assertEquals(4375.0, HoldingInput.avgPrice("4.375").value!!, 0.0)
    }

    @Test
    fun averagePriceLimits() {
        assertEquals(FieldProblem.Empty, HoldingInput.avgPrice("").problem)
        assertEquals(FieldProblem.TooSmall, HoldingInput.avgPrice("0").problem)
        assertEquals(FieldProblem.TooSmall, HoldingInput.avgPrice("0,00").problem)
        assertEquals(0.01, HoldingInput.avgPrice("0,01").value!!, 0.0)
        assertEquals(FieldProblem.TooLarge, HoldingInput.avgPrice("10.000.000,01").problem)
        assertEquals(10_000_000.0, HoldingInput.avgPrice("10.000.000").value!!, 0.0)
        assertEquals(FieldProblem.TooManyDecimals, HoldingInput.avgPrice("4812,505").problem)
        assertEquals(FieldProblem.NotANumber, HoldingInput.avgPrice("4,8,1").problem)
        assertEquals(FieldProblem.NotANumber, HoldingInput.avgPrice("48.00.0").problem)
        assertEquals(FieldProblem.NotANumber, HoldingInput.avgPrice("abc").problem)
    }

    @Test
    fun fieldTextOfAStoredPrice() {
        assertEquals("4800", HoldingInput.avgPriceText(4800.0))
        assertEquals("4812,51", HoldingInput.avgPriceText(4812.51))
        assertEquals(4812.51, HoldingInput.avgPrice(HoldingInput.avgPriceText(4812.51)).value!!, 0.0)
    }

    @Test
    fun previewMatchesTheServer() {
        // protocol §20 example and server/internal/stocks http_test: 10 lots at 4 800, price 4 930, fees 0.15 / 0.25.
        assertEquals(HoldingMath.Preview(1000, 4_807_200, 4_917_675, 110_475, 2.3), HoldingMath.preview(10, 4800.0, 4930, StockFees()))
        // ANTM 2 lots at 3 000, price 3 000: −2 400, −0,40 %.
        assertEquals(HoldingMath.Preview(200, 600_900, 598_500, -2400, -0.4), HoldingMath.preview(2, 3000.0, 3000, StockFees()))
        // Fees 0.2 / 0 (TestSettingsEndpoints): cost 4 809 600, value 4 930 000.
        val other = HoldingMath.preview(10, 4800.0, 4930, StockFees(0.2, 0.0))
        assertEquals(4_809_600L, other.cost)
        assertEquals(4_930_000L, other.value)
        val noPrice = HoldingMath.preview(2, 3000.0, null, StockFees())
        assertEquals(600_900L, noPrice.cost)
        assertNull(noPrice.value)
        assertNull(noPrice.pl)
    }

    @Test
    fun feeFields() {
        assertEquals(0.15, FeeInput.parse("0,15").value!!, 0.0)
        assertEquals(0.25, FeeInput.parse("0.25").value!!, 0.0)
        assertEquals(0.0, FeeInput.parse("0").value!!, 0.0)
        assertEquals(5.0, FeeInput.parse("5").value!!, 0.0)
        assertEquals(FieldProblem.TooLarge, FeeInput.parse("5,01").problem)
        assertEquals(FieldProblem.NotANumber, FeeInput.parse("-1").problem)
        assertEquals(FieldProblem.TooManyDecimals, FeeInput.parse("0,12345").problem)
        assertEquals(FieldProblem.Empty, FeeInput.parse("").problem)
        assertEquals("0,15", FeeInput.text(0.15))
        assertEquals("0,2", FeeInput.text(0.2))
        assertEquals("0", FeeInput.text(0.0))
    }
}

class NewsListsTest {
    private fun item(id: String, at: String, vararg codes: String) = NewsItem(id = id, publishedAt = at, codes = codes.toList(), link = "https://x/$id")

    private val items = listOf(
        item("a", "2026-11-05T10:31:00+07:00", "BBCA"),
        item("b", "2026-11-05T09:12:00+07:00", "BBCA", "BBRI"),
        item("c", "2026-11-05T00:02:00+07:00"),
        item("d", "2026-11-04T23:59:00+07:00", "ADRO"),
    )

    @Test
    fun filtersByTheMatchedCodes() {
        assertEquals(items, NewsLists.filter(items, NewsFilter.All, listOf("BBCA"), emptyList()))
        assertEquals(listOf("a", "b"), NewsLists.filter(items, NewsFilter.Watchlist, listOf("BBCA"), listOf("ADRO")).map { it.id })
        assertEquals(listOf("b", "d"), NewsLists.filter(items, NewsFilter.Holdings, listOf("BBCA"), listOf("ADRO", "BBRI")).map { it.id })
        assertTrue(NewsLists.filter(items, NewsFilter.Holdings, listOf("BBCA"), emptyList()).isEmpty())
    }

    @Test
    fun groupsByWibDay() {
        val days = NewsLists.byDay(items)
        assertEquals(listOf("2026-11-05", "2026-11-04"), days.map { it.date.toString() })
        assertEquals(listOf("a", "b", "c"), days.first().items.map { it.id })
    }

    @Test
    fun checkedAtAndFailingSources() {
        val sources = listOf(
            NewsSource("cnbc", true, "2026-11-05T10:40:00+07:00"),
            NewsSource("kontan", false, "2026-11-05T09:00:00+07:00", "http 503"),
            NewsSource("idxchannel", true, null),
        )
        assertEquals("2026-11-05T10:40:00+07:00", NewsLists.checkedAt(sources))
        assertEquals(listOf("kontan"), NewsLists.failing(sources))
    }
}

class SparkGeometryTest {
    @Test
    fun scalesClosesAndThePreviousCloseIntoTheBox() {
        val shape = SparkGeometry.of(listOf(4860, 4930), prevClose = 4840, width = 64f, height = 28f, inset = 2f)!!
        assertEquals(listOf(2f to (2f + 24f * (1f - 20f / 90f)), 62f to 2f), shape.points)
        assertEquals(26f, shape.baselineY!!, 0.001f)
    }

    @Test
    fun aFlatDayIsAMiddleLineAndTooFewPointsDrawNothing() {
        val flat = SparkGeometry.of(listOf(100, 100, 100), prevClose = 100, width = 64f, height = 28f, inset = 2f)!!
        assertTrue(flat.points.all { it.second == 14f })
        assertNull(SparkGeometry.of(listOf(100), 99, 64f, 28f, 2f))
        assertNull(SparkGeometry.of(emptyList(), null, 64f, 28f, 2f))
    }
}

/** Q92: the Home switch remembers its view across starts. */
class HomeModeSettingTest {
    @Test
    fun chatsFirstThenWhatTheUserPicked() {
        val store = MemoryStore()
        assertEquals(HomeMode.Chats, AppSettings(store).homeMode.value)
        AppSettings(store).setHomeMode(HomeMode.Stocks)
        assertEquals("stocks", store.map["home_mode"])
        assertEquals(HomeMode.Stocks, AppSettings(store).homeMode.value)
        AppSettings(store).setHomeMode(HomeMode.Chats)
        assertEquals(HomeMode.Chats, AppSettings(store).homeMode.value)
    }

    @Test
    fun aDamagedValueFallsBackToChats() {
        val store = MemoryStore().apply { map["home_mode"] = "charts" }
        assertEquals(HomeMode.Chats, AppSettings(store).homeMode.value)
    }
}
