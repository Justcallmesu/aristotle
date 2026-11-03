package io.github.justcallmesu.aristotle.ui.quote

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.justcallmesu.aristotle.R
import io.github.justcallmesu.aristotle.data.Quote
import io.github.justcallmesu.aristotle.data.QuoteMotion
import io.github.justcallmesu.aristotle.data.QuoteSource
import io.github.justcallmesu.aristotle.ui.theme.AristotleTextStyles
import io.github.justcallmesu.aristotle.ui.theme.Sizes
import io.github.justcallmesu.aristotle.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val EaseOut = CubicBezierEasing(0f, 0f, 0.2f, 1f)
private const val LINES = 3

/**
 * The loading screen (Q44-Q46): the static app icon, one quote fading in line by line (English, Greek, citation)
 * and a thin verdigris progress line at the bottom. No quote (broken asset) = the icon and the progress line.
 */
@Composable
fun QuoteLoadingScreen(source: QuoteSource) {
    var quote by remember { mutableStateOf<Quote?>(null) }
    LaunchedEffect(Unit) { quote = withContext(Dispatchers.Default) { source.next() } }
    val reduced = QuoteMotion.reduced(
        Settings.Global.getFloat(LocalContext.current.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f),
    )
    QuoteLoadingContent(quote, reduced)
}

@Composable
fun QuoteLoadingContent(quote: Quote?, reduced: Boolean) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Column(
                modifier = Modifier.align(Alignment.Center).padding(horizontal = Spacing.xxxl),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.l),
            ) {
                LauncherIcon()
                if (quote != null) QuoteLines(quote, reduced)
            }
            ProgressLine(reduced, Modifier.align(Alignment.BottomCenter))
        }
    }
}

@Composable
private fun LauncherIcon() {
    // The adaptive icon's 108 dp canvas shows its middle 72 dp; draw it so that part fills the 88 dp tile.
    Box(Modifier.size(88.dp).clip(RoundedCornerShape(22.dp)).background(colorResource(R.color.ic_launcher_background)), contentAlignment = Alignment.Center) {
        Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = Modifier.requiredSize(132.dp))
    }
}

@Composable
private fun QuoteLines(quote: Quote, reduced: Boolean) {
    val alphas = remember(quote) { List(LINES) { Animatable(if (reduced) 1f else 0f) } }
    LaunchedEffect(quote, reduced) {
        if (reduced) return@LaunchedEffect
        // Staggered: each line starts a little after the previous one, and fades in with ease-out.
        alphas.forEachIndexed { line, alpha ->
            launch {
                delay(QuoteMotion.delayMillis(line, reduced).toLong())
                alpha.animateTo(1f, tween(QuoteMotion.durationMillis(reduced), easing = EaseOut))
            }
        }
    }
    Column(
        modifier = Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = quote.spoken },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        Text(quote.en, Modifier.alpha(alphas[0].value), color = MaterialTheme.colorScheme.onSurface, style = AristotleTextStyles.quote, textAlign = TextAlign.Center)
        Text(quote.el, Modifier.alpha(alphas[1].value), color = MaterialTheme.colorScheme.onSurfaceVariant, style = AristotleTextStyles.quoteGreek, textAlign = TextAlign.Center)
        Column(Modifier.alpha(alphas[2].value), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(quote.source, color = MaterialTheme.colorScheme.onSurfaceVariant, style = AristotleTextStyles.quoteCitation, textAlign = TextAlign.Center)
            Text(quote.translator, color = MaterialTheme.colorScheme.onSurfaceVariant, style = AristotleTextStyles.quoteCitation, textAlign = TextAlign.Center)
        }
    }
}

/** Thin verdigris line (`tertiary`, live work); a static 40 % bar when motion is reduced (design.md §4). */
@Composable
private fun ProgressLine(reduced: Boolean, modifier: Modifier) {
    val description = stringResource(R.string.loading_progress)
    val line = modifier.fillMaxWidth().height(Sizes.progressLine).semantics { contentDescription = description }
    val color = MaterialTheme.colorScheme.tertiary
    val track = MaterialTheme.colorScheme.surfaceVariant
    if (reduced) {
        LinearProgressIndicator(progress = { QuoteMotion.STATIC_PROGRESS }, modifier = line, color = color, trackColor = track, gapSize = 0.dp, drawStopIndicator = {})
    } else {
        LinearProgressIndicator(modifier = line, color = color, trackColor = track)
    }
}
