package io.github.justcallmesu.aristotle.data.stocks

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

// The 08:30 brief (protocol §20.4, spec Q18, Q105, Q108, Q110): `GET /v1/stocks/brief[?date]`, `GET /v1/stocks/briefs`,
// `POST /v1/stocks/brief`. The model writes no prices: it cites ids (`lvl:BBCA:r1`) and the phone renders the numbers the
// server stored in `refs`, so nothing on the page is a number the model typed (Q97). Pure apart from the models.

@Serializable
data class BriefFocus(val code: String = "", val stance: String = "", val note: String = "", val sources: List<String> = emptyList())

@Serializable
data class BriefPoint(val text: String = "", val sources: List<String> = emptyList())

@Serializable
data class BriefResult(
    val headline: String = "",
    val summary: String = "",
    val focus: List<BriefFocus> = emptyList(),
    val news: List<BriefPoint> = emptyList(),
    val risks: List<BriefPoint> = emptyList(),
)

/**
 * A day's brief. [state] `pending|running|done|failed|missed`; [result] is the day's last good brief (a failed rerun
 * keeps it); [refs] the DATA values by id; [news] the headlines `news:N` stands for.
 */
@Serializable
data class BriefBody(
    val date: String = "",
    val state: String = "",
    val late: Boolean = false,
    val trigger: String = "",
    val error: String? = null,
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("finished_at") val finishedAt: String? = null,
    val model: String? = null,
    val lang: String? = null,
    val repaired: Boolean = false,
    val result: BriefResult? = null,
    val refs: JsonObject = JsonObject(emptyMap()),
    val news: List<AnalysisNews> = emptyList(),
) {
    val running: Boolean get() = state == RUNNING

    companion object {
        const val PENDING = "pending"
        const val RUNNING = "running"
        const val DONE = "done"
        const val FAILED = "failed"
        const val MISSED = "missed"
    }
}

/** One row of `GET /v1/stocks/briefs` (newest first, ≤ 7). */
@Serializable
data class BriefSummary(val date: String = "", val state: String = "", val late: Boolean = false, val headline: String? = null)

@Serializable
internal data class BriefsBody(val briefs: List<BriefSummary> = emptyList())

/** `202` to `POST /v1/stocks/brief`. */
@Serializable
data class BriefStarted(val date: String = "", val state: String = "")

/** Stocks home's "Today's brief" card, before it becomes words. */
sealed interface BriefCard {
    /** Nothing read yet (or the PC out of reach before a first read). */
    data object Unknown : BriefCard

    /** Today's brief is there; [running]: a rerun is on its way. */
    data class Ready(val headline: String, val late: Boolean, val running: Boolean) : BriefCard

    /** Today is a trading day and the brief has not run yet; [at] the job's time ("08:30"). */
    data class Pending(val at: String?) : BriefCard

    data object Running : BriefCard

    /** Today is a trading day but no brief came and none will: the brief job was turned on after its slot (Q115). */
    data object NoneToday : BriefCard

    /** Today's run failed and there is no good brief of the day ([error] the server's code). */
    data class Failed(val error: String?) : BriefCard

    /** The PC was off at 08:30 and it was too late to catch up (Q31). */
    data object Missed : BriefCard

    /** No brief today: a weekend or holiday (Q108); [last] the newest kept brief's day, if any. */
    data class NotTradingDay(val last: LocalDate?) : BriefCard

    /** The brief job is off on the PC (Settings › Stocks); [last] as above. */
    data class Off(val last: LocalDate?) : BriefCard
}

object BriefStates {
    /** From this WIB time on, a `pending` brief will not come: the job's slot is long past (Q115). */
    val GIVE_UP_AT: LocalTime = LocalTime.of(9, 0)

    /**
     * The card for the latest brief [body] (null: nothing read; [missing]: the server has none, `404 no_brief`) on WIB
     * [today] at [now]. The server answers today's record (or `pending`) only on a trading day with the job on (§20.4);
     * an older latest means no brief today: the job off when [briefJobOn] says so, not a trading day when it says on,
     * and unknown until `/jobs` has answered (null). A `pending` at or after [GIVE_UP_AT] is "no brief today": the job
     * was turned on after its slot (Q115).
     */
    fun card(body: BriefBody?, missing: Boolean, today: LocalDate, now: LocalTime, briefJobOn: Boolean?, briefAt: String?): BriefCard {
        val date = body?.let { runCatching { LocalDate.parse(it.date) }.getOrNull() }
        if (body == null || date == null || date < today) {
            if (body == null && !missing) return BriefCard.Unknown
            return when (briefJobOn) {
                null -> BriefCard.Unknown
                false -> BriefCard.Off(date)
                true -> BriefCard.NotTradingDay(date)
            }
        }
        return ofBody(body, now, briefAt)
    }

