package io.github.justcallmesu.aristotle.data.stocks

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import kotlin.math.abs
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * `PUT /v1/stocks/screener/settings` replaces the whole object and refuses a body without every field (§20.3), so it is
 * written out key by key: HermesJson leaves default values out, which the server would answer with `400`.
 */
object ScreenerSettingsJson {
    fun encode(s: ScreenerSettings): String = buildJsonObject {
        put("min_value_bn", s.minValueBn)
        putJsonObject("setups") {
            putJsonObject("breakout") {
                put("on", s.setups.breakout.on)
                put("lookback", s.setups.breakout.lookback)
                put("vol_mult", s.setups.breakout.volMult)
            }
            putJsonObject("volume_spike") {
                put("on", s.setups.volumeSpike.on)
                put("mult", s.setups.volumeSpike.mult)
            }
            putJsonObject("ma_cross") { put("on", s.setups.maCross.on) }
            putJsonObject("rsi_rebound") {
                put("on", s.setups.rsiRebound.on)
                put("level", s.setups.rsiRebound.level)
            }
            putJsonObject("gap") {
                put("on", s.setups.gap.on)
                put("min_pct", s.setups.gap.minPct)
                put("up", s.setups.gap.up)
                put("down", s.setups.gap.down)
            }
        }
    }.toString()
}

/** A setup label on a row (screener-notes §2 `SetupLabel`), before it becomes words. Direction lives in an arrow or a sign. */
sealed interface SetupLabel {
    val bearish: Boolean get() = false

    /** At the upper auto-reject limit: nothing to buy. Neutral, always first. */
    data object Ara : SetupLabel

    /** "Breakout 20d". */
    data class Breakout(val days: Int?) : SetupLabel

    /** "Vol ×2,3" ([ratio] already written the Indonesian way). */
    data class Volume(val ratio: String?) : SetupLabel

    /** "MA20↑50 · Wed": the cross and the day of the session it happened on. */
    data class MaCross(val up: Boolean, val date: LocalDate?) : SetupLabel {
        override val bearish: Boolean get() = !up
    }

    /** "RSI 31". */
    data class Rsi(val value: Int?) : SetupLabel

    /** "Gap +2,4 %" / "Gap −2,9 %" ([pct] signed); without a value, "Gap up" / "Gap down". */
    data class Gap(val up: Boolean, val pct: String?) : SetupLabel {
        override val bearish: Boolean get() = !up
    }
}

/** The labels a row shows: [shown] (at most the row's room) and how many more there are ("+N"). */
data class ShownLabels(val shown: List<SetupLabel>, val more: Int)

/** Setup labels, numbers and the filter, from the §20.3 fields (screener-notes §2: setup text is built on the phone from `values`). */
object SetupLabels {
    /** Score weights (§20.3), used only to order the labels: the strongest setup first. */
    fun weight(id: String): Int = when (id) {
        ScreenerSetup.BREAKOUT -> 3
        ScreenerSetup.VOLUME_SPIKE, ScreenerSetup.MA_CROSS, ScreenerSetup.RSI_REBOUND -> 2
        ScreenerSetup.GAP -> 1
        else -> 0
    }

    /** ARA first, then the setups by weight (ties keep the server's order). Unknown setup ids are left out. */
    fun of(row: ScreenerRow): List<SetupLabel> {
        val setups = row.setups.sortedByDescending { weight(it.id) }.mapNotNull(::label)
        return if (row.atAra) listOf(SetupLabel.Ara) + setups else setups
    }

    fun label(setup: ScreenerSetup): SetupLabel? {
        val v = setup.values
        return when (setup.id) {
            ScreenerSetup.BREAKOUT -> SetupLabel.Breakout(v.lookback)
            ScreenerSetup.VOLUME_SPIKE -> SetupLabel.Volume(v.volRatio?.let(::oneDecimal))
            ScreenerSetup.MA_CROSS -> SetupLabel.MaCross(!setup.bearish, runCatching { LocalDate.parse(setup.date) }.getOrNull())
            ScreenerSetup.RSI_REBOUND -> SetupLabel.Rsi(v.rsi?.let { BigDecimal.valueOf(it).setScale(0, RoundingMode.HALF_UP).toInt() })
            ScreenerSetup.GAP -> SetupLabel.Gap(!setup.bearish, v.gapPct?.let(::signedPercentOneDecimal))
            else -> null
        }
    }

    /** At most [max] labels and the count of the rest. */
    fun shown(labels: List<SetupLabel>, max: Int): ShownLabels = ShownLabels(labels.take(max), (labels.size - max).coerceAtLeast(0))

