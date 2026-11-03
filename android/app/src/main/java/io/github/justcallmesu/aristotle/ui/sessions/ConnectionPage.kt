package io.github.justcallmesu.aristotle.ui.sessions

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import io.github.justcallmesu.aristotle.R
import io.github.justcallmesu.aristotle.ui.theme.AristotleTextStyles
import io.github.justcallmesu.aristotle.ui.theme.ButtonShape
import io.github.justcallmesu.aristotle.ui.theme.Spacing

private enum class PageAction { RetryNow, CheckAgain, PairAgain }

private class PageCopy(@StringRes val title: Int, @StringRes val body: Int, val action: PageAction)

private fun copyOf(issue: ConnectionIssue): PageCopy = when (issue) {
    is ConnectionIssue.HermesNotRunning -> PageCopy(R.string.conn_4503_title, R.string.conn_4503_body, PageAction.RetryNow)
    is ConnectionIssue.HermesUpdated -> PageCopy(R.string.conn_4505_title, R.string.conn_4505_body, PageAction.CheckAgain)
    is ConnectionIssue.CertificateChanged -> PageCopy(R.string.conn_cert_title, R.string.conn_cert_body, PageAction.PairAgain)
    is ConnectionIssue.Generic -> PageCopy(R.string.conn_generic_title, R.string.conn_generic_body, PageAction.RetryNow)
}

/** design.md §5 connection page: left-aligned headline, one paragraph, mono details, one button. */
@Composable
fun ConnectionPage(issue: ConnectionIssue, address: String, onRetry: () -> Unit, onPairAgain: () -> Unit) {
    val copy = copyOf(issue)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = Spacing.xxl, end = Spacing.xxl, top = Spacing.xxxl, bottom = Spacing.xxl),
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        Text(
            text = stringResource(copy.title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = stringResource(copy.body, address),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        DetailsBlock(issue.details)
        PageButton(copy.action, onRetry, onPairAgain)
    }
}

@Composable
private fun DetailsBlock(details: String) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(top = Spacing.s),
    ) {
        Column(modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.m)) {
            Text(
                text = stringResource(R.string.conn_details),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(text = details, style = AristotleTextStyles.details, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PageButton(action: PageAction, onRetry: () -> Unit, onPairAgain: () -> Unit) {
    when (action) {
        PageAction.RetryNow -> Button(onClick = onRetry, shape = ButtonShape) { Text(stringResource(R.string.conn_retry_now)) }
        PageAction.CheckAgain -> FilledTonalButton(onClick = onRetry, shape = ButtonShape) {
            Text(stringResource(R.string.conn_check_again))
        }
        PageAction.PairAgain -> Button(onClick = onPairAgain, shape = ButtonShape) {
            Text(stringResource(R.string.conn_pair_again))
        }
    }
}
