package io.github.justcallmesu.aristotle.data.stocks

import io.github.justcallmesu.aristotle.data.ConnectionProblem
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun at(wib: String): Long = OffsetDateTime.parse("$wib:00+07:00").toEpochSecond()

private fun bar(t: String, c: Long, o: Long = c, h: Long = maxOf(o, c), l: Long = minOf(o, c), v: Long = 100_000) = ChartBar(at(t), o, h, l, c, v)

/** 5-minute bars of [date] from [from] (WIB), one per slot, closes as given. */
private fun fiveMinute(date: String, from: String, closes: List<Long>): List<ChartBar> {
    val start = at("${date}T$from")
    return closes.mapIndexed { i, c -> ChartBar(start + i * 300L, c, c, c, c, 1_000) }
}

/** The chart's scaling: prices to y on the IDX tick grid, a flat series and one bar still drawable. */
class PriceScaleTest {
    @Test
    fun ticksFollowTheIdxBands() {
        assertEquals(1L, IdxTick.of(199))
        assertEquals(2L, IdxTick.of(200))
        assertEquals(5L, IdxTick.of(500))
        assertEquals(10L, IdxTick.of(2_000))
        assertEquals(10L, IdxTick.of(4_990))
        assertEquals(25L, IdxTick.of(5_000))
    }

    @Test
    fun rangePaddedAndGridOnTradablePrices() {
        val scale = PriceScale.of(listOf(bar("2026-11-05T09:00", 4_900, h = 4_980, l = 4_830)))!!
        // Span 150, padded by max(8 %, one tick of 10) = 12 each side.
        assertEquals(4_818.0, scale.lo, 0.0)
        assertEquals(4_992.0, scale.hi, 0.0)
        assertEquals(50L, scale.step)
        assertEquals(listOf(4_850L, 4_900L, 4_950L), scale.ticks)
        assertTrue(scale.ticks.all { it % IdxTick.of(scale.hi.toLong()) == 0L })
    }

    @Test
    fun yRunsFromTheTopDown() {
        val scale = PriceScale(100.0, 200.0)
        assertEquals(10f, scale.y(200L, top = 10f, height = 100f), 0.001f)
        assertEquals(110f, scale.y(100L, top = 10f, height = 100f), 0.001f)
        assertEquals(60f, scale.y(150L, top = 10f, height = 100f), 0.001f)
    }

    @Test
    fun aFlatSeriesStillHasHeightAndSitsInTheMiddle() {
        val flat = (0 until 5).map { ChartBar(it.toLong(), 5_000, 5_000, 5_000, 5_000, 0) }
        val scale = PriceScale.of(flat)!!
        assertEquals(4_975.0, scale.lo, 0.0)
        assertEquals(5_025.0, scale.hi, 0.0)
        assertEquals(90f, scale.y(5_000L, 0f, 180f), 0.001f)
        assertEquals(listOf(4_975L, 5_000L, 5_025L), scale.ticks)
        assertFalse(scale.y(5_000L, 0f, 180f).isNaN())
    }

    @Test
    fun theDaysPreviousCloseWidensTheRange() {
        val bars = listOf(bar("2026-11-05T09:00", 4_900, h = 4_910, l = 4_890))
        val scale = PriceScale.of(bars, include = 4_700)!!
        assertTrue(scale.lo < 4_700)
        assertNull(PriceScale.of(emptyList()))
    }

    @Test
    fun niceSteps() {
        assertEquals(10L, PriceScale.niceStep(0.0, 10))
        assertEquals(25L, PriceScale.niceStep(80.0, 25))
        assertEquals(200L, PriceScale.niceStep(800.0, 25))
        assertEquals(250L, PriceScale.niceStep(900.0, 25))
        assertEquals(5L, PriceScale.niceStep(18.0, 5))
        // Penny stocks: whole rupiah, never below Rp1.
        val cheap = PriceScale.of(listOf(ChartBar(0, 50, 52, 49, 51, 0)))!!
        assertTrue(cheap.ticks.isNotEmpty() && cheap.ticks.all { it >= 1 })
    }
}

/** Bars by slot, not wall time (P3): the lunch break, nights and weekends take no room; the crosshair snaps to bars. */
class BarAxisTest {
    private fun fixture(name: String) = StocksParser.chart(javaClass.classLoader!!.getResource("stocks/$name")!!.readText())!!

