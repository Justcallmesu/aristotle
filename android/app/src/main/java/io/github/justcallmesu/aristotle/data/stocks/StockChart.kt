package io.github.justcallmesu.aristotle.data.stocks

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The detail page's ranges (board: 1D 5D 1M 6M 1Y; no 5Y). [query] is the server's `?range=`; the server picks the bar
 * interval (protocol §20: 5m, 15m, 1h, 1d, 1d).
 */
enum class ChartRange(val label: String, val query: String) {
    Day("1D", "1d"),
    FiveDays("5D", "5d"),
    Month("1M", "1mo"),
    SixMonths("6M", "6mo"),
    Year("1Y", "1y");

    /** Bars shorter than a day: a bar's time carries the clock. */
    val intraday: Boolean get() = this == Day || this == FiveDays || this == Month

    companion object {
        /** The board's first view ("Stock detail · held, 1M candles"). */
        val DEFAULT = Month
    }
}

/** IDX price steps by band (protocol §20 `tick`): < 200: 1, < 500: 2, < 2 000: 5, < 5 000: 10, else 25. */
object IdxTick {
    fun of(price: Long): Long = when {
        price < 200 -> 1
        price < 500 -> 2
        price < 2_000 -> 5
        price < 5_000 -> 10
        else -> 25
    }
}

/**
 * Prices [lo]..[hi] (already padded) onto a panel, [lo] at its bottom, with grid prices every [step] (a multiple of the
 * IDX tick at the top of the range, so every grid line is a price that can trade). Pure.
 */
class PriceScale(val lo: Double, val hi: Double) {
    val step: Long = niceStep(hi - lo, IdxTick.of(hi.toLong()))

    /** Grid prices inside the range, low to high (none below Rp1). */
    val ticks: List<Long> = buildList {
        var v = ceil(lo / step).toLong() * step
        while (v <= hi) {
            if (v >= 1) add(v)
            v += step
        }
    }

    fun y(price: Double, top: Float, height: Float): Float = top + ((hi - price) / (hi - lo) * height).toFloat()

    fun y(price: Long, top: Float, height: Float): Float = y(price.toDouble(), top, height)

    companion object {
        private const val PAD = 0.08
        private const val GRID_LINES = 4.0
        private val NICE = doubleArrayOf(1.0, 2.0, 2.5, 5.0, 10.0)

        /**
         * The bars' lows to highs, plus [include] (1D: the previous close), padded by 8 % of the span and at least one
         * tick each side, so a flat series or a single bar still has height. Null without bars.
         */
        fun of(bars: List<ChartBar>, include: Long? = null): PriceScale? {
            if (bars.isEmpty()) return null
            var lo = bars.minOf { it.l }.toDouble()
            var hi = bars.maxOf { it.h }.toDouble()
            include?.let {
                lo = min(lo, it.toDouble())
                hi = max(hi, it.toDouble())
            }
            val pad = max((hi - lo) * PAD, IdxTick.of(hi.toLong()).toDouble())
            return PriceScale(lo - pad, hi + pad)
        }

        /** About four grid lines: 1, 2, 2.5 or 5 × 10ⁿ, rounded up to a whole number of [tick]s. */
        fun niceStep(span: Double, tick: Long): Long {
            val raw = span / GRID_LINES
            if (raw <= 0.0) return tick
            val mag = 10.0.pow(floor(log10(raw)))
            val nice = NICE.first { it * mag >= raw } * mag
            return max(tick, ceil(nice / tick).toLong() * tick)
        }
    }
}

/**
 * Bars on the x axis by **slot**, not wall time, so the lunch break, nights and weekends take no room (P3). Each bar
 * sits in the middle of its slot; [slots] may exceed the bars (1D keeps room for the rest of the day). [breaks] are slot
 * boundaries to mark (1D: the session break; 5D: each new day). Pure.
 */
class BarAxis private constructor(val slots: Int, private val positions: IntArray, val breaks: List<Int>) {
    val size: Int get() = positions.size

    fun slotOf(index: Int): Int = positions[index]

