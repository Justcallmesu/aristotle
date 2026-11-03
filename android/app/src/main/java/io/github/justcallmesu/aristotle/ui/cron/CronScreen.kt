package io.github.justcallmesu.aristotle.ui.cron

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.justcallmesu.aristotle.R
import io.github.justcallmesu.aristotle.data.cron.CronJob
import io.github.justcallmesu.aristotle.data.cron.CronSchedule
import io.github.justcallmesu.aristotle.ui.components.SubPageTopBar
import io.github.justcallmesu.aristotle.ui.theme.AristotleTextStyles
import io.github.justcallmesu.aristotle.ui.theme.ButtonShape
import io.github.justcallmesu.aristotle.ui.theme.Sizes
import io.github.justcallmesu.aristotle.ui.theme.Spacing
import java.time.ZonedDateTime
import java.util.Locale

/**
 * "Scheduled" (T18): the selected profile's Hermes cron jobs, a job's page, the create form and the delete
 * confirmation. [profileLabel] names the profile in the page. Back closes the form, then the job's page, then [onBack].
 */
@Composable
fun CronScreen(viewModel: CronViewModel, profileLabel: String?, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel) {
        viewModel.onResume()
        onPauseOrDispose { viewModel.onPause() }
    }
    BackHandler(enabled = state.form != null || state.open != null) {
        if (state.form != null) viewModel.closeForm() else viewModel.close()
    }
    val snackbar = remember { SnackbarHostState() }
    val message = state.message?.let { cronMessageText(it) }
    LaunchedEffect(state.message) {
        if (message != null) {
            snackbar.showSnackbar(message)
            viewModel.messageShown()
        }
    }
    val locale = LocalConfiguration.current.locales[0]
    val bottom = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Box(modifier = Modifier.fillMaxSize()) {
            val form = state.form
            val job = state.openJob
            when {
                form != null -> CronFormPage(form, state.online, CronArm.CREATE in state.armed, locale, remember(viewModel) { ViewModelFormActions(viewModel) })
                job != null -> JobPage(job, state, locale, viewModel)
                else -> ListPage(state, profileLabel, locale, bottom.asPaddingValues(), viewModel, onBack)
            }
            if (form == null && job == null && state.online && state.jobs != null && state.problem == null) {
                ExtendedFloatingActionButton(
                    onClick = viewModel::newJob,
                    icon = { Icon(painter = painterResource(R.drawable.ic_add), contentDescription = null) },
                    text = { Text(stringResource(R.string.cron_new)) },
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.align(Alignment.BottomEnd).windowInsetsPadding(bottom).padding(Spacing.l),
                )
            }
            SnackbarHost(hostState = snackbar, modifier = Modifier.align(Alignment.BottomCenter).windowInsetsPadding(bottom))
        }
    }
    state.confirmDelete?.let { id -> state.job(id)?.let { DeleteDialog(it, CronArm.confirmKey(id) in state.armed && state.online, viewModel) } }
}

private class ViewModelFormActions(private val vm: CronViewModel) : CronFormActions {
    override fun edit(change: (CronForm) -> CronForm) = vm.editForm(change)
    override fun create() = vm.create()
    override fun close() = vm.closeForm()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ListPage(state: CronUiState, profileLabel: String?, locale: Locale, insets: PaddingValues, vm: CronViewModel, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
        SubPageTopBar(stringResource(R.string.cron_title), onBack)
        QuietLine(
            listOfNotNull(profileLabel?.let { stringResource(R.string.cron_profile, it) }, stringResource(R.string.cron_tz_note)).joinToString(" · "),
            Modifier.padding(start = Spacing.listGutter, end = Spacing.listGutter, bottom = Spacing.s),
        )
        if (!state.online) Banner(stringResource(R.string.cron_offline))
        state.warning?.let { Banner(it) }
        val jobs = state.jobs
        when {
            state.problem != null && state.online -> ProblemBody(state.problem, vm::retry)
            jobs == null -> QuietLine(stringResource(R.string.cron_loading), Modifier.padding(horizontal = Spacing.listGutter, vertical = Spacing.xxl))
            else -> PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = vm::refresh, modifier = Modifier.fillMaxSize()) {
                if (jobs.isEmpty()) EmptyBody() else JobList(jobs, state, locale, insets, vm)
            }
        }
    }
}

@Composable
private fun JobList(jobs: List<CronJob>, state: CronUiState, locale: Locale, insets: PaddingValues, vm: CronViewModel) {
    val now = ZonedDateTime.now()
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = insets.calculateBottomPadding() + FAB_CLEARANCE)) {
        items(jobs, key = { it.id }) { job -> JobRow(job, state, locale, now, onOpen = { vm.open(job.id) }, onToggle = { vm.toggle(job.id) }) }
    }
}

