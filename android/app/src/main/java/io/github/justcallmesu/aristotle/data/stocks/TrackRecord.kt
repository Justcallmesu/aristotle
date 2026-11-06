package io.github.justcallmesu.aristotle.data.stocks

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// The AI track record (TR2; protocol §20.5, spec Q117-Q125, P16a). The server sends raw counts only: every rate, threshold
// and average here is the phone's. Unknown fields are ignored and every field has a default, so a newer server never fails
// the whole answer.

/** One summary group: a verdict × model × trigger × confidence over every scored record (`n` counts ALL of them). */
@Serializable
data class TrackGroup(
    val verdict: String = "",
    val model: String = "",
    val trigger: String = "",
    val confidence: String = "",
    val n: Int = 0,
    val win: Int = 0,
    val loss: Int = 0,
    val closed: Int = 0,
    @SerialName("not_triggered") val notTriggered: Int = 0,
    val right: Int = 0,
    val wrong: Int = 0,
    @SerialName("would_have") val wouldHave: Int = 0,
    @SerialName("no_session") val noSession: Int = 0,
    val unscorable: Int = 0,
    @SerialName("result_n") val resultN: Int = 0,
    @SerialName("result_sum") val resultSum: Double = 0.0,
    @SerialName("info_3d_n") val info3dN: Int = 0,
    @SerialName("info_3d_sum") val info3dSum: Double = 0.0,
)

/** What a score decided; an outcome a newer server adds reads as [Other]. */
enum class TrackOutcome(val wire: String) {
    Win("win"),
    Loss("loss"),
    Closed("closed"),
    NotTriggered("not_triggered"),
    Right("right"),
    Wrong("wrong"),
    WouldHave("would_have"),
    NoSession("no_session"),
    Unscorable("unscorable"),
    Other(""),
    ;

    companion object {
        fun of(wire: String): TrackOutcome = entries.firstOrNull { it.wire == wire && it != Other } ?: Other
    }
}

/** A record's score; `source` `daily` marks a score from the daily bar (P15: it may count a touch from before the call). */
@Serializable
data class TrackScore(
    val rule: Int = 0,
    val source: String = "",
    val outcome: String = "",
    @SerialName("result_pct") val resultPct: Double? = null,
    @SerialName("info_3d_pct") val info3dPct: Double? = null,
    @SerialName("scored_at") val scoredAt: String? = null,
) {
    val outcomeKind: TrackOutcome get() = TrackOutcome.of(outcome)
    val daily: Boolean get() = source == DAILY

    companion object {
        const val DAILY = "daily"
    }
}

/** One past call (the kept record without its levels and prose); [score] is null while it waits to be scored. */
@Serializable
data class TrackCall(
    val id: String = "",
    val code: String = "",
    val held: Boolean = false,
    @SerialName("analyzed_at") val analyzedAt: String = "",
    @SerialName("session_date") val sessionDate: String = "",
    val model: String = "",
    val trigger: String = "",
    val verdict: String = "",
    val confidence: String = "",
    val score: TrackScore? = null,
)

/** `GET /v1/stocks/track-record[?code=]`: [summary] is never filtered by code; [calls] are newest first (≤ 50). */
@Serializable
data class TrackRecordBody(
    val summary: List<TrackGroup> = emptyList(),
    val calls: List<TrackCall> = emptyList(),
    @SerialName("scored_through") val scoredThrough: String? = null,
    val pending: Int = 0,
)

/** The page's filters (Q121: model, trigger, confidence); null = all. Applied to the groups, so the sums stay exact. */
/** [model] is a family key of [TrackRecords.family], not a raw model id. */
data class TrackFilters(val model: String? = null, val trigger: String? = null, val confidence: String? = null) {
    val any: Boolean get() = model != null || trigger != null || confidence != null

    fun matches(group: TrackGroup): Boolean =
        (model == null || TrackRecords.family(group.model) == model) && (trigger == null || group.trigger == trigger) && (confidence == null || group.confidence == confidence)
}

/** What the three filter menus offer, from the groups the server sent. */
data class TrackOptions(val models: List<String>, val triggers: List<String>, val confidences: List<String>)

/**
 * One verdict type over the filtered groups, summed. [decided] is §20.5's (P16a): every outcome except `not_triggered`,
 * `no_session`, `unscorable` and `would_have`. [hits] is the rate's numerator; a Wait has none and no rate (Q120).
 */