    /** The centre of bar [index] in a plot [width] wide starting at [left]. */
    fun x(index: Int, left: Float, width: Float): Float = left + (positions[index] + 0.5f) * width / slots

    /** The left edge of [slot] (break lines). */
    fun slotX(slot: Int, left: Float, width: Float): Float = left + slot * width / slots

    /** The middle of [slot] (time labels). */
    fun slotCentre(slot: Int, left: Float, width: Float): Float = left + (slot + 0.5f) * width / slots

    /**
     * The bar under [x] (crosshair snapping): the bar whose slot centre is nearest, the earlier one on a tie; outside
     * the plot the first or last bar. -1 without bars. No allocation (called on every pointer move).
     */
    fun barAt(x: Float, left: Float, width: Float): Int {
        if (positions.isEmpty() || width <= 0f) return -1
        val target = (x - left) / width * slots - 0.5f
        var lo = 0
        var hi = positions.size - 1
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (positions[mid] < target) lo = mid + 1 else hi = mid
        }
        if (lo > 0 && abs(positions[lo - 1] - target) <= abs(positions[lo] - target)) return lo - 1
        return lo
    }

    companion object {
        /** One slot per bar (6M, 1Y, 1M). */
        fun byIndex(count: Int): BarAxis = BarAxis(max(count, 1), IntArray(count) { it }, emptyList())

        /** One slot per bar, a break before each new WIB day (5D). */
        fun days(bars: List<ChartBar>): BarAxis {
            val breaks = (1 until bars.size).filter { dateOf(bars[it]) != dateOf(bars[it - 1]) }
            return BarAxis(max(bars.size, 1), IntArray(bars.size) { it }, breaks)
        }

        /**
         * 1D: the whole trading day of the bars' date at [intervalMinutes] (session 1, then session 2 with its closing
         * bar), the break between them collapsed and marked; bars so far fill the left part. A bar off the schedule
         * (before the open, at the session-1 end, in the break) shares the nearest session slot; an on-schedule bar
         * always takes a slot after the on-schedule bar before it.
         */
        fun day(bars: List<ChartBar>, intervalMinutes: Int): BarAxis {
            if (bars.isEmpty()) return byIndex(0)
            val sessions = IdxSessions.of(dateOf(bars.first()))
            val step = max(intervalMinutes, 1)
            val s1 = sessions.firstSlots(step)
            val positions = IntArray(bars.size)
            var previous = -1
            // The last slot an on-schedule bar took: only those push the next bar one slot on.
            var lastOnSchedule = -1
            bars.forEachIndexed { i, bar ->
                val time = timeOf(bar)
                // A bar stamped at the session end, in the break or before the open takes the nearest session slot
                // (shared with the bar there, if any) instead of pushing every later bar one slot on: a 08:55 bar
                // shares slot 0 with the 09:00 bar, a 12:00 bar shares the 11:55 slot.
                val off = sessions.isOffSchedule(time)
                val slot = max(sessions.slotOf(time, step), if (off) previous else max(lastOnSchedule + 1, previous))
                if (!off) lastOnSchedule = slot
                positions[i] = slot
                previous = slot
            }
            return BarAxis(max(s1 + sessions.secondSlots(step), previous + 1), positions, listOf(s1))
        }

        internal fun dateOf(bar: ChartBar): LocalDate = wib(bar.t).toLocalDate()

        internal fun timeOf(bar: ChartBar): LocalTime = wib(bar.t).toLocalTime()

        internal fun wib(seconds: Long): ZonedDateTime = Instant.ofEpochSecond(seconds).atZone(StockTimes.WIB)
    }
}

/** IDX regular sessions (protocol §20): Mon–Thu 09:00–12:00 and 13:30–16:00, Fri 09:00–11:30 and 14:00–16:00 (16:00 = closing). */
data class IdxSessions(val firstStart: LocalTime, val firstEnd: LocalTime, val secondStart: LocalTime, val secondEnd: LocalTime) {
    /** Bars from the open to the last bar before the break (09:00 … 11:55 at 5 minutes: 36). */
    fun firstSlots(step: Int): Int = minutes(firstStart, firstEnd) / step

