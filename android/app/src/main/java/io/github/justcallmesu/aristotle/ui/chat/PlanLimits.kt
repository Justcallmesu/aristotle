package io.github.justcallmesu.aristotle.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.justcallmesu.aristotle.R
import io.github.justcallmesu.aristotle.data.chat.PlanReset
import io.github.justcallmesu.aristotle.data.chat.PlanLimitsLogic
import io.github.justcallmesu.aristotle.data.chat.PlanUsage
import io.github.justcallmesu.aristotle.data.chat.PlanWindow
import io.github.justcallmesu.aristotle.ui.theme.AristotleTextStyles
import io.github.justcallmesu.aristotle.ui.theme.Sizes
import io.github.justcallmesu.aristotle.ui.theme.Spacing
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.delay

/**
 * The Claude plan limits (Q81e) at the top of the model sheet: "Current session", "Current week", "Opus week" and
 * "Sonnet week" as thin meters with their reset time, under a collapsible head. [onRetry] reloads the catalog; it is
 * called once, 3 s after an empty first answer (Hermes fills its cache in the background). Nothing shows when the
 * Anthropic provider is absent, when the retry is empty too, or when the numbers are older than 15 min.
 */
@Composable
fun PlanLimits(plan: PlanUsage, onRetry: () -> Unit) {
    var retried by remember { mutableStateOf(false) }
    var expired by remember(plan) { mutableStateOf(false) }
    var open by remember { mutableStateOf(true) }
    LaunchedEffect(plan) {
        if (PlanLimitsLogic.shouldRetry(plan, retried)) {
            delay(PlanLimitsLogic.RETRY_MS)
            retried = true
            onRetry()
        } else if (plan.windows.isNotEmpty()) {
            delay(PlanUsage.STALE_MS)
            expired = true
        }
    }
    val loading = PlanLimitsLogic.loading(plan) && !retried
    if (!loading && !PlanLimitsLogic.visible(plan, 0, if (expired) PlanUsage.STALE_MS else 0)) return
    val colors = MaterialTheme.colorScheme
    val state = stringResource(if (open) R.string.model_group_open else R.string.model_group_closed)
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Sizes.listRowHeight)
                .clickable(role = Role.Button) { open = !open }
                .padding(horizontal = Spacing.xxl)
                .semantics(mergeDescendants = true) { stateDescription = state; heading() },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            Text(stringResource(R.string.plan_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Icon(
                painter = painterResource(R.drawable.ic_expand_more),
                contentDescription = null,
                tint = colors.onSurfaceVariant,
                modifier = Modifier.rotate(if (open) 180f else 0f),
            )
        }
        if (open) {
            if (loading) {
                Text(
                    stringResource(R.string.plan_loading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.xxl).padding(bottom = Spacing.m),
                )
            } else {
                Column(
                    modifier = Modifier.padding(horizontal = Spacing.xxl).padding(bottom = Spacing.m),
                    verticalArrangement = Arrangement.spacedBy(Spacing.m),
                ) {
                    if (plan.accounts > 1) {
                        val who = plan.account?.takeIf { it.isNotBlank() }
                        Text(
                            stringResource(if (who != null) R.string.plan_pool_named else R.string.plan_pool, plan.accounts, who.orEmpty()),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                    val now = Instant.now()
                    val zone = ZoneId.systemDefault()
                    val locale = LocalConfiguration.current.locales[0]
                    plan.windows.forEach { w -> LimitMeter(w, PlanLimitsLogic.reset(w.resetsAt, now, zone, locale)) }
                }
            }
        }
        Spacer(Modifier.height(Spacing.xs))
    }
}

@Composable
private fun LimitMeter(w: PlanWindow, reset: PlanReset) {
    val colors = MaterialTheme.colorScheme
    val name = when (w.label) {
        "Current session" -> stringResource(R.string.plan_session)
        "Current week" -> stringResource(R.string.plan_week)
        "Opus week" -> stringResource(R.string.plan_opus)
        "Sonnet week" -> stringResource(R.string.plan_sonnet)
        else -> w.label
    }
    val percent = stringResource(R.string.plan_percent, Math.round(w.usedPercent).toInt())
    val resetText = when (reset) {
        PlanReset.Unknown -> null
        PlanReset.Passed -> stringResource(R.string.plan_resets_passed)
        is PlanReset.In -> if (reset.hours == 0) stringResource(R.string.plan_resets_in_m, reset.minutes) else stringResource(R.string.plan_resets_in_hm, reset.hours, reset.minutes)
        is PlanReset.At -> stringResource(R.string.plan_resets_at, reset.text)
    }
    val spoken = listOfNotNull(name, percent, resetText).joinToString(", ")
    Column(modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = spoken }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(percent, style = AristotleTextStyles.tabular(MaterialTheme.typography.titleSmall), color = colors.onSurface, maxLines = 1)
        }
        // The PC card's meter (design-notes §5.7): a 4 dp round bar; the error colour once the window is nearly spent.
        LinearProgressIndicator(
            progress = { PlanLimitsLogic.fraction(w) },
            modifier = Modifier.padding(top = 6.dp).fillMaxWidth().height(4.dp),
            color = if (w.usedPercent >= 90) colors.error else colors.primary,
            trackColor = colors.surfaceContainerHighest,
            strokeCap = StrokeCap.Round,
            gapSize = 4.dp,
            drawStopIndicator = {},
        )
        resetText?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)) }
    }
}
