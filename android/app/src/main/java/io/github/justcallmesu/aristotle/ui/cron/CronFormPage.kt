package io.github.justcallmesu.aristotle.ui.cron

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import io.github.justcallmesu.aristotle.R
import io.github.justcallmesu.aristotle.data.cron.CronSchedule
import io.github.justcallmesu.aristotle.data.cron.Frequency
import io.github.justcallmesu.aristotle.data.cron.SchedulePlan
import io.github.justcallmesu.aristotle.data.cron.TimeUnit
import io.github.justcallmesu.aristotle.ui.components.SubPageTopBar
import io.github.justcallmesu.aristotle.ui.theme.AristotleTextStyles
import io.github.justcallmesu.aristotle.ui.theme.ButtonShape
import io.github.justcallmesu.aristotle.ui.theme.Sizes
import io.github.justcallmesu.aristotle.ui.theme.Spacing
import java.time.DayOfWeek
import java.util.Locale

/** What the create form can ask of its ViewModel. */
interface CronFormActions {
    fun edit(change: (CronForm) -> CronForm)
    fun create()
    fun close()
}

/**
 * New job, the fields of the Desktop's create dialog (`apps/desktop/src/plugins/hermes-bots/cron.tsx:1057-1133`): name,
 * prompt, when (its schedule picker, `:766-929`), stop after N runs, continuity. The schedule string it sends is shown;
 * Hermes checks it and a refusal shows Hermes' own text. [armed]: Create has been on screen [CronArm.ARM_MS].
 */
@Composable
fun CronFormPage(form: CronForm, online: Boolean, armed: Boolean, locale: Locale, actions: CronFormActions) {
    val editable = !form.sending
    Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        SubPageTopBar(stringResource(R.string.cron_new_title), actions::close)
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(horizontal = Spacing.formGutter).padding(bottom = Spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(Spacing.l),
        ) {
            OutlinedTextField(
                value = form.name,
                onValueChange = { text -> actions.edit { it.copy(name = text) } },
                enabled = editable,
                singleLine = true,
                label = { Text(stringResource(R.string.cron_form_name)) },
                shape = MaterialTheme.shapes.extraSmall,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = form.prompt,
                onValueChange = { text -> actions.edit { it.copy(prompt = text) } },
                enabled = editable,
                minLines = 3,
                label = { Text(stringResource(R.string.cron_form_prompt)) },
                supportingText = { Text(stringResource(R.string.cron_form_prompt_help)) },
                shape = MaterialTheme.shapes.extraSmall,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
            SchedulePicker(form.plan, editable, locale) { change -> actions.edit { it.copy(plan = change(it.plan)) } }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Sizes.listRowHeight)
                    .toggleable(value = form.continuity, enabled = editable, role = Role.Checkbox, onValueChange = { on -> actions.edit { it.copy(continuity = on) } }),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.m),
            ) {
                Checkbox(checked = form.continuity, onCheckedChange = null, enabled = editable)
                Text(stringResource(R.string.cron_form_continuity), style = MaterialTheme.typography.bodyLarge)
            }
            Text(
                text = stringResource(R.string.cron_form_sends, form.schedule.ifBlank { "—" }),
                style = AristotleTextStyles.codeInline,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            form.error?.let { RefusalBlock(it) }
            Button(
                onClick = actions::create,
                enabled = online && armed && form.complete && !form.sending,
                shape = ButtonShape,
                modifier = Modifier.fillMaxWidth().heightIn(min = Sizes.minTouchTarget),
            ) { Text(stringResource(if (form.sending) R.string.cron_form_creating else R.string.cron_form_create)) }
        }
    }
}

