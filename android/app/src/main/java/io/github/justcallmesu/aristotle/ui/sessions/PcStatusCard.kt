package io.github.justcallmesu.aristotle.ui.sessions

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.github.justcallmesu.aristotle.R
import io.github.justcallmesu.aristotle.data.Network
import io.github.justcallmesu.aristotle.data.PcMemory
import io.github.justcallmesu.aristotle.data.PcStatus
import io.github.justcallmesu.aristotle.data.PcStatusFormat
import io.github.justcallmesu.aristotle.data.Uptime
import io.github.justcallmesu.aristotle.ui.theme.AristotleTextStyles
import io.github.justcallmesu.aristotle.ui.theme.Sizes
import java.util.Locale

/**
 * The PC status card on Home, design option A "Meters" (Q79, design-notes §5.7): battery, CPU and RAM as label + value +
 * thin bar; tap to open 2-column tiles (disk, uptime, Hermes, Tailscale, address). [address] is the PC line (shown when
 * opened); [asOf] is set when the values are old: they are muted and the card says "As of HH:MM". TalkBack reads the
 * head row as one sentence. A null field hides its meter or tile.
 */
@Composable
fun PcStatusCard(
    status: PcStatus,
    address: String,
    network: Network?,
    expanded: Boolean,
    onToggle: () -> Unit,
    asOf: String?,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val locale: Locale = LocalLocale.current.platformLocale
    val stale = asOf != null
    val sentence = cardSentence(status, locale, asOf)
    val toggleLabel = stringResource(if (expanded) R.string.pc_collapse else R.string.pc_expand)
    Surface(shape = MaterialTheme.shapes.large, color = colors.surfaceContainerLow, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.animateContentSize()) {
            Row(
                verticalAlignment = Alignment.Top,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClickLabel = toggleLabel, role = Role.Button, onClick = onToggle)
                    .heightIn(min = Sizes.minTouchTarget)
                    .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 14.dp)
                    .clearAndSetSemantics { contentDescription = sentence },
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (asOf != null) AsOfLine(asOf)
                    if (status.hermesRunning == false) HermesDownLine()
                    MeterRow(status, locale, stale)
                }
                val turn by animateFloatAsState(if (expanded) 180f else 0f, label = "pc-chevron")
                Icon(
                    painterResource(R.drawable.ic_expand_more),
                    contentDescription = null,
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp).size(Sizes.fieldIcon).rotate(turn),
                )
            }
            AnimatedVisibility(visible = expanded) { DetailTiles(status, address, network, locale, stale) }
        }
    }
}

@Composable
private fun MeterRow(status: PcStatus, locale: Locale, stale: Boolean) {
    val colors = MaterialTheme.colorScheme
    val battery = PcStatusFormat.batteryFraction(status.battery)
    val cpu = PcStatusFormat.cpuFraction(status.cpuPercent)
    val ram = PcStatusFormat.ramFraction(status.memory)
    if (battery == null && cpu == null && ram == null) {
        // Nothing to meter: the uptime as text keeps the row from being empty.
        status.uptimeSeconds?.let {
            Text(
                uptimeText(PcStatusFormat.uptime(it)),
                style = AristotleTextStyles.tabular(MaterialTheme.typography.titleMedium),
                color = if (stale) colors.onSurfaceVariant else colors.onSurface,
            )
        }
        return
    }
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        if (battery != null && status.battery != null) {
            Meter(
                stringResource(R.string.pc_battery_label),
                AnnotatedString(stringResource(R.string.pc_battery_value, status.battery.percent)),
                battery,
                stale,
                charging = status.battery.charging,
                modifier = Modifier.weight(1f),
            )
        }
        if (cpu != null && status.cpuPercent != null) {
            Meter(
                stringResource(R.string.pc_cpu_label),
                AnnotatedString(stringResource(R.string.pc_battery_value, PcStatusFormat.percent(status.cpuPercent))),
                cpu,
                stale,
                modifier = Modifier.weight(1f),
            )
        }
        if (ram != null && status.memory != null) {
            Meter(stringResource(R.string.pc_ram_label), ramValue(status.memory, locale), ram, stale, modifier = Modifier.weight(1.55f))
        }
    }
}

