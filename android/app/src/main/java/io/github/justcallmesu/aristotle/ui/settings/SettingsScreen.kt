package io.github.justcallmesu.aristotle.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.justcallmesu.aristotle.BuildConfig
import io.github.justcallmesu.aristotle.data.ServerVersions
import io.github.justcallmesu.aristotle.R
import io.github.justcallmesu.aristotle.background.AppChannel
import io.github.justcallmesu.aristotle.background.ChannelPage
import io.github.justcallmesu.aristotle.background.ChannelSwitch
import io.github.justcallmesu.aristotle.background.SystemSettings
import io.github.justcallmesu.aristotle.data.AppLanguage
import io.github.justcallmesu.aristotle.data.LockTimeout
import io.github.justcallmesu.aristotle.data.PcCredentials
import io.github.justcallmesu.aristotle.data.TextSize
import io.github.justcallmesu.aristotle.data.ThemeChoice
import io.github.justcallmesu.aristotle.ui.components.SubPageTopBar
import io.github.justcallmesu.aristotle.ui.theme.ButtonShape
import io.github.justcallmesu.aristotle.ui.theme.Sizes
import io.github.justcallmesu.aristotle.ui.theme.Spacing

/**
 * What Settings asks of the app around it. Android owns the notification channels and (on 13+) the app language, so
 * those are read and changed through these, not through [SettingsViewModel].
 */
class SettingsActions(
    val onBack: () -> Unit,
    val onBatteryGuide: () -> Unit,
    val onRepair: () -> Unit,
    val onUnpair: () -> Unit,
    val canPostNotifications: () -> Boolean,
    val channelOn: (AppChannel) -> Boolean,
    val currentLanguage: () -> AppLanguage,
    val pickLanguage: (AppLanguage) -> Unit,
    /** The phone has a PIN, pattern or password (the app lock needs one, Q61). */
    val screenLockSet: () -> Boolean = { false },
)

/** The settings screen (Q60, TS1), grouped like Android's own: connection, notifications, appearance, language, security, about. */
@Composable
fun SettingsScreen(viewModel: SettingsViewModel, pc: PcCredentials, actions: SettingsActions) {
    val sheet by viewModel.sheet.collectAsStateWithLifecycle()
    val theme by viewModel.theme.collectAsStateWithLifecycle()
    val textSize by viewModel.textSize.collectAsStateWithLifecycle()
    val lockTimeout by viewModel.lockTimeout.collectAsStateWithLifecycle()
    var notificationsOn by remember { mutableStateOf(actions.canPostNotifications()) }
    var channels by remember { mutableStateOf(AppChannel.entries.associateWith(actions.channelOn)) }
    var language by remember { mutableStateOf(actions.currentLanguage()) }
    // Back from a system page (notifications, the app's language): read Android's values again.
    LifecycleResumeEffect(Unit) {
        notificationsOn = actions.canPostNotifications()
        channels = AppChannel.entries.associateWith(actions.channelOn)
        language = actions.currentLanguage()
        onPauseOrDispose { }
    }
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            SubPageTopBar(stringResource(R.string.settings_title), actions.onBack)
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                ConnectionSection(viewModel, pc, actions.onRepair)
                NotificationsSection(notificationsOn, channels, actions.onBatteryGuide)
                AppearanceSection(theme, textSize, onOpen = viewModel::show)
                LanguageSection(language) { viewModel.show(SettingsSheet.Language) }
                SecuritySection(viewModel, actions.screenLockSet)
                AboutSection()
                Spacer(Modifier.height(Spacing.xxl))
            }
        }
    }
    when (sheet) {
        SettingsSheet.Theme -> ChoiceSheet(
            title = stringResource(R.string.settings_theme),
            body = null,
            options = ThemeChoice.entries,
            selected = theme,
            label = { themeLabel(it) },
            onPick = viewModel::pickTheme,
            onDismiss = viewModel::close,
        )
        SettingsSheet.TextSize -> ChoiceSheet(
            title = stringResource(R.string.settings_text_size),
            body = stringResource(R.string.settings_text_size_body),
            options = TextSize.entries,
            selected = textSize,
            label = { textSizeLabel(it) },
            onPick = viewModel::pickTextSize,
            onDismiss = viewModel::close,
        )
        SettingsSheet.Language -> ChoiceSheet(
            title = stringResource(R.string.settings_language),
            body = stringResource(R.string.settings_language_body),
            options = AppLanguage.entries,
            selected = language,
            label = { languageLabel(it) },
            onPick = { choice ->
                viewModel.close()
                language = choice
                actions.pickLanguage(choice)
            },
            onDismiss = viewModel::close,
        )
        SettingsSheet.LockTimeout -> ChoiceSheet(
            title = stringResource(R.string.settings_lock_after),
            body = stringResource(R.string.settings_lock_after_body),
            options = LockTimeout.entries,
            selected = lockTimeout,
            label = { lockTimeoutLabel(it) },
            onPick = viewModel::pickLockTimeout,
            onDismiss = viewModel::close,
        )
        SettingsSheet.Unpair -> UnpairDialog(
            deviceId = pc.deviceId,
            onConfirm = {
                viewModel.close()
                actions.onUnpair()
            },
            onDismiss = viewModel::close,
        )
        null -> Unit
    }
}

