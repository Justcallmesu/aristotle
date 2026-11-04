package io.github.justcallmesu.aristotle

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.justcallmesu.aristotle.background.AppLocales
import io.github.justcallmesu.aristotle.data.DeviceName
import io.github.justcallmesu.aristotle.data.ThemeChoice
import io.github.justcallmesu.aristotle.ui.AristotleApp
import io.github.justcallmesu.aristotle.ui.lock.BiometricHost
import io.github.justcallmesu.aristotle.ui.theme.AristotleTheme

/** A FragmentActivity because BiometricPrompt (the app lock, TS2) needs one; nothing else changes. */
class MainActivity : FragmentActivity() {
    private var biometric: BiometricHost? = null

    /** Below Android 13 the app's language (TS1) is applied here; on 13+ the platform does it ([AppLocales]). */
    override fun attachBaseContext(newBase: Context) {
        val settings = (newBase.applicationContext as AristotleApplication).container.settings
        super.attachBaseContext(AppLocales.wrap(newBase, settings.language.value))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Transparent bars whose icons follow the system light/dark mode, as AristotleTheme does (D8); the
        // screens pad their content by the window insets. The launch theme paints the same colours before this.
        enableEdgeToEdge()
        val container = (application as AristotleApplication).container
        // BiometricPrompt is built in onCreate; the app lock and the fresh checks (Q61) ask through it.
        biometric = BiometricHost(this).also(container.auth::attach)
        if (savedInstanceState == null) openFrom(intent)
        // The name the user gave the phone ("POCO X5 Pro 5G"), not the model code ("22101320G").
        val deviceName = DeviceName.of(
            Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME),
            Build.MANUFACTURER,
            Build.MODEL,
        )
        setContent {
            // The user's theme and text size (TS1) apply at once, app-wide; the bar icons follow the theme, not the system.
            val theme by container.settings.theme.collectAsStateWithLifecycle()
            val textSize by container.settings.textSize.collectAsStateWithLifecycle()
            val dark = theme.isDark(isSystemInDarkTheme())
            LaunchedEffect(theme, dark) { followTheme(theme, dark) }
            // With the app lock on, the recents preview never shows the app's content (Android 13+).
            val appLock by container.settings.appLock.collectAsStateWithLifecycle()
            LaunchedEffect(appLock) { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) setRecentsScreenshotEnabled(!appLock) }
            AristotleTheme(darkTheme = dark, textSize = textSize) {
                AristotleApp(container = container, defaultDeviceName = deviceName)
            }
        }
    }

    /** Transparent bars; with a forced light or dark theme their icons follow it instead of the system's mode. */
    private fun followTheme(theme: ThemeChoice, dark: Boolean) {
        if (theme == ThemeChoice.System) {
            enableEdgeToEdge()
            return
        }
        val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }

    override fun onDestroy() {
        biometric?.let {
            (application as AristotleApplication).container.auth.detach(it)
            it.cancel()
        }
        biometric = null
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openFrom(intent)
    }

    /**
     * A notification tap ([openIntent]): show that session's chat, with the prompt sheet up when it asked for one.
     * Only through the non-exported alias ([OpenSession]); the exported launcher entry never opens a session.
     */
    private fun openFrom(intent: Intent?) {
        intent ?: return
        val request = OpenSession.read(
            action = intent.action,
            component = intent.component?.className,
            session = intent.getStringExtra(OpenSession.EXTRA_SESSION),
            title = intent.getStringExtra(OpenSession.EXTRA_TITLE),
            prompt = intent.getBooleanExtra(OpenSession.EXTRA_PROMPT, false),
            flags = intent.flags,
            profile = intent.getStringExtra(OpenSession.EXTRA_PROFILE),
        ) ?: return
        (application as AristotleApplication).container.openChat.value = request
    }

    companion object {
        /**
         * Brings the running app forward (no second copy) and delivers this to [onNewIntent], or starts it. Targets
         * [OpenSession.ALIAS], so only the app's own PendingIntents carry it.
         */
        fun openIntent(context: Context, sessionId: String, title: String?, showPrompt: Boolean, profile: String? = null): Intent =
            Intent()
                .setClassName(context, OpenSession.ALIAS)
                .setAction(OpenSession.ACTION)
                .putExtra(OpenSession.EXTRA_SESSION, sessionId)
                .putExtra(OpenSession.EXTRA_TITLE, title)
                .putExtra(OpenSession.EXTRA_PROMPT, showPrompt)
                .apply { if (profile != null) putExtra(OpenSession.EXTRA_PROFILE, profile) }
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
