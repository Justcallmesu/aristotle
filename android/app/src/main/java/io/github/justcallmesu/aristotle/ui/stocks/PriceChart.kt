package io.github.justcallmesu.aristotle.ui.stocks

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.justcallmesu.aristotle.data.stocks.ChartFit
import io.github.justcallmesu.aristotle.data.stocks.ChartLabels
import io.github.justcallmesu.aristotle.data.stocks.ChartModel
import io.github.justcallmesu.aristotle.data.stocks.ChartRange
import io.github.justcallmesu.aristotle.data.stocks.StockFormat
import io.github.justcallmesu.aristotle.ui.theme.AristotleTextStyles
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * The chart's ink (Q93 "Ink": hollow up, filled down, no direction colour), from the theme. A data class, so an equal
 * palette on the next composition does not rebuild the chart's cached paths.
 */
@Immutable
internal data class ChartInk(
    val ink: Color,
    val paper: Color,
    val grid: Color,
    val axis: Color,
    val volumeUp: Color,
    val volumeDown: Color,
    val line: Color,
    val lastTag: Color,
    val onLastTag: Color,
    val prevClose: Color,
    val brk: Color,
)

@Composable
internal fun chartInk(): ChartInk {
    val c = MaterialTheme.colorScheme
    return ChartInk(
        ink = c.onSurface,
        paper = c.surface,
        grid = c.outlineVariant,
        axis = c.onSurfaceVariant,
        volumeUp = c.outline.copy(alpha = VOLUME_UP_ALPHA),
        volumeDown = c.outline.copy(alpha = VOLUME_DOWN_ALPHA),
        line = c.primary,
        lastTag = c.primary,
        onLastTag = c.onPrimary,
        prevClose = c.outline,
        brk = c.outlineVariant,
    )
}

/**
 * The price chart (P3, stocks-notes §3 `PriceChart`): 180 dp of price over 40 dp of volume, a 46 dp price axis on the
 * right, time labels under it. Candles are ink only (Q93: hollow up, filled down); line mode is one `primary` stroke. The
 * last close has a dashed line and a tag; 1D adds the previous close (dotted) and the session break; 5D marks each day.
 * Bars sit by slot, not wall time ([io.github.justcallmesu.aristotle.data.stocks.BarAxis]).
 *
 * Every path, grid line and label is built in [drawWithCache] (again only when the size, the bars or the type change).
 * The crosshair is a second layer that reads [crosshair] (the touched bar, −1 for none) while drawing, so moving it
 * redraws only that layer; its price tags are measured once per price. Long press then drag moves it, snapping to bars
 * with a haptic tick on each new bar; lifting the finger ends it.
 */