    /**
     * What one brief [body] of the day says: the page's status line and the card's tail share it. A brief that is there
     * stays [BriefCard.Ready] (running and late are its flags); without one, the state decides, and a `pending` at or
     * after [GIVE_UP_AT] is [BriefCard.NoneToday].
     */
    fun ofBody(body: BriefBody, now: LocalTime, briefAt: String?): BriefCard {
        body.result?.let { return BriefCard.Ready(it.headline, body.late, body.running) }
        return when (body.state) {
            BriefBody.RUNNING -> BriefCard.Running
            BriefBody.FAILED -> BriefCard.Failed(body.error)
            BriefBody.MISSED -> BriefCard.Missed
            else -> if (now >= GIVE_UP_AT) BriefCard.NoneToday else BriefCard.Pending(briefAt)
        }
    }
}

/** A cited id as the brief page shows it: its kind and the value the server stored. */
enum class BriefCiteKind { Close, LastChange, DistMa20, DistMa50, Rsi14, Trend, Support1, Resistance1, Pivot, Ara, Arb, HoldProfit, Verdict, Screener, News, Other }

/**
 * One cited id. [code] the stock it is about (null for `news:N`); [value] the stored number already formatted (null when
 * the id has none: the page shows the label only); [words] wire words the page translates (trend, verdict + confidence +
 * trigger, setup ids); [news] the headline of a `news:N`.
 */
data class BriefCite(
    val id: String,
    val code: String?,
    val kind: BriefCiteKind,
    val value: String? = null,
    val words: List<String> = emptyList(),
    val news: AnalysisNews? = null,
)

/** Turns the brief's cited ids into what the page draws, from the server's `refs` only (Q97: no number from the model). */
object BriefRefs {
    fun cite(id: String, body: BriefBody): BriefCite {
        if (id.startsWith("news:")) return BriefCite(id, null, BriefCiteKind.News, news = body.news.firstOrNull { it.id == id })
        val parts = id.split(':')
        val prefix = parts.getOrNull(0).orEmpty()
        val code = parts.getOrNull(1)?.takeIf { StockCodes.valid(it) }
        val field = parts.getOrNull(2).orEmpty()
        val ref = body.refs[id]
        if (code == null) return BriefCite(id, null, BriefCiteKind.Other)
        return when ("$prefix:$field") {
            "ind:close" -> BriefCite(id, code, BriefCiteKind.Close, price(ref))
            "ind:change_pct" -> BriefCite(id, code, BriefCiteKind.LastChange, percent(ref))
            "ind:dist_ma20_pct" -> BriefCite(id, code, BriefCiteKind.DistMa20, percent(ref))
            "ind:dist_ma50_pct" -> BriefCite(id, code, BriefCiteKind.DistMa50, percent(ref))
            "ind:rsi14" -> BriefCite(id, code, BriefCiteKind.Rsi14, oneDecimal(ref))
            "ind:trend" -> BriefCite(id, code, BriefCiteKind.Trend, words = listOfNotNull(text(ref)))
            "lvl:s1" -> BriefCite(id, code, BriefCiteKind.Support1, price(ref))
            "lvl:r1" -> BriefCite(id, code, BriefCiteKind.Resistance1, price(ref))
            "lvl:pivot" -> BriefCite(id, code, BriefCiteKind.Pivot, price(ref))
            "lvl:ara" -> BriefCite(id, code, BriefCiteKind.Ara, price(ref))
            "lvl:arb" -> BriefCite(id, code, BriefCiteKind.Arb, price(ref))
            "hold:pl_pct" -> BriefCite(id, code, BriefCiteKind.HoldProfit, percent(ref))
            "vd:" -> (ref as? JsonObject).let { o ->
                BriefCite(id, code, BriefCiteKind.Verdict, words = listOf(text(o?.get("verdict")).orEmpty(), text(o?.get("confidence")).orEmpty(), text(o?.get("trigger")).orEmpty()))
            }
            "scr:" -> (ref as? JsonObject).let { o ->
                BriefCite(id, code, BriefCiteKind.Screener, oneDecimal(o?.get("score")), (o?.get("setups") as? JsonArray)?.mapNotNull { text(it) }.orEmpty())
            }
            else -> BriefCite(id, code, BriefCiteKind.Other)
        }
    }

    /** Every cite of [sources] in order, each once. */
    fun cites(sources: List<String>, body: BriefBody): List<BriefCite> = sources.distinct().map { cite(it, body) }

    private fun number(ref: JsonElement?): Double? = (ref as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull

    private fun price(ref: JsonElement?): String? = (ref as? JsonPrimitive)?.takeIf { !it.isString }?.let { p -> p.longOrNull ?: p.doubleOrNull?.let { Math.round(it) } }?.let(StockFormat::number)

    private fun percent(ref: JsonElement?): String? = number(ref)?.let(StockFormat::percent)

    /** RSI and scores: one decimal, Indonesian comma ("38,2"). */
    private fun oneDecimal(ref: JsonElement?): String? = number(ref)?.let { BigDecimal.valueOf(it).setScale(1, RoundingMode.HALF_UP).toPlainString().replace('.', ',') }

    private fun text(ref: JsonElement?): String? = (ref as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
}

/** A focus stock's stance (`stock_brief/1`). */
enum class BriefStance(val wire: String) {
    WatchBuy("watch_buy"),
    Watch("watch"),
    Avoid("avoid"),
    ;

    companion object {
        fun of(wire: String): BriefStance? = entries.firstOrNull { it.wire == wire }
    }
}
