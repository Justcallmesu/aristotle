package io.github.justcallmesu.aristotle.data.stocks

import java.math.BigDecimal
import java.math.RoundingMode

/** P/L after broker fees (spec Q88, protocol §20), the server's exact rule, for the edit sheet's preview. Pure. */
object HoldingMath {
    const val LOT_SIZE = 100L
    private val HUNDRED = BigDecimal(100)

    data class Preview(val shares: Long, val cost: Long, val value: Long?, val pl: Long?, val plPct: Double?)

    /**
     * shares = lots × 100; cost = avg × shares × (1 + buy %); value = price × shares × (1 − sell %); pl = value − cost;
     * pl % = pl / cost × 100 to 2 decimals. Each amount is rounded half away from zero once, from the exact product; fees
     * count to 0.0001 % and the average to 0.01 IDR, as on the server. Without a [price] only the cost is known.
     */
    fun preview(lots: Long, avgPrice: Double, price: Long?, fees: StockFees): Preview {
        val shares = lots * LOT_SIZE
        val avg = BigDecimal.valueOf(avgPrice).setScale(2, RoundingMode.HALF_UP)
        val cost = avg.multiply(BigDecimal(shares)).multiply(BigDecimal.ONE + fraction(fees.buyPct)).whole()
        if (price == null) return Preview(shares, cost, null, null, null)
        val value = BigDecimal(price).multiply(BigDecimal(shares)).multiply(BigDecimal.ONE - fraction(fees.sellPct)).whole()
        val pl = value - cost
        val pct = if (cost > 0) BigDecimal(pl).multiply(HUNDRED).divide(BigDecimal(cost), 2, RoundingMode.HALF_UP).toDouble() else 0.0
        return Preview(shares, cost, value, pl, pct)
    }

    private fun fraction(pct: Double): BigDecimal = BigDecimal.valueOf(pct).setScale(4, RoundingMode.HALF_UP).divide(HUNDRED)

    private fun BigDecimal.whole(): Long = setScale(0, RoundingMode.HALF_UP).longValueExact()
}

/** What is wrong with a typed lots or average price (protocol §20 limits). */
enum class FieldProblem { Empty, NotANumber, NotWhole, TooManyDecimals, TooSmall, TooLarge }

/** A parsed field: the value, or why there is none. */
data class Parsed<T>(val value: T?, val problem: FieldProblem?) {
    companion object {
        fun <T> ok(value: T) = Parsed(value, null)
        fun <T> bad(problem: FieldProblem) = Parsed<T>(null, problem)
    }
}

/**
 * The holding sheet's checks, as the server applies them (§20): lots a whole number 1..10 000 000, the average price
 * above 0 and at most 10 000 000 IDR with at most two decimals. Numbers are typed the Indonesian way ("4.812,51") or
 * with a decimal point ("4812.51"); a point followed by exactly three digits groups thousands ("4.800" is 4800).
 * No IDX tick check: an average of several buys is legitimately off the tick.
 */
object HoldingInput {
    const val MAX_LOTS = 10_000_000L
    const val MAX_AVG_PRICE = 10_000_000.0
    private val DIGITS = Regex("\\d+")

    fun lots(text: String): Parsed<Long> {
        val raw = text.filterNot { it.isWhitespace() }
        if (raw.isEmpty()) return Parsed.bad(FieldProblem.Empty)
        val number = normalise(raw) ?: return Parsed.bad(FieldProblem.NotANumber)
        if (number.stripTrailingZeros().scale() > 0) return Parsed.bad(FieldProblem.NotWhole)
        return when {
            number < BigDecimal.ONE -> Parsed.bad(FieldProblem.TooSmall)
            number > BigDecimal(MAX_LOTS) -> Parsed.bad(FieldProblem.TooLarge)
            else -> Parsed.ok(number.toLong())
        }
    }

    fun avgPrice(text: String): Parsed<Double> {
        val raw = text.filterNot { it.isWhitespace() }.removePrefix("Rp").removePrefix("rp")
        if (raw.isEmpty()) return Parsed.bad(FieldProblem.Empty)
        val number = normalise(raw) ?: return Parsed.bad(FieldProblem.NotANumber)
        return when {
            number.stripTrailingZeros().scale() > 2 -> Parsed.bad(FieldProblem.TooManyDecimals)
            number.signum() <= 0 -> Parsed.bad(FieldProblem.TooSmall)
            number > BigDecimal.valueOf(MAX_AVG_PRICE) -> Parsed.bad(FieldProblem.TooLarge)
            else -> Parsed.ok(number.toDouble())
        }
    }