@Composable
internal fun PriceChart(
    model: ChartModel,
    candles: Boolean,
    crosshair: MutableIntState,
    description: String,
    prevCloseLabel: String?,
    locale: Locale,
    modifier: Modifier = Modifier,
) {
    val ink = chartInk()
    val measurer = rememberTextMeasurer()
    val haptics = LocalHapticFeedback.current
    val axisStyle = AristotleTextStyles.chartAxis
    val tagStyle = axisStyle.copy(fontWeight = FontWeight.W600)
    val density = LocalDensity.current
    val stripPx = with(density) { GESTURE_STRIP.toPx() }
    val stripMaxPx = with(density) { ChartFit.GESTURE_EDGE_MAX_DP.dp.toPx() }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(chartHeight())
            // The chart's first and last bars sit near the screen edges: a drag there must move the crosshair, not go back.
            // Only an edge strip of the chart, no taller than the system's per-edge limit, so the system keeps all of it.
            .systemGestureExclusion { edge(it.size.width, it.size.height, atLeft = true, stripPx, stripMaxPx) }
            .systemGestureExclusion { edge(it.size.width, it.size.height, atLeft = false, stripPx, stripMaxPx) }
            .semantics { contentDescription = description }
            .pointerInput(model) {
                val axisWidth = AXIS_WIDTH.toPx()
                fun snap(x: Float) {
                    val index = model.axis.barAt(x, 0f, size.width - axisWidth)
                    if (index >= 0 && index != crosshair.intValue) {
                        crosshair.intValue = index
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                    }
                }
                detectDragGesturesAfterLongPress(
                    onDragStart = { snap(it.x) },
                    onDrag = { change, _ ->
                        change.consume()
                        snap(change.position.x)
                    },
                    onDragEnd = { crosshair.intValue = NONE },
                    onDragCancel = { crosshair.intValue = NONE },
                )
            }
            .drawWithCache {
                val frame = ChartFrame(this, size)
                val base = BaseLayer.build(this, frame, model, candles, ink, measurer, axisStyle, tagStyle, prevCloseLabel, locale)
                onDrawBehind { base.draw(this) }
            }
            .drawWithCache {
                val frame = ChartFrame(this, size)
                val dash = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx()))
                // Each bar's price tag is measured the first time the finger reaches it, then reused (no boxing, no map).
                val tags = arrayOfNulls<TextLayoutResult>(model.bars.size)
                val stroke = 1.dp.toPx()
                val dot = 3.dp.toPx()
                val tagHeight = TAG_HEIGHT.toPx()
                val tagRadius = CornerRadius(TAG_RADIUS.toPx())
                val tagPad = TAG_PAD.toPx()
                onDrawBehind {
                    val i = crosshair.intValue
                    if (i < 0 || i >= model.bars.size) return@onDrawBehind
                    val bar = model.bars[i]
                    val x = model.axis.x(i, 0f, frame.plotWidth)
                    val y = model.scale.y(bar.c, 0f, frame.priceHeight)
                    drawLine(ink.axis, Offset(x, 0f), Offset(x, frame.volumeBottom), stroke, pathEffect = dash)
                    drawLine(ink.axis, Offset(0f, y), Offset(frame.plotWidth, y), stroke, pathEffect = dash)
                    drawCircle(ink.ink, dot, Offset(x, y))
                    val tag = tags[i] ?: fitTag(measurer, StockFormat.number(bar.c), tagStyle, frame.axisWidth - tagPad - 1f).also { tags[i] = it }
                    drawRoundRect(ink.ink, Offset(frame.plotWidth + 1f, y - tagHeight / 2), Size(frame.axisWidth - 1f, tagHeight), tagRadius)
                    drawText(tag, ink.paper, Offset(frame.plotWidth + tagPad, y - tag.size.height / 2f))
                }
            },
    )
}

/** The panels in pixels: price on top, volume under it, time labels at the bottom; the price axis on the right. */
private class ChartFrame(density: Density, size: Size) {
    val height: Float = size.height
    val axisWidth: Float = with(density) { AXIS_WIDTH.toPx() }
    val plotWidth: Float = max(size.width - axisWidth, 1f)
    val priceHeight: Float = with(density) { PRICE_HEIGHT.toPx() }
    val volumeTop: Float = priceHeight + with(density) { VOLUME_GAP.toPx() }
    val volumeHeight: Float = with(density) { VOLUME_HEIGHT.toPx() }
    val volumeBottom: Float = volumeTop + volumeHeight
    val labelTop: Float = labelTopPx(density)
}

/** Where the time labels start, in px: price, gap, volume, gap (the same sum [ChartFrame] and [chartHeightFor] use). */
internal fun labelTopPx(density: Density): Float = with(density) { PRICE_HEIGHT.toPx() + VOLUME_GAP.toPx() + VOLUME_HEIGHT.toPx() + LABEL_GAP.toPx() }

/** A price label measured to fit [room] px: shrunk, or null when it would have to be too small to read. */
private fun fitLabel(measurer: TextMeasurer, text: String, style: TextStyle, room: Float): TextLayoutResult? {
    val first = measurer.measure(text, style)
    val factor = ChartFit.shrink(first.size.width.toFloat(), room) ?: return null
    return if (factor == 1f) first else measurer.measure(text, style.copy(fontSize = style.fontSize * factor))
}