/** Android owns each channel: the row mirrors its switch and opens its system page (or the app's, while all are off). */
@Composable
private fun NotificationsSection(appAllowed: Boolean, channels: Map<AppChannel, Boolean>, onBatteryGuide: () -> Unit) {
    val context = LocalContext.current
    SectionHeader(stringResource(R.string.settings_section_notifications))
    if (!appAllowed) {
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
    val openLabel = stringResource(R.string.settings_channel_open)
    AppChannel.entries.forEach { channel ->
        SettingRow(
            title = stringResource(channel.title),
            body = stringResource(channel.description),
            modifier = Modifier.clickable(onClickLabel = openLabel) {
                when (ChannelSwitch.pageFor(appAllowed)) {
                    ChannelPage.Channel -> SystemSettings.openChannelSettings(context, channel.id)
                    ChannelPage.App -> SystemSettings.openNotificationSettings(context)
                }
            },
            trailing = { Switch(checked = channels[channel] == true, onCheckedChange = null) },
        )
    }
    SettingRow(
        title = stringResource(R.string.settings_battery_guide),
        body = stringResource(R.string.settings_battery_guide_body),
        modifier = Modifier.clickable(onClick = onBatteryGuide),
    )
}

@Composable
private fun AppearanceSection(theme: ThemeChoice, textSize: TextSize, onOpen: (SettingsSheet) -> Unit) {
    SectionHeader(stringResource(R.string.settings_section_appearance))
    SettingRow(
        title = stringResource(R.string.settings_theme),
        body = themeLabel(theme),
        modifier = Modifier.clickable { onOpen(SettingsSheet.Theme) },
    )
    SettingRow(
        title = stringResource(R.string.settings_text_size),
        body = textSizeLabel(textSize),
        modifier = Modifier.clickable { onOpen(SettingsSheet.TextSize) },
    )
}

@Composable
private fun LanguageSection(language: AppLanguage, onOpen: () -> Unit) {
    SectionHeader(stringResource(R.string.settings_section_language))
    SettingRow(title = stringResource(R.string.settings_language), body = languageLabel(language), modifier = Modifier.clickable(onClick = onOpen))
}

/**
 * App lock (Q61) and the phone's Terminal switch (Q62). The lock switch moves only after a fingerprint / screen-lock
 * check ([SettingsViewModel.setAppLock]); without a screen lock on the phone it says so and stays off.
 */
@Composable
private fun SecuritySection(viewModel: SettingsViewModel, screenLockSet: () -> Boolean) {
    val appLock by viewModel.appLock.collectAsStateWithLifecycle()
    val lockTimeout by viewModel.lockTimeout.collectAsStateWithLifecycle()
    val showTerminal by viewModel.showTerminal.collectAsStateWithLifecycle()
    val notice by viewModel.lockNotice.collectAsStateWithLifecycle()
    SectionHeader(stringResource(R.string.settings_section_security))
    SwitchRow(
        title = stringResource(R.string.settings_app_lock),
        body = stringResource(
            when {
                notice == LockNotice.NoScreenLock && !appLock -> R.string.settings_app_lock_no_screen_lock
                appLock -> R.string.settings_app_lock_on_body
                else -> R.string.settings_app_lock_off_body
            },
        ),
        checked = appLock,
        onChange = { on -> viewModel.setAppLock(on, screenLockSet()) },
    )
    if (appLock) {
        SettingRow(
            title = stringResource(R.string.settings_lock_after),
            body = lockTimeoutLabel(lockTimeout),
            modifier = Modifier.clickable { viewModel.show(SettingsSheet.LockTimeout) },
        )
    }
    SwitchRow(
        title = stringResource(R.string.settings_show_terminal),
        body = stringResource(R.string.settings_show_terminal_body),
        checked = showTerminal,
        onChange = viewModel::setShowTerminal,
    )
}

@Composable
private fun lockTimeoutLabel(choice: LockTimeout): String = stringResource(
    when (choice) {
        LockTimeout.Immediately -> R.string.settings_lock_immediately
        LockTimeout.OneMinute -> R.string.settings_lock_1_min
        LockTimeout.FiveMinutes -> R.string.settings_lock_5_min
        LockTimeout.FifteenMinutes -> R.string.settings_lock_15_min
    },
)

/** "What's new" is TV2's and the licence notices D45's; both say so until they exist. */
@Composable
private fun AboutSection() {
    SectionHeader(stringResource(R.string.settings_section_about))
    SettingRow(title = stringResource(R.string.app_name), body = stringResource(R.string.settings_about_tagline))
    SettingRow(
        title = stringResource(R.string.settings_version),
        body = stringResource(R.string.settings_app_version_value, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
    )
    val server by ServerVersions.current.collectAsStateWithLifecycle()
    SettingRow(
        title = stringResource(R.string.settings_server_version),
        body = server?.let { info ->
            val unknown = stringResource(R.string.settings_server_unknown)
            stringResource(R.string.settings_server_version_value, info.version ?: unknown, info.protocol)
        } ?: stringResource(R.string.settings_server_unknown),
    )
    SettingRow(title = stringResource(R.string.settings_whats_new), body = stringResource(R.string.settings_coming_soon), quiet = true)
    SettingRow(title = stringResource(R.string.settings_licences), body = stringResource(R.string.settings_coming_soon), quiet = true)
    SettingRow(title = stringResource(R.string.settings_source), body = stringResource(R.string.settings_source_body))
}

@Composable
internal fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .padding(start = Spacing.listGutter, end = Spacing.listGutter, top = Spacing.xxl, bottom = Spacing.xs)
            .semantics { heading() },
    )
}

