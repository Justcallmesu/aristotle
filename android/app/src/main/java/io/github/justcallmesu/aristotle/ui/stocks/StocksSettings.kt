package io.github.justcallmesu.aristotle.ui.stocks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch
import androidx.compose.ui.Alignment
import io.github.justcallmesu.aristotle.data.stocks.AutoRuns
import io.github.justcallmesu.aristotle.ui.theme.AristotleTextStyles
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.justcallmesu.aristotle.R
import io.github.justcallmesu.aristotle.data.stocks.FeeInput
import io.github.justcallmesu.aristotle.data.stocks.FieldProblem
import io.github.justcallmesu.aristotle.data.stocks.StockFees
import io.github.justcallmesu.aristotle.data.stocks.StockFormat
import io.github.justcallmesu.aristotle.ui.components.GroupedRows
import io.github.justcallmesu.aristotle.ui.theme.ButtonShape
import io.github.justcallmesu.aristotle.ui.theme.Spacing

/**
 * Settings › Stocks: the broker fees P/L counts with (Q88, `/v1/stocks/settings`, kept on the PC), the Screener's own page
 * (TSC3, [onOpenScreener]) and the broker app (Q94: Ajaib; its Open action shows only when Ajaib is installed).
 */
@Composable
fun StocksSettingsSection(viewModel: StocksViewModel, onOpenScreener: (() -> Unit)? = null, alerts: StockAlertsViewModel? = null) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel, state.reachable) { if (state.reachable) viewModel.showFees() }
    val snackbar = remember { SnackbarHostState() }
    StocksMessages(state.message, snackbar, viewModel::messageShown)
    FeesBlock(state.fees, state.reachable, state.feesSaving, state.feesProblem?.let { stocksErrorText(it) }, viewModel::saveFees)
    alerts?.let { AlertsBlock(it, snackbar) }
    onOpenScreener?.let { ScreenerBlock(it) }
    BrokerBlock()
    SnackbarHost(hostState = snackbar)
}

