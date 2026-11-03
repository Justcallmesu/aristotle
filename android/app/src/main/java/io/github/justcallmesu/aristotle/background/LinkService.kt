package io.github.justcallmesu.aristotle.background

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import io.github.justcallmesu.aristotle.AristotleApplication
import io.github.justcallmesu.aristotle.data.ConnectionNotice
import io.github.justcallmesu.aristotle.data.CredentialState
import io.github.justcallmesu.aristotle.data.LinkState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * The `specialUse` foreground service (spec Q5, T10). It owns nothing: while it runs, Android keeps the
 * process, and with it the process-wide link, alive in the background (and on the network in Doze). No wake
 * lock; the server's 25 s pings keep the socket. It stops itself when [BackgroundController.keepRunning] turns
 * false (Stop, the setting, unpaired, revoked).
 */
class LinkService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var following: Job? = null
    private val container get() = (application as AristotleApplication).container

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            container.background.stopByUser()
            finish()
            return START_NOT_STICKY
        }
        val notice = noticeOf(container.link.state.value, container.credentials.state.value)
        try {
            ServiceCompat.startForeground(this, Notifier.ID_LINK, container.notifier.ongoing(notice), foregroundType())
        } catch (e: IllegalStateException) {
            // A sticky restart from the background may be refused (not exempt from battery optimisation).
            Log.w(TAG, "background link refused: ${e.javaClass.name}")
            stopSelf()
            return START_NOT_STICKY
        }
        if (following == null) following = scope.launch { follow() }
        // Restarted after the system killed the process: the container reconnects on its own.
        return START_STICKY
    }

    /** Swiping the app away from recents keeps the link: only Stop, the setting or unpairing end it. */
    override fun onTaskRemoved(rootIntent: Intent?) = Unit

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun follow() {
        container.background.keepRunning.collectLatest { keep ->
            if (!keep) finish() else notices().distinctUntilChanged().collect(container.notifier::updateOngoing)
        }
    }

    private fun notices(): Flow<ConnectionNotice?> = combine(container.link.state, container.credentials.state, ::noticeOf)

    private fun noticeOf(link: LinkState, credentials: CredentialState): ConnectionNotice? {
        val pc = (credentials as? CredentialState.Paired)?.credentials?.let(ConnectionNotice::pcLabel).orEmpty()
        return ConnectionNotice.of(link, pc)
    }

    private fun finish() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun foregroundType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0

    companion object {
        const val ACTION_STOP = "io.github.justcallmesu.aristotle.action.STOP_LINK"
        private const val TAG = "Aristotle"
    }
}
