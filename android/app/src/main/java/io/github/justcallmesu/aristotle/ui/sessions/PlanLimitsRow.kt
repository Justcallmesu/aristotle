package io.github.justcallmesu.aristotle.ui.sessions

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.justcallmesu.aristotle.R
import io.github.justcallmesu.aristotle.data.chat.PlanLimitsLogic
import io.github.justcallmesu.aristotle.data.chat.PlanUsage
import io.github.justcallmesu.aristotle.data.chat.PlanWindow
import io.github.justcallmesu.aristotle.ui.chat.LimitBar
import io.github.justcallmesu.aristotle.ui.chat.LimitMeter
import io.github.justcallmesu.aristotle.ui.theme.Sizes
import io.github.justcallmesu.aristotle.ui.theme.Spacing
import java.time.Instant
import java.time.ZoneId

/**
 * Home's Claude limits (Q83): under the PC status card, "Session · Week · Opus · Sonnet" as four thin labelled bars (the
 * model sheet's meter, compact); a tap opens the full list with "resets in…". A bar turns red from 90%. The caller shows this
 * only with data: there is no loading or empty state here.
 */
@Composable
fun PlanLimitsRow(plan: PlanUsage, expanded: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val now = Instant.now()
    val zone = ZoneId.systemDefault()
    val locale = LocalConfiguration.current.locales[0]
    val title = stringResource(R.string.plan_title)
    val names = plan.windows.map { it to shortName(it) }
    val percents = plan.windows.map { stringResource(R.string.plan_percent, Math.round(it.usedPercent).toInt()) }
    val spoken = title + ": " + names.indices.joinToString(", ") { names[it].second + " " + percents[it] }
    val state = stringResource(if (expanded) R.string.plan_row_open else R.string.plan_row_closed)
    Surface(shape = MaterialTheme.shapes.large, color = colors.surfaceContainerLow, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.animateContentSize()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button, onClick = onToggle)
                    .heightIn(min = Sizes.minTouchTarget)
                    .padding(horizontal = Spacing.l, vertical = 10.dp)
                    .clearAndSetSemantics { contentDescription = spoken; stateDescription = state },
            ) {
                names.forEach { (w, name) -> CompactBar(w, name, Modifier.weight(1f)) }
            }
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.padding(horizontal = Spacing.l).padding(bottom = Spacing.m),
                    verticalArrangement = Arrangement.spacedBy(Spacing.m),
                ) {
                    plan.windows.forEach { w -> LimitMeter(w, PlanLimitsLogic.reset(w.resetsAt, now, zone, locale)) }
                }
            }
        }
    }
}

@Composable
private fun CompactBar(w: PlanWindow, name: String, modifier: Modifier) {
    Column(modifier = modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        LimitBar(w, Modifier.fillMaxWidth())
    }
}

@Composable
private fun shortName(w: PlanWindow): String = when (w.label) {
    "Current session" -> stringResource(R.string.plan_short_session)
    "Current week" -> stringResource(R.string.plan_short_week)
    "Opus week" -> stringResource(R.string.plan_short_opus)
    "Sonnet week" -> stringResource(R.string.plan_short_sonnet)
    else -> w.label
}
