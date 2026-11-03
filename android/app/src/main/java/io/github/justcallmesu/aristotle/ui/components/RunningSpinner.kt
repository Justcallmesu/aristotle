package io.github.justcallmesu.aristotle.ui.components

import android.provider.Settings
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import io.github.justcallmesu.aristotle.ui.theme.Sizes

/** True when the system turned animations off (design.md §4 reduced motion). */
@Composable
fun reducedMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) {
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

/** The 14 dp verdigris spinner for live work only; a static 270° arc when motion is reduced. */
@Composable
fun RunningSpinner(modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.tertiary
    val sized = modifier.then(Modifier.size(Sizes.runningSpinner))
    if (reducedMotion()) {
        CircularProgressIndicator(
            progress = { STATIC_ARC },
            modifier = sized,
            color = color,
            strokeWidth = Sizes.runningSpinnerStroke,
            trackColor = Color.Transparent,
        )
    } else {
        CircularProgressIndicator(
            modifier = sized,
            color = color,
            strokeWidth = Sizes.runningSpinnerStroke,
            trackColor = Color.Transparent,
        )
    }
}

private const val STATIC_ARC = 0.75f
