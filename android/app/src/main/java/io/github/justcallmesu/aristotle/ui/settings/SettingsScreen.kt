package io.github.justcallmesu.aristotle.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.justcallmesu.aristotle.R
import io.github.justcallmesu.aristotle.background.SystemSettings
import io.github.justcallmesu.aristotle.data.AppSettings
import io.github.justcallmesu.aristotle.ui.components.SubPageTopBar
import io.github.justcallmesu.aristotle.ui.theme.ButtonShape
import io.github.justcallmesu.aristotle.ui.theme.Sizes

/** The app's few settings (T10): the background link, a notifications warning when they are off, the battery guide. */
@Composable
fun SettingsScreen(settings: AppSettings, canPostNotifications: () -> Boolean, onBack: () -> Unit, onBatteryGuide: () -> Unit) {
    val keepConnected by settings.keepConnected.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var notificationsOn by remember { mutableStateOf(canPostNotifications()) }
    // Back from the system's notification settings: read the switch again.
    LifecycleResumeEffect(Unit) {
        notificationsOn = canPostNotifications()
        onPauseOrDispose { }
    }
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            SubPageTopBar(stringResource(R.string.settings_title), onBack)
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                SettingRow(
                    title = stringResource(R.string.settings_keep_connected),
                    body = stringResource(R.string.settings_keep_connected_body),
                    modifier = Modifier.toggleable(value = keepConnected, role = Role.Switch, onValueChange = settings::setKeepConnected),
                    trailing = { Switch(checked = keepConnected, onCheckedChange = null) },
                )
                if (!notificationsOn) {
                    SettingRow(
                        title = stringResource(R.string.settings_notifications_off),
                        body = stringResource(R.string.settings_notifications_off_body),
                        trailing = {
                            TextButton(onClick = { SystemSettings.openNotificationSettings(context) }, shape = ButtonShape) {
                                Text(stringResource(R.string.guide_open_settings))
                            }
                        },
                    )
                }
                SettingRow(
                    title = stringResource(R.string.settings_battery_guide),
                    body = stringResource(R.string.settings_battery_guide_body),
                    modifier = Modifier.clickable(onClick = onBatteryGuide),
                )
            }
        }
    }
}

@Composable
private fun SettingRow(title: String, body: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    ListItem(
        modifier = modifier.heightIn(min = Sizes.listRowHeight),
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        headlineContent = { Text(title, style = MaterialTheme.typography.bodyLarge) },
        supportingContent = {
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        trailingContent = trailing,
    )
}
