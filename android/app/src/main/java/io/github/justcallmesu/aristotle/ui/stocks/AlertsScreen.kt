package io.github.justcallmesu.aristotle.ui.stocks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.justcallmesu.aristotle.R
import io.github.justcallmesu.aristotle.data.stocks.AlertFeed
import io.github.justcallmesu.aristotle.data.stocks.StockAlert
import io.github.justcallmesu.aristotle.data.stocks.StockOpen
import io.github.justcallmesu.aristotle.data.stocks.StockTimes
import io.github.justcallmesu.aristotle.ui.components.GroupedRows
import io.github.justcallmesu.aristotle.ui.sessions.QuietLine
import io.github.justcallmesu.aristotle.ui.theme.AristotleTextStyles
import io.github.justcallmesu.aristotle.ui.theme.Sizes
import io.github.justcallmesu.aristotle.ui.theme.Spacing
import java.time.LocalDate
import java.util.Locale

/**
 * The alerts list (§20.4 ring, newest first): time, title and text, data time; a silent one says it was not notified.
 * A row opens its stock (or the brief); everything listed counts as seen.
 */
@Composable
fun AlertsScreen(viewModel: StockAlertsViewModel, onBack: () -> Unit, onOpen: (StockOpen) -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel) {
        viewModel.pageVisible(true)
        onPauseOrDispose { viewModel.pageVisible(false) }
    }
    val locale = currentLocale()
    val today = StockTimes.today(System.currentTimeMillis())
    val items = state.feed.items
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            StocksTopBar(title = stringResource(R.string.stocks_alerts), subline = stringResource(R.string.stocks_alerts_subline), onBack = onBack)
            LazyColumn(modifier = Modifier.weight(1f), contentPadding = PaddingValues(bottom = Spacing.xxl)) {
                state.listProblem?.let { item(key = "problem") { QuietLine(stocksErrorText(it)) } }
                if (items.isEmpty()) {
                    item(key = "empty") { QuietLine(stringResource(if (state.reachable) R.string.stocks_alerts_empty else R.string.stocks_not_loaded)) }
                } else {
                    item(key = "rows") {
                        GroupedRows(items.size, modifier = Modifier.padding(horizontal = Spacing.listGutter)) { index ->
                            AlertRow(items[index], today, locale, onOpen)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AlertRow(alert: StockAlert, today: LocalDate, locale: Locale, onOpen: (StockOpen) -> Unit) {
    val time = StockTimes.dayClock(alert.at, today, locale)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button) { onOpen(StockOpen.of(alert)) }
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = Spacing.l, vertical = Spacing.m),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(alert.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            time?.let { Text(it, style = AristotleTextStyles.tabular(MaterialTheme.typography.bodySmall), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = Spacing.s)) }
        }
        if (alert.text.isNotBlank()) Text(alert.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val notes = listOfNotNull(
            StockTimes.dayClock(alert.dataAsOf, today, locale)?.let { stringResource(R.string.stocks_alert_data_as_of, it) },
            if (alert.silent) stringResource(R.string.stocks_alert_silent) else null,
        )
        if (notes.isNotEmpty()) {
            Text(notes.joinToString(" · "), style = AristotleTextStyles.tabular(MaterialTheme.typography.bodySmall), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Spacing.xxs))
        }
    }
}

/** Stocks home's alerts row: "3 new alerts" and the newest one; hidden while there is nothing to list. */
@Composable
internal fun AlertsHomeRow(feed: AlertFeed, locale: Locale, onOpen: () -> Unit) {
    val newest = feed.items.firstOrNull() ?: return
    val today = StockTimes.today(System.currentTimeMillis())
    GroupedRows(1, modifier = Modifier.padding(start = Spacing.listGutter, end = Spacing.listGutter, top = Spacing.s)) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onOpen).heightIn(min = Sizes.listRowHeight).padding(horizontal = Spacing.l, vertical = Spacing.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painter = painterResource(R.drawable.ic_notifications), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(modifier = Modifier.weight(1f).padding(horizontal = Spacing.m).semantics(mergeDescendants = true) {}) {
                Text(
                    if (feed.unseen > 0) pluralStringResource(R.plurals.stocks_alerts_new, feed.unseen, feed.unseen) else stringResource(R.string.stocks_alerts),
                    style = MaterialTheme.typography.bodyLarge,
                )
                val time = StockTimes.dayClock(newest.at, today, locale)
                Text(
                    listOfNotNull(time, newest.title).joinToString(" · "),
                    style = AristotleTextStyles.tabular(MaterialTheme.typography.bodySmall),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(painter = painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
