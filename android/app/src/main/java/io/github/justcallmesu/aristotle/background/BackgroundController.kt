package io.github.justcallmesu.aristotle.background

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.justcallmesu.aristotle.data.AppSettings
import io.github.justcallmesu.aristotle.data.BackgroundPolicy
import io.github.justcallmesu.aristotle.data.CredentialState
import io.github.justcallmesu.aristotle.data.LinkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Starts [LinkService] when the app leaves the screen and the link should stay (T10), and remembers a Stop
 * until the app is opened again. Fed by the Application's activity callbacks; main thread only.
 */
class BackgroundController(
    private val context: Context,
    private val link: LinkManager,
    credentials: StateFlow<CredentialState>,
    settings: AppSettings,
    scope: CoroutineScope,
) {
    private val visible = MutableStateFlow(false)
    val appVisible: StateFlow<Boolean> = visible.asStateFlow()

    private val stoppedByUser = MutableStateFlow(false)
    private var startedActivities = 0

    val keepRunning: StateFlow<Boolean> = combine(credentials, link.state, settings.keepConnected, stoppedByUser) { c, l, keep, stopped ->
        BackgroundPolicy.keepRunning(c, l, keep, stopped)
    }.stateIn(
        scope,
        SharingStarted.Eagerly,
        BackgroundPolicy.keepRunning(credentials.value, link.state.value, settings.keepConnected.value, stoppedByUser.value),
    )

    fun onActivityStarted() {
        startedActivities++
        if (startedActivities == 1) {
            visible.value = true
            stoppedByUser.value = false
            link.onForeground()
        }
    }

    /** A rotation stops one activity and starts the next: not a trip to the background. */
    fun onActivityStopped(changingConfigurations: Boolean) {
        startedActivities = (startedActivities - 1).coerceAtLeast(0)
        if (startedActivities > 0 || changingConfigurations) return
        visible.value = false
        if (keepRunning.value) startService()
    }

    /** The notification's Stop: the link closes and stays closed until the app is opened. */
    fun stopByUser() {
        stoppedByUser.value = true
        link.stop()
    }

    /** Android 12+ lets an app start a foreground service while it leaves the screen, not later from the background. */
    private fun startService() {
        try {
            ContextCompat.startForegroundService(context, Intent(context, LinkService::class.java))
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException; only the type is logged.
            Log.w(TAG, "background link not started: ${e.javaClass.name}")
        }
    }

    private companion object {
        const val TAG = "Aristotle"
    }
}
