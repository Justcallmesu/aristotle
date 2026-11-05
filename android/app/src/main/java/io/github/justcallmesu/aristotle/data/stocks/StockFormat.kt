package io.github.justcallmesu.aristotle.data.stocks

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs

/**
 * Every stock number on screen, as pure functions (stocks-notes §0): Indonesian grouping in both app languages — "."
 * between thousands, "," before decimals — as IDX and the brokers write them. Negative values carry U+2212 (−), positive
 * changes a "+", and a no-break space sits before "%" and after "Rp" so a value never wraps.
 */
object StockFormat {
    const val MINUS = '\u2212'
    private const val NBSP = '\u00A0'
    private const val DASH = "\u2014"

    /** A whole number: 9875 -> "9.875", −2400 -> "−2.400". Prices, lots, shares. */
    fun number(value: Long): String = (if (value < 0) "$MINUS" else "") + group(abs(value).toString())

    /** Rupiah, no decimals: 4917675 -> "Rp 4.917.675". */
    fun rupiah(value: Long): String = (if (value < 0) "$MINUS" else "") + "Rp$NBSP" + group(abs(value).toString())

    /** A change in rupiah with its sign: "+Rp 110.475", "−Rp 2.400", "Rp 0". */
    fun signedRupiah(value: Long): String = sign(value.compareTo(0)) + "Rp$NBSP" + group(abs(value).toString())

    /** A price change with its sign: "+90", "−100", "0". */
    fun signedNumber(value: Long): String = sign(value.compareTo(0)) + group(abs(value).toString())

    /**
     * A percent change, two decimals and its sign: 1.86 -> "+1,86 %", −3.23 -> "−3,23 %". The sign follows the rounded
     * value, so −0.004 is "0,00 %".
     */
    fun percent(value: Double): String {
        val rounded = BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP)
        return sign(rounded.signum()) + decimal(rounded.abs(), minDecimals = 2) + "$NBSP%"
    }

    /** A fee: 0.15 -> "0,15 %", 0.2 -> "0,20 %", 1 -> "1,00 %". */
    fun fee(value: Double): String = decimal(BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).abs(), minDecimals = 2) + "$NBSP%"

    /** An average price as kept (0.01 IDR): 4800.0 -> "4.800", 4812.51 -> "4.812,51". */
    fun price(value: Double): String {
        val rounded = BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP)
        return (if (rounded.signum() < 0) "$MINUS" else "") + decimal(rounded.abs(), minDecimals = 0)
    }

    /** A value that may be missing: the em dash stands in. */
    fun orDash(value: Long?, format: (Long) -> String): String = value?.let(format) ?: DASH

    val missing: String get() = DASH

    private fun sign(signum: Int): String = when {
        signum > 0 -> "+"
        signum < 0 -> "$MINUS"
        else -> ""
    }

    /** [value] (non-negative) with grouped whole part and [minDecimals]..2 decimals, trailing zeros dropped above the minimum. */
    private fun decimal(value: BigDecimal, minDecimals: Int): String {
        val plain = value.setScale(2, RoundingMode.HALF_UP).toPlainString()
        val whole = plain.substringBefore('.')
        var fraction = plain.substringAfter('.', "")
        while (fraction.length > minDecimals && fraction.endsWith('0')) fraction = fraction.dropLast(1)
        return group(whole) + if (fraction.isEmpty()) "" else ",$fraction"
    }

    private fun group(digits: String): String = digits.reversed().chunked(3).joinToString(".").reversed()
}
