package io.github.justcallmesu.aristotle.ui.stocks

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.justcallmesu.aristotle.R
import io.github.justcallmesu.aristotle.data.stocks.StockChanges
import io.github.justcallmesu.aristotle.data.stocks.StockTimes
import io.github.justcallmesu.aristotle.ui.theme.AristotleTextStyles
import io.github.justcallmesu.aristotle.ui.theme.Spacing

/**
 * A stock's page, placeholder until TS3 draws the chart (route `stock/{code}`): the code and name, the last price with
 * its change and the time it is from. Says honestly that the chart comes later.
 */
@Composable
fun StockDetailScreen(code: String, viewModel: StocksViewModel, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(code) { viewModel.showStock(code) }
    val quote = state.quoteOf(code)
    val locale = currentLocale()
    val muted = !state.reachable || quote?.stale == true
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            StocksTopBar(code, state.nameOf(code).ifEmpty { null }, onBack, titleStyle = AristotleTextStyles.stockCode)
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.listGutter, vertical = Spacing.s)) {
                Text(
                    text = priceText(quote?.price),
                    style = AristotleTextStyles.stockValueLarge,
                    color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
                quote?.let { ChangeText(StockChanges.quote(it), muted = muted, style = MaterialTheme.typography.titleMedium.let(AristotleTextStyles::tabular)) }
                val asOf = state.quotes?.let { StockTimes.dayClock(quote?.marketTime ?: it.asOf, StockTimes.today(System.currentTimeMillis()), locale) }
                asOf?.let {
                    Text(stringResource(R.string.stocks_prices_as_of, it), style = AristotleTextStyles.tabular(MaterialTheme.typography.bodySmall), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    text = stringResource(R.string.stocks_detail_soon),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.xxl),
                )
            }
        }
    }
}
