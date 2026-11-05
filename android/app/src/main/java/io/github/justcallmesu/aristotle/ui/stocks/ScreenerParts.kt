package io.github.justcallmesu.aristotle.ui.stocks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.justcallmesu.aristotle.R
import io.github.justcallmesu.aristotle.data.stocks.ScreenerKind
import io.github.justcallmesu.aristotle.data.stocks.ScreenerMode
import io.github.justcallmesu.aristotle.data.stocks.ScreenerRow
import io.github.justcallmesu.aristotle.data.stocks.ScreenerState
import io.github.justcallmesu.aristotle.data.stocks.SetupLabel
import io.github.justcallmesu.aristotle.data.stocks.SetupLabels
import io.github.justcallmesu.aristotle.data.stocks.StockChanges
import io.github.justcallmesu.aristotle.data.stocks.StockTimes
import io.github.justcallmesu.aristotle.ui.components.GroupedRows
import io.github.justcallmesu.aristotle.ui.sessions.QuietLine
import io.github.justcallmesu.aristotle.ui.sessions.SectionHeader
import io.github.justcallmesu.aristotle.ui.theme.AristotleStockColors
import io.github.justcallmesu.aristotle.ui.theme.AristotleTextStyles
import io.github.justcallmesu.aristotle.ui.theme.ButtonShape
import io.github.justcallmesu.aristotle.ui.theme.Spacing
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * A setup label (screener-notes §2 `SetupLabel`): not a chip and not a button, a 24 dp box with `extraSmall` corners so it
 * never reads as a 12 dp FilterChip; tonal `surfaceContainerHigh`, bearish in the stock loss container. [muted] (stale or
 * offline) drops the loss colour; the sign or the arrow stays.
 */
@Composable
fun SetupLabelBox(label: SetupLabel, muted: Boolean, locale: Locale) {
    val bearish = label.bearish && !muted
    val container = if (bearish) AristotleStockColors.lossContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    val content = when {
        bearish -> AristotleStockColors.onLossContainer
        muted -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSurface
    }
    val style = AristotleTextStyles.tabular(MaterialTheme.typography.labelMedium)
    Surface(shape = MaterialTheme.shapes.extraSmall, color = container, contentColor = content, modifier = Modifier.height(LABEL_HEIGHT)) {
        Row(modifier = Modifier.padding(horizontal = Spacing.s), verticalAlignment = Alignment.CenterVertically) {
            if (label is SetupLabel.MaCross) {
                Text("MA20", style = style, maxLines = 1)
                Icon(
                    painter = painterResource(if (label.up) R.drawable.ic_arrow_drop_up else R.drawable.ic_arrow_drop_down),
                    contentDescription = null,
                    modifier = Modifier.size(LABEL_ARROW),
                )
                Text(label.date?.let { "50 · ${shortDay(it, locale)}" } ?: "50", style = style, maxLines = 1)
            } else {
                Text(labelText(label), style = style, maxLines = 1)
            }
        }
    }
}

/** A label's words (all but the MA cross, which carries an arrow icon). */
@Composable
fun labelText(label: SetupLabel): String = when (label) {
    SetupLabel.Ara -> stringResource(R.string.screener_label_ara)
    is SetupLabel.Breakout -> label.days?.let { stringResource(R.string.screener_label_breakout, it) } ?: stringResource(R.string.screener_label_breakout_plain)
    is SetupLabel.Volume -> label.ratio?.let { stringResource(R.string.screener_label_volume, it) } ?: stringResource(R.string.screener_label_volume_plain)
    is SetupLabel.Rsi -> label.value?.let { stringResource(R.string.screener_label_rsi, it) } ?: stringResource(R.string.screener_label_rsi_plain)
    is SetupLabel.Gap -> label.pct?.let { stringResource(R.string.screener_label_gap, it) }
        ?: stringResource(if (label.up) R.string.screener_label_gap_up else R.string.screener_label_gap_down)
    is SetupLabel.MaCross -> "MA20 ${if (label.up) "↑" else "↓"} 50"
}

/** How a screen reader says a label: "breakout 20 days", "MA20 crossed below MA50 on Tuesday". */
@Composable
fun labelSpoken(label: SetupLabel, locale: Locale): String = when (label) {
    SetupLabel.Ara -> stringResource(R.string.screener_say_ara)
    is SetupLabel.Breakout -> label.days?.let { stringResource(R.string.screener_say_breakout, it) } ?: stringResource(R.string.screener_say_breakout_plain)
    is SetupLabel.Volume -> label.ratio?.let { stringResource(R.string.screener_say_volume, it) } ?: stringResource(R.string.screener_say_volume_plain)
    is SetupLabel.Rsi -> label.value?.let { stringResource(R.string.screener_say_rsi, it) } ?: stringResource(R.string.screener_say_rsi_plain)
    is SetupLabel.Gap -> {
        val pct = label.pct?.removePrefix("+")?.removePrefix("\u2212").orEmpty()
        stringResource(if (label.up) R.string.screener_say_gap_up else R.string.screener_say_gap_down, pct).trim()
    }
    is SetupLabel.MaCross -> {
        val day = label.date?.dayOfWeek?.getDisplayName(TextStyle.FULL, locale)
        when {
            day == null -> stringResource(if (label.up) R.string.screener_say_ma_up_plain else R.string.screener_say_ma_down_plain)
            else -> stringResource(if (label.up) R.string.screener_say_ma_up else R.string.screener_say_ma_down, day)
        }
    }
}