@Composable
private fun FeesBlock(fees: StockFees?, online: Boolean, saving: Boolean, problem: String?, onSave: (StockFees) -> Unit) {
    var buy by rememberSaveable(fees) { mutableStateOf(fees?.let { FeeInput.text(it.buyPct) }.orEmpty()) }
    var sell by rememberSaveable(fees) { mutableStateOf(fees?.let { FeeInput.text(it.sellPct) }.orEmpty()) }
    val buyParsed = FeeInput.parse(buy)
    val sellParsed = FeeInput.parse(sell)
    val changed = fees != null && (buyParsed.value != fees.buyPct || sellParsed.value != fees.sellPct)
    GroupedRows(1) {
        Column(modifier = Modifier.fillMaxWidth().padding(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Text(stringResource(R.string.stocks_fees_title), style = MaterialTheme.typography.bodyLarge)
            Text(
                text = problem ?: stringResource(if (fees == null) R.string.stocks_fees_loading else R.string.stocks_fees_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FeeField(buy, { buy = it }, stringResource(R.string.stocks_fee_buy), buyParsed.problem, enabled = fees != null && online)
            FeeField(sell, { sell = it }, stringResource(R.string.stocks_fee_sell), sellParsed.problem, enabled = fees != null && online)
            Row {
                TextButton(
                    onClick = {
                        buy = FeeInput.text(StockFees.DEFAULT_BUY)
                        sell = FeeInput.text(StockFees.DEFAULT_SELL)
                    },
                    enabled = fees != null && online,
                    shape = ButtonShape,
                ) { Text(stringResource(R.string.stocks_fees_defaults)) }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = {
                        val b = buyParsed.value
                        val s = sellParsed.value
                        if (b != null && s != null) onSave(StockFees(b, s))
                    },
                    enabled = online && changed && buyParsed.value != null && sellParsed.value != null && !saving,
                    shape = ButtonShape,
                ) { Text(stringResource(if (saving) R.string.stocks_saving else R.string.stocks_save)) }
            }
        }
    }
}

@Composable
private fun FeeField(value: String, onValue: (String) -> Unit, label: String, problem: FieldProblem?, enabled: Boolean) {
    val bad = problem != null && value.isNotEmpty()
    OutlinedTextField(
        value = value,
        onValueChange = { onValue(it.take(MAX_FEE_CHARS)) },
        label = { Text(label) },
        suffix = { Text("%") },
        supportingText = { Text(stringResource(if (bad) R.string.stocks_fee_range else R.string.stocks_fee_support, StockFormat.fee(StockFees.MAX_PCT))) },
        isError = bad,
        enabled = enabled,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * TBA2 "Alerts": the PC's `alerts` job switch (off = no alert and no automatic analysis, §20.2), today's automatic
 * analyses ("2 of 5", Q87), and the Q109 line: with Keep connected off, alerts arrive only while the app is open.
 */
@Composable
private fun AlertsBlock(viewModel: StockAlertsViewModel, snackbar: SnackbarHostState) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel) {
        viewModel.settingsVisible(true)
        onPauseOrDispose { viewModel.settingsVisible(false) }
    }
    val failed = state.saveProblem?.let { stringResource(R.string.stocks_alerts_save_failed, stocksErrorText(it)) }
    LaunchedEffect(state.saveProblem) {
        if (failed != null) {
            snackbar.showSnackbar(failed)
            viewModel.saveProblemShown()
        }
    }
    val runs = state.autoRuns
    val on = state.alertsOn
    GroupedRows(1) {
        Column(modifier = Modifier.fillMaxWidth().padding(start = Spacing.l, end = Spacing.l, top = Spacing.s, bottom = Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Row(
                modifier = Modifier.fillMaxWidth().toggleable(
                    value = on == true,
                    enabled = on != null && state.reachable && !state.saving,
                    role = Role.Switch,
                    onValueChange = viewModel::setAlertsOn,
                ).padding(vertical = Spacing.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.stocks_alerts_switch), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(checked = on == true, onCheckedChange = null, enabled = on != null && state.reachable && !state.saving)
            }
            Text(
                stringResource(R.string.stocks_alerts_switch_body, runs?.cap ?: AutoRuns.DEFAULT_CAP),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val status = when {
                state.jobsProblem != null && state.jobs == null -> stocksErrorText(state.jobsProblem!!)
                state.jobs == null && state.reachable -> stringResource(R.string.stocks_loading)
                state.jobs == null -> stringResource(R.string.stocks_not_loaded)
                else -> null
            }
            status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            runs?.let {
                Text(
                    stringResource(R.string.stocks_auto_runs_today, it.used, it.cap),
                    style = AristotleTextStyles.tabular(MaterialTheme.typography.bodyMedium),
                    modifier = Modifier.padding(top = Spacing.s),
                )
            }
            Text(stringResource(R.string.stocks_alerts_q109), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Spacing.s))
        }
    }
}

/** "Screener": setups, liquidity floor, scan interval and the stock list, on its own page. */
@Composable
private fun ScreenerBlock(onOpen: () -> Unit) {
    GroupedRows(1) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            headlineContent = { Text(stringResource(R.string.screener_settings_row), style = MaterialTheme.typography.bodyLarge) },
            supportingContent = {
                Text(stringResource(R.string.screener_settings_row_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            },
            trailingContent = { Icon(painter = painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
            modifier = Modifier.clickable(role = Role.Button, onClick = onOpen),
        )
    }
}

/** "Broker app · Ajaib": Open when installed; otherwise it says so and offers nothing (Q94). Checked again on resume. */
@Composable
private fun BrokerBlock() {
    val context = LocalContext.current
    var installed by remember { mutableStateOf(BrokerApp.launchIntent(context) != null) }
    LifecycleResumeEffect(Unit) {
        installed = BrokerApp.launchIntent(context) != null
        onPauseOrDispose { }
    }
    GroupedRows(1) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            headlineContent = { Text(stringResource(R.string.stocks_broker_app, BrokerApp.NAME), style = MaterialTheme.typography.bodyLarge) },
            supportingContent = {
                Text(
                    stringResource(if (installed) R.string.stocks_broker_installed else R.string.stocks_broker_missing, BrokerApp.NAME),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            trailingContent = if (installed) {
                { TextButton(onClick = { BrokerApp.open(context) }, shape = ButtonShape) { Text(stringResource(R.string.stocks_broker_open)) } }
            } else {
                null
            },
        )
    }
}

private const val MAX_FEE_CHARS = 8