data class VerdictRecord(
    val verdict: Verdict,
    val scored: Int,
    val win: Int,
    val loss: Int,
    val closed: Int,
    val notTriggered: Int,
    val right: Int,
    val wrong: Int,
    val wouldHave: Int,
    /** `no_session` + `unscorable`: scored but never in a rate. */
    val notScored: Int,
    private val resultN: Int,
    private val resultSum: Double,
    private val info3dN: Int,
    private val info3dSum: Double,
) {
    val decided: Int
        get() = when (verdict) {
            Verdict.BuyToday, Verdict.Add -> win + loss + closed
            Verdict.Hold -> win + loss + right
            Verdict.Avoid, Verdict.Sell -> right + wrong
            Verdict.Wait -> 0
        }

    val hits: Int
        get() = when (verdict) {
            Verdict.BuyToday, Verdict.Add -> win
            Verdict.Hold -> win + right
            Verdict.Avoid, Verdict.Sell -> right
            Verdict.Wait -> 0
        }

    /** What Q122's "n of 10" counts: decided calls, or for a Wait (no right / wrong) its would-have calls. */
    val sample: Int get() = if (verdict == Verdict.Wait) wouldHave else decided

    val enough: Boolean get() = sample >= TrackRecords.MIN_DECIDED
    val hasRate: Boolean get() = verdict != Verdict.Wait && enough

    /** Whole percent of [hits] over [decided] (Q123 for a buy: not triggered stays outside); null below the threshold. */
    val ratePct: Int? get() = if (hasRate && decided > 0) Math.round(hits * 100.0 / decided).toInt() else null

    /** Mean result %, over the records that have one (`result_n`), only from the threshold (the board hides it below). */
    val resultAvg: Double? get() = if (enough && resultN > 0) resultSum / resultN else null

    /** Mean result % three sessions later, over the records that have it; information only (Q117). */
    val info3dAvg: Double? get() = if (enough && info3dN > 0) info3dSum / info3dN else null
}

/** The verdict-card line (Q124) before it becomes words. */
sealed interface RecordLine {
    /** No scored call of this verdict type yet. */
    data object None : RecordLine

    /** Under 10 calls: [decided] is what counted so far (a Wait's would-have calls). */
    data class Few(val verdict: Verdict, val decided: Int) : RecordLine

    data class Hits(val verdict: Verdict, val hit: Int, val decided: Int) : RecordLine

    data class WaitAverage(val avgPct: Double) : RecordLine
}

/** Pure rules over the server's counts (spec Q117-Q125, P16a). */
object TrackRecords {
    /** Q122: a rate (or an average) shows only from this many decided calls of one verdict type. */
    const val MIN_DECIDED = 10

    fun filter(groups: List<TrackGroup>, filters: TrackFilters): List<TrackGroup> = groups.filter(filters::matches)

    /** Every scored record of [groups] (a group's `n` counts all of them). */
    fun scored(groups: List<TrackGroup>): Int = groups.sumOf { it.n }

    /** One record per verdict type that has scored calls in [groups], in the verdict order; a verdict this app does not know is left out. */
    fun byVerdict(groups: List<TrackGroup>): List<VerdictRecord> = Verdict.entries.mapNotNull { sum(groups, it) }

    fun line(groups: List<TrackGroup>, verdict: Verdict): RecordLine = sum(groups, verdict)?.let(::line) ?: RecordLine.None

    /** The one rule for how a verdict type's record reads: the verdict card's line and the page's card both use it. */
    fun line(record: VerdictRecord): RecordLine {
        val verdict = record.verdict
        if (!record.enough) return RecordLine.Few(verdict, record.sample)
        if (verdict == Verdict.Wait) return record.resultAvg?.let(RecordLine::WaitAverage) ?: RecordLine.Few(verdict, record.sample)
        return RecordLine.Hits(verdict, record.hits, record.decided)
    }

    fun options(groups: List<TrackGroup>): TrackOptions = TrackOptions(
        models = FAMILY_ORDER.filter { f -> groups.any { family(it.model) == f } },
        triggers = TRIGGER_ORDER.filter { t -> groups.any { it.trigger == t } } + groups.map { it.trigger }.distinct().filter { it !in TRIGGER_ORDER }.sorted(),
        confidences = CONFIDENCE_ORDER.filter { c -> groups.any { it.confidence == c } } + groups.map { it.confidence }.distinct().filter { it !in CONFIDENCE_ORDER }.sorted(),
    )

    /** The filter key of a model id: `opus`, `sonnet` or `other` (every other model, and none). */
    fun family(raw: String): String {
        val lower = raw.lowercase()
        return FAMILY_ORDER.firstOrNull { it != OTHER && it in lower } ?: OTHER
    }