    @Test
    fun aTradingDayCollapsesTheLunchBreak() {
        // Wednesday's 15-minute bars of the 5d fixture: 09:00..11:45, then 13:30..16:00 (the server dropped 12:00..13:15).
        val wed = fixture("chart_5d.json").bars.filter { BarAxis.dateOf(it) == LocalDate.of(2026, 11, 4) }
        assertEquals(23, wed.size)
        val axis = BarAxis.day(wed, 15)
        assertEquals((0 until 23).toList(), (0 until axis.size).map(axis::slotOf))
        assertEquals(23, axis.slots)
        assertEquals("the break before 13:30", listOf(12), axis.breaks)
    }

    @Test
    fun aYoungDayKeepsRoomForTheRestOfTheSession() {
        // Thursday at 5 minutes, 09:00 to 10:40: 21 bars on a 36 + 31 slot day.
        val bars = fiveMinute("2026-11-05", "09:00", List(21) { 4_900L + it })
        val axis = BarAxis.day(bars, 5)
        assertEquals(67, axis.slots)
        assertEquals(20, axis.slotOf(20))
        assertEquals(listOf(36), axis.breaks)
        // Friday: 09:00–11:30 and 14:00–16:00.
        val friday = BarAxis.day(fiveMinute("2026-11-06", "09:00", listOf(1, 2, 3)), 5)
        assertEquals(30 + 25, friday.slots)
        assertEquals(listOf(30), friday.breaks)
    }

    @Test
    fun sessionTwoFollowsSessionOneAndOffScheduleBarsStillGetASlot() {
        val bars = listOf(bar("2026-11-05T09:00", 1), bar("2026-11-05T09:05", 2), bar("2026-11-05T13:30", 3), bar("2026-11-05T13:30", 4), bar("2026-11-05T16:00", 5))
        val axis = BarAxis.day(bars, 5)
        assertEquals(listOf(0, 1, 36, 37, 66), (0 until axis.size).map(axis::slotOf))
        assertEquals(67, axis.slots)
    }

    @Test
    fun fiveDaysByIndexWithEachNewDayMarked() {
        val body = fixture("chart_5d.json")
        val axis = BarAxis.days(body.bars)
        assertEquals(34, axis.slots)
        assertEquals("Wednesday starts at bar 11", listOf(11), axis.breaks)
        assertEquals(33, axis.slotOf(33))
    }

    @Test
    fun crosshairSnapsToTheNearestBar() {
        val axis = BarAxis.byIndex(10)
        // Slot centres at 5, 15, … 95 in a 100 px plot.
        assertEquals(0, axis.barAt(0f, 0f, 100f))
        assertEquals(1, axis.barAt(14.9f, 0f, 100f))
        assertEquals(1, axis.barAt(19.9f, 0f, 100f))
        assertEquals("a tie goes to the earlier bar", 0, axis.barAt(10f, 0f, 100f))
        assertEquals(0, axis.barAt(-50f, 0f, 100f))
        assertEquals(9, axis.barAt(500f, 0f, 100f))
        assertEquals(15f, axis.x(1, 0f, 100f), 0.001f)
        assertEquals(-1, BarAxis.byIndex(0).barAt(10f, 0f, 100f))
    }

    @Test
    fun crosshairOnAYoungDayStaysOnTheBarsSoFar() {
        val bars = listOf(bar("2026-11-05T09:00", 1), bar("2026-11-05T09:05", 2), bar("2026-11-05T13:30", 3))
        val axis = BarAxis.day(bars, 5)
        val width = 670f // 10 px per slot
        assertEquals("slot 30 is nearer 13:30 (slot 36) than 09:05 (slot 1)", 2, axis.barAt(305f, 0f, width))
        assertEquals(1, axis.barAt(150f, 0f, width))
        assertEquals("the empty rest of the day snaps to the last bar", 2, axis.barAt(660f, 0f, width))
    }

    @Test
    fun oneBarSitsInTheMiddle() {
        val axis = BarAxis.byIndex(1)
        assertEquals(50f, axis.x(0, 0f, 100f), 0.001f)
        assertEquals(0, axis.barAt(99f, 0f, 100f))
    }
}