/** A job: name; schedule in words and the next run (or why none); the last result. The switch pauses or resumes. */
@Composable
private fun JobRow(job: CronJob, state: CronUiState, locale: Locale, now: ZonedDateTime, onOpen: () -> Unit, onToggle: () -> Unit) {
    val active = stringResource(R.string.cron_active)
    ListItem(
        modifier = Modifier.clickable(onClick = onOpen, role = Role.Button).heightIn(min = Sizes.sessionRowHeight),
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        headlineContent = { Text(job.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                Text(
                    text = listOfNotNull(scheduleText(CronSchedule.describe(job.schedule), locale), runLine(job, now, locale)).joinToString(" · "),
                    style = AristotleTextStyles.tabular(MaterialTheme.typography.bodyMedium),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                lastResultText(job.lastStatus)?.let { ResultText(it, lastResultFailed(job.lastStatus)) }
            }
        },
        trailingContent = {
            Switch(
                checked = job.active,
                onCheckedChange = { onToggle() },
                enabled = state.canToggle(job),
                modifier = Modifier.semantics { contentDescription = active },
            )
        },
    )
}

/** "Next Mon 4 Nov 09:00", "Overdue since …", "Paused" or "Finished"; null when Hermes has no next run. */
@Composable
private fun runLine(job: CronJob, now: ZonedDateTime, locale: Locale): String? {
    if (job.state == "completed") return stringResource(R.string.cron_completed)
    if (!job.active) return stringResource(R.string.cron_paused)
    val next = CronSchedule.at(job.nextRunAt, now.zone) ?: return null
    val text = CronTimes.at(next, now, locale)
    return stringResource(if (CronSchedule.overdue(job, now.toInstant())) R.string.cron_overdue else R.string.cron_next, text)
}

@Composable
private fun ResultText(text: String, failed: Boolean) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** One job's page: the switch, Hermes' own reason when it is not running as expected, the facts, the prompt, Delete. */
@Composable
private fun JobPage(job: CronJob, state: CronUiState, locale: Locale, vm: CronViewModel) {
    val now = ZonedDateTime.now()
    val busy = job.id in state.busy
    Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        SubPageTopBar(job.name, vm::close)
        if (!state.online) Banner(stringResource(R.string.cron_offline))
        Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(bottom = Spacing.xxl)) {
            ListItem(
                modifier = Modifier
                    .toggleable(value = job.active, enabled = state.canToggle(job), role = Role.Switch, onValueChange = { vm.toggle(job.id) })
                    .heightIn(min = Sizes.listRowHeight),
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                headlineContent = { Text(stringResource(R.string.cron_active), style = MaterialTheme.typography.bodyLarge) },
                supportingContent = {
                    val status = when {
                        busy -> stringResource(R.string.cron_sending)
                        job.state == "completed" -> stringResource(R.string.cron_completed)
                        job.active -> stringResource(R.string.cron_status_active)
                        else -> stringResource(R.string.cron_paused)
                    }
                    Text(status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                },
                trailingContent = { Switch(checked = job.active, onCheckedChange = null, enabled = state.canToggle(job)) },
            )
            job.issue?.let { Banner(it) }
            Facts(job, now, locale)
            PromptBlock(job)
            QuietLine(stringResource(R.string.cron_tz_note), Modifier.padding(horizontal = Spacing.listGutter, vertical = Spacing.s))
            QuietLine(stringResource(R.string.cron_pc_only), Modifier.padding(horizontal = Spacing.listGutter, vertical = Spacing.s))
            OutlinedButton(
                onClick = { vm.askDelete(job.id) },
                enabled = state.online && !busy && CronArm.deleteKey(job.id) in state.armed,
                shape = ButtonShape,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.padding(horizontal = Spacing.listGutter, vertical = Spacing.l).heightIn(min = Sizes.minTouchTarget),
            ) { Text(stringResource(R.string.cron_delete)) }
        }
    }
}

