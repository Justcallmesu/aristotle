package io.github.justcallmesu.aristotle.ui.stocks

import io.github.justcallmesu.aristotle.data.stocks.AnalysisNews
import io.github.justcallmesu.aristotle.data.stocks.AnalysisResult
import io.github.justcallmesu.aristotle.data.stocks.Confidence
import io.github.justcallmesu.aristotle.data.stocks.StockFormat
import io.github.justcallmesu.aristotle.data.stocks.StockQuote
import io.github.justcallmesu.aristotle.data.stocks.Verdict
import io.github.justcallmesu.aristotle.data.stocks.VerdictLevels
import io.github.justcallmesu.aristotle.data.stocks.VerdictSource
import io.github.justcallmesu.aristotle.data.stocks.VerdictSources
import io.github.justcallmesu.aristotle.ui.chat.ChatLinks

/**
 * A headline chip under a reason: the only thing on the card that opens anything. [link] is the result's `news` link
 * when it is a web page (`http` / `https`, [ChatLinks.webTarget]), else null and the chip opens nothing.
 */
data class NewsChip(val id: String, val title: String, val source: String, val publishedAt: String, val link: String?)

/**
 * One reason as drawn. [text] is the model's words **verbatim, as plain text** (TA1 review MINOR-1, prompt injection):
 * the card renders it with a plain-`String` `Text`, so Markdown stays literal and a URL in it is neither styled nor
 * clickable. [news] are the cited headlines (chips), [labels] the other cited ids (plain labels).
 */
data class ReasonLine(val text: String, val news: List<NewsChip>, val labels: List<VerdictSource>)

/** An entry zone, target or stop: the price and its percent from [VerdictCardModel.reference] (one or two ends). */
data class LevelCell(val value: String, val pct: List<String>?)

/** The verdict card (Q98), pure: everything the composable draws except the words that come from resources. */
data class VerdictCardModel(
    val verdict: Verdict?,
    /** The wire word when the verdict is not one of the six (a newer server). */
    val verdictWire: String,
    val confidence: Confidence?,
    /** Plain text, verbatim (see [ReasonLine.text]). */
    val summary: String,
    val entry: LevelCell?,
    val target: LevelCell?,
    val stop: LevelCell?,
    /** The price the percents are from: the live quote's, else the one the analysis used. */
    val reference: Long,
    val reasons: List<ReasonLine>,
    val flags: List<String>,
    val repaired: Boolean,
    /** An automatic run (§20.4: the server started it when setups fired), not the Analyze button. */
    val auto: Boolean = false,
) {
    val hasLevels: Boolean get() = entry != null || target != null || stop != null

    companion object {
        fun of(result: AnalysisResult, quote: StockQuote?): VerdictCardModel {
            val reference = VerdictLevels.reference(result, quote)
            fun level(price: Long?): LevelCell? = price?.let { LevelCell(StockFormat.number(it), VerdictLevels.pctText(it, reference)?.let(::listOf)) }
            val entry = VerdictLevels.zone(result.entryLow, result.entryHigh)?.let { LevelCell(it, VerdictLevels.zonePct(result.entryLow, result.entryHigh, reference)) }
            return VerdictCardModel(
                verdict = Verdict.of(result.verdict),
                verdictWire = result.verdict,
                confidence = Confidence.of(result.confidence),
                summary = result.summary,
                entry = entry,
                target = level(result.target),
                stop = level(result.stop),
                reference = reference,
                reasons = result.reasons.map { reason -> reasonLine(reason.text, reason.sources, result.news) },
                flags = result.flags,
                repaired = result.repaired,
                auto = result.auto,
            )
        }

        fun reasonLine(text: String, sources: List<String>, news: List<AnalysisNews>): ReasonLine {
            val all = sources.distinct().map { VerdictSources.of(it, news) }
            val chips = all.filterIsInstance<VerdictSource.News>().mapNotNull { source ->
                source.item?.let { NewsChip(source.id, it.title, it.source, it.publishedAt, ChatLinks.webTarget(it.link)) }
            }
            // A `news:N` the result does not carry has no title to show: it is left out rather than drawn as an id.
            return ReasonLine(text, chips, all.filterNot { it is VerdictSource.News })
        }
    }
}