    /** Bars of session 2 including the closing bar at its end (13:30 … 16:00 at 5 minutes: 31). */
    fun secondSlots(step: Int): Int = minutes(secondStart, secondEnd) / step + 1

    /** The slot of a bar at [time]: session 1 from 0, session 2 right after it; before the break ends counts as session 1. */
    fun slotOf(time: LocalTime, step: Int): Int = if (time < secondStart) {
        (max(minutes(firstStart, time), 0) / step).coerceAtMost(max(firstSlots(step) - 1, 0))
    } else {
        firstSlots(step) + minutes(secondStart, time) / step
    }

    /** Before the open, or from the end of session 1 up to the start of session 2: no slot of its own (clamped by [slotOf]). */
    fun isOffSchedule(time: LocalTime): Boolean = time < firstStart || (time >= firstEnd && time < secondStart)

    companion object {
        private val MON_THU = IdxSessions(LocalTime.of(9, 0), LocalTime.of(12, 0), LocalTime.of(13, 30), LocalTime.of(16, 0))
        private val FRIDAY = IdxSessions(LocalTime.of(9, 0), LocalTime.of(11, 30), LocalTime.of(14, 0), LocalTime.of(16, 0))

        fun of(date: LocalDate): IdxSessions = if (date.dayOfWeek == DayOfWeek.FRIDAY) FRIDAY else MON_THU

        private fun minutes(from: LocalTime, to: LocalTime): Int = (to.toSecondOfDay() - from.toSecondOfDay()) / 60
    }
}

/**
 * What fits in the chart at a large font (TM3a), pure: text is measured by the drawer and passed in pixels, so the
 * rules are tested without a canvas.
 */
object ChartFit {
    /** Smallest share a label's font may shrink to before it is dropped. */
    const val MIN_SHRINK = 0.7f

    /** The font factor that makes a label [width] fit [room]: 1 when it fits, else room / width; null (drop it) below [MIN_SHRINK]. */
    fun shrink(width: Float, room: Float): Float? = when {
        width <= room -> 1f
        room <= 0f || width <= 0f -> null
        room / width >= MIN_SHRINK -> room / width
        else -> null
    }

    /** The readout's height in dp: [baseDp] for two lines at 1x, growing with the font scale (never below [baseDp]). */
    fun readoutHeightDp(baseDp: Float, fontScale: Float): Float = baseDp * max(fontScale, 1f)

    /** Rounding room under the time labels: the box's height is rounded to whole pixels, possibly down by half a pixel. */
    const val SLACK_PX = 1f

    /**
     * The chart's height in px (TM3a2): the panels down to the labels ([labelTop]) plus the time label's line as the
     * drawer MEASURED it ([labelHeight], so the phone's non-linear font scale and the app's text size are in it) plus
     * [SLACK_PX]; never under [minimum]. A label [labelHeight] tall then always [fitsBelow] the rounded box.
     */
    fun chartHeightPx(labelTop: Float, labelHeight: Float, minimum: Float): Float = max(minimum, labelTop + labelHeight + SLACK_PX)

    /** A label [height] tall drawn at [top] ends inside a chart [total] tall. */
    fun fitsBelow(top: Float, height: Float, total: Float): Boolean = top + height <= total

    /** Line mode with a single bar has no segment to stroke: it draws a dot. */
    fun lineIsDot(barCount: Int): Boolean = barCount == 1
}

/** A time label under the chart, centred on [slot]. */
data class AxisLabel(val slot: Int, val text: String)

/** The span of a range for the readout: 1D "Today, 09:00–10:40" ([day] null on today), else "29 Oct – 5 Nov". */
data class ChartSpan(val from: String, val to: String, val day: String?)

/** Time labels in WIB, per range; the drawer passes how many slots a label needs so they never collide. Pure. */
object ChartLabels {
    private fun pattern(p: String, locale: Locale) = DateTimeFormatter.ofPattern(p, locale)

    /** Minutes per bar from the server's `interval` ("5m", "15m", "1h", "1d"); null when unreadable. */
    fun intervalMinutes(interval: String): Int? {
        val n = interval.dropLast(1).toIntOrNull() ?: return null
        return when (interval.lastOrNull()) {
            'm' -> n
            'h' -> n * 60
            'd' -> n * 60 * 24
            else -> null
        }
    }

