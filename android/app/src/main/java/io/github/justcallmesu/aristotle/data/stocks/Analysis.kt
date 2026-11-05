package io.github.justcallmesu.aristotle.data.stocks

import java.time.LocalDate
import java.util.Locale
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// The analyzer (protocol §20.1, TA1; spec Q96-Q103): `POST /v1/stocks/analyze/{code}` starts a job on the PC,
// `GET /v1/stocks/analysis/{code}` says how the latest job went and carries the kept verdict. Pure apart from the
// models; the page's ViewModel does the calls and the polling.

/** `202` to `POST /v1/stocks/analyze/{code}`. */
@Serializable
data class AnalysisStarted(
    @SerialName("job_id") val jobId: String = "",
    val state: String = "",
    @SerialName("started_at") val startedAt: String? = null,
)

/**
 * `GET /v1/stocks/analysis/{code}`: the latest job since the server started ([state] `idle` when there was none) and
 * [result], the kept last good verdict, shown whatever the job state (a failed job never replaces it).
 */
@Serializable
data class AnalysisStatus(
    val code: String = "",
    val state: String = IDLE,
    @SerialName("job_id") val jobId: String? = null,
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("finished_at") val finishedAt: String? = null,
    /** The job's error code when [state] is `failed` (`timeout`, `invalid_verdict`, …). */
    val error: String? = null,
    val result: AnalysisResult? = null,
) {
    val running: Boolean get() = state == RUNNING

    companion object {
        const val IDLE = "idle"
        const val RUNNING = "running"
        const val DONE = "done"
        const val FAILED = "failed"
    }
}

/** One reason of a verdict and the ids it cites (`ind:rsi14`, `lvl:s1`, `news:2`, `hold:pl_pct`). */
@Serializable
data class AnalysisReason(val text: String = "", val sources: List<String> = emptyList())

/** A headline the model was given, kept with the result so `news:N` resolves to its title, time and link. */
@Serializable
data class AnalysisNews(
    val id: String = "",
    val source: String = "",
    val title: String = "",
    val link: String = "",
    @SerialName("published_at") val publishedAt: String = "",
)

/**
 * A kept verdict (Q98). Prices are integer IDR or null: buy/add have all four, hold a stop (entry null), sell/avoid
 * none, wait an entry zone or none. [sessionDate] is the WIB trading day the verdict is for; [price] the last price
 * the analysis used. The server also sends the indicator and level values; the card does not show them.
 */
@Serializable
data class AnalysisResult(
    val code: String = "",
    val name: String = "",
    val held: Boolean = false,
    val lang: String = "",
    @SerialName("analyzed_at") val analyzedAt: String = "",
    @SerialName("data_as_of") val dataAsOf: String = "",
    @SerialName("session_date") val sessionDate: String = "",
    @SerialName("market_state") val marketState: String = "",
    val model: String = "",
    @SerialName("duration_ms") val durationMs: Long = 0,
    val verdict: String = "",
    val confidence: String = "",
    @SerialName("entry_low") val entryLow: Long? = null,
    @SerialName("entry_high") val entryHigh: Long? = null,
    val target: Long? = null,
    val stop: Long? = null,
    val summary: String = "",
    val reasons: List<AnalysisReason> = emptyList(),
    val flags: List<String> = emptyList(),
    val repaired: Boolean = false,
    val price: Long = 0,
    val ara: Long? = null,
    val arb: Long? = null,
    val news: List<AnalysisNews> = emptyList(),
)

/** Which way a verdict leans, for its colour: buy/add the stock-up tone, avoid/sell the stock-down tone, wait/hold ink. */
enum class VerdictTone { Up, Down, Neutral }

/** The six verdicts (Q98): not held → buy today / wait / avoid; held → add / hold / sell. */
enum class Verdict(val wire: String, val tone: VerdictTone) {
    BuyToday("buy_today", VerdictTone.Up),
    Wait("wait", VerdictTone.Neutral),
    Avoid("avoid", VerdictTone.Down),
    Add("add", VerdictTone.Up),
    Hold("hold", VerdictTone.Neutral),
    Sell("sell", VerdictTone.Down),
    ;

