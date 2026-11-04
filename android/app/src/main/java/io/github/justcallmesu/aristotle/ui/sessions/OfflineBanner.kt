package io.github.justcallmesu.aristotle.ui.sessions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.justcallmesu.aristotle.R
import io.github.justcallmesu.aristotle.data.LinkState
import io.github.justcallmesu.aristotle.data.OfflineCause
import io.github.justcallmesu.aristotle.ui.components.reducedMotion
import io.github.justcallmesu.aristotle.ui.theme.AristotleTextStyles
import io.github.justcallmesu.aristotle.ui.theme.Sizes
import io.github.justcallmesu.aristotle.ui.theme.Spacing
import kotlinx.coroutines.delay

/**
 * design-notes §5.1 offline notice, under the Home C head (whose PC line carries "Offline since HH:MM" and Retry now):
 * the cause and when the list is from as one quiet text, then "Reconnecting… try N, next in S s". The verdigris
 * progress line shows only while a round is running (live work); between rounds the line counts down and nothing moves.
 */
@Composable
fun OfflineNotice(offline: LinkState.Offline, address: String, listedAt: String?) {
    val cause = causeText(offline.cause, address)
    val text = listedAt?.let { "$cause ${stringResource(R.string.offline_list_from, it)}" } ?: cause
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.xs),
    ) {
        Column {
            if (offline.trying) ReconnectingLine()
            Column(
                modifier = Modifier
                    .heightIn(min = NOTICE_MIN_HEIGHT)
                    .padding(start = Spacing.m, end = Spacing.s, top = Spacing.s, bottom = Spacing.s),
                verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
            ) {
                Text(text = text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                RetryStatus(offline)
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

/** Each notice (offline, compatibility, what's new) is at least one 48 dp line (board `.notice`). */
internal val NOTICE_MIN_HEIGHT = 48.dp

private const val TICK_MILLIS = 1_000L
private const val STATIC_PROGRESS = 0.4f
