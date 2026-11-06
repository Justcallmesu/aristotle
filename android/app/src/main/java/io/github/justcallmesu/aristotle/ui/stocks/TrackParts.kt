package io.github.justcallmesu.aristotle.ui.stocks

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.justcallmesu.aristotle.R
import io.github.justcallmesu.aristotle.data.stocks.ChipKind
import io.github.justcallmesu.aristotle.data.stocks.ChipTone
import io.github.justcallmesu.aristotle.data.stocks.PastCallsView
import io.github.justcallmesu.aristotle.data.stocks.RecordLine
import io.github.justcallmesu.aristotle.data.stocks.ResultChip
import io.github.justcallmesu.aristotle.data.stocks.ResultChips
import io.github.justcallmesu.aristotle.data.stocks.StockFormat
import io.github.justcallmesu.aristotle.data.stocks.StockTimes
import io.github.justcallmesu.aristotle.data.stocks.TrackCall
import io.github.justcallmesu.aristotle.data.stocks.TrackDisplay
import io.github.justcallmesu.aristotle.data.stocks.TrackRecords
import io.github.justcallmesu.aristotle.data.stocks.Verdict
import io.github.justcallmesu.aristotle.ui.components.GroupedRows
import io.github.justcallmesu.aristotle.ui.sessions.SectionHeader
import io.github.justcallmesu.aristotle.ui.theme.AristotleStockColors
import io.github.justcallmesu.aristotle.ui.theme.AristotleTextStyles
import io.github.justcallmesu.aristotle.ui.theme.ButtonShape
import io.github.justcallmesu.aristotle.ui.theme.Sizes
import io.github.justcallmesu.aristotle.ui.theme.Spacing
import java.time.LocalDate
import java.util.Locale

// TR2: the pieces the track record page and a stock's page share: a sentence with its numbers in SemiBold, the result chip,
// the verdict card's record line (Q124) and the Past calls list. No fixed heights and no maxLines on text: at 1.3x text and
// 393 dp everything wraps; the only sized parts are minimum heights.

/** [words] as one string with its numbers SemiBold in [emphasis]. */
@Composable
internal fun wordsText(words: Words, emphasis: androidx.compose.ui.graphics.Color): AnnotatedString =
    emphasised(stringResource(words.res, *words.args.toTypedArray()), words.args, emphasis)

internal fun emphasised(text: String, args: List<String>, color: androidx.compose.ui.graphics.Color): AnnotatedString = buildAnnotatedString {
    append(text)
    TrackDisplay.argRanges(text, args).forEach { addStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = color), it.first, it.last + 1) }
}

/** The verdict card's record line (board `RecordLine`, Q124): always opens the page. Muted while the PC is out of reach. */
@Composable
internal fun RecordLineRow(line: RecordLine, muted: Boolean, onOpen: () -> Unit) {
    val ink = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    val text: AnnotatedString = when (val words = TrackText.line(line)) {
        LineWords.None -> AnnotatedString(stringResource(R.string.stocks_record_line_none))
        is LineWords.Sentence -> {
            val verdict = stringResource(TrackText.verdictName(words.verdict))
            val body = words.body
            if (body == null) {
                AnnotatedString(stringResource(R.string.stocks_record_line_few, verdict))
            } else {
                emphasised(stringResource(R.string.stocks_record_line, verdict, stringResource(body.res, *body.args.toTypedArray())), body.args, ink)
            }
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = Sizes.minTouchTarget).clickable(role = Role.Button, onClick = onOpen),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f).padding(vertical = Spacing.s))
        Icon(painter = painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(CHEVRON))
    }
}

/**
 * A tonal result chip (Q121): gain / loss container or neutral, a 24 dp minimum, `labelMedium`; the word carries the result and
 * the colour only confirms it. A call still waiting for its score is an outline with a clock.
 */
@Composable
internal fun ResultChipView(chip: ResultChip, modifier: Modifier = Modifier, muted: Boolean = false) {
    val stock = AristotleStockColors
    val tone = if (muted) ChipTone.Neutral else chip.tone
    val container = when (tone) {
        ChipTone.Gain -> stock.gainContainer
        ChipTone.Loss -> stock.lossContainer
        ChipTone.Neutral -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val content = when (tone) {
        ChipTone.Gain -> stock.onGainContainer
        ChipTone.Loss -> stock.onLossContainer
        ChipTone.Neutral -> MaterialTheme.colorScheme.onSurface
    }
    val pending = chip.kind == ChipKind.Pending
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraSmall,
        color = if (pending) androidx.compose.ui.graphics.Color.Transparent else container,
        contentColor = content,
        border = if (pending) BorderStroke(Sizes.divider, MaterialTheme.colorScheme.outline) else null,
    ) {
        Row(modifier = Modifier.heightIn(min = CHIP_MIN).padding(horizontal = Spacing.s, vertical = Spacing.xxs), verticalAlignment = Alignment.CenterVertically) {
            if (pending) {
                Icon(painter = painterResource(R.drawable.ic_schedule), contentDescription = null, modifier = Modifier.padding(end = Spacing.xs).size(Sizes.lineIcon))
            }
            Text(chipText(chip), style = AristotleTextStyles.tabular(MaterialTheme.typography.labelMedium))
        }
    }
}