/** A price tag (on a filled box): shrunk to fit [room] but never dropped; below the smallest share it is cut at the box. */
private fun fitTag(measurer: TextMeasurer, text: String, style: TextStyle, room: Float): TextLayoutResult {
    val first = measurer.measure(text, style)
    val factor = ChartFit.shrink(first.size.width.toFloat(), room) ?: ChartFit.MIN_SHRINK
    return if (factor == 1f) first else measurer.measure(text, style.copy(fontSize = style.fontSize * factor))
}

/**
 * The chart's height: [CHART_HEIGHT], more when the time labels are taller than the room under the volume. The label
 * line is measured with the axis style the drawer uses, so it carries the app's text size and the phone's font scale
 * as Compose applies it (non-linear from 1.03x: at 1.3x a 14 sp line is 18.8 dp, not 18.2), never a guess.
 */
@Composable
internal fun chartHeight(): Dp {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val style = AristotleTextStyles.chartAxis
    val line = remember(measurer, style, density) { measurer.measure(AXIS_PROBE, style, density = density).size.height }
    return chartHeightFor(density, line.toFloat())
}

/** [chartHeight] for a time label [labelHeightPx] tall as measured: it ends inside the box once the box is rounded to px. */
internal fun chartHeightFor(density: Density, labelHeightPx: Float): Dp = with(density) {
    ChartFit.chartHeightPx(labelTopPx(density), labelHeightPx, CHART_HEIGHT.toPx()).toDp()
}

