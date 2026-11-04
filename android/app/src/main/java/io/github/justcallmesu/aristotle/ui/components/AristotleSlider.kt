package io.github.justcallmesu.aristotle.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import kotlin.math.roundToInt

/** Pure: the slider's float value -> a level index clamped to `0 until count`. */
internal fun sliderIndex(value: Float, count: Int): Int = value.roundToInt().coerceIn(0, (count - 1).coerceAtLeast(0))

/** Pure: Material `steps` (interior stops) for [count] levels. */
internal fun sliderSteps(count: Int): Int = (count - 2).coerceAtLeast(0)

/** Pure: a gesture end commits only when it landed on another level than the current one. */
internal fun shouldCommit(index: Int, currentIndex: Int): Boolean = index != currentIndex

/**
 * Stepped slider for an ordered list ([levels], at least 2) with the level name written above it (the name is the real
 * signal; the handle is not). [onPick] fires once per gesture end, never per tick. Colours per design.md §3.
 */
@Composable
fun <T> AristotleSlider(
    levels: List<T>,
    current: T,
    label: (T) -> String,
    onPick: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    startLabel: String? = null,
    endLabel: String? = null,
) {
    if (levels.size < 2) return
    val haptics = LocalHapticFeedback.current
    val currentIndex = levels.indexOf(current).coerceAtLeast(0)
    var index by remember(levels, current) { mutableIntStateOf(currentIndex) }
    val scheme = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth()) {
        Text(label(levels[index]), style = MaterialTheme.typography.titleMedium)
        Slider(
            value = index.toFloat(),
            onValueChange = { v ->
                val i = sliderIndex(v, levels.size)
                if (i != index) {
                    index = i
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                }
            },
            onValueChangeFinished = { if (shouldCommit(index, currentIndex)) onPick(levels[index]) },
            valueRange = 0f..(levels.size - 1).toFloat(),
            steps = sliderSteps(levels.size),
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = scheme.primary,
                activeTrackColor = scheme.primary,
                inactiveTrackColor = scheme.surfaceContainerHighest,
                activeTickColor = scheme.onPrimary,
                inactiveTickColor = scheme.onSurfaceVariant,
            ),
            modifier = Modifier.fillMaxWidth().semantics { stateDescription = label(levels[index]) },
        )
        if (startLabel != null || endLabel != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween) {
                Text(startLabel.orEmpty(), style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                Text(endLabel.orEmpty(), style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
            }
        }
    }
}
