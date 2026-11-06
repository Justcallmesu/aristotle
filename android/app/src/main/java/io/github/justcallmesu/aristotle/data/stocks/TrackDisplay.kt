package io.github.justcallmesu.aristotle.data.stocks

/** The words a card's counts line is made of (board: "8 win · 13 loss · 9 not triggered · 3 closed flat"). Avoid / Sell read right / wrong (Q121). */
enum class CountKind { Win, Loss, ClosedFlat, NotTriggered, Right, Wrong, TargetHit, Held, StopHit, NotScored }

data class CountPart(val kind: CountKind, val n: Int)

/** A colour of the split bar: gain / loss, `outline` for closed flat, `outlineVariant` for not triggered. */
enum class BarTone { Gain, Loss, Flat, Muted }

data class BarSegment(val n: Int, val tone: BarTone)

/** Pure display rules of a verdict card, so the composable only draws. */
object TrackDisplay {
    /** The non-empty counts of [record], in reading order; a Wait has none (it has no right or wrong). */
    fun counts(record: VerdictRecord): List<CountPart> {
        val parts = when (record.verdict) {
            Verdict.BuyToday, Verdict.Add -> listOf(
                CountPart(CountKind.Win, record.win),
                CountPart(CountKind.Loss, record.loss),
                CountPart(CountKind.NotTriggered, record.notTriggered),
                CountPart(CountKind.ClosedFlat, record.closed),
            )
            Verdict.Hold -> listOf(CountPart(CountKind.TargetHit, record.win), CountPart(CountKind.Held, record.right), CountPart(CountKind.StopHit, record.loss))
            Verdict.Avoid, Verdict.Sell -> listOf(CountPart(CountKind.Right, record.right), CountPart(CountKind.Wrong, record.wrong))
            Verdict.Wait -> return emptyList()
        }
        return (parts + CountPart(CountKind.NotScored, record.notScored)).filter { it.n > 0 }
    }

    /** The bar's segments, weighted by count (no bar for a Wait). Not-scored records are not in the bar. */
    fun bar(record: VerdictRecord): List<BarSegment> = when (record.verdict) {
        Verdict.BuyToday, Verdict.Add -> listOf(
            BarSegment(record.win, BarTone.Gain),
            BarSegment(record.loss, BarTone.Loss),
            BarSegment(record.closed, BarTone.Flat),
            BarSegment(record.notTriggered, BarTone.Muted),
        )
        Verdict.Hold -> listOf(BarSegment(record.win + record.right, BarTone.Gain), BarSegment(record.loss, BarTone.Loss))
        Verdict.Avoid, Verdict.Sell -> listOf(BarSegment(record.right, BarTone.Gain), BarSegment(record.wrong, BarTone.Loss))
        Verdict.Wait -> emptyList()
    }.filter { it.n > 0 }

    /** Avoid and Sell: a falling price is the right answer, so their average is drawn in ink, not green / red (board). */
    fun inkAverage(verdict: Verdict): Boolean = verdict == Verdict.Avoid || verdict == Verdict.Sell

    /** The "3 days later" line (Q117, for information) shows only when there is an average of it: from 10 decided calls, over records that have the value. */
    fun showsInfo(record: VerdictRecord): Boolean = record.info3dAvg != null

    /** A past-call row's chip is at most this share of the row's width (board `ResultChip`: max 52 %, then it wraps). */
    fun chipMaxWidth(rowWidth: Float): Float = rowWidth * CHIP_SHARE

    private const val CHIP_SHARE = 0.52f

    /** Where each of [args] first appears in [text], searching on from the end of the one before: the numbers a sentence makes bold. */
    fun argRanges(text: String, args: List<String>): List<IntRange> {
        val ranges = mutableListOf<IntRange>()
        var from = 0
        for (arg in args) {
            if (arg.isEmpty()) continue
            val at = text.indexOf(arg, from)
            if (at < 0) continue
            ranges += at until at + arg.length
            from = at + arg.length
        }
        return ranges
    }
}
