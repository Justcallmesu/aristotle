package io.github.justcallmesu.aristotle.ui.sessions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import io.github.justcallmesu.aristotle.R
import io.github.justcallmesu.aristotle.data.LinkState
import io.github.justcallmesu.aristotle.data.OfflineCause
import io.github.justcallmesu.aristotle.ui.components.reducedMotion
import io.github.justcallmesu.aristotle.ui.theme.AristotleTextStyles
import io.github.justcallmesu.aristotle.ui.theme.ButtonShape
import io.github.justcallmesu.aristotle.ui.theme.Sizes
import io.github.justcallmesu.aristotle.ui.theme.Spacing
import kotlinx.coroutines.delay

/**
 * design.md §5 offline banner, under the top bar: "PC offline since HH:MM", the cause, when the list is
 * from, "Reconnecting… try N, next in S s" and Retry now. The verdigris progress line shows only while a
 * round is running (live work); between rounds the banner counts down to the next one and nothing moves.
 */
@Composable
fun OfflineBanner(
    offline: LinkState.Offline,
    address: String,
    listedAt: String?,
    timeText: (Long) -> String,
    onRetry: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.m, vertical = Spacing.xs),
    ) {
        Column {
            if (offline.trying) ReconnectingLine() else Spacer(Modifier.height(Sizes.progressLine))
            Row(modifier = Modifier.padding(start = Spacing.l, end = Spacing.m, top = Spacing.m)) {
                Icon(
                    painter = painterResource(R.drawable.ic_cloud_off),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(Sizes.fieldIcon),
                )
                Column(
                    modifier = Modifier.padding(start = Spacing.m).weight(1f),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    Text(
                        text = stringResource(R.string.offline_title, timeText(offline.sinceMillis)),
                        style = AristotleTextStyles.tabular(MaterialTheme.typography.titleSmall),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    BodyLine(causeText(offline.cause, address))
                    listedAt?.let { BodyLine(stringResource(R.string.offline_list_from, it)) }
                    RetryStatus(offline)
                }
            }
            Row(modifier = Modifier.fillMaxWidth().padding(end = Spacing.xs), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onRetry, shape = ButtonShape) { Text(stringResource(R.string.conn_retry_now)) }
            }
        }
    }
}

@Composable
private fun causeText(cause: OfflineCause, address: String): String = when (cause) {
    OfflineCause.TailscaleMayBeOff -> stringResource(R.string.offline_cause_tailscale)
    OfflineCause.ConnectionLost -> stringResource(R.string.offline_cause_lost, address)
    OfflineCause.Unreachable, OfflineCause.CertificateChanged -> stringResource(R.string.offline_cause_unreachable, address)
    OfflineCause.AppError -> stringResource(R.string.offline_cause_app_error, address)
    OfflineCause.Superseded -> stringResource(R.string.offline_cause_superseded, address)
}

@Composable
private fun BodyLine(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * "Reconnecting… try N" during a round, else "Reconnecting… try N, next in S s" counting down (whole
 * seconds, tabular). N is the try that runs or runs next: [LinkState.Offline.attempt] tries have failed.
 * A superseded link (D36) says why it waits, counting down to the slow backstop (D39).
 */
@Composable
private fun RetryStatus(offline: LinkState.Offline) {
    val next = offline.nextAttemptAtMillis
    val tryNumber = offline.attempt + 1
    val text = when {
        next == null -> stringResource(R.string.offline_reconnecting, tryNumber)
        offline.waiting -> stringResource(R.string.offline_waiting, secondsLeft(next))
        else -> stringResource(R.string.offline_reconnecting_next, tryNumber, secondsLeft(next))
    }
    Text(
        text = text,
        style = AristotleTextStyles.tabular(MaterialTheme.typography.bodySmall),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** The reconnecting progress line (tertiary = live work); a static 40 % bar when motion is reduced. */
@Composable
private fun ReconnectingLine() {
    val modifier = Modifier.fillMaxWidth().height(Sizes.progressLine)
    val color = MaterialTheme.colorScheme.tertiary
    if (reducedMotion()) {
        LinearProgressIndicator(
            progress = { STATIC_PROGRESS },
            modifier = modifier,
            color = color,
            trackColor = Color.Transparent,
        )
    } else {
        LinearProgressIndicator(modifier = modifier, color = color, trackColor = Color.Transparent)
    }
}

/** Whole seconds until [atMillis], ticking down once a second. */
@Composable
private fun secondsLeft(atMillis: Long): Int {
    val left by produceState(secondsUntil(atMillis), atMillis) {
        while (value > 0) {
            delay(TICK_MILLIS)
            value = secondsUntil(atMillis)
        }
    }
    return left
}

private fun secondsUntil(atMillis: Long): Int = ((atMillis - System.currentTimeMillis() + 999) / 1000).toInt().coerceAtLeast(0)

private const val TICK_MILLIS = 1_000L
private const val STATIC_PROGRESS = 0.4f
