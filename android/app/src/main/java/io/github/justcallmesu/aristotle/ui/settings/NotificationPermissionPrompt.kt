package io.github.justcallmesu.aristotle.ui.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import io.github.justcallmesu.aristotle.R
import io.github.justcallmesu.aristotle.data.AppSettings
import io.github.justcallmesu.aristotle.ui.theme.ButtonShape

/**
 * Android 13+: explains why Aristotle wants notifications, then lets Android ask. Asked once; after that the
 * settings page shows a line while they are off.
 */
@Composable
fun NotificationPermissionPrompt(settings: AppSettings, canPostNotifications: () -> Boolean) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    var show by remember { mutableStateOf(!settings.notificationsAsked && !canPostNotifications()) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    if (!show) return
    val done = {
        settings.markNotificationsAsked()
        show = false
    }
    AlertDialog(
        onDismissRequest = done,
        title = { Text(stringResource(R.string.perm_title)) },
        text = { Text(stringResource(R.string.perm_body)) },
        confirmButton = {
            TextButton(
                onClick = {
                    done()
                    request.launch(Manifest.permission.POST_NOTIFICATIONS)
                },
                shape = ButtonShape,
            ) { Text(stringResource(R.string.perm_continue)) }
        },
        dismissButton = { TextButton(onClick = done, shape = ButtonShape) { Text(stringResource(R.string.perm_not_now)) } },
    )
}