/** What `cron.manage list` says about the job, a row per fact it sent (the Desktop's `routineDetailRows`, `cron.tsx:370-407`). */
@Composable
private fun Facts(job: CronJob, now: ZonedDateTime, locale: Locale) {
    val words = scheduleText(CronSchedule.describe(job.schedule), locale)
    Column(modifier = Modifier.padding(horizontal = Spacing.listGutter, vertical = Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
        Fact(stringResource(R.string.cron_fact_schedule), words)
        if (job.schedule.isNotBlank() && job.schedule != words) Fact(stringResource(R.string.cron_fact_raw), job.schedule, mono = true)
        job.repeat?.let { Fact(stringResource(R.string.cron_fact_repeat), it) }
        if (job.active) {
            CronSchedule.at(job.nextRunAt, now.zone)?.let {
                val label = if (CronSchedule.overdue(job, now.toInstant())) R.string.cron_fact_overdue else R.string.cron_fact_next
                Fact(stringResource(label), CronTimes.at(it, now, locale))
            }
        }
        Fact(stringResource(R.string.cron_fact_last_run), CronSchedule.at(job.lastRunAt, now.zone)?.let { CronTimes.at(it, now, locale) } ?: stringResource(R.string.cron_never_run))
        lastResultText(job.lastStatus)?.let { Fact(stringResource(R.string.cron_fact_last_result), it, failed = lastResultFailed(job.lastStatus)) }
        job.lastError?.takeIf { it.isNotBlank() && lastResultFailed(job.lastStatus) }?.let { Fact(stringResource(R.string.cron_fact_error), it) }
        job.deliver?.let { Fact(stringResource(R.string.cron_fact_deliver), it, mono = true) }
        job.model?.let { Fact(stringResource(R.string.cron_fact_model), it, mono = true) }
        job.workdir?.let { Fact(stringResource(R.string.cron_fact_workdir), it, mono = true) }
        if (job.skills.isNotEmpty()) Fact(stringResource(R.string.cron_fact_skills), job.skills.joinToString(", "), mono = true)
        if (job.continuity) Fact(stringResource(R.string.cron_fact_continuity), stringResource(R.string.cron_continuity_on))
    }
}

@Composable
private fun Fact(label: String, value: String, mono: Boolean = false, failed: Boolean = false) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = value,
            style = if (mono) AristotleTextStyles.codeInline else AristotleTextStyles.tabular(MaterialTheme.typography.bodyLarge),
            color = if (failed) MaterialTheme.colorScheme.error else Color.Unspecified,
        )
    }
}

/** Hermes sends the list only the prompt's first 100 characters; the page says so when it was cut. */
@Composable
private fun PromptBlock(job: CronJob) {
    if (job.promptPreview.isBlank()) return
    Column(modifier = Modifier.padding(horizontal = Spacing.listGutter, vertical = Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(
            stringResource(R.string.cron_fact_prompt),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { heading() },
        )
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.fillMaxWidth()) {
            Text(job.promptPreview, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(Spacing.m))
        }
        if (job.promptCut) QuietLine(stringResource(R.string.cron_prompt_cut))
    }
}

/** design.md §5 confirmation: Delete (outlined, `error`) behind the arm, Cancel beside it. */
@Composable
private fun DeleteDialog(job: CronJob, armed: Boolean, vm: CronViewModel) {
    AlertDialog(
        onDismissRequest = vm::cancelDelete,
        title = { Text(stringResource(R.string.cron_delete_title)) },
        text = { Text(stringResource(R.string.cron_delete_body, job.name)) },
        confirmButton = {
            OutlinedButton(
                onClick = vm::confirmDelete,
                enabled = armed,
                shape = ButtonShape,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text(stringResource(R.string.cron_delete_confirm)) }
        },
        dismissButton = { TextButton(onClick = vm::cancelDelete, shape = ButtonShape) { Text(stringResource(R.string.cron_cancel)) } },
        shape = MaterialTheme.shapes.extraLarge,
    )
}

/** design.md §5 banner: `surfaceContainerHighest`, `medium`, `onSurface` text. */
@Composable
private fun Banner(text: String) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.listGutter, vertical = Spacing.s),
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(Spacing.m))
    }
}

@Composable
private fun ProblemBody(problem: String, onRetry: () -> Unit) {
    Column(modifier = Modifier.padding(horizontal = Spacing.listGutter, vertical = Spacing.xxl), verticalArrangement = Arrangement.spacedBy(Spacing.l)) {
        Text(stringResource(R.string.cron_failed), style = MaterialTheme.typography.titleSmall)
        Text(problem, style = AristotleTextStyles.details, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = onRetry, shape = ButtonShape) { Text(stringResource(R.string.cron_retry)) }
    }
}

@Composable
private fun EmptyBody() {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.xxl, vertical = Spacing.xxxl)) {
        Text(stringResource(R.string.cron_empty_title), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(Spacing.xs))
        Text(stringResource(R.string.cron_empty_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun QuietLine(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

private val FAB_CLEARANCE = Sizes.minTouchTarget + Spacing.xxl + Spacing.l