/** Time labels in WIB per range, and the readout's numbers. */
class ChartLabelsTest {
    private val thursday = LocalDate.of(2026, 11, 5)

    private fun texts(labels: List<AxisLabel>) = labels.map { it.text }

    @Test
    fun dayLabelsAreSessionHoursWithSessionTwosStart() {
        val bars = fiveMinute("2026-11-05", "09:00", List(5) { 4_900L })
        val axis = BarAxis.day(bars, 5)
        val labels = ChartLabels.axis(ChartRange.Day, bars, axis, "5m", minGapSlots = 11, locale = Locale.ENGLISH)
        assertEquals(listOf("09:00", "10:00", "11:00", "13:30", "15:00", "16:00"), texts(labels))
        assertEquals(listOf(0, 12, 24, 36, 54, 66), labels.map { it.slot })
        // Friday: session 2 opens 14:00; 11:00 is too close to it and gives way.
        val friday = fiveMinute("2026-11-06", "09:00", listOf(1))
        assertEquals(listOf("09:00", "10:00", "14:00", "15:00", "16:00"), texts(ChartLabels.axis(ChartRange.Day, friday, BarAxis.day(friday, 5), "5m", 11, Locale.ENGLISH)))
    }

    @Test
    fun multiDayLabelsMarkDaysAndMonths() {
        val five = StocksParser.chart(javaClass.classLoader!!.getResource("stocks/chart_5d.json")!!.readText())!!
        assertEquals(listOf("Tue 3", "Wed 4"), texts(ChartLabels.axis(ChartRange.FiveDays, five.bars, BarAxis.days(five.bars), five.interval, 5, Locale.ENGLISH)))
        assertEquals(listOf("3 Nov", "4 Nov"), texts(ChartLabels.axis(ChartRange.Month, five.bars, BarAxis.byIndex(five.bars.size), "1h", 5, Locale.ENGLISH)))
        // Too close together: the later one gives way.
        assertEquals(listOf("Tue 3"), texts(ChartLabels.axis(ChartRange.FiveDays, five.bars, BarAxis.days(five.bars), five.interval, 20, Locale.ENGLISH)))

        val days = (0L until 120L).map { LocalDate.of(2026, 11, 2).plusDays(it) }.filter { it.dayOfWeek.value <= 5 }
        val daily = days.map { ChartBar(OffsetDateTime.of(it.atTime(9, 0), java.time.ZoneOffset.ofHours(7)).toEpochSecond(), 1, 1, 1, 1, 0) }
        val months = ChartLabels.axis(ChartRange.SixMonths, daily, BarAxis.byIndex(daily.size), "1d", 5, Locale.ENGLISH)
        assertEquals("no label on the first bar; January is its year", listOf("Dec", "2027", "Feb", "Mar"), texts(months))
    }

    @Test
    fun barTimesAndSpans() {
        val tenTen = bar("2026-11-05T10:10", 4_900)
        assertEquals("10:10", ChartLabels.barTime(ChartRange.Day, tenTen, thursday, Locale.ENGLISH))
        assertEquals("Thu 5 Nov, 10:10", ChartLabels.barTime(ChartRange.FiveDays, tenTen, thursday, Locale.ENGLISH))
        assertEquals("Thu 5 Nov", ChartLabels.barTime(ChartRange.Year, tenTen, thursday, Locale.ENGLISH))
        assertEquals("Wed 5 Nov 2025", ChartLabels.barTime(ChartRange.Year, bar("2025-11-05T09:00", 1), thursday, Locale.ENGLISH))

        val day = listOf(bar("2026-11-05T09:00", 1), bar("2026-11-05T10:40", 2))
        assertEquals(ChartSpan("09:00", "10:40", null), ChartLabels.span(ChartRange.Day, day, thursday, Locale.ENGLISH))
        assertEquals(ChartSpan("09:00", "10:40", "Thu 5 Nov"), ChartLabels.span(ChartRange.Day, day, thursday.plusDays(1), Locale.ENGLISH))
        assertEquals(ChartSpan("5 Nov 2025", "5 Nov", null), ChartLabels.span(ChartRange.Year, listOf(bar("2025-11-05T09:00", 1), bar("2026-11-05T09:00", 2)), thursday, Locale.ENGLISH))
        assertNull(ChartLabels.span(ChartRange.Day, emptyList(), thursday, Locale.ENGLISH))
    }

