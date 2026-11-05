package io.github.justcallmesu.aristotle.ui.stocks

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import io.github.justcallmesu.aristotle.R
import io.github.justcallmesu.aristotle.data.ConnectionProblem
import io.github.justcallmesu.aristotle.data.stocks.StocksError
import io.github.justcallmesu.aristotle.ui.chat.ChatLinks

/** One line for why a stocks call failed (protocol §20 error codes), the cause and, where there is one, the fix. */
@Composable
fun stocksErrorText(error: StocksError): String = when (error) {
    is StocksError.Unreachable -> stringResource(
        if (error.problem == ConnectionProblem.CertificateChanged) R.string.stocks_error_certificate else R.string.stocks_error_unreachable,
    )
    StocksError.BadAnswer -> stringResource(R.string.stocks_error_bad_answer)
    is StocksError.Server -> when (error.code) {
        StocksError.LIMIT_REACHED -> stringResource(R.string.stocks_error_limit)
        StocksError.UNKNOWN_SYMBOL -> stringResource(R.string.stocks_error_unknown_symbol)
        StocksError.UPSTREAM_BLOCKED -> stringResource(R.string.stocks_error_blocked, minutesFrom(error.retryAfterSeconds))
        StocksError.UPSTREAM_ERROR -> stringResource(R.string.stocks_error_upstream)
        StocksError.STATE_ERROR -> stringResource(R.string.stocks_error_state)
        NOT_FOUND -> stringResource(R.string.stocks_error_old_server)
        else -> stringResource(R.string.stocks_error_status, error.status, error.code ?: "-")
    }
}

/** A failed action as a snackbar line: "Couldn't add BBCA. Yahoo doesn't know this code." */
@Composable
fun stocksMessageText(message: StocksMessage): String {
    val why = stocksErrorText(message.error)
    val code = message.code.orEmpty()
    return when (message.action) {
        StocksAction.AddWatch -> stringResource(R.string.stocks_failed_add, code, why)
        StocksAction.RemoveWatch -> stringResource(R.string.stocks_failed_remove, code, why)
        StocksAction.Reorder -> stringResource(R.string.stocks_failed_move, code, why)
        StocksAction.SaveHolding -> stringResource(R.string.stocks_failed_save_holding, code, why)
        StocksAction.RemoveHolding -> stringResource(R.string.stocks_failed_remove_holding, code, why)
        StocksAction.SaveFees -> stringResource(R.string.stocks_failed_fees, why)
    }
}

/** Whole minutes, at least one, for "try again in N min". */
private fun minutesFrom(seconds: Int?): Int = ((seconds ?: SECONDS_PER_MINUTE) + SECONDS_PER_MINUTE - 1) / SECONDS_PER_MINUTE

private const val SECONDS_PER_MINUTE = 60
private const val NOT_FOUND = "not_found"

/**
 * Opens an article in the browser: `ACTION_VIEW` (BROWSABLE), as chat links do — the app has no Custom Tabs. Only
 * http(s) links ([ChatLinks.webTarget]); with no browser the tap does nothing.
 */
@Composable
fun rememberLinkOpener(): (String) -> Unit {
    val context = LocalContext.current
    return remember(context) { { link -> openWebLink(context, link) } }
}

fun openWebLink(context: Context, link: String) {
    val target = ChatLinks.webTarget(link) ?: return
    val intent = Intent(Intent.ACTION_VIEW, target.toUri()).addCategory(Intent.CATEGORY_BROWSABLE)
    if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // No browser: nothing opens.
    }
}

/**
 * The broker app (Q94): Ajaib, `ajaib.co.id` (declared in the manifest's `<queries>`, so Android 11+ lets the app see
 * it). No stock-specific deep link is known, so it launches the app; null when it is not installed.
 */
object BrokerApp {
    const val PACKAGE = "ajaib.co.id"
    const val NAME = "Ajaib"

    fun launchIntent(context: Context): Intent? = context.packageManager.getLaunchIntentForPackage(PACKAGE)

    fun open(context: Context): Boolean {
        val intent = launchIntent(context) ?: return false
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }
}
