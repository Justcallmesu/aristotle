package io.github.justcallmesu.aristotle.ui.sessions

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import io.github.justcallmesu.aristotle.R
import io.github.justcallmesu.aristotle.data.PcStatus
import io.github.justcallmesu.aristotle.data.PcStatusFormat
import io.github.justcallmesu.aristotle.data.Uptime
import io.github.justcallmesu.aristotle.ui.theme.AristotleTextStyles
import io.github.justcallmesu.aristotle.ui.theme.Sizes
import io.github.justcallmesu.aristotle.ui.theme.Spacing
import java.util.Locale

/**
 * The PC status card on Home (Q78): one compact row (battery, CPU, RAM), tap to expand to disk, Hermes, Tailscale
 * and uptime. [address] is the PC line (kept here, shown when expanded); [asOf] is set when the values are old (the
 * link is not online): they are dimmed and say "as of HH:MM". TalkBack reads the compact row as one sentence.
 */
@Composable
fun PcStatusCard(
    status: PcStatus,
    address: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    asOf: String?,
    modifier: Modifier = Modifier,
) {
    val locale: Locale = LocalLocale.current.platformLocale
    val colors = MaterialTheme.colorScheme
    val sentence = compactSentence(status, locale)
    val toggleLabel = stringResource(if (expanded) R.string.pc_collapse else R.string.pc_expand)
    Surface(shape = MaterialTheme.shapes.large, color = colors.surfaceContainerLow, modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.animateContentSize()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClickLabel = toggleLabel, role = Role.Button, onClick = onToggle)
                    .heightIn(min = Sizes.minTouchTarget)
                    .padding(horizontal = Spacing.l, vertical = Spacing.s)
                    .clearAndSetSemantics { contentDescription = sentence },
            ) {
                CompactRow(status, locale, dimmed = asOf != null, modifier = Modifier.weight(1f))
                val turn by animateFloatAsState(if (expanded) 180f else 0f, label = "pc-chevron")
                Icon(
                    painter = painterResource(R.drawable.ic_expand_more),
                    contentDescription = null,
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.padding(start = Spacing.s).size(Sizes.fieldIcon).rotate(turn),
                )
            }
            AnimatedVisibility(visible = expanded) {
                Details(status, address, locale, dimmed = asOf != null)
            }
            if (asOf != null) {
                Text(
                    text = stringResource(R.string.pc_as_of, asOf),
                    style = AristotleTextStyles.tabular(MaterialTheme.typography.bodySmall),
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(start = Spacing.l, end = Spacing.l, bottom = Spacing.s),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CompactRow(status: PcStatus, locale: Locale, dimmed: Boolean, modifier: Modifier = Modifier) {
    val muted = MaterialTheme.colorScheme.onSurface
    val style = AristotleTextStyles.tabular(MaterialTheme.typography.bodyMedium)
    FlowRow(
        modifier = modifier.alpha(if (dimmed) DIMMED else 1f),
        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        status.battery?.let { battery ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painter = painterResource(R.drawable.ic_battery), contentDescription = null, tint = muted, modifier = Modifier.size(Sizes.stepIcon))
                if (battery.charging) {
                    Icon(painter = painterResource(R.drawable.ic_bolt), contentDescription = null, tint = muted, modifier = Modifier.size(Sizes.stepIcon))
                }
                Text(stringResource(R.string.pc_battery_value, battery.percent), style = style, color = muted, maxLines = 1, modifier = Modifier.padding(start = Spacing.xs))
            }
        }
        status.cpuPercent?.let { Text(stringResource(R.string.pc_cpu_value, PcStatusFormat.percent(it)), style = style, color = muted, maxLines = 1) }
        status.memory?.let {
            Text(
                stringResource(R.string.pc_ram_value, PcStatusFormat.memoryGb(it.usedBytes, locale), PcStatusFormat.memoryGb(it.totalBytes, locale)),
                style = style,
                color = muted,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun Details(status: PcStatus, address: String, locale: Locale, dimmed: Boolean) {
    val colors = MaterialTheme.colorScheme
    val style = MaterialTheme.typography.bodyMedium
    Column(
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        modifier = Modifier.fillMaxWidth().alpha(if (dimmed) DIMMED else 1f).padding(start = Spacing.l, end = Spacing.l, bottom = Spacing.m),
    ) {
        if (address.isNotBlank()) Text(address, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        status.disk?.let {
            Text(
                stringResource(R.string.pc_disk_value, PcStatusFormat.diskSize(it.freeBytes, locale), PcStatusFormat.diskSize(it.totalBytes, locale)),
                style = style,
                color = colors.onSurface,
            )
        }
        status.hermesRunning?.let { running ->
            LabelValue(
                stringResource(R.string.pc_hermes_label),
                stringResource(if (running) R.string.pc_hermes_running else R.string.pc_hermes_not_running),
                if (running) colors.onSurface else colors.error,
            )
        }
        status.tailscaleUp?.let { up ->
            LabelValue(stringResource(R.string.pc_tailscale_label), stringResource(if (up) R.string.pc_tailscale_up else R.string.pc_tailscale_down), colors.onSurface)
        }
        status.uptimeSeconds?.let { Text(uptimeText(PcStatusFormat.uptime(it)), style = style, color = colors.onSurface) }
    }
}

@Composable
private fun LabelValue(label: String, value: String, valueColor: androidx.compose.ui.graphics.Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = valueColor)
    }
}

@Composable
private fun uptimeText(uptime: Uptime): String = when (uptime.kind) {
    Uptime.Kind.DaysHours -> stringResource(R.string.pc_uptime_dh, uptime.days, uptime.hours)
    Uptime.Kind.HoursMinutes -> stringResource(R.string.pc_uptime_hm, uptime.hours, uptime.minutes)
    Uptime.Kind.Minutes -> stringResource(R.string.pc_uptime_m, uptime.minutes)
}

/** One sentence for TalkBack: "Battery 82 percent, charging. Processor 14 percent. Memory 9.1 of 15.5 gigabytes used". */
@Composable
private fun compactSentence(status: PcStatus, locale: Locale): String {
    val parts = listOfNotNull(
        status.battery?.let { stringResource(if (it.charging) R.string.pc_say_charging else R.string.pc_say_battery, it.percent) },
        status.cpuPercent?.let { stringResource(R.string.pc_say_cpu, PcStatusFormat.percent(it)) },
        status.memory?.let { stringResource(R.string.pc_say_ram, PcStatusFormat.memoryGb(it.usedBytes, locale), PcStatusFormat.memoryGb(it.totalBytes, locale)) },
    )
    return parts.joinToString(", ").replaceFirstChar { it.titlecase(locale) }
}

private const val DIMMED = 0.6f