    @Test
    fun intervals() {
        assertEquals(5, ChartLabels.intervalMinutes("5m"))
        assertEquals(60, ChartLabels.intervalMinutes("1h"))
        assertEquals(1_440, ChartLabels.intervalMinutes("1d"))
        assertNull(ChartLabels.intervalMinutes("1wk"))
        assertNull(ChartLabels.intervalMinutes(""))
    }

    @Test
    fun readoutOfABarAndOfTheRange() {
        val bars = listOf(bar("2026-11-05T09:00", 4_900, o = 4_850, h = 4_910, l = 4_840, v = 1_234_500), bar("2026-11-05T09:05", 4_880, o = 4_900, h = 4_920, l = 4_870))
        val first = ChartReadout.bar(ChartRange.Day, bars, 0, prevClose = 4_840)!!
        assertEquals(60L, first.change)
        assertEquals(1.24, first.changePct!!, 0.0)
        assertEquals(12_345L, first.volumeLots)
        val second = ChartReadout.bar(ChartRange.Day, bars, 1, prevClose = 4_840)!!
        assertEquals("from the bar before", -20L, second.change)
        assertEquals(-0.41, second.changePct!!, 0.0)
        assertEquals("first bar of a long range: from its open", 50L, ChartReadout.bar(ChartRange.Month, bars, 0, 4_840)!!.change)
        assertNull(ChartReadout.bar(ChartRange.Day, bars, -1, 4_840))
        assertNull(ChartReadout.bar(ChartRange.Day, bars, 2, 4_840))

        val day = ChartReadout.summary(ChartRange.Day, bars, prevClose = 4_840)!!
        assertEquals(4_840L, day.base)
        assertEquals(40L, day.change)
        assertEquals(4_920L, day.high)
        assertEquals(4_840L, day.low)
        assertEquals(0.83, day.changePct!!, 0.0)
        assertEquals(4_850L, ChartReadout.summary(ChartRange.Month, bars, 4_840)!!.base)
        assertNull(ChartReadout.pct(10, 0))
    }

    @Test
    fun changesForTheReadout() {
        assertEquals(ChangeDisplay(Direction.Up, "+25 · +0,25\u00A0%"), StockChanges.move(25, 0.25))
        assertEquals(ChangeDisplay(Direction.Down, "\u22121,24\u00A0%"), StockChanges.movePercent(-60, -1.24))
        assertEquals(ChangeDisplay(Direction.Flat, "0"), StockChanges.movePercent(0, null))
    }
}

/** `/v1/stocks/chart/{code}` as the server writes it (fixtures: the server's parser over its own Yahoo test data). */
class ChartApiTest {
    private fun fixture(name: String): String = javaClass.classLoader!!.getResource("stocks/$name")!!.readText()

    @Test
    fun intradayBarsWithoutTheLunchBreak() {
        val body = StocksParser.chart(fixture("chart_5d.json"))!!
        assertEquals("BBCA", body.code)
        assertEquals("5d", body.range)
        assertEquals("15m", body.interval)
        assertEquals("Asia/Jakarta", body.tz)
        assertEquals(4_840L, body.prevClose)
        assertFalse(body.stale)
        assertEquals(34, body.bars.size)
        assertEquals(ChartBar(1793782800, 4_930, 4_930, 4_930, 4_930, 25_673_700), body.bars.last())
        assertTrue("nulls were dropped by the server", body.bars.none { BarAxis.timeOf(it).let { t -> t.hour == 12 || (t.hour == 13 && t.minute < 30) } })
        assertNotNull(ChartModel.of(ChartRange.FiveDays, body, body.prevClose))
    }

    @Test
    fun dailyBarsWithoutAPreviousClose() {
        val body = StocksParser.chart(fixture("chart_5y.json"))!!
        assertEquals("1d", body.interval)
        assertNull(body.prevClose)
        assertEquals(8, body.bars.size)
        assertEquals(4_930L, body.bars.last().c)
        val model = ChartModel.of(ChartRange.Year, body, prevClose = 4_840)!!
        assertNull("only 1D draws the previous close", model.prevClose)
        assertEquals(583_695_400L, model.maxVolume)
    }