/** Frequency first, then only the detail it needs (the Desktop's picker, `cron.tsx:766-929`). */
@Composable
private fun SchedulePicker(plan: SchedulePlan, enabled: Boolean, locale: Locale, edit: ((SchedulePlan) -> SchedulePlan) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Label(stringResource(R.string.cron_form_when))
        Chips(Frequency.entries, plan.frequency, enabled, { frequencyLabel(it) }) { f -> edit { it.copy(frequency = f) } }
        when (plan.frequency) {
            Frequency.Once -> CountAndUnit(R.string.cron_form_after, plan.onceCount, plan.onceUnit, enabled, { n -> edit { it.copy(onceCount = n) } }) { u -> edit { it.copy(onceUnit = u) } }
            Frequency.Interval -> CountAndUnit(R.string.cron_form_every, plan.intervalCount, plan.intervalUnit, enabled, { n -> edit { it.copy(intervalCount = n) } }) { u -> edit { it.copy(intervalUnit = u) } }
            Frequency.Weekly -> {
                Label(stringResource(R.string.cron_form_day))
                Chips(WEEK, plan.weekday, enabled, { CronTimes.weekday(it, locale) }) { d -> edit { it.copy(weekday = d) } }
            }
            Frequency.Monthly -> NumberField(R.string.cron_form_month_day, plan.monthDay, 2, enabled) { n -> edit { it.copy(monthDay = CronSchedule.monthDay(n, it.monthDay)) } }
            Frequency.Advanced -> OutlinedTextField(
                value = plan.raw,
                onValueChange = { text -> edit { it.copy(raw = text) } },
                enabled = enabled,
                singleLine = true,
                textStyle = AristotleTextStyles.codeInline,
                label = { Text(stringResource(R.string.cron_form_advanced)) },
                supportingText = { Text(stringResource(R.string.cron_form_advanced_help)) },
                shape = MaterialTheme.shapes.extraSmall,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                modifier = Modifier.fillMaxWidth(),
            )
            Frequency.Hourly, Frequency.Daily, Frequency.Weekdays -> Unit
        }
        if (plan.needsTime) TimeChoice(plan, enabled, locale) { h, m -> edit { it.copy(hour = h, minute = m) } }
        if (plan.repeats) {
            NumberField(R.string.cron_form_repeat, plan.repeat, 4, enabled, R.string.cron_form_repeat_help) { n -> edit { it.copy(repeat = n) } }
        }
    }
}

@Composable
private fun CountAndUnit(label: Int, count: String, unit: TimeUnit, enabled: Boolean, onCount: (String) -> Unit, onUnit: (TimeUnit) -> Unit) {
    NumberField(label, count, 4, enabled, onChange = onCount)
    Chips(TimeUnit.entries, unit, enabled, { unitLabel(it) }, onUnit)
}

@Composable
private fun NumberField(label: Int, value: String, digits: Int, enabled: Boolean, help: Int? = null, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { text -> onChange(text.filter(Char::isDigit).take(digits)) },
        enabled = enabled,
        singleLine = true,
        label = { Text(stringResource(label)) },
        supportingText = help?.let { { Text(stringResource(it)) } },
        shape = MaterialTheme.shapes.extraSmall,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** The half-hour times the Desktop offers (`cron.tsx:651-665`), Hermes' timezone. */
@Composable
private fun TimeChoice(plan: SchedulePlan, enabled: Boolean, locale: Locale, onPick: (Int, Int) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Label(stringResource(R.string.cron_form_time))
        Box {
            OutlinedButton(onClick = { open = true }, enabled = enabled, shape = ButtonShape, modifier = Modifier.heightIn(min = Sizes.minTouchTarget)) {
                Text(CronTimes.clock(plan.hour, plan.minute, locale), style = AristotleTextStyles.tabular(MaterialTheme.typography.labelLarge))
                Icon(painter = painterResource(R.drawable.ic_arrow_drop_down), contentDescription = null)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                CronSchedule.halfHours.forEach { (h, m) ->
                    DropdownMenuItem(
                        text = { Text(CronTimes.clock(h, m, locale), style = AristotleTextStyles.tabular(MaterialTheme.typography.bodyLarge)) },
                        onClick = {
                            open = false
                            onPick(h, m)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun <T> Chips(options: List<T>, selected: T, enabled: Boolean, label: @Composable (T) -> String, onPick: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s), modifier = Modifier.selectableGroup()) {
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { if (option != selected) onPick(option) },
                enabled = enabled,
                label = { Text(label(option)) },
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.heightIn(min = Sizes.minTouchTarget),
            )
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.semantics { heading() })
}

/** Hermes said no: its text as it wrote it (for a schedule, the forms it accepts, `cron/jobs.py:853-860`). */
@Composable
private fun RefusalBlock(text: String) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(Spacing.m), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(stringResource(R.string.cron_form_refused), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onErrorContainer)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
}

@Composable
private fun frequencyLabel(frequency: Frequency): String = stringResource(
    when (frequency) {
        Frequency.Once -> R.string.cron_freq_once
        Frequency.Hourly -> R.string.cron_freq_hourly
        Frequency.Daily -> R.string.cron_freq_daily
        Frequency.Weekdays -> R.string.cron_freq_weekdays
        Frequency.Weekly -> R.string.cron_freq_weekly
        Frequency.Monthly -> R.string.cron_freq_monthly
        Frequency.Interval -> R.string.cron_freq_interval
        Frequency.Advanced -> R.string.cron_freq_advanced
    },
)

@Composable
private fun unitLabel(unit: TimeUnit): String = stringResource(
    when (unit) {
        TimeUnit.Minutes -> R.string.cron_unit_minutes
        TimeUnit.Hours -> R.string.cron_unit_hours
        TimeUnit.Days -> R.string.cron_unit_days
    },
)

/** Monday first, as the Desktop lists them (`cron.tsx:647-649`). */
private val WEEK = DayOfWeek.entries