/** Everything that does not move with the finger, built once. */
private class BaseLayer(
    private val ink: ChartInk,
    private val frame: ChartFrame,
    private val gridYs: FloatArray,
    private val gridLabels: List<TextLayoutResult?>,
    private val volumeUp: Path,
    private val volumeDown: Path,
    private val wicks: Path,
    private val upBodies: Path,
    private val downBodies: Path,
    private val line: Path?,
    private val dot: Path?,
    private val breaks: FloatArray,
    private val timeLabels: List<Pair<Float, TextLayoutResult>>,
    private val lastY: Float,
    private val lastTag: TextLayoutResult,
    private val prevY: Float?,
    private val prevLabel: TextLayoutResult?,
    private val prevLabelAt: Offset?,
    private val stroke: Float,
    private val lineStroke: Float,
    private val dash: PathEffect,
    private val dots: PathEffect,
    private val tagHeight: Float,
    private val tagRadius: CornerRadius,
    private val tagPad: Float,
) {
    fun draw(scope: DrawScope) = with(scope) {
        gridYs.forEachIndexed { i, y ->
            drawLine(ink.grid, Offset(0f, y), Offset(frame.plotWidth, y), stroke)
            val label = gridLabels[i] ?: return@forEachIndexed
            drawText(label, ink.axis, Offset(frame.plotWidth + tagPad, y - label.size.height / 2f))
        }
        drawLine(ink.grid, Offset(0f, frame.volumeBottom), Offset(frame.plotWidth, frame.volumeBottom), stroke)
        breaks.forEach { x -> drawLine(ink.brk, Offset(x, 0f), Offset(x, frame.volumeBottom), stroke, pathEffect = dash) }
        prevY?.let { y -> drawLine(ink.prevClose, Offset(0f, y), Offset(frame.plotWidth, y), stroke, pathEffect = dots) }
        if (prevLabel != null && prevLabelAt != null) drawText(prevLabel, ink.axis, prevLabelAt)
        drawPath(volumeUp, ink.volumeUp, style = Fill)
        drawPath(volumeDown, ink.volumeDown, style = Fill)
        if (line != null) {
            drawPath(line, ink.line, style = Stroke(width = lineStroke, join = StrokeJoin.Round))
            if (dot != null) drawPath(dot, ink.line, style = Fill)
        } else {
            drawPath(wicks, ink.ink, style = Stroke(width = stroke))
            drawPath(upBodies, ink.paper, style = Fill)
            drawPath(upBodies, ink.ink, style = Stroke(width = stroke))
            drawPath(downBodies, ink.ink, style = Fill)
        }
        drawLine(ink.lastTag, Offset(0f, lastY), Offset(frame.plotWidth, lastY), stroke, pathEffect = dash)
        drawRoundRect(ink.lastTag, Offset(frame.plotWidth + 1f, lastY - tagHeight / 2), Size(frame.axisWidth - 1f, tagHeight), tagRadius)
        drawText(lastTag, ink.onLastTag, Offset(frame.plotWidth + tagPad, lastY - lastTag.size.height / 2f))
        timeLabels.forEach { (x, label) -> drawText(label, ink.axis, Offset(x, frame.labelTop)) }
    }

    companion object {
        fun build(
            density: Density,
            frame: ChartFrame,
            model: ChartModel,
            candles: Boolean,
            ink: ChartInk,
            measurer: TextMeasurer,
            axisStyle: TextStyle,
            tagStyle: TextStyle,
            prevCloseLabel: String?,
            locale: Locale,
        ): BaseLayer = with(density) {
            val bars = model.bars
            val axis = model.axis
            val scale = model.scale
            val w = frame.plotWidth
            val ph = frame.priceHeight
            val slotWidth = w / axis.slots
            val bodyWidth = (slotWidth * BODY_SHARE).coerceIn(1.dp.toPx(), MAX_BODY.toPx())
            val half = bodyWidth / 2

            val gridYs = FloatArray(scale.ticks.size) { scale.y(scale.ticks[it], 0f, ph) }
            // A price wider than the axis (6 digits at a large font) shrinks, or is dropped when it would be too small.
            val room = frame.axisWidth - TAG_PAD.toPx() - 1f
            val gridLabels = scale.ticks.map { fitLabel(measurer, StockFormat.number(it), axisStyle, room) }

            val volumeUp = Path()
            val volumeDown = Path()
            val minVolume = 1.dp.toPx()
            if (model.maxVolume > 0) {
                bars.forEachIndexed { i, b ->
                    val h = max(minVolume, b.v.toFloat() / model.maxVolume * frame.volumeHeight)
                    val x = axis.x(i, 0f, w)
                    (if (b.c >= b.o) volumeUp else volumeDown).addRect(Rect(x - half, frame.volumeBottom - h, x + half, frame.volumeBottom))
                }
            }

            val wicks = Path()
            val upBodies = Path()
            val downBodies = Path()
            var line: Path? = null
            var dot: Path? = null
            if (candles) {
                val stroke = 1.dp.toPx()
                bars.forEachIndexed { i, b ->
                    val x = axis.x(i, 0f, w)
                    val top = scale.y(max(b.o, b.c), 0f, ph)
                    val bottom = max(scale.y(min(b.o, b.c), 0f, ph), top + stroke)
                    wicks.moveTo(x, scale.y(b.h, 0f, ph))
                    wicks.lineTo(x, top)
                    wicks.moveTo(x, bottom)
                    wicks.lineTo(x, scale.y(b.l, 0f, ph))
                    val inset = if (bodyWidth > 2 * stroke) stroke / 2 else 0f
                    val rect = Rect(x - half + inset, top, x + half - inset, bottom)
                    if (b.c >= b.o) upBodies.addRect(rect) else downBodies.addRect(rect)
                }
            } else {
                line = Path().apply {
                    bars.forEachIndexed { i, b ->
                        val x = axis.x(i, 0f, w)
                        val y = scale.y(b.c, 0f, ph)
                        if (i == 0) moveTo(x, y) else lineTo(x, y)
                    }
                }
                if (ChartFit.lineIsDot(bars.size)) {
                    // One bar: a stroke of one point draws nothing, so a dot.
                    val r = LINE_STROKE.toPx() * 1.5f
                    dot = Path().apply { addOval(Rect(axis.x(0, 0f, w) - r, scale.y(bars[0].c, 0f, ph) - r, axis.x(0, 0f, w) + r, scale.y(bars[0].c, 0f, ph) + r)) }
                }
            }

            val breaks = FloatArray(axis.breaks.size) { axis.slotX(axis.breaks[it], 0f, w) }

            val minGap = ceil(LABEL_MIN_GAP.toPx() / slotWidth).toInt().coerceAtLeast(1)
            val labels = ChartLabels.axis(model.range, bars, axis, model.body.interval, minGap, locale)
            val timeLabels = labels.mapNotNull { l ->
                val text = measurer.measure(l.text, axisStyle)
                if (!ChartFit.fitsBelow(frame.labelTop, text.size.height.toFloat(), frame.height)) return@mapNotNull null
                val x = (axis.slotCentre(l.slot, 0f, w) - text.size.width / 2f).coerceIn(0f, max(w - text.size.width, 0f))
                x to text
            }

            val last = bars.last().c
            val prev = model.prevClose
            val prevY = prev?.let { scale.y(it, 0f, ph) }
            var prevLabel: TextLayoutResult? = null
            var prevLabelAt: Offset? = null
            if (prevCloseLabel != null && prevY != null && model.range == ChartRange.Day) {
                // Clear of the candles: on the empty right side while the day is young, else at the left.
                val label = measurer.measure(prevCloseLabel, axisStyle)
                val right = bars.size < axis.slots * YOUNG_DAY
                val x = if (right) w - label.size.width - 4.dp.toPx() else 4.dp.toPx()
                val y = if (prevY < ph / 2) prevY + 2.dp.toPx() else prevY - label.size.height - 2.dp.toPx()
                prevLabel = label
                prevLabelAt = Offset(x, y)
            }

            BaseLayer(
                ink = ink,
                frame = frame,
                gridYs = gridYs,
                gridLabels = gridLabels,
                volumeUp = volumeUp,
                volumeDown = volumeDown,
                wicks = wicks,
                upBodies = upBodies,
                downBodies = downBodies,
                line = line,
                dot = dot,
                breaks = breaks,
                timeLabels = timeLabels,
                lastY = scale.y(last, 0f, ph),
                lastTag = fitTag(measurer, StockFormat.number(last), tagStyle, room),
                prevY = prevY,
                prevLabel = prevLabel,
                prevLabelAt = prevLabelAt,
                stroke = 1.dp.toPx(),
                lineStroke = LINE_STROKE.toPx(),
                dash = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())),
                dots = PathEffect.dashPathEffect(floatArrayOf(1.dp.toPx(), 3.dp.toPx())),
                tagHeight = TAG_HEIGHT.toPx(),
                tagRadius = CornerRadius(TAG_RADIUS.toPx()),
                tagPad = TAG_PAD.toPx(),
            )
        }
    }
}

