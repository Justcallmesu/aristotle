package io.github.justcallmesu.aristotle

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.justcallmesu.aristotle.data.DeviceName
import io.github.justcallmesu.aristotle.ui.AristotleApp
import io.github.justcallmesu.aristotle.ui.theme.AristotleTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Transparent bars whose icons follow the system light/dark mode, as AristotleTheme does (D8); the
        // screens pad their content by the window insets. The launch theme paints the same colours before this.
        enableEdgeToEdge()
        val container = (application as AristotleApplication).container
        if (savedInstanceState == null) openFrom(intent)
        // The name the user gave the phone ("POCO X5 Pro 5G"), not the model code ("22101320G").
        val deviceName = DeviceName.of(
            Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME),
            Build.MANUFACTURER,
            Build.MODEL,
        )
        setContent {
            AristotleTheme {
                AristotleApp(container = container, defaultDeviceName = deviceName)
            }
        }
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
        ) ?: return
        (application as AristotleApplication).container.openChat.value = request
    }

    companion object {
        /**
         * Brings the running app forward (no second copy) and delivers this to [onNewIntent], or starts it. Targets
         * [OpenSession.ALIAS], so only the app's own PendingIntents carry it.
         */
        fun openIntent(context: Context, sessionId: String, title: String?, showPrompt: Boolean): Intent =
            Intent()
                .setClassName(context, OpenSession.ALIAS)
                .setAction(OpenSession.ACTION)
                .putExtra(OpenSession.EXTRA_SESSION, sessionId)
                .putExtra(OpenSession.EXTRA_TITLE, title)
                .putExtra(OpenSession.EXTRA_PROMPT, showPrompt)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