    @Test
    fun anEmptyChartHasNoModel() {
        assertNull(ChartModel.of(ChartRange.Day, ChartBody(code = "XYZA", bars = emptyList()), 100))
    }

    @Test
    fun eachRangeAsksForItsServerRange() = runBlocking {
        val sent = mutableListOf<StocksRequest>()
        val api = StocksApi { request -> sent += request; StocksReply.Http(200, fixture("chart_5d.json")) }
        ChartRange.entries.forEach { api.chart("BBCA", it) }
        assertEquals(List(5) { "chart/BBCA" }, sent.map { it.path })
        assertEquals(listOf("1d", "5d", "1mo", "6mo", "1y"), sent.map { it.query["range"] })
        assertTrue(sent.all { it.method == "GET" })
        api.newsAbout("BBCA", 5)
        assertEquals(mapOf("code" to "BBCA", "limit" to "5"), sent.last().query)
        assertEquals(listOf("1D", "5D", "1M", "6M", "1Y"), ChartRange.entries.map { it.label })
    }

    @Test
    fun staleAndBlockedCharts() = runBlocking {
        val stale = StocksApi { StocksReply.Http(200, fixture("chart_5d.json").replace("\"stale\":false", "\"stale\":true")) }.chart("BBCA", ChartRange.FiveDays)
        assertTrue((stale as StocksResult.Ok).value.stale)
        val blocked = StocksApi { StocksReply.Http(503, fixture("error_upstream_blocked.json"), retryAfterSeconds = 60) }.chart("BBCA", ChartRange.SixMonths)
        assertEquals(StocksResult.Failed(StocksError.Server(503, StocksError.UPSTREAM_BLOCKED, 60)), blocked)
        val down = StocksApi { StocksReply.Failed(ConnectionProblem.Unreachable) }.chart("BBCA", ChartRange.Day)
        assertEquals(StocksResult.Failed(StocksError.Unreachable(ConnectionProblem.Unreachable)), down)
    }
}

/** The detail page's holding block, cache rule and the day's open. */
class StockDetailRulesTest {
    private val row = HoldingRow("BBCA", "Bank Central Asia Tbk", lots = 10, avgPrice = 4_800.0, price = 4_930, cost = 4_807_200, value = 4_917_675, pl = 110_475, plPct = 2.3, today = 90_000)
    private val fees = StockFees(0.15, 0.25)

    @Test
    fun holdingBlockUsesTheServersRuleWithTheFresherQuote() {
        val same = HoldingBlock.of(row, StockQuote("BBCA", price = 4_930, change = 90), fees)
        assertEquals(HoldingView(10, 4_800.0, 4_917_675, 90_000, 110_475, 2.3), same)
        val moved = HoldingBlock.of(row, StockQuote("BBCA", price = 5_000, change = 160), fees)
        assertEquals(4_987_500L, moved.value)
        assertEquals(180_300L, moved.pl)
        assertEquals(3.75, moved.plPct!!, 0.0)
        assertEquals(160_000L, moved.today)
        assertEquals("no fees known yet: the server's numbers", HoldingView(10, 4_800.0, 4_917_675, 90_000, 110_475, 2.3), HoldingBlock.of(row, StockQuote("BBCA", price = 5_000), null))
        assertEquals(HoldingView(10, 4_800.0, 4_917_675, 90_000, 110_475, 2.3), HoldingBlock.of(row, null, fees))
    }

    @Test
    fun rangesStayCachedButTheDayAgesOutAfterAMinute() {
        assertTrue(ChartCache.due(ChartRange.Month, null, 0))
        assertFalse(ChartCache.due(ChartRange.Month, 0, 3_600_000))
        assertFalse(ChartCache.due(ChartRange.Day, 0, 59_999))
        assertTrue(ChartCache.due(ChartRange.Day, 0, 60_000))
    }

    @Test
    fun theDaysOpenComesFromTheFirstBarOfTheQuotesDay() {
        val day = ChartBody(bars = listOf(bar("2026-11-05T09:00", 4_900, o = 4_850), bar("2026-11-05T09:05", 4_910)))
        assertEquals(4_850L, TodayOpen.of(day, StockQuote("BBCA", price = 4_910, marketTime = "2026-11-05T10:42:00+07:00")))
        assertNull("yesterday's bars are not today's open", TodayOpen.of(day, StockQuote("BBCA", price = 4_910, marketTime = "2026-11-06T09:10:00+07:00")))
        assertEquals(4_850L, TodayOpen.of(day, null))
        assertNull(TodayOpen.of(null, null))
    }
}

