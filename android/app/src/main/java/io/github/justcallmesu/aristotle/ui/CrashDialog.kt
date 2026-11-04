package io.github.justcallmesu.aristotle.ui

import androidx.activity.compose.LocalActivity
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.justcallmesu.aristotle.R
import io.github.justcallmesu.aristotle.background.DebugShare
import io.github.justcallmesu.aristotle.data.Diagnostics
import io.github.justcallmesu.aristotle.ui.theme.ButtonShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * After a crash, the next launch asks once (Q80b, TL2): Send report shares the one report file through the share
 * sheet, Dismiss (or Back) deletes it. Composed only while the app is unlocked, so it never shows above the lock
 * screen; a lock that comes on hides it until unlock.
 */
@Composable
internal fun CrashReportDialog(diagnostics: Diagnostics) {
    val offered by diagnostics.crashOffered.collectAsStateWithLifecycle()
    if (!offered) return
    val activity = LocalActivity.current
    val scope = rememberCoroutineScope()
    val subject = stringResource(R.string.crash_share_subject)
    val chooser = stringResource(R.string.share_chooser_title)
    val dismiss = { scope.launch { withContext(Dispatchers.IO) { diagnostics.dismissCrash() } } }
    AlertDialog(
        onDismissRequest = { dismiss() },
        title = { Text(stringResource(R.string.crash_title)) },
        text = { Text(stringResource(R.string.crash_body)) },
        confirmButton = {
            Button(
                onClick = {
                    scope.launch {
                        val file = withContext(Dispatchers.IO) { diagnostics.crashReportFile() }
                        if (file != null && activity != null) DebugShare.open(activity, file, subject, chooser)
                    }
                },
                shape = ButtonShape,
            ) { Text(stringResource(R.string.crash_send)) }
        },
        dismissButton = { TextButton(onClick = { dismiss() }, shape = ButtonShape) { Text(stringResource(R.string.crash_dismiss)) } },
        properties = DialogProperties(dismissOnClickOutside = false),
        shape = MaterialTheme.shapes.extraLarge,
    )
}