    companion object {
        fun of(wire: String): Verdict? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * The notes a kept verdict may carry (protocol §20.1 `flags`), shown quietly on the card. [NewsOnly]: a buy today / add
 * whose reasons cite only headlines (untrusted text); the server caps its confidence at medium.
 */
enum class VerdictFlag(val wire: String) {
    LowRewardRisk("low_reward_risk"),
    WideStop("wide_stop"),
    EntryFar("entry_far"),
    TargetAboveR1("target_above_r1"),
    MarketClosed("market_closed"),
    LimitsUnknown("limits_unknown"),
    NewsOnly("news_only"),
    ;

    companion object {
        /** Null for a flag this app does not know yet (the card shows its wire word). */
        fun of(wire: String): VerdictFlag? = entries.firstOrNull { it.wire == wire }
    }
}

enum class Confidence(val wire: String) {
    Low("low"),
    Medium("med"),
    High("high"),
    ;

    companion object {
        fun of(wire: String): Confidence? = entries.firstOrNull { it.wire == wire }
    }
}

/** The `lang` sent with the request (Q101): Indonesian for an Indonesian locale (`id`, or Android's legacy `in`), else English. */
object AnalysisLang {
    const val ID = "id"
    const val EN = "en"

    fun of(locale: Locale): String = if (locale.language.lowercase() in INDONESIAN) ID else EN

    private val INDONESIAN = setOf("id", "in")
}

/** Why an analysis did not give a verdict: a refused start (`POST` error) or a failed job (its `error` code). */
sealed interface AnalysisProblem {
    /** Hermes is not running or the socket was lost (`503 hermes_unavailable`, job `hermes_lost`). */
    data object HermesUnavailable : AnalysisProblem

    /** Job `hermes_refused`: Hermes answered a request with an error, or with an answer the server cannot use. */
    data object HermesRefused : AnalysisProblem

    /** `503 shutting_down`: the PC's server is stopping. */
    data object ShuttingDown : AnalysisProblem

    /** Hermes does not serve the `aristotle-stocks` profile, or the server has no analyzer. */
    data object NotSetUp : AnalysisProblem

    /** No completed daily bar (`422 no_data`) or the data could not be gathered (job `data_unavailable`). */
    data object NoData : AnalysisProblem

    /** `409 analysis_running`: a job for this code already runs (another phone, or a tap that raced). */
    data object AlreadyRunning : AnalysisProblem

    /** `429 busy`: two analyses already run on the PC. */
    data object Busy : AnalysisProblem

    /** Job `timeout`: the model did not answer within 120 s. */
    data object Timeout : AnalysisProblem

    /** Job `invalid_verdict` / `verdict_rejected`: the answer failed the checks twice (the repair turn included). */
    data object InvalidVerdict : AnalysisProblem

    data object ModelError : AnalysisProblem

    /** Job `tools_present`: the profile's session had tools, so nothing was asked. */
    data object ToolsPresent : AnalysisProblem

    /** Job `unexpected_prompt`: Hermes asked for an approval or a clarification; the turn was stopped unanswered. */
    data object UnexpectedPrompt : AnalysisProblem

    /** Job `interrupted`: the turn was stopped, or the server stopped. */
    data object Interrupted : AnalysisProblem

    /** Any other failed call (out of reach, Yahoo, `state_error`, …): said as any stocks error. */
    data class Request(val error: StocksError) : AnalysisProblem

    /** A job error code this app does not know yet. */
    data class Job(val code: String?) : AnalysisProblem

    companion object {
        fun ofPost(error: StocksError): AnalysisProblem {
            val server = error as? StocksError.Server ?: return Request(error)
            return when (server.code) {
                HERMES_UNAVAILABLE -> HermesUnavailable
                SHUTTING_DOWN -> ShuttingDown
                NOT_SET_UP -> NotSetUp
                NO_DATA -> NoData
                ANALYSIS_RUNNING -> AlreadyRunning
                BUSY -> Busy
                else -> when (server.status) {
                    CONFLICT -> AlreadyRunning
                    TOO_MANY -> Busy
                    else -> Request(error)
                }
            }
        }

        fun ofJob(code: String?): AnalysisProblem = when (code) {
            "timeout" -> Timeout
            "invalid_verdict", "verdict_rejected" -> InvalidVerdict
            "model_error" -> ModelError
            "tools_present" -> ToolsPresent
            "unexpected_prompt" -> UnexpectedPrompt
            "interrupted" -> Interrupted
            "hermes_lost" -> HermesUnavailable
            "hermes_refused" -> HermesRefused
            "data_unavailable" -> NoData
            NOT_SET_UP -> NotSetUp
            else -> Job(code)
        }

        const val HERMES_UNAVAILABLE = "hermes_unavailable"
        const val SHUTTING_DOWN = "shutting_down"
        const val NOT_SET_UP = "analyzer_not_set_up"
        const val NO_DATA = "no_data"
        const val ANALYSIS_RUNNING = "analysis_running"
        const val BUSY = "busy"
        private const val CONFLICT = 409
        private const val TOO_MANY = 429
    }
}

enum class AnalysisPhase {
    /** Nothing read yet (the PC was not reachable, or the first read is on its way). */
    Unknown,

    /** No job and no kept verdict: the Analyze button. */
    Idle,

    /** The `POST` is on its way. */
    Starting,

    /** A job runs on the PC: progress, polled. */
    Running,

    /** A kept verdict and no failure to report: the verdict card. */
    Done,

    /** The start was refused or the latest job failed: why, and Retry (the kept verdict, if any, stays below). */
    Failed,
}

/**
 * What the Analyze section shows. [result] is the kept verdict (also under a failure); [staleFrom] is set when that
 * verdict is for an earlier session than the current one ("From Wed 4 Nov — analyze again"): the WIB day it was made.
 */
data class AnalysisView(
    val phase: AnalysisPhase,
    val result: AnalysisResult? = null,
    val startedAt: String? = null,
    val problem: AnalysisProblem? = null,
    val staleFrom: LocalDate? = null,
)

/** The section's state machine and its rules, pure: the session and "now" are parameters. */
object AnalysisStates {
    /** Poll `GET /analysis` this often while a job runs and the page is visible (TA2 brief). */
    const val POLL_MILLIS = 3_000L

    /**
     * [status]: the last `GET` (null before one); [starting]: a `POST` on its way; [refused]: why the last start was
     * refused; [session]: the current session's date ([currentSession]).
     */
    fun view(status: AnalysisStatus?, starting: Boolean, refused: AnalysisProblem?, session: LocalDate): AnalysisView {
        val result = status?.result
        val staleFrom = result?.takeIf { isStale(it, session) }?.let { StockTimes.date(it.analyzedAt) ?: sessionDateOf(it) }
        return when {
            starting -> AnalysisView(AnalysisPhase.Starting, result, staleFrom = staleFrom)
            refused != null -> AnalysisView(AnalysisPhase.Failed, result, problem = refused, staleFrom = staleFrom)
            status == null -> AnalysisView(AnalysisPhase.Unknown)
            status.state == AnalysisStatus.RUNNING -> AnalysisView(AnalysisPhase.Running, result, startedAt = status.startedAt, staleFrom = staleFrom)
            status.state == AnalysisStatus.FAILED -> AnalysisView(AnalysisPhase.Failed, result, problem = AnalysisProblem.ofJob(status.error), staleFrom = staleFrom)
            result != null -> AnalysisView(AnalysisPhase.Done, result, staleFrom = staleFrom)
            else -> AnalysisView(AnalysisPhase.Idle)
        }
    }

    /**
     * The trading day a verdict made now would be for, as the server decides it (protocol §20.1 `session_date`): today
     * while the market is open or on its break; when closed, the day of the next open (later today before 09:00,
     * else the next trading day). Without a market status, [today].
     */
    fun currentSession(market: MarketStatus?, today: LocalDate): LocalDate = when (market?.state) {
        MarketStatus.OPEN, MarketStatus.BREAK -> today
        MarketStatus.CLOSED -> StockTimes.date(market.nextOpen)?.takeIf { it >= today } ?: today
        else -> today
    }

    /** A verdict for a session before [session] (an unreadable date counts as stale: it cannot be shown as today's). */
    fun isStale(result: AnalysisResult, session: LocalDate): Boolean = sessionDateOf(result)?.let { it < session } ?: true

    /** Whole seconds since [startedAt] (never negative: the PC's clock may run ahead), or null without a time. */
    fun elapsedSeconds(startedAt: String?, nowMillis: Long): Long? {
        val start = StockTimes.parse(startedAt)?.toInstant()?.toEpochMilli() ?: return null
        return ((nowMillis - start) / MILLIS_PER_SECOND).coerceAtLeast(0)
    }

    /** "0:42", "1:05", "12:00". */
    fun elapsedText(seconds: Long): String = "%d:%02d".format(Locale.ROOT, seconds / SECONDS_PER_MINUTE, seconds % SECONDS_PER_MINUTE)

    /** The model's time on the PC, rounded to whole seconds (at least one): 48 213 ms -> 48. */
    fun durationSeconds(durationMs: Long): Long = ((durationMs + MILLIS_PER_SECOND / 2) / MILLIS_PER_SECOND).coerceAtLeast(1)

    private fun sessionDateOf(result: AnalysisResult): LocalDate? = runCatching { LocalDate.parse(result.sessionDate) }.getOrNull()

    private const val MILLIS_PER_SECOND = 1_000L
    private const val SECONDS_PER_MINUTE = 60L
}

/** The card's price levels against a reference price (the live quote, else the price the analysis used). */
object VerdictLevels {
    /** Percent from [price] to [level]: (level − price) / price × 100; null without a positive price. */
    fun pctFrom(level: Long, price: Long): Double? = if (price <= 0) null else (level - price) * PERCENT / price

    /** "+3,13 %" / "−1,88 %" ([StockFormat.percent]); null without a positive price. */
    fun pctText(level: Long, price: Long): String? = pctFrom(level, price)?.let(StockFormat::percent)

    /** The entry zone: "1.595–1.600", one price when both ends meet, null without both. */
    fun zone(low: Long?, high: Long?): String? {
        if (low == null || high == null) return null
        return if (low == high) StockFormat.number(low) else "${StockFormat.number(low)}\u2013${StockFormat.number(high)}"
    }

    /** The zone's percent: "−0,31 % to 0,00 %" as two ends (the caller joins them), or one when both ends meet. */
    fun zonePct(low: Long?, high: Long?, price: Long): List<String>? {
        if (low == null || high == null) return null
        val from = pctText(low, price) ?: return null
        val to = pctText(high, price) ?: return null
        return if (low == high) listOf(from) else listOf(from, to)
    }

    /** The price the percents are from: the live quote's when there is one, else the analysis's own. */
    fun reference(result: AnalysisResult, quote: StockQuote?): Long = quote?.price?.takeIf { it > 0 } ?: result.price

    private const val PERCENT = 100.0
}

/** What a cited id stands for on the card. */
sealed interface VerdictSource {
    /** `news:N`: the headline (null when the result does not carry it). */
    data class News(val id: String, val item: AnalysisNews?) : VerdictSource

    /** An indicator, level or holding id with a known [label]. */
    data class Known(val id: String, val label: SourceLabel) : VerdictSource

    /** An id this app has no label for (a newer server): shown without its prefix. */
    data class Other(val id: String) : VerdictSource {
        val text: String get() = id.substringAfter(':')
    }
}

/**
 * The labels of the citable ids (protocol §20.1: `indicators`, `levels`, `hold:*`). [fixed] is a label that reads the
 * same in English and Indonesian (MA20, RSI 14); the others are strings.
 */
enum class SourceLabel(val id: String, val fixed: String? = null) {
    Price("ind:price"),
    Trend("ind:trend"),
    ChangeToday("ind:change_pct"),
    LastChange("ind:last_change_pct"),
    Ma20("ind:ma20", "MA20"),
    Ma50("ind:ma50", "MA50"),
    DistMa20("ind:dist_ma20_pct"),
    DistMa50("ind:dist_ma50_pct"),
    Rsi14("ind:rsi14", "RSI 14"),
    Atr14("ind:atr14", "ATR 14"),
    AvgVolume20("ind:avg_vol20"),
    VolumeToday("ind:vol_today"),
    VolumeRatio("ind:vol_ratio"),
    VolumePace("ind:vol_ratio_paced"),
    VolumeLast("ind:vol_ratio_prev"),
    High52("ind:high52"),
    Low52("ind:low52"),
    Position52("ind:pos52"),
    Open("ind:open"),
    DayHigh("ind:day_high"),
    DayLow("ind:day_low"),
    Vwap("ind:vwap", "VWAP"),
    Gap("ind:gap_pct"),
    Support1("lvl:s1"),
    Support2("lvl:s2"),
    Resistance1("lvl:r1"),
    Resistance2("lvl:r2"),
    High20("lvl:high20"),
    Low20("lvl:low20"),
    Pivot("lvl:pivot", "Pivot"),
    PivotR1("lvl:pivot_r1", "Pivot R1"),
    PivotS1("lvl:pivot_s1", "Pivot S1"),
    HoldLots("hold:lots"),
    HoldAverage("hold:avg_price"),
    HoldProfit("hold:pl_pct"),
    ;

    companion object {
        fun of(id: String): SourceLabel? = entries.firstOrNull { it.id == id }
    }
}

object VerdictSources {
    private const val NEWS_PREFIX = "news:"

    fun of(id: String, news: List<AnalysisNews>): VerdictSource = when {
        id.startsWith(NEWS_PREFIX) -> VerdictSource.News(id, news.firstOrNull { it.id == id })
        else -> SourceLabel.of(id)?.let { VerdictSource.Known(id, it) } ?: VerdictSource.Other(id)
    }
}