/** TM3a: 1D slots for bars at the session edges, and the small pure rules the detail page uses. */
class DayEdgeSlotsTest {
    @Test
    fun aBarAtTheEndOfSessionOneTakesItsLastSlotAndLaterBarsDoNotShift() {
        // Mon–Thu: 11:55 (slot 35), 12:00 (the end of session 1: clamped to 35, shared), then 13:30 stays at slot 36.
        val bars = listOf(bar("2026-11-05T11:55", 1), bar("2026-11-05T12:00", 2), bar("2026-11-05T13:30", 3), bar("2026-11-05T13:35", 4))
        val axis = BarAxis.day(bars, 5)
        assertEquals(listOf(35, 35, 36, 37), (0 until axis.size).map(axis::slotOf))
        assertEquals(67, axis.slots)
    }

    @Test
    fun fridayAtElevenThirtyAndBarsBeforeTheOpenAreClampedIntoASessionSlot() {
        val friday = BarAxis.day(listOf(bar("2026-11-06T08:55", 1), bar("2026-11-06T09:00", 2), bar("2026-11-06T11:30", 3), bar("2026-11-06T14:00", 4)), 5)
        // Session 1 is 30 slots (0..29): 08:55 shares slot 0 with 09:00, 11:30 -> 29; 14:00 opens session 2 at slot 30.
        assertEquals(listOf(0, 0, 29, 30), (0 until friday.size).map(friday::slotOf))
        assertEquals(55, friday.slots)
        val alone = BarAxis.day(listOf(bar("2026-11-06T11:30", 3)), 5)
        assertEquals(29, alone.slotOf(0))
    }

    @Test
    fun aBarBeforeTheOpenDoesNotShiftTheBarsAfterIt() {
        // TM3a2: 08:50 and 08:55 clamp into slot 0 beside 09:00; 09:05 stays at 1, 13:30 at 36 (Mon–Thu, 5 min).
        val early = listOf(bar("2026-11-05T08:50", 1), bar("2026-11-05T08:55", 2), bar("2026-11-05T09:00", 3), bar("2026-11-05T09:05", 4), bar("2026-11-05T13:30", 5))
        val axis = BarAxis.day(early, 5)
        assertEquals(listOf(0, 0, 0, 1, 36), (0 until axis.size).map(axis::slotOf))
        assertEquals(67, axis.slots)
        // Without the early bars the same bars sit on the same slots.
        val onTime = BarAxis.day(early.drop(2), 5)
        assertEquals(listOf(0, 1, 36), (0 until onTime.size).map(onTime::slotOf))
        // Two bars stamped at the same on-schedule time still get a slot each.
        val twice = BarAxis.day(listOf(bar("2026-11-05T09:00", 1), bar("2026-11-05T09:00", 2)), 5)
        assertEquals(listOf(0, 1), (0 until twice.size).map(twice::slotOf))
    }

    @Test
    fun theSessionEdgeRule() {
        val thu = IdxSessions.of(LocalDate.of(2026, 11, 5))
        assertTrue(thu.isOffSchedule(LocalTime.of(8, 59)))
        assertTrue(thu.isOffSchedule(LocalTime.of(12, 0)))
        assertTrue(thu.isOffSchedule(LocalTime.of(13, 0)))
        assertFalse(thu.isOffSchedule(LocalTime.of(11, 55)))
        assertFalse(thu.isOffSchedule(LocalTime.of(13, 30)))
        assertEquals(35, thu.slotOf(LocalTime.of(12, 0), 5))
    }
}

class ChartFitTest {
    @Test
    fun aWidePriceLabelShrinksOrIsDropped() {
        assertEquals(1f, ChartFit.shrink(30f, 40f)!!, 0f)
        assertEquals(40f / 50f, ChartFit.shrink(50f, 40f)!!, 1e-6f)
        assertNull("too wide to read when shrunk below 70%", ChartFit.shrink(80f, 40f))
        assertNull(ChartFit.shrink(10f, 0f))
    }