    /**
     * 1D: the session starts and whole hours (session 2's start wins over a nearby hour); 5D: each day ("Tue 3"); 1M:
     * days ("3 Nov"); 6M / 1Y: months ("Nov", January as its year). Labels closer than [minGapSlots] are dropped.
     */
    fun axis(range: ChartRange, bars: List<ChartBar>, axis: BarAxis, interval: String, minGapSlots: Int, locale: Locale): List<AxisLabel> {
        if (bars.isEmpty()) return emptyList()
        val candidates: List<AxisLabel> = when (range) {
            ChartRange.Day -> return dayLabels(bars, interval, minGapSlots)
            ChartRange.FiveDays -> firstOfEach(bars) { BarAxis.dateOf(it) }.map { (i, bar) -> AxisLabel(axis.slotOf(i), BarAxis.wib(bar.t).format(pattern("EEE d", locale))) }
            ChartRange.Month -> firstOfEach(bars) { BarAxis.dateOf(it) }.map { (i, bar) -> AxisLabel(axis.slotOf(i), BarAxis.wib(bar.t).format(pattern("d MMM", locale))) }
            ChartRange.SixMonths, ChartRange.Year -> firstOfEach(bars) { BarAxis.dateOf(it).withDayOfMonth(1) }
                .filter { (i, _) -> i > 0 }
                .map { (i, bar) ->
                    val at = BarAxis.wib(bar.t)
                    AxisLabel(axis.slotOf(i), if (at.monthValue == 1) at.year.toString() else at.format(pattern("MMM", locale)))
                }
        }
        return thin(candidates, minGapSlots)
    }

    private fun <K> firstOfEach(bars: List<ChartBar>, key: (ChartBar) -> K): List<Pair<Int, ChartBar>> =
        bars.indices.filter { it == 0 || key(bars[it]) != key(bars[it - 1]) }.map { it to bars[it] }

    private fun dayLabels(bars: List<ChartBar>, interval: String, minGapSlots: Int): List<AxisLabel> {
        val step = intervalMinutes(interval) ?: DEFAULT_STEP
        val sessions = IdxSessions.of(BarAxis.dateOf(bars.first()))
        val clock = DateTimeFormatter.ofPattern("HH:mm")
        fun label(t: LocalTime) = AxisLabel(sessions.slotOf(t, step), t.format(clock))
        val first = label(sessions.secondStart)
        val hours = (sessions.firstStart.hour..sessions.secondEnd.hour).map { LocalTime.of(it, 0) }
            .filter { (it >= sessions.firstStart && it < sessions.firstEnd) || (it >= sessions.secondStart && it <= sessions.secondEnd) }
            .map(::label)
        return thin(listOf(first) + hours.filter { it.slot != first.slot }, minGapSlots, keepOrder = true).sortedBy { it.slot }
    }

    /** Keeps labels at least [minGap] slots from every kept one: left to right, or in the given order when [keepOrder]. */
    private fun thin(labels: List<AxisLabel>, minGap: Int, keepOrder: Boolean = false): List<AxisLabel> {
        val kept = mutableListOf<AxisLabel>()
        (if (keepOrder) labels else labels.sortedBy { it.slot }).forEach { l -> if (kept.none { abs(it.slot - l.slot) < minGap }) kept += l }
        return kept
    }

    /** A bar's time for the readout: 1D "10:10"; 5D / 1M "Wed 4 Nov, 10:15"; daily "Wed 4 Nov" (+ year when not [today]'s). */
    fun barTime(range: ChartRange, bar: ChartBar, today: LocalDate, locale: Locale): String {
        val at = BarAxis.wib(bar.t)
        return when {
            range == ChartRange.Day -> at.format(pattern("HH:mm", locale))
            range.intraday -> at.format(pattern("EEE d MMM, HH:mm", locale))
            at.year != today.year -> at.format(pattern("EEE d MMM yyyy", locale))
            else -> at.format(pattern("EEE d MMM", locale))
        }
    }

