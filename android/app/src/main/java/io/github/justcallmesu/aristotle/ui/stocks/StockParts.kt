package io.github.justcallmesu.aristotle.ui.stocks

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.justcallmesu.aristotle.R
import io.github.justcallmesu.aristotle.data.stocks.ChangeDisplay
import io.github.justcallmesu.aristotle.data.stocks.Direction
import io.github.justcallmesu.aristotle.data.stocks.SparkGeometry
import io.github.justcallmesu.aristotle.data.stocks.StockChanges
import io.github.justcallmesu.aristotle.data.stocks.StockFormat
import io.github.justcallmesu.aristotle.data.stocks.StockQuote
import io.github.justcallmesu.aristotle.ui.theme.AristotleStockColors
import io.github.justcallmesu.aristotle.ui.theme.AristotleTextStyles

/**
 * The colour of a change (Q91 "green / red text"): gain, loss, flat in `onSurfaceVariant`. [muted] (offline, a stale
 * price) drops all direction colour; the arrow and the sign stay (stocks-notes §2).
 */
@Composable
@ReadOnlyComposable
fun changeColor(direction: Direction, muted: Boolean): Color = when {
    muted || direction == Direction.Flat || direction == Direction.Unknown -> MaterialTheme.colorScheme.onSurfaceVariant
    direction == Direction.Up -> AristotleStockColors.gain
    else -> AristotleStockColors.loss
}

/** The arrow of a direction: `arrow_drop_up` / `arrow_drop_down`, `remove` for no change, none when unknown. */
private fun arrowOf(direction: Direction): Int? = when (direction) {
    Direction.Up -> R.drawable.ic_arrow_drop_up
    Direction.Down -> R.drawable.ic_arrow_drop_down
    Direction.Flat -> R.drawable.ic_remove
    Direction.Unknown -> null
}

/** Arrow + signed text in the change colour (`ChangeText`, stocks-notes §3). Colour is never the only cue. */
@Composable
fun ChangeText(change: ChangeDisplay, modifier: Modifier = Modifier, muted: Boolean = false, style: TextStyle = AristotleTextStyles.stockChange, arrow: Dp = ARROW) {
    val color = changeColor(change.direction, muted)
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        arrowOf(change.direction)?.let { icon ->
            Icon(painter = painterResource(icon), contentDescription = null, tint = color, modifier = Modifier.size(if (change.direction == Direction.Flat) arrow * FLAT_SCALE else arrow))
        }
        Text(text = change.text, style = style, color = color, maxLines = 1)
    }
}

/** How a screen reader says a change: "up 90, 1,86 %" / "down …" / "unchanged". */
@Composable
fun changeWords(change: ChangeDisplay): String = when (change.direction) {
    Direction.Up -> stringResource(R.string.stocks_change_up, change.text.removePrefix("+"))
    Direction.Down -> stringResource(R.string.stocks_change_down, change.text.removePrefix(StockFormat.MINUS.toString()))
    Direction.Flat -> stringResource(R.string.stocks_change_flat)
    Direction.Unknown -> stringResource(R.string.stocks_change_unknown)
}

/**
 * The watchlist sparkline (stocks-notes §3, 64 × 28): today's 5-minute closes and a dotted previous-close line, in the
 * change colour; offline or stale it is drawn in `outline`. Decorative: the row's description says the numbers.
 */
@Composable
fun Sparkline(closes: List<Long>, prevClose: Long?, direction: Direction, muted: Boolean, modifier: Modifier = Modifier) {
    val line = if (muted) MaterialTheme.colorScheme.outline else changeColor(direction, muted = false)
    val baseline = MaterialTheme.colorScheme.outline
    Canvas(modifier = modifier.size(SPARK_WIDTH, SPARK_HEIGHT)) {
        val inset = 2.dp.toPx()
        val shape = SparkGeometry.of(closes, prevClose, size.width, size.height, inset) ?: return@Canvas
        shape.baselineY?.let { y ->
            drawLine(baseline, Offset(inset, y), Offset(size.width - inset, y), strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 3.dp.toPx())))
        }
        val path = Path().apply {
            shape.points.forEachIndexed { i, (x, y) -> if (i == 0) moveTo(x, y) else lineTo(x, y) }
        }
        drawPath(path, line, style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** A ticker chip (`AssistChip`, small shape): the code in Mono 600 13 and today's change in percent. Opens the stock. */
@Composable
fun TickerChip(code: String, quote: StockQuote?, muted: Boolean, onOpen: (String) -> Unit) {
    val change = StockChanges.quotePercent(quote)
    val description = stringResource(R.string.stocks_chip_description, code, changeWords(change))
    AssistChip(
        onClick = { onOpen(code) },
        label = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = code, style = AristotleTextStyles.stockCodeSmall, color = MaterialTheme.colorScheme.onSurface)
                if (quote != null) ChangeText(change, muted = muted, arrow = CHIP_ARROW)
            }
        },
        shape = MaterialTheme.shapes.small,
        border = AssistChipDefaults.assistChipBorder(enabled = true, borderColor = MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.semantics { contentDescription = description },
    )
}

/** A price, or the em dash without one. */
fun priceText(price: Long?): String = StockFormat.orDash(price, StockFormat::number)

/** The change arrow's size; the readout measures its first line against it. */
internal val ARROW = 18.dp
private val CHIP_ARROW = 16.dp
private const val FLAT_SCALE = 0.75f
private val SPARK_WIDTH = 64.dp
private val SPARK_HEIGHT = 28.dp