    /**
     * The labels of a watchlist / holding row [code] in [body]: its candidate's and its flag's together, bearish first. The
     * server lists one code in both when bullish and bearish setups fire at once (a gap down on a volume spike), and Q106
     * never hides a bearish signal on the user's own rows: the row's one visible label (or its "+N") must cover it.
     */
    fun forCode(code: String, body: ScreenerBody?): List<SetupLabel> {
        body ?: return emptyList()
        val candidate = body.candidates.firstOrNull { it.code == code }?.let(::of).orEmpty()
        val flag = body.flags.firstOrNull { it.code == code }?.let(::of).orEmpty()
        val (bearish, rest) = (candidate + flag).distinct().partition { it.bearish }
        return bearish + rest
    }

    /** One decimal, Indonesian comma: 2.3 -> "2,3", 6.0 -> "6,0". */
    fun oneDecimal(value: Double): String = BigDecimal.valueOf(abs(value)).setScale(1, RoundingMode.HALF_UP).toPlainString().replace('.', ',')
        .let { if (value < 0 && it != "0,0") "${StockFormat.MINUS}$it" else it }

    /** "+2,4 %" / "−2,9 %": one decimal, the sign always written (the label's direction cue). */
    fun signedPercentOneDecimal(value: Double): String {
        val text = BigDecimal.valueOf(abs(value)).setScale(1, RoundingMode.HALF_UP).toPlainString().replace('.', ',')
        val sign = when {
            text == "0,0" -> ""
            value > 0 -> "+"
            else -> StockFormat.MINUS.toString()
        }
        return "$sign$text\u00A0%"
    }

    /** A score as the row writes it: 5.2 -> "5,2". */
    fun score(value: Double): String = oneDecimal(value)
}

/** The page's filter chips (screener-notes §2): All, then one per setup. */
enum class SetupFilter(private val id: String?) {
    All(null),
    Breakout(ScreenerSetup.BREAKOUT),
    Volume(ScreenerSetup.VOLUME_SPIKE),
    Ma(ScreenerSetup.MA_CROSS),
    Rsi(ScreenerSetup.RSI_REBOUND),
    Gap(ScreenerSetup.GAP);

    fun matches(row: ScreenerRow): Boolean = id == null || row.setups.any { it.id == id }
}

/** What the screener shows (screener-notes §3), before the words. */
enum class ScreenerKind {
    /** Nothing read yet, the PC answers. */
    Loading,

    /** Nothing read and the PC does not answer. */
    NotLoaded,

    /** Before the first `bars.daily`: no price history, so no list (`scan.error:"no_bars"`). */
    NoBars,

    /** Today's view before today's first scan. */
    NoScanYet,

    /** The scan ran; no candidate fired (bearish flags may still be there). */
    Empty,

    List,
}

/**
 * The screener's state for one body: [kind]; [stale] (the last run did not replace the list: Yahoo refused, every quote
 * failed); [blocked] (that run was refused by Yahoo); [offline] (the list is the one kept on this phone); [failedCodes]
 * (a partial scan: some codes could not be read); [sessionBreak] (between the sessions).
 */
data class ScreenerState(
    val kind: ScreenerKind,
    val body: ScreenerBody? = null,
    val stale: Boolean = false,
    val blocked: Boolean = false,
    val offline: Boolean = false,
    val failedCodes: Int = 0,
    val sessionBreak: Boolean = false,
) {
    /** Values drawn muted (stocks-notes §2 `.stale`): arrows and signs stay, direction colour goes. */
    val muted: Boolean get() = stale || offline
}

object ScreenerStates {
    fun of(body: ScreenerBody?, reachable: Boolean): ScreenerState {
        body ?: return ScreenerState(if (reachable) ScreenerKind.Loading else ScreenerKind.NotLoaded)
        val kind = when {
            body.sessionDate == null && (body.scan.error == ScreenerScan.NO_BARS || body.screenerMode != ScreenerMode.Intraday) -> ScreenerKind.NoBars
            body.sessionDate == null -> ScreenerKind.NoScanYet
            body.candidates.isEmpty() -> ScreenerKind.Empty
            else -> ScreenerKind.List
        }
        return ScreenerState(
            kind = kind,
            body = body,
            stale = body.stale,
            blocked = body.stale && body.scan.error == ScreenerScan.UPSTREAM_BLOCKED,
            offline = !reachable,
            failedCodes = if (body.scan.status == ScreenerScan.PARTIAL) body.scan.codesFailed else 0,
            sessionBreak = body.market.state == MarketStatus.BREAK,
        )
    }

    /** [rows] that pass [filter]. */
    fun filtered(rows: List<ScreenerRow>, filter: SetupFilter): List<ScreenerRow> = rows.filter(filter::matches)
}