/** The labels as one spoken line. */
@Composable
fun labelsSpoken(labels: List<SetupLabel>, locale: Locale): String = labels.map { labelSpoken(it, locale) }.joinToString(", ")

/** Up to [max] labels and "+N" for the rest (`labelMedium`, muted). */
@Composable
fun LabelLine(labels: List<SetupLabel>, max: Int, muted: Boolean, locale: Locale, modifier: Modifier = Modifier) {
    val shown = SetupLabels.shown(labels, max)
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(LABEL_GAP), verticalAlignment = Alignment.CenterVertically) {
        shown.shown.forEach { SetupLabelBox(it, muted, locale) }
        if (shown.more > 0) {
            Text(
                stringResource(R.string.screener_more_labels, shown.more),
                style = AristotleTextStyles.tabular(MaterialTheme.typography.labelMedium),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/**
 * A candidate or bearish-flag row (screener-notes §2 `CandidateRow`, two-line, min 64 dp, padding 16 × 7): code, name,
 * last, change; then up to 3 labels and "+N", the watchlist / held marks and "score 5,2" (flags have no score). The whole
 * row opens the stock; there is no Analyze here (a stray tap must not start an AI run).
 */
@Composable
fun CandidateRow(row: ScreenerRow, flag: Boolean, muted: Boolean, locale: Locale, onOpen: (String) -> Unit) {
    val labels = SetupLabels.of(row)
    val change = StockChanges.percent(row.changePct)
    val score = SetupLabels.score(row.score)
    val spoken = labelsSpoken(labels, locale)
    val marks = listOfNotNull(
        if (row.inWatchlist) stringResource(R.string.screener_on_watchlist) else null,
        if (row.held) stringResource(R.string.screener_held) else null,
    ).joinToString(". ")
    val base = stringResource(R.string.screener_row_description, row.code, row.name, row.price?.toString() ?: "-", changeWords(change), spoken)
    val description = listOf(base, marks.takeIf { it.isNotEmpty() }?.let { "$it." }, if (flag) null else stringResource(R.string.screener_row_score, score))
        .filterNotNull().joinToString(" ")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button) { onOpen(row.code) }
            .semantics(mergeDescendants = true) { contentDescription = description }
            .heightIn(min = ROW_HEIGHT)
            .padding(horizontal = Spacing.l, vertical = ROW_PADDING),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Text(row.code, style = AristotleTextStyles.stockCode, maxLines = 1, modifier = Modifier.widthIn(min = CODE_WIDTH))
            Text(
                row.name,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                priceText(row.price),
                style = AristotleTextStyles.stockPrice,
                color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.End,
                maxLines = 1,
                modifier = Modifier.widthIn(min = PRICE_MIN),
            )
            ChangeText(change, muted = muted)
        }
        Row(modifier = Modifier.padding(top = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
            LabelLine(labels, MAX_LABELS, muted, locale, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.weight(1f))
            val muteColor = MaterialTheme.colorScheme.onSurfaceVariant
            Row(horizontalArrangement = Arrangement.spacedBy(MARK_GAP), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = MARK_GAP)) {
                if (row.inWatchlist) Icon(painterResource(R.drawable.ic_bookmark_added), contentDescription = null, tint = muteColor, modifier = Modifier.size(MARK_ICON))
                if (row.held) Icon(painterResource(R.drawable.ic_account_balance_wallet), contentDescription = null, tint = muteColor, modifier = Modifier.size(MARK_ICON))
                if (!flag) Text(stringResource(R.string.screener_score, score), style = AristotleTextStyles.tabular(MaterialTheme.typography.bodySmall), color = muteColor, maxLines = 1)
            }
        }
    }
}

/** Rows as one grouped list on the 16 dp gutter. */
@Composable
fun CandidateList(rows: List<ScreenerRow>, flag: Boolean, muted: Boolean, locale: Locale, onOpen: (String) -> Unit) {
    GroupedRows(rows.size, modifier = Modifier.padding(horizontal = Spacing.listGutter)) { index -> CandidateRow(rows[index], flag, muted, locale, onOpen) }
}

/**
 * The Stocks home section (screener-notes §2-§3, under the watchlist): "Screener · scanned 10:45", See all (N) or Open,
 * then the top 3 candidates, or the one state line (building history, no setups, stale, offline).
 */
