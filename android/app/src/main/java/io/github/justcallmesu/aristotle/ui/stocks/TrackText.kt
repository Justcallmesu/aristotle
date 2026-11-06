package io.github.justcallmesu.aristotle.ui.stocks

import androidx.annotation.StringRes
import io.github.justcallmesu.aristotle.R
import io.github.justcallmesu.aristotle.data.stocks.RecordLine
import io.github.justcallmesu.aristotle.data.stocks.StockFormat
import io.github.justcallmesu.aristotle.data.stocks.Verdict

/** A sentence as a string resource and its arguments (numbers already formatted), so what it says is testable without Android. */
data class Words(@StringRes val res: Int, val args: List<String> = emptyList())

/** The verdict-card line (Q124): "AI record on [verdict]: [body]", or the no-history line. */
sealed interface LineWords {
    data object None : LineWords

    /** A [body] of null reads "not enough calls yet". */
    data class Sentence(val verdict: Verdict, val body: Words?) : LineWords
}

/** Which words say a record, per verdict type: Buy today / Add hit the target, Hold held above the stop, Avoid / Sell were right (Q121 note). */
object TrackText {
    /** "11 of 21 hit target" / "16 of 26 were right" / "18 of 22 held above the stop". */
    fun hits(verdict: Verdict, hit: Int, decided: Int): Words {
        val res = when (verdict) {
            Verdict.BuyToday, Verdict.Add -> R.string.stocks_record_hits_target
            Verdict.Hold -> R.string.stocks_record_hits_held
            Verdict.Avoid, Verdict.Sell -> R.string.stocks_record_hits_right
            Verdict.Wait -> error("a Wait has no hit rate (Q120): use waitAverage")
        }
        return Words(res, listOf(hit.toString(), decided.toString()))
    }

    fun waitAverage(avgPct: Double): Words = Words(R.string.stocks_record_wait_avg, listOf(StockFormat.percent(avgPct)))

    /** "Not enough calls yet · 8 of 10". */
    fun few(sample: Int, needed: Int): Words = Words(R.string.stocks_record_few_n, listOf(sample.toString(), needed.toString()))

    fun line(line: RecordLine): LineWords = when (line) {
        RecordLine.None -> LineWords.None
        is RecordLine.Few -> LineWords.Sentence(line.verdict, null)
        is RecordLine.Hits -> LineWords.Sentence(line.verdict, hits(line.verdict, line.hit, line.decided))
        is RecordLine.WaitAverage -> LineWords.Sentence(Verdict.Wait, waitAverage(line.avgPct))
    }

    @StringRes
    fun verdictName(verdict: Verdict): Int = when (verdict) {
        Verdict.BuyToday -> R.string.stocks_verdict_buy_today
        Verdict.Wait -> R.string.stocks_verdict_wait
        Verdict.Avoid -> R.string.stocks_verdict_avoid
        Verdict.Add -> R.string.stocks_verdict_add
        Verdict.Hold -> R.string.stocks_verdict_hold
        Verdict.Sell -> R.string.stocks_verdict_sell
    }
}
