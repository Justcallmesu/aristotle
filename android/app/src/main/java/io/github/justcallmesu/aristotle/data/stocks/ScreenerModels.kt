package io.github.justcallmesu.aristotle.data.stocks

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// The screener bodies (docs/protocol.md §20.2 jobs/universe, §20.3 screener). Read with HermesJson like the rest of §20:
// unknown keys are ignored and a missing optional field takes its default, so a newer server never fails a whole answer.

/** `?mode=`: today's scans (`intraday`) or the last completed session's screen (`eod`). */
enum class ScreenerMode(val query: String) {
    Intraday("intraday"),
    Eod("eod");

    companion object {
        fun of(text: String?): ScreenerMode? = entries.firstOrNull { it.query == text }
    }
}

/** The numbers a setup fired on; only the ones it uses are set (§20.3 "Setup"). */
@Serializable
data class SetupValues(
    val high: Long? = null,
    val lookback: Int? = null,
    @SerialName("vol_ratio") val volRatio: Double? = null,
    val rsi: Double? = null,
    @SerialName("gap_pct") val gapPct: Double? = null,
    val open: Long? = null,
    val filled: Boolean? = null,
    val ma20: Double? = null,
    val ma50: Double? = null,
)

/** One fired setup: [id] `breakout|volume_spike|ma_cross|rsi_rebound|gap`, [dir] `up|down`, [date] the session it is about. */
@Serializable
data class ScreenerSetup(
    val id: String,
    val dir: String = UP,
    val date: String = "",
    @SerialName("first_at") val firstAt: String? = null,
    val values: SetupValues = SetupValues(),
) {
    val bearish: Boolean get() = dir == DOWN

    companion object {
        const val BREAKOUT = "breakout"
        const val VOLUME_SPIKE = "volume_spike"
        const val MA_CROSS = "ma_cross"
        const val RSI_REBOUND = "rsi_rebound"
        const val GAP = "gap"
        const val UP = "up"
        const val DOWN = "down"
    }
}

/**
 * A candidate (≥ 1 bullish setup, liquid) or a bearish flag on a watchlist/holding code (Q106). Flags carry no [val20],
 * [score] or [atAra]: they keep the defaults here.
 */
@Serializable
data class ScreenerRow(
    val code: String,
    val name: String = "",
    @SerialName("in_watchlist") val inWatchlist: Boolean = false,
    val held: Boolean = false,
    val price: Long? = null,
    @SerialName("change_pct") val changePct: Double? = null,
    val val20: Double = 0.0,
    val score: Double = 0.0,
    @SerialName("at_ara") val atAra: Boolean = false,
    val setups: List<ScreenerSetup> = emptyList(),
)

@Serializable
data class ScreenerUniverse(
    val count: Int = 0,
    val source: String = "",
    @SerialName("valid_to") val validTo: String = "",
    val expired: Boolean = false,
)

/** The last run of the shown mode; [nextRun] is the `screener.scan` slot (intraday) or `bars.daily` (eod). */
@Serializable
data class ScreenerScan(
    val running: Boolean = false,
    @SerialName("last_run") val lastRun: String? = null,
    @SerialName("next_run") val nextRun: String? = null,
    val status: String? = null,
    val error: String? = null,
    @SerialName("codes_ok") val codesOk: Int = 0,
    @SerialName("codes_failed") val codesFailed: Int = 0,
    @SerialName("codes_stale") val codesStale: Int = 0,
) {
    companion object {
        const val PARTIAL = "partial"
        const val FAILED = "failed"
        const val NO_BARS = "no_bars"
        const val UPSTREAM_BLOCKED = "upstream_blocked"
    }
}

@Serializable
data class ScreenerSkipped(val illiquid: Int = 0, @SerialName("no_bars") val noBars: Int = 0)

/** `GET /v1/stocks/screener`. No result yet: [sessionDate] / [dataAsOf] null and no candidates. */
@Serializable
data class ScreenerBody(
    val at: String = "",
    val mode: String = "",
    @SerialName("session_date") val sessionDate: String? = null,
    @SerialName("data_as_of") val dataAsOf: String? = null,
    val stale: Boolean = false,
    val market: MarketStatus = MarketStatus(),
    val universe: ScreenerUniverse = ScreenerUniverse(),
    val scan: ScreenerScan = ScreenerScan(),
    val skipped: ScreenerSkipped = ScreenerSkipped(),
    val candidates: List<ScreenerRow> = emptyList(),
    val flags: List<ScreenerRow> = emptyList(),
) {
    val screenerMode: ScreenerMode? get() = ScreenerMode.of(mode)
}

/** `202` of `POST /v1/stocks/screener/scan`: the mode the scan runs in and its scan block (`running`). */
@Serializable
data class ScanStarted(val mode: String = "", val scan: ScreenerScan = ScreenerScan())

@Serializable
data class BreakoutSettings(val on: Boolean = true, val lookback: Int = 20, @SerialName("vol_mult") val volMult: Double = 1.5)

@Serializable
data class VolumeSpikeSettings(val on: Boolean = true, val mult: Double = 2.0)

@Serializable
data class MaCrossSettings(val on: Boolean = true)

@Serializable
data class RsiReboundSettings(val on: Boolean = true, val level: Double = 30.0)

@Serializable
data class GapSettings(val on: Boolean = true, @SerialName("min_pct") val minPct: Double = 2.0, val up: Boolean = true, val down: Boolean = true)

@Serializable
data class SetupSettings(
    val breakout: BreakoutSettings = BreakoutSettings(),
    @SerialName("volume_spike") val volumeSpike: VolumeSpikeSettings = VolumeSpikeSettings(),
    @SerialName("ma_cross") val maCross: MaCrossSettings = MaCrossSettings(),
    @SerialName("rsi_rebound") val rsiRebound: RsiReboundSettings = RsiReboundSettings(),
    val gap: GapSettings = GapSettings(),
)

/** `GET`/`PUT /v1/stocks/screener/settings`; the defaults are the server's (Q111). The scan interval is the job's. */
@Serializable
data class ScreenerSettings(@SerialName("min_value_bn") val minValueBn: Double = 5.0, val setups: SetupSettings = SetupSettings())

/** One scheduler job (§20.2). */
@Serializable
data class JobView(
    val id: String,
    val on: Boolean = false,
    val at: String? = null,
    @SerialName("every_min") val everyMin: Int? = null,
    val running: Boolean = false,
    @SerialName("last_due") val lastDue: String? = null,
    @SerialName("last_run") val lastRun: String? = null,
    @SerialName("last_status") val lastStatus: String? = null,
    @SerialName("last_error") val lastError: String? = null,
    val late: Boolean = false,
    @SerialName("next_run") val nextRun: String? = null,
    val editable: List<String> = emptyList(),
) {
    companion object {
        const val SCAN = "screener.scan"
    }
}

@Serializable
internal data class JobsBody(val jobs: List<JobView> = emptyList())

/** `GET`/`PUT`/`DELETE /v1/stocks/universe`: [source] `bundled|file`, [count] distinct codes of LQ45 ∪ IDX80. */
@Serializable
data class UniverseBody(
    val source: String = "",
    @SerialName("valid_from") val validFrom: String? = null,
    @SerialName("valid_to") val validTo: String = "",
    val expired: Boolean = false,
    val count: Int = 0,
    val lq45: List<String> = emptyList(),
    val idx80: List<String> = emptyList(),
    @SerialName("file_error") val fileError: String? = null,
) {
    companion object {
        const val BUNDLED = "bundled"
        const val FILE = "file"
    }
}
