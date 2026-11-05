package io.github.justcallmesu.aristotle.ui.sessions

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import io.github.justcallmesu.aristotle.R
import io.github.justcallmesu.aristotle.data.LinkState
import io.github.justcallmesu.aristotle.data.OfflineCause
import io.github.justcallmesu.aristotle.ui.theme.AristotleTextStyles
import io.github.justcallmesu.aristotle.ui.theme.ButtonShape
import io.github.justcallmesu.aristotle.ui.theme.Spacing

/** The states that replace the session list (design.md §5 connection pages). */
enum class PageKind { HermesDown, HermesUpdated, CertificateChanged, ListFailed }

private enum class PageAction { RetryNow, CheckAgain, PairAgain }

private class PageCopy(@StringRes val title: Int, @StringRes val body: Int, val action: PageAction)

private fun copyOf(kind: PageKind): PageCopy = when (kind) {
    PageKind.HermesDown -> PageCopy(R.string.conn_4503_title, R.string.conn_4503_body, PageAction.RetryNow)
    PageKind.HermesUpdated -> PageCopy(R.string.conn_4505_title, R.string.conn_4505_body, PageAction.CheckAgain)
    PageKind.CertificateChanged -> PageCopy(R.string.conn_cert_title, R.string.conn_cert_body, PageAction.PairAgain)
    PageKind.ListFailed -> PageCopy(R.string.conn_list_title, R.string.conn_list_body, PageAction.RetryNow)
}

/** design.md §5 connection page: left-aligned headline, one paragraph, mono [details], one button. */
@Composable
fun ConnectionPage(kind: PageKind, details: String, address: String, onRetry: () -> Unit, onPairAgain: () -> Unit) {
    val copy = copyOf(kind)
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
        DetailsBlock(details)
        PageButton(copy.action, onRetry, onPairAgain)
    }
}

@Composable
private fun DetailsBlock(details: String) {
    Surface(
        shape = MaterialTheme.shapes.small,
        // design.md §4: code blocks sit on surfaceContainerHighest.
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
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
    val label = stringResource(actionLabel(action))
    when (action) {
        PageAction.RetryNow -> Button(onClick = onRetry, shape = ButtonShape) { Text(label) }
        PageAction.CheckAgain -> FilledTonalButton(onClick = onRetry, shape = ButtonShape) { Text(label) }
        PageAction.PairAgain -> Button(onClick = onPairAgain, shape = ButtonShape) { Text(label) }
    }
}

/**
 * The link states that wait for the user (a changed certificate: Pair again; Hermes updated: Check again), for a view
 * that keeps its own content instead of a full [ConnectionPage]: Home's Stocks view, whose stocks still load while Hermes
 * is updated. Hermes merely down needs nobody (it is retried by itself) and has no notice there. A refused token never
 * reaches Home: the pairing is wiped and the pair screen opens with its own notice.
 */
fun noticeFor(link: LinkState): PageKind? = when (link) {
    is LinkState.HermesUpdated -> PageKind.HermesUpdated
    is LinkState.Offline -> if (link.cause == OfflineCause.CertificateChanged) PageKind.CertificateChanged else null
    else -> null
}

/**
 * The compact form of a connection page, as one Home notice (the offline / compatibility notice surface): the page's
 * title and its one action, so the fix stays one tap away.
 */
@Composable
fun ConnectionNotice(kind: PageKind, onRetry: () -> Unit, onPairAgain: () -> Unit) {
    val copy = copyOf(kind)
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.xs),
    ) {
        Row(
            modifier = Modifier.heightIn(min = NOTICE_MIN_HEIGHT).padding(start = Spacing.m, end = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(copy.title),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f).padding(vertical = Spacing.s).semantics { liveRegion = LiveRegionMode.Polite },
            )
            TextButton(onClick = if (copy.action == PageAction.PairAgain) onPairAgain else onRetry, shape = ButtonShape) {
                Text(stringResource(actionLabel(copy.action)))
            }
        }
    }
}

@StringRes
private fun actionLabel(action: PageAction): Int = when (action) {
    PageAction.RetryNow -> R.string.conn_retry_now
    PageAction.CheckAgain -> R.string.conn_check_again
    PageAction.PairAgain -> R.string.conn_pair_again
}