/** "Target hit +3,7 %" / "Not triggered": the word, then the percent when the chip has one. */
@Composable
internal fun chipText(chip: ResultChip): String {
    val word = stringResource(
        when (chip.kind) {
            ChipKind.TargetHit -> R.string.stocks_chip_target
            ChipKind.StopHit -> R.string.stocks_chip_stop
            ChipKind.ClosedFlat -> R.string.stocks_chip_flat
            ChipKind.NotTriggered -> R.string.stocks_chip_not_triggered
            ChipKind.Right -> R.string.stocks_chip_right
            ChipKind.Wrong -> R.string.stocks_chip_wrong
            ChipKind.Held -> R.string.stocks_chip_held
            ChipKind.IfBought -> R.string.stocks_chip_if_bought
            ChipKind.Pending -> R.string.stocks_chip_pending
            ChipKind.NoSession -> R.string.stocks_chip_no_session
            ChipKind.Unscorable -> R.string.stocks_chip_unscorable
            ChipKind.Other -> R.string.stocks_chip_other
        },
    )
    return chip.pct?.let { "$word ${StockFormat.percent(it)}" } ?: word
}

@Composable
internal fun triggerText(trigger: String): String = when (trigger) {
    "manual" -> stringResource(R.string.stocks_record_trigger_manual)
    "auto" -> stringResource(R.string.stocks_record_trigger_auto)
    else -> trigger.ifEmpty { StockFormat.missing }
}

/**
 * Past calls of one stock (board `PastCalls`, P15): newest first, collapsed to 3 and "Show N more"; a result chip per call and a
 * small "daily" marker for a score from the daily bar. Rows are not tappable (the old reasons are not kept). Nothing is drawn
 * until the record was read ([view] null): an old server without the route simply has no such section.
 */
@Composable
internal fun PastCalls(code: String, view: PastCallsView?, expanded: Boolean, muted: Boolean, locale: Locale, onToggle: () -> Unit) {
    if (view == null) return
    SectionHeader(stringResource(R.string.stocks_past_title, view.total))
    if (view.total == 0) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.listGutter, vertical = Spacing.s)) {
            Text(stringResource(R.string.stocks_past_empty, code), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.stocks_past_empty_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Spacing.xs))
        }
        return
    }
    GroupedRows(view.shown.size, modifier = Modifier.padding(horizontal = Spacing.listGutter)) { index ->
        PastCallRow(view.shown[index], muted, locale)
    }
    if (view.canToggle) {
        TextButton(onClick = onToggle, shape = ButtonShape, modifier = Modifier.padding(horizontal = Spacing.s)) {
            Text(if (expanded) stringResource(R.string.stocks_past_less) else pluralStringResource(R.plurals.stocks_past_more, view.hidden, view.hidden))
        }
    }
}

@Composable
private fun PastCallRow(call: TrackCall, muted: Boolean, locale: Locale) {
    val verdict = Verdict.of(call.verdict)
    val chip = ResultChips.of(call.verdict, call.score)
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val inner = maxWidth.value - 2 * ROW_PADDING.value
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = PAST_ROW_MIN).padding(horizontal = ROW_PADDING, vertical = Spacing.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(verdictWord(verdict, call.verdict), style = MaterialTheme.typography.titleSmall)
                Text(pastMeta(call, locale), style = AristotleTextStyles.tabular(MaterialTheme.typography.bodySmall), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            ResultChipView(chip, modifier = Modifier.padding(start = Spacing.s).widthIn(max = TrackDisplay.chipMaxWidth(inner).dp), muted = muted)
        }
    }
}

/** "Wed 4 Nov · Sonnet · auto", then "· daily" for a score from the daily bar. */
@Composable
private fun pastMeta(call: TrackCall, locale: Locale): String {
    val date = StockTimes.date(call.analyzedAt) ?: runCatching { LocalDate.parse(call.sessionDate) }.getOrNull()
    val day = date?.let { StockTimes.day(it, locale) } ?: StockFormat.missing
    val base = stringResource(R.string.stocks_past_meta, day, TrackRecords.modelName(call.model), triggerText(call.trigger))
    return if (call.score?.daily == true) stringResource(R.string.stocks_record_with_pct, base, stringResource(R.string.stocks_past_daily)) else base
}

private val CHEVRON = 20.dp
private val CHIP_MIN = 24.dp
private val PAST_ROW_MIN = 64.dp
private val ROW_PADDING = Spacing.l