    @Test
    fun atFontScale1p3TheReadoutAndTheLabelsStayInsideTheChart() {
        val scale = 1.3f
        // Two lines (12 + 14 sp-ish, ~46 dp at 1.3x) plus 4 dp fit; at 1x it is the old 44 dp.
        assertEquals(44f, ChartFit.readoutHeightDp(44f, 1f), 0f)
        assertEquals(44f, ChartFit.readoutHeightDp(44f, 0.85f), 0f)
        assertTrue(ChartFit.readoutHeightDp(44f, scale) >= 46f + 4f)
        // The box grows by the MEASURED label line plus 1 px; never under the minimum (TM3a2, ChartHeightTest for scales).
        assertEquals(234f + 18.8f + ChartFit.SLACK_PX, ChartFit.chartHeightPx(234f, 18.8f, 250f), 1e-4f)
        assertEquals(250f, ChartFit.chartHeightPx(234f, 14f, 250f), 0f)
        assertTrue(ChartFit.fitsBelow(234f, 18.8f, ChartFit.chartHeightPx(234f, 18.8f, 250f) - 0.5f))
        // A 6-digit price at 1.3x (11 sp, ~7.2 dp a digit = 43 + 6 dp) against the axis' room (46 − 5 − 1 = 40 dp).
        val width = 6 * 7.2f * 11f / 11f
        val factor = ChartFit.shrink(width, 40f)
        assertNotNull(factor)
        assertTrue(width * factor!! <= 40f + 1e-3f)
    }

    @Test
    fun oneBarInLineModeIsADot() {
        assertTrue(ChartFit.lineIsDot(1))
        assertFalse(ChartFit.lineIsDot(0))
        assertFalse(ChartFit.lineIsDot(2))
    }
}

class ChartHoldAndDetailRulesTest {
    @Test
    fun aRefreshWaitsForTheReleaseOfAHold() {
        assertEquals("old", ChartHold.pick("old", "new", holding = true, sameSeries = true))
        assertEquals("new", ChartHold.pick("old", "new", holding = false, sameSeries = true))
        assertEquals("another range wins at once", "new", ChartHold.pick("old", "new", holding = true, sameSeries = false))
        assertEquals("new", ChartHold.pick(null, "new", holding = true, sameSeries = true))
        assertNull(ChartHold.pick("old", null, holding = false, sameSeries = true))
    }

    @Test
    fun addHoldingWaitsForTheHoldings() {
        assertFalse(DetailRules.holdingEnabled(reachable = true, holdingsLoaded = false))
        assertTrue(DetailRules.holdingEnabled(reachable = true, holdingsLoaded = true))
        assertFalse(DetailRules.holdingEnabled(reachable = false, holdingsLoaded = true))
    }

    @Test
    fun aBrokerTapWithTheAppGoneSaysSo() {
        var said = 0
        DetailRules.brokerTap(open = { true }, notInstalled = { said++ })
        assertEquals(0, said)
        DetailRules.brokerTap(open = { false }, notInstalled = { said++ })
        assertEquals(1, said)
    }

    @Test
    fun aStaleLongRangeIsDueAgainAFreshOneIsKept() {
        assertFalse(ChartCache.due(ChartRange.SixMonths, 1_000L, 9_000_000L))
        assertTrue(ChartCache.due(ChartRange.SixMonths, 1_000L, 9_000_000L, stale = true))
        assertTrue(ChartCache.due(ChartRange.Year, null, 0L))
    }

    /** The chart opts out of the back gesture on its own box only (a guard on the source: it is a Compose modifier). */
    @Test
    fun theChartExcludesItselfFromTheSystemGesture() {
        val path = "src/main/java/io/github/justcallmesu/aristotle/ui/stocks/PriceChart.kt"
        val text = listOf(File(path), File("app/$path")).first { it.exists() }.readText()
        assertEquals(1, Regex("\\.systemGestureExclusion\\(\\)").findAll(text).count())
        val screen = listOf(File(path.replace("PriceChart", "StockDetailScreen")), File("app/" + path.replace("PriceChart", "StockDetailScreen"))).first { it.exists() }.readText()
        assertFalse("not on the whole page", screen.contains("systemGestureExclusion"))
    }
}