/** One settings row (56 dp list row). [quiet]: a placeholder with nothing to do yet, its title dimmed. */
@Composable
internal fun SettingRow(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    quiet: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    ListItem(
        modifier = modifier.heightIn(min = Sizes.listRowHeight),
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        headlineContent = {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (quiet) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
        },
        supportingContent = {
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        trailingContent = trailing,
    )
}

/** A switch row for one of the app's own settings (not Android's). */
@Composable
internal fun SwitchRow(title: String, body: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    SettingRow(
        title = title,
        body = body,
        modifier = Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        trailing = { Switch(checked = checked, onCheckedChange = null) },
    )
}

@Composable
private fun themeLabel(choice: ThemeChoice): String = stringResource(
    when (choice) {
        ThemeChoice.System -> R.string.settings_theme_system
        ThemeChoice.Light -> R.string.settings_theme_light
        ThemeChoice.Dark -> R.string.settings_theme_dark
    },
)

@Composable
private fun textSizeLabel(choice: TextSize): String = stringResource(
    when (choice) {
        TextSize.Small -> R.string.settings_text_small
        TextSize.Default -> R.string.settings_text_default
        TextSize.Large -> R.string.settings_text_large
    },
)

/** English and Bahasa Indonesia in their own words, so each can be found whatever the app shows now. */
@Composable
private fun languageLabel(choice: AppLanguage): String = stringResource(
    when (choice) {
        AppLanguage.System -> R.string.settings_language_system
        AppLanguage.English -> R.string.settings_language_english
        AppLanguage.Indonesian -> R.string.settings_language_indonesian
    },
)
