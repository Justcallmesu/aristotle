package io.github.justcallmesu.aristotle

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.Log
import io.github.justcallmesu.aristotle.background.BackgroundController
import io.github.justcallmesu.aristotle.background.Notifier
import io.github.justcallmesu.aristotle.data.AlertRouter
import io.github.justcallmesu.aristotle.data.AppSettings
import io.github.justcallmesu.aristotle.data.ApplyingPreferencesStore
import io.github.justcallmesu.aristotle.data.DeviceMaker
import io.github.justcallmesu.aristotle.data.ResumedSessions
import io.github.justcallmesu.aristotle.data.ScreenFocus
import io.github.justcallmesu.aristotle.data.SessionWatcher
import io.github.justcallmesu.aristotle.data.AesGcmTokenCipher
import io.github.justcallmesu.aristotle.data.CertificateProbe
import io.github.justcallmesu.aristotle.data.CredentialCodec
import io.github.justcallmesu.aristotle.data.CredentialStore
import io.github.justcallmesu.aristotle.data.AndroidNetworkWatch
import io.github.justcallmesu.aristotle.data.HttpClients
import io.github.justcallmesu.aristotle.data.HttpLinkDialer
import io.github.justcallmesu.aristotle.data.KeystoreTokenKey
import io.github.justcallmesu.aristotle.data.LinkManager
import io.github.justcallmesu.aristotle.data.LinkState
import io.github.justcallmesu.aristotle.data.SharedPreferencesStore
import io.github.justcallmesu.aristotle.data.chat.ChatDrafts
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

/** Why the app went back to the pair screen on its own. */
enum class PairNotice {
    /** The laptop refused the token (revoked). */
    Revoked,

    /** The stored pairing could never be used again (no pin from an old build, lost key) and was removed (D28). */
    PairAgain,
}

/** A notification asked to show [sessionId]'s chat; [showPrompt]: with the prompt sheet up (T12). */
data class OpenChat(val sessionId: String, val title: String?, val showPrompt: Boolean)

/** The app's long-lived objects, built once per process. No DI framework. */
class AppContainer(context: Context) {
    /** Process-lifetime work (credential reads and wipes); the store moves it off the main thread. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val credentials = CredentialStore(
        SharedPreferencesStore(context.getSharedPreferences(CREDENTIALS_FILE, Context.MODE_PRIVATE)),
        CredentialCodec(AesGcmTokenCipher(encryptKey = KeystoreTokenKey::getOrCreate, decryptKey = KeystoreTokenKey::existing)),
    )
    val httpClients = HttpClients()
    val certificateProbe = CertificateProbe()

    /**
     * The one link to the paired PC (T09), for every screen; it runs off the main thread. The link turns
     * its own errors into an offline round; the handler is the last guard, so nothing it starts can crash
     * the app (only the error's type is logged, never a message that could hold a token).
     */
    val link = LinkManager(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> Log.w(TAG, "link: ${e.javaClass.name}") }),
        credentials = credentials.state,
        dialer = HttpLinkDialer(httpClients),
        network = AndroidNetworkWatch(context),
        memory = credentials,
    )

    val settings = AppSettings(ApplyingPreferencesStore(context.getSharedPreferences(SETTINGS_FILE, Context.MODE_PRIVATE)))

    /** Unsent composer text per session (T13): this phone only, written in the background as the user types. */
    val drafts = ChatDrafts(ApplyingPreferencesStore(context.getSharedPreferences(DRAFTS_FILE, Context.MODE_PRIVATE)))
    val notifier = Notifier(context)
    val background = BackgroundController(context, link, credentials.state, settings, scope)

    /** HyperOS stops background apps unless told otherwise; the battery guide is for these phones (spec Q27). */
    val isXiaomiFamily = DeviceMaker.isXiaomiFamily(Build.MANUFACTURER, Build.BRAND)

    /** The session open on screen; the chat screen (T11) sets it. No notification is posted for it. */
    val openSession = MutableStateFlow<String?>(null)

    /** Set by a notification tap, taken (set back to null) by the screen that shows the chat. */
    val openChat = MutableStateFlow<OpenChat?>(null)

    /** Sessions the current relay socket has resumed: the watcher and the chat screen share it (one resume each). */
    val resumedSessions = ResumedSessions()
    private val alerts = AlertRouter()

    private val notice = MutableStateFlow<PairNotice?>(null)
    val pairNotice: StateFlow<PairNotice?> = notice.asStateFlow()

    /** Bumped on every forget, so the pair screen starts from a fresh form. */
    private val epoch = MutableStateFlow(0)
    val pairEpoch: StateFlow<Int> = epoch.asStateFlow()

    init {
        reloadCredentials()
        link.start()
        scope.launch { link.state.collect { if (it == LinkState.Revoked) forgetPc(PairNotice.Revoked) } }
        notifier.createChannels()
        watchSessions()
    }

    /** Notifications from the watched sessions (T10); router and notifier run on the main thread. */
    private fun watchSessions() {
        scope.launch {
            SessionWatcher(link, resumed = resumedSessions).alerts().flowOn(Dispatchers.Default).collect { alert ->
                alerts.onAlert(alert, ScreenFocus(background.appVisible.value, openSession.value)).forEach(notifier::apply)
            }
        }
        scope.launch { openSession.filterNotNull().collect { id -> alerts.onOpened(id).forEach(notifier::apply) } }
    }

    /** A "needs you" notification was swiped away (main thread, like the router's other callers). */
    fun onAlertDismissed(sessionId: String, promptId: String) = alerts.onDismissed(sessionId, promptId)

    /** Reads the stored pairing again (at start, and Retry after the Keystore was unavailable). */
    fun reloadCredentials() {
        scope.launch { if (credentials.load()) notice.value = PairNotice.PairAgain }
    }

    /** Forgets the active PC and returns to pairing, optionally telling the user why. */
    fun forgetPc(reason: PairNotice?) {
        notice.value = reason
        epoch.value += 1
        scope.launch { credentials.wipeActive() }
    }

    fun clearPairNotice() {
        notice.value = null
    }

    private companion object {
        const val CREDENTIALS_FILE = "credentials"
        const val SETTINGS_FILE = "settings"
        const val DRAFTS_FILE = "drafts"
        const val TAG = "Aristotle"
    }
}

class AristotleApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(VisibilityCallbacks())
    }

    /** Tells the background controller when the app comes on screen and leaves it (T10). */
    private inner class VisibilityCallbacks : ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) = container.background.onActivityStarted()
        override fun onActivityStopped(activity: Activity) = container.background.onActivityStopped(activity.isChangingConfigurations)
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }
}