@Composable
private fun ramValue(memory: PcMemory, locale: Locale): AnnotatedString {
    val gb = stringResource(R.string.pc_ram_gb, PcStatusFormat.memoryGb(memory.usedBytes, locale), PcStatusFormat.memoryGb(memory.totalBytes, locale))
    val unit = MaterialTheme.typography.bodySmall.toSpanStyle().copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    return buildAnnotatedString {
        append(gb)
        withStyle(unit) { append(" GB") }
    }
}

@Composable
private fun AsOfLine(asOf: String) {
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(painterResource(R.drawable.ic_history), null, tint = colors.onSurfaceVariant, modifier = Modifier.size(14.dp))
        Text(
            stringResource(R.string.pc_as_of_title, asOf),
            style = AristotleTextStyles.tabular(MaterialTheme.typography.labelMedium),
            color = colors.onSurfaceVariant,
        )
    }
}

@Composable
private fun HermesDownLine() {
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(painterResource(R.drawable.ic_error), null, tint = colors.error, modifier = Modifier.size(18.dp))
        Text(stringResource(R.string.pc_hermes_down_line), style = MaterialTheme.typography.titleSmall, color = colors.error)
    }
}

@Composable
private fun Meter(
    label: String,
    value: AnnotatedString,
    fraction: Float,
    stale: Boolean,
    charging: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, maxLines = 1)
            if (charging) Icon(painterResource(R.drawable.ic_bolt), null, tint = colors.onSurfaceVariant, modifier = Modifier.size(14.dp))
        }
        Text(
            value,
            style = AristotleTextStyles.tabular(MaterialTheme.typography.titleMedium),
            color = if (stale) colors.onSurfaceVariant else colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Bar(fraction, stale, Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun Bar(fraction: Float, stale: Boolean, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    LinearProgressIndicator(
        progress = { fraction.coerceIn(0f, 1f) },
        modifier = modifier.fillMaxWidth().height(4.dp),
        color = if (stale) colors.outline else colors.primary,
        trackColor = colors.surfaceContainerHighest,
        strokeCap = StrokeCap.Round,
        gapSize = 4.dp,
        drawStopIndicator = {},
    )
}

private data class PcTile(
    @DrawableRes val icon: Int,
    val label: String,
    val value: String,
    val sub: String? = null,
    val fraction: Float? = null,
    val error: Boolean = false,
)

@Composable
private fun DetailTiles(status: PcStatus, address: String, network: Network?, locale: Locale, stale: Boolean) {
    val tiles = buildList {
        status.disk?.let {
            add(
                PcTile(
                    R.drawable.ic_hard_drive,
                    stringResource(R.string.pc_disk_label),
                    stringResource(R.string.pc_disk_free, PcStatusFormat.diskSize(it.freeBytes, locale)),
                    sub = stringResource(R.string.pc_disk_of, PcStatusFormat.diskSize(it.totalBytes, locale)),
                    fraction = PcStatusFormat.diskUsedFraction(it),
                ),
            )
        }
        status.uptimeSeconds?.let {
            add(
                PcTile(
                    R.drawable.ic_timer,
                    stringResource(R.string.pc_uptime_label),
                    uptimeValue(PcStatusFormat.uptime(it)),
                    sub = stringResource(R.string.pc_uptime_since_boot),
                ),
            )
        }
        status.hermesRunning?.let {
            add(
                PcTile(
                    if (it) R.drawable.ic_check_circle else R.drawable.ic_error,
                    stringResource(R.string.pc_hermes_label),
                    stringResource(if (it) R.string.pc_hermes_running else R.string.pc_hermes_not_running),
                    error = !it,
                ),
            )
        }
        status.tailscaleUp?.let {
            add(
                PcTile(
                    R.drawable.ic_vpn_lock,
                    stringResource(R.string.pc_tailscale_label),
                    stringResource(if (it) R.string.pc_tailscale_up else R.string.pc_tailscale_down),
                ),
            )
        }
    }
    if (tiles.isEmpty() && address.isBlank()) return
    Column(Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tiles.chunked(2).forEach { pair ->
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { Tile(it, stale, Modifier.weight(1f).fillMaxHeight()) }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        if (address.isNotBlank()) {
            Tile(
                PcTile(
                    if (network == Network.Tailscale) R.drawable.ic_vpn_lock else R.drawable.ic_wifi,
                    stringResource(R.string.pc_address_label),
                    address,
                ),
                stale,
                Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun Tile(t: PcTile, stale: Boolean, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = MaterialTheme.shapes.small, color = colors.surfaceContainer, modifier = modifier.semantics(mergeDescendants = true) {}) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(
                painterResource(t.icon),
                null,
                tint = if (t.error) colors.error else colors.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp).size(20.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(t.label, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                Text(
                    t.value,
                    style = AristotleTextStyles.tabular(MaterialTheme.typography.titleSmall),
                    color = when {
                        t.error -> colors.error
                        stale -> colors.onSurfaceVariant
                        else -> colors.onSurface
                    },
                )
                t.sub?.let { Text(it, style = AristotleTextStyles.tabular(MaterialTheme.typography.bodySmall), color = colors.onSurfaceVariant) }
                t.fraction?.let { Bar(it, stale, Modifier.padding(top = 6.dp)) }
            }
        }
    }
}

@Composable
private fun uptimeValue(uptime: Uptime): String = when (uptime.kind) {
    Uptime.Kind.DaysHours -> stringResource(R.string.pc_uptime_value_dh, uptime.days, uptime.hours)
    Uptime.Kind.HoursMinutes -> stringResource(R.string.pc_uptime_value_hm, uptime.hours, uptime.minutes)
    Uptime.Kind.Minutes -> stringResource(R.string.pc_uptime_value_m, uptime.minutes)
}

@Composable
private fun uptimeText(uptime: Uptime): String = when (uptime.kind) {
    Uptime.Kind.DaysHours -> stringResource(R.string.pc_uptime_dh, uptime.days, uptime.hours)
    Uptime.Kind.HoursMinutes -> stringResource(R.string.pc_uptime_hm, uptime.hours, uptime.minutes)
    Uptime.Kind.Minutes -> stringResource(R.string.pc_uptime_m, uptime.minutes)
}

/** One sentence for TalkBack: "PC status. Battery 58 percent, processor 27 percent, memory 7.0 of 11.5 gigabytes used. Hermes not running. Values as of 17:40." */
@Composable
private fun cardSentence(status: PcStatus, locale: Locale, asOf: String?): String {
    val readings = listOfNotNull(
        status.battery?.let { stringResource(if (it.charging) R.string.pc_say_charging else R.string.pc_say_battery, it.percent) },
        status.cpuPercent?.let { stringResource(R.string.pc_say_cpu, PcStatusFormat.percent(it)) },
        status.memory?.let {
            stringResource(R.string.pc_say_ram, PcStatusFormat.memoryGb(it.usedBytes, locale), PcStatusFormat.memoryGb(it.totalBytes, locale))
        },
    ).joinToString(", ").replaceFirstChar { it.titlecase(locale) }
    val sentences = listOfNotNull(
        stringResource(R.string.pc_say_title),
        readings.takeIf { it.isNotEmpty() },
        stringResource(R.string.pc_say_hermes_down).takeIf { status.hermesRunning == false },
        asOf?.let { stringResource(R.string.pc_say_as_of, it).replaceFirstChar { c -> c.titlecase(locale) } },
    )
    return sentences.joinToString(". ", postfix = ".")
}