@Composable
fun ScreenerHomeSection(screen: ScreenerState, locale: Locale, onOpenPage: () -> Unit, onOpenStock: (String) -> Unit) {
    val body = screen.body
    val count = body?.candidates?.size ?: 0
    val listed = screen.kind == ScreenerKind.List
    Row(modifier = Modifier.fillMaxWidth().padding(end = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.weight(1f)) {
            SectionHeader(listOfNotNull(stringResource(R.string.screener_title), headerNote(screen, locale)).joinToString(" "))
        }
        val description = if (listed) stringResource(R.string.screener_see_all_description, count) else stringResource(R.string.screener_open_description)
        TextButton(onClick = onOpenPage, shape = ButtonShape, modifier = Modifier.semantics { contentDescription = description }) {
            Text(if (listed) stringResource(R.string.screener_see_all, count) else stringResource(R.string.screener_open))
            Icon(painter = painterResource(R.drawable.ic_chevron_right), contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
        }
    }
    when (screen.kind) {
        ScreenerKind.Loading -> QuietLine(stringResource(R.string.stocks_loading))
        ScreenerKind.NotLoaded -> QuietLine(stringResource(R.string.stocks_not_loaded))
        ScreenerKind.NoBars -> StateBlock(
            stringResource(R.string.screener_building),
            stringResource(R.string.screener_building_home, StockTimes.clock(body?.scan?.nextRun) ?: BARS_TIME, body?.universe?.count ?: 0),
        )
        ScreenerKind.NoScanYet -> StateBlock(stringResource(R.string.screener_no_scan), noScanBody(screen))
        ScreenerKind.Empty -> StateBlock(
            stringResource(R.string.screener_none),
            stringResource(R.string.screener_none_home, body?.scan?.codesOk ?: 0, StockTimes.clock(body?.scan?.lastRun) ?: "-"),
        )
        ScreenerKind.List -> {
            whyLine(screen)?.let { Note(it) }
            CandidateList(body?.candidates.orEmpty().take(HOME_ROWS), flag = false, muted = screen.muted, locale = locale, onOpen = onOpenStock)
        }
    }
}

/** "· scanned 10:45", "· last close Thu 5 Nov", "· as of 10:20" (stale), "· no list yet". */
@Composable
private fun headerNote(screen: ScreenerState, locale: Locale): String? {
    val body = screen.body ?: return null
    return when (screen.kind) {
        ScreenerKind.NoBars -> stringResource(R.string.screener_no_list)
        ScreenerKind.NoScanYet -> stringResource(R.string.screener_no_scan_yet)
        else -> when {
            body.screenerMode == ScreenerMode.Eod -> sessionDay(body.sessionDate, locale)?.let { stringResource(R.string.screener_last_close_on, it) }
            screen.stale -> StockTimes.clock(body.dataAsOf)?.let { stringResource(R.string.screener_as_of, it) }
            else -> StockTimes.clock(body.scan.lastRun)?.let { stringResource(R.string.screener_scanned, it) }
        }
    }
}

/** Why the list shown is old: Yahoo refused the last scan, another failure, or the PC is offline. */
@Composable
internal fun whyLine(screen: ScreenerState): String? {
    val body = screen.body ?: return null
    return when {
        screen.offline -> stringResource(R.string.screener_kept)
        screen.stale -> {
            val failed = StockTimes.clock(body.scan.lastRun) ?: "-"
            val asOf = StockTimes.clock(body.dataAsOf) ?: "-"
            stringResource(if (screen.blocked) R.string.screener_refused else R.string.screener_failed_scan, failed, asOf)
        }
        else -> null
    }
}

@Composable
internal fun noScanBody(screen: ScreenerState): String =
    StockTimes.clock(screen.body?.scan?.nextRun)?.let { stringResource(R.string.screener_no_scan_body, it) } ?: stringResource(R.string.screener_no_scan_body_plain)

/** A state's title and one muted paragraph (board `.empty`). */
@Composable
internal fun StateBlock(title: String, body: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = Spacing.listGutter, vertical = Spacing.s)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Spacing.xs))
    }
}

/** One muted `bodySmall` line on the gutter. */
@Composable
internal fun Note(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = AristotleTextStyles.tabular(MaterialTheme.typography.bodySmall),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.fillMaxWidth().padding(horizontal = Spacing.listGutter, vertical = Spacing.xs),
    )
}

/** "Thu 5 Nov" for a session date (`YYYY-MM-DD`). */
internal fun sessionDay(date: String?, locale: Locale): String? = date?.let { runCatching { LocalDate.parse(it) }.getOrNull() }?.let { StockTimes.day(it, locale) }

/** "29 Jan 2027". */
internal fun longDate(date: String?, locale: Locale): String? = date?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    ?.let { "${it.dayOfMonth} ${it.month.getDisplayName(TextStyle.SHORT, locale)} ${it.year}" }

private fun shortDay(date: LocalDate, locale: Locale): String = date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)

private val LABEL_HEIGHT = 24.dp
private val LABEL_ARROW = 16.dp
private val LABEL_GAP = 6.dp
private val ROW_HEIGHT = 64.dp
private val ROW_PADDING = 7.dp
private val CODE_WIDTH = 46.dp
private val PRICE_MIN = 48.dp
private val MARK_ICON = 16.dp
private val MARK_GAP = 6.dp
private const val MAX_LABELS = 3
private const val HOME_ROWS = 3
private const val BARS_TIME = "16:20"