    /**
     * An optional stop or target (Q104): empty is none; else a whole price above 0 and at most 10 000 000 IDR on the IDX
     * tick grid of its own band ([IdxTick], as the server checks it). Typed like the average ("4.600" is 4600).
     */
    fun level(text: String): LevelField {
        val raw = text.filterNot { it.isWhitespace() }.removePrefix("Rp").removePrefix("rp")
        if (raw.isEmpty()) return LevelField(null, null)
        val number = normalise(raw) ?: return LevelField(null, LevelProblem.NotANumber)
        if (number.stripTrailingZeros().scale() > 0) return LevelField(null, LevelProblem.NotWhole)
        if (number.signum() <= 0) return LevelField(null, LevelProblem.TooSmall)
        if (number > BigDecimal.valueOf(MAX_AVG_PRICE)) return LevelField(null, LevelProblem.TooLarge)
        val price = number.toLong()
        val tick = IdxTick.of(price)
        if (price % tick != 0L) {
            val below = price - price % tick
            return LevelField(null, LevelProblem.OffTick, tick = tick, near = below to below + tick)
        }
        return LevelField(price, null)
    }

    /**
     * Both levels against the average, as the server checks them (§20 `checkLevels`): each on the grid, the stop below the
     * average and below the target. The server sets no rule between the target and the average.
     */
    fun levels(stopText: String, targetText: String, avgPrice: Double?): HoldingLevels {
        var stop = level(stopText)
        val target = level(targetText)
        val s = stop.value
        if (s != null) {
            when {
                avgPrice != null && s >= avgPrice -> stop = stop.copy(value = null, problem = LevelProblem.NotBelowAverage)
                target.value != null && s >= target.value -> stop = stop.copy(value = null, problem = LevelProblem.NotBelowTarget)
            }
        }
        return HoldingLevels(stop, target)
    }

    /** The field text for a stored level: 4600 -> "4600" (no grouping while editing). */
    fun levelText(value: Long?): String = value?.toString().orEmpty()

    /** The text for a stored average price in the field: 4800.0 -> "4800", 4812.51 -> "4812,51" (no grouping while editing). */
    fun avgPriceText(value: Double): String =
        BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString().replace('.', ',')

    /** "4.812,51" / "4812,51" / "4812.51" / "4.800" to a number; null when it is not one. */
    private fun normalise(raw: String): BigDecimal? {
        val plain = when {
            ',' in raw -> {
                if (raw.count { it == ',' } > 1) return null
                raw.replace(".", "").replace(',', '.')
            }
            raw.count { it == '.' } == 1 && raw.substringAfter('.').length in 1..2 -> raw
            else -> {
                // Grouping points only: each group after the first has three digits.
                val groups = raw.split('.')
                if (groups.size > 1 && groups.drop(1).any { it.length != 3 }) return null
                raw.replace(".", "")
            }
        }
        val parts = plain.split('.')
        if (parts.size > 2 || parts.any { !DIGITS.matches(it) }) return null
        return runCatching { BigDecimal(plain) }.getOrNull()
    }
}

/** What is wrong with a typed stop or target (Q104, protocol §20). */
enum class LevelProblem { NotANumber, NotWhole, TooSmall, TooLarge, OffTick, NotBelowAverage, NotBelowTarget }

/** A typed level: the price (null = none or invalid), why it is invalid, and for an off-tick price its [tick] and the two [near] prices. */
data class LevelField(val value: Long?, val problem: LevelProblem?, val tick: Long? = null, val near: Pair<Long, Long>? = null)

/** The holding sheet's two levels; [valid] when neither has a problem (both may be empty). */
data class HoldingLevels(val stop: LevelField, val target: LevelField) {
    val valid: Boolean get() = stop.problem == null && target.problem == null
}

/** Broker fee fields in Settings › Stocks (Q88): 0..5 %, at most four decimals ("0,15" or "0.15"). */
object FeeInput {
    fun parse(text: String): Parsed<Double> {
        val raw = text.filterNot { it.isWhitespace() }.removeSuffix("%").replace(',', '.')
        if (raw.isEmpty()) return Parsed.bad(FieldProblem.Empty)
        val number = runCatching { BigDecimal(raw) }.getOrNull()?.takeIf { raw.all { it.isDigit() || it == '.' } }
            ?: return Parsed.bad(FieldProblem.NotANumber)
        return when {
            number.stripTrailingZeros().scale() > 4 -> Parsed.bad(FieldProblem.TooManyDecimals)
            number > BigDecimal.valueOf(StockFees.MAX_PCT) -> Parsed.bad(FieldProblem.TooLarge)
            else -> Parsed.ok(number.toDouble())
        }
    }

    /** The field text for a stored fee: 0.15 -> "0,15", 0.2 -> "0,2", 0.0 -> "0". */
    fun text(value: Double): String = BigDecimal.valueOf(value).stripTrailingZeros().let { if (it.signum() == 0) BigDecimal.ZERO else it }
        .toPlainString().replace('.', ',')
}
