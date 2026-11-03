package io.github.justcallmesu.aristotle.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.justcallmesu.aristotle.R
import io.github.justcallmesu.aristotle.background.SystemSettings
import io.github.justcallmesu.aristotle.data.AppSettings
import io.github.justcallmesu.aristotle.data.GuideStep
import io.github.justcallmesu.aristotle.ui.components.SubPageTopBar
import io.github.justcallmesu.aristotle.ui.theme.ButtonShape
import io.github.justcallmesu.aristotle.ui.theme.Sizes
import io.github.justcallmesu.aristotle.ui.theme.Spacing

/**
 * Mockup 6, "Keep Aristotle running" (spec Q27). Step 1 is read from Android; the HyperOS steps cannot be
 * read by an app, so the user ticks them. On other phones only step 1 is shown.
 */
@Composable
fun BatteryGuideScreen(settings: AppSettings, xiaomi: Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    val marks by settings.guideMarks.collectAsStateWithLifecycle()
    var optimisationOff by remember { mutableStateOf(SystemSettings.ignoresBatteryOptimisation(context)) }
    // Back from the system dialog or settings: check again.
    LifecycleResumeEffect(Unit) {
        optimisationOff = SystemSettings.ignoresBatteryOptimisation(context)
        onPauseOrDispose { }
    }
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            SubPageTopBar(stringResource(R.string.guide_title), onBack)
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(start = Spacing.xxl, end = Spacing.xxl, top = Spacing.xs, bottom = Spacing.xxl),
            ) {
                Text(
                    text = stringResource(if (xiaomi) R.string.guide_body_xiaomi else R.string.guide_body_other),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Spacing.m))
                StepRow(1, stringResource(R.string.guide_step_optimisation), stringResource(R.string.guide_step_optimisation_body), optimisationOff) {
                    if (optimisationOff) DoneLine() else OpenSettingsButton { SystemSettings.openBatteryOptimisation(context) }
                }
                if (xiaomi) {
                    HyperOsStep(2, R.string.guide_step_saver, R.string.guide_step_saver_body, GuideStep.BatterySaver, marks, settings) {
                        SystemSettings.openBatterySaver(context)
                    }
                    HyperOsStep(3, R.string.guide_step_autostart, R.string.guide_step_autostart_body, GuideStep.Autostart, marks, settings) {
                        SystemSettings.openAutostart(context)
                    }
                }
            }
        }
    }
}

@Composable
private fun HyperOsStep(
    number: Int,
    title: Int,
    body: Int,
    step: GuideStep,
    marks: Set<GuideStep>,
    settings: AppSettings,
    onOpen: () -> Unit,
) {
    val marked = step in marks
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    StepRow(number, stringResource(title), stringResource(body), done = marked) {
        OpenSettingsButton(onOpen)
        MarkedCheck(marked) { settings.markGuide(step, it) }
    }
}

@Composable
private fun StepRow(number: Int, title: String, body: String, done: Boolean, actions: @Composable () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.l)) {
        StepBadge(number, done)
        Column(modifier = Modifier.padding(start = Spacing.m).weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            Text(text = body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(Spacing.xs))
            actions()
        }
    }
}

/** The step number, or a check once done. Not verdigris: that colour is for live work only (design.md §1). */
@Composable
private fun StepBadge(number: Int, done: Boolean) {
    val label = stringResource(R.string.guide_step_number, number)
    val container = if (done) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer
    val content = if (done) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(Sizes.stepBadge)
            .background(container, MaterialTheme.shapes.small)
            .semantics { contentDescription = label },
    ) {
        if (done) {
            Icon(painter = painterResource(R.drawable.ic_check), contentDescription = null, tint = content, modifier = Modifier.size(Sizes.stepIcon))
        } else {
            Text(text = number.toString(), style = MaterialTheme.typography.labelLarge, color = content)
        }
    }
}

@Composable
private fun DoneLine() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Icon(
            painter = painterResource(R.drawable.ic_check_circle),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(Sizes.stepIcon),
        )
        Text(text = stringResource(R.string.guide_done), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun OpenSettingsButton(onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, shape = ButtonShape) {
        Icon(painter = painterResource(R.drawable.ic_open_in_new), contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
        Text(stringResource(R.string.guide_open_settings))
    }
}

@Composable
private fun MarkedCheck(checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .heightIn(min = Sizes.minTouchTarget)
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange)
            .padding(end = Spacing.s),
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Spacer(Modifier.size(Spacing.s))
        Text(text = stringResource(R.string.guide_marked), style = MaterialTheme.typography.bodyMedium)
    }
}