    /** "Opus" for any Opus model string, likewise Sonnet and Haiku; else the string as sent; a dash for none. */
    fun modelName(raw: String): String {
        val lower = raw.lowercase()
        return FAMILIES.firstOrNull { it.lowercase() in lower } ?: raw.ifEmpty { StockFormat.missing }
    }

    private fun sum(groups: List<TrackGroup>, verdict: Verdict): VerdictRecord? {
        val mine = groups.filter { it.verdict == verdict.wire }
        if (mine.isEmpty() || mine.sumOf { it.n } == 0) return null
        return VerdictRecord(
            verdict = verdict,
            scored = mine.sumOf { it.n },
            win = mine.sumOf { it.win },
            loss = mine.sumOf { it.loss },
            closed = mine.sumOf { it.closed },
            notTriggered = mine.sumOf { it.notTriggered },
            right = mine.sumOf { it.right },
            wrong = mine.sumOf { it.wrong },
            wouldHave = mine.sumOf { it.wouldHave },
            notScored = mine.sumOf { it.noSession + it.unscorable },
            resultN = mine.sumOf { it.resultN },
            resultSum = mine.sumOf { it.resultSum },
            info3dN = mine.sumOf { it.info3dN },
            info3dSum = mine.sumOf { it.info3dSum },
        )
    }

    private const val OTHER = "other"
    private val FAMILY_ORDER = listOf("opus", "sonnet", OTHER)
    private val TRIGGER_ORDER = listOf("manual", "auto")
    private val CONFIDENCE_ORDER = listOf("low", "med", "high")
    private val FAMILIES = listOf("Opus", "Sonnet", "Haiku")
}

/** The Past calls list on a stock's page (board): newest first as the server sends, collapsed to [PastCalls.COLLAPSED]. */
data class PastCallsView(val shown: List<TrackCall>, val hidden: Int, val total: Int, val canToggle: Boolean)

object PastCalls {
    const val COLLAPSED = 3

    fun view(calls: List<TrackCall>, expanded: Boolean): PastCallsView {
        val canToggle = calls.size > COLLAPSED
        val shown = if (expanded || !canToggle) calls else calls.take(COLLAPSED)
        return PastCallsView(shown, calls.size - shown.size, calls.size, canToggle)
    }
}

enum class ChipKind { TargetHit, StopHit, ClosedFlat, NotTriggered, Right, Wrong, Held, IfBought, Pending, NoSession, Unscorable, Other }

/** Tonal fill of a result chip: gain / loss container, else neutral. The word carries the result, colour only confirms it. */
enum class ChipTone { Gain, Loss, Neutral }

/** A result chip before it becomes words: [pct] is the result % when the chip shows one. */
data class ResultChip(val kind: ChipKind, val tone: ChipTone, val pct: Double?)

object ResultChips {
    /** The chip of a call with [verdict] (wire) and [score] (null while waiting for its score). */
    fun of(verdict: String, score: TrackScore?): ResultChip {
        score ?: return ResultChip(ChipKind.Pending, ChipTone.Neutral, null)
        val pct = score.resultPct
        return when (score.outcomeKind) {
            TrackOutcome.Win -> ResultChip(ChipKind.TargetHit, ChipTone.Gain, pct)
            TrackOutcome.Loss -> ResultChip(ChipKind.StopHit, ChipTone.Loss, pct)
            TrackOutcome.Closed -> ResultChip(ChipKind.ClosedFlat, ChipTone.Neutral, pct)
            TrackOutcome.NotTriggered -> ResultChip(ChipKind.NotTriggered, ChipTone.Neutral, null)
            TrackOutcome.Right -> if (verdict == Verdict.Hold.wire) ResultChip(ChipKind.Held, ChipTone.Gain, null) else ResultChip(ChipKind.Right, ChipTone.Gain, pct)
            TrackOutcome.Wrong -> ResultChip(ChipKind.Wrong, ChipTone.Loss, pct)
            TrackOutcome.WouldHave -> ResultChip(ChipKind.IfBought, ChipTone.Neutral, pct)
            TrackOutcome.NoSession -> ResultChip(ChipKind.NoSession, ChipTone.Neutral, null)
            TrackOutcome.Unscorable -> ResultChip(ChipKind.Unscorable, ChipTone.Neutral, null)
            TrackOutcome.Other -> ResultChip(ChipKind.Other, ChipTone.Neutral, null)
        }
    }
}