/** [ChartFit.gestureStrip] as a Compose [Rect] in the chart box's own coordinates. */
private fun edge(width: Int, height: Int, atLeft: Boolean, stripPx: Float, maxHeightPx: Float): Rect =
    ChartFit.gestureStrip(atLeft, width.toFloat(), height.toFloat(), stripPx, maxHeightPx).let { Rect(it.left, it.top, it.right, it.bottom) }

internal const val NONE = -1
/** Wider than the system back-gesture zone at its largest sensitivity, measured from the chart's own edge. */
private val GESTURE_STRIP = 48.dp
private val PRICE_HEIGHT = 180.dp
private val VOLUME_GAP = 10.dp
private val VOLUME_HEIGHT = 40.dp
private val LABEL_GAP = 4.dp
internal val CHART_HEIGHT = 250.dp
/** A time label to measure the axis line by: digits and letters, one line (every label is one line of the same style). */
private const val AXIS_PROBE = "09:00 Nov"
private val AXIS_WIDTH = 46.dp
private val MAX_BODY = 9.dp
private val LINE_STROKE = 1.75.dp
private val TAG_HEIGHT = 18.dp
private val TAG_RADIUS = 4.dp
private val TAG_PAD = 5.dp
private val LABEL_MIN_GAP = 52.dp
private const val BODY_SHARE = 0.62f
private const val YOUNG_DAY = 0.6f
private const val VOLUME_UP_ALPHA = 0.45f
private const val VOLUME_DOWN_ALPHA = 0.85f