    /** The readout's span of [bars]: 1D the first and last bar's clock and, when not [today], the day. */
    fun span(range: ChartRange, bars: List<ChartBar>, today: LocalDate, locale: Locale): ChartSpan? {
        val first = bars.firstOrNull() ?: return null
        val last = bars.last()
        if (range == ChartRange.Day) {
            val clock = pattern("HH:mm", locale)
            val date = BarAxis.dateOf(first)
            return ChartSpan(BarAxis.wib(first.t).format(clock), BarAxis.wib(last.t).format(clock), if (date == today) null else StockTimes.day(date, locale))
        }
        fun day(bar: ChartBar): String {
            val at = BarAxis.wib(bar.t)
            return at.format(pattern(if (at.year != today.year) "d MMM yyyy" else "d MMM", locale))
        }
        return ChartSpan(day(first), day(last), null)
    }

    private const val DEFAULT_STEP = 5
}

/** The crosshair's bar: its change from the bar before (the first bar: from the previous close on 1D, else its open). */
data class BarReading(val index: Int, val bar: ChartBar, val change: Long, val changePct: Double?, val volumeLots: Long)

/** The range when nothing is touched: last close against the base (1D: previous close; else the first bar's open). */
data class RangeSummary(val first: ChartBar, val last: ChartBar, val high: Long, val low: Long, val base: Long, val change: Long, val changePct: Double?)

/** The readout above the chart (stocks-notes `Readout`), as numbers. Pure. */
object ChartReadout {
    fun bar(range: ChartRange, bars: List<ChartBar>, index: Int, prevClose: Long?): BarReading? {
        val bar = bars.getOrNull(index) ?: return null
        val ref = if (index > 0) bars[index - 1].c else if (range == ChartRange.Day) prevClose ?: bar.o else bar.o
        val change = bar.c - ref
        return BarReading(index, bar, change, pct(change, ref), lots(bar.v))
    }

    fun summary(range: ChartRange, bars: List<ChartBar>, prevClose: Long?): RangeSummary? {
        val first = bars.firstOrNull() ?: return null
        val last = bars.last()
        val base = if (range == ChartRange.Day) prevClose ?: first.o else first.o
        val change = last.c - base
        return RangeSummary(first, last, bars.maxOf { it.h }, bars.minOf { it.l }, base, change, pct(change, base))
    }

    /** Percent of [base] to two decimals, half up; null for a base of zero. */
    fun pct(change: Long, base: Long): Double? = if (base <= 0) {
        null
    } else {
        BigDecimal(change).multiply(BigDecimal(100)).divide(BigDecimal(base), 2, RoundingMode.HALF_UP).toDouble()
    }

    /** Shares to whole lots (a lot is 100 shares). */
    fun lots(shares: Long): Long = shares / HoldingMath.LOT_SIZE
}

/**
 * One chart ready to draw: the bars on their [axis] and [scale] (1D includes the previous close), the tallest volume.
 * Built once per answer, outside drawing.
 */
class ChartModel private constructor(
    val range: ChartRange,
    val body: ChartBody,
    val axis: BarAxis,
    val scale: PriceScale,
    val prevClose: Long?,
    val maxVolume: Long,
) {
    val bars: List<ChartBar> get() = body.bars

    companion object {
        /** Null without bars. [prevClose] is the chart's, else the quote's; only 1D draws it. */
        fun of(range: ChartRange, body: ChartBody, prevClose: Long?): ChartModel? {
            val bars = body.bars
            if (bars.isEmpty()) return null
            val pc = if (range == ChartRange.Day) prevClose else null
            val axis = when (range) {
                ChartRange.Day -> BarAxis.day(bars, ChartLabels.intervalMinutes(body.interval) ?: DAY_STEP)
                ChartRange.FiveDays -> BarAxis.days(bars)
                else -> BarAxis.byIndex(bars.size)
            }
            val scale = PriceScale.of(bars, pc) ?: return null
            return ChartModel(range, body, axis, scale, pc, bars.maxOf { it.v })
        }

        private const val DAY_STEP = 5
    }
}
