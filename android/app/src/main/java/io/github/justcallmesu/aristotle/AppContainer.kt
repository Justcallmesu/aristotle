package io.github.justcallmesu.aristotle

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import io.github.justcallmesu.aristotle.background.BackgroundController
import io.github.justcallmesu.aristotle.background.Notifier
import io.github.justcallmesu.aristotle.data.AlertRouter
import io.github.justcallmesu.aristotle.data.AppLock
import io.github.justcallmesu.aristotle.data.AppSettings
import io.github.justcallmesu.aristotle.data.AuthBroker
import io.github.justcallmesu.aristotle.data.AttachmentFiles
import io.github.justcallmesu.aristotle.data.ApplyingPreferencesStore
import io.github.justcallmesu.aristotle.data.DeviceMaker
import io.github.justcallmesu.aristotle.data.QuoteGate
import io.github.justcallmesu.aristotle.data.QuoteSource
import io.github.justcallmesu.aristotle.data.ServerVersions
import io.github.justcallmesu.aristotle.data.WhatsNewCard
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
import io.github.justcallmesu.aristotle.data.AndroidMulticastGuard
import io.github.justcallmesu.aristotle.data.AndroidNsdBrowser
import io.github.justcallmesu.aristotle.data.AndroidWifiWatch
import io.github.justcallmesu.aristotle.data.FindState
import io.github.justcallmesu.aristotle.data.LanAutoFind
import io.github.justcallmesu.aristotle.data.PcFinder
import io.github.justcallmesu.aristotle.data.PcSearch
import io.github.justcallmesu.aristotle.data.TcpPortProbe
import io.github.justcallmesu.aristotle.data.LinkState
import io.github.justcallmesu.aristotle.data.PcRecords
import io.github.justcallmesu.aristotle.data.ProfileSelection
import io.github.justcallmesu.aristotle.data.SessionPins
import io.github.justcallmesu.aristotle.data.CredentialState
import io.github.justcallmesu.aristotle.data.SharedPreferencesStore
import io.github.justcallmesu.aristotle.data.chat.ChatDrafts
import io.github.justcallmesu.aristotle.data.terminal.TerminalAccess
import io.github.justcallmesu.aristotle.data.terminal.TerminalDialer
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

/**
 * A notification asked to show [sessionId]'s chat; [showPrompt]: with the prompt sheet up (T12). [profile]: the
 * session's Hermes profile (null = the relay's launch profile, T14).
 */
data class OpenChat(val sessionId: String, val title: String?, val showPrompt: Boolean, val profile: String? = null)

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
    private val linkScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> Log.w(TAG, "link: ${e.javaClass.name}") })
    private val dialer = HttpLinkDialer(httpClients)

    /** The phone's network; the link follows it, and a change ends every open terminal (Q55). */
    val network = AndroidNetworkWatch(context)
    val link = LinkManager(
        scope = linkScope,
        credentials = credentials.state,
        dialer = dialer,
        network = network,
        memory = credentials,
    )

    /** The phone's Wi-Fi (Q65): its joins start LAN discovery, its /24 is what "Find PC on this Wi-Fi" probes. */
    private val wifi = AndroidWifiWatch(context, log = { Log.d(TAG, it) })

    /** LAN discovery (Q65, TL1): finds the paired PC's new Wi-Fi address; trusted only through the pinned `/healthz`. */
    private val finder = PcFinder(
        browser = AndroidNsdBrowser(context),
        multicast = AndroidMulticastGuard(context),
        health = dialer,
        ports = TcpPortProbe(),
        onFound = link::useFound,
        log = { Log.d(TAG, it) },
    )

    /** Settings' "Find PC on this Wi-Fi": announcement first, then the /24. Never automatic (Q65). */
    val pcSearch = PcSearch { onPhase ->
        (credentials.state.value as? CredentialState.Paired)?.credentials?.let { pc -> finder.findOnWifi(pc, wifi, onPhase) } ?: FindState.NotFound
    }

    /** T19b: whether the Terminal entry shows (`/v1/me` on every connect), and where a terminal opens. */
    val terminalAccess = TerminalAccess(linkScope, link.state, credentials.state, dialer)

    /** Opens terminal sockets with the link's pinned clients (never the relay socket). */
    val terminalDialer: TerminalDialer = dialer

    val settings = AppSettings(ApplyingPreferencesStore(context.getSharedPreferences(SETTINGS_FILE, Context.MODE_PRIVATE)))

    /**
     * The app lock (Q61, TS2). Built with the process, so a new process starts locked while the lock is on; the
     * activity callbacks below drive it. [auth] asks the activity on screen for a fingerprint or the screen lock.
     */
    val appLock = AppLock(
        enabled = { settings.appLock.value },
        timeout = { settings.lockTimeout.value },
        now = SystemClock::elapsedRealtime,
        schedule = { delayMillis, action -> Handler(Looper.getMainLooper()).postDelayed(action, delayMillis) },
    )
    val auth = AuthBroker(appLock, lockOn = { settings.appLock.value }, turnOff = { settings.setAppLock(false) })

    /** Picked photos and files, prepared in the app cache until sent (T16). */
    val attachments = AttachmentFiles(context)

    /** Unsent composer text per session (T13): this phone only, written in the background as the user types. */
    val drafts = ChatDrafts(ApplyingPreferencesStore(context.getSharedPreferences(DRAFTS_FILE, Context.MODE_PRIVATE)))

    /** The one-time "What's new" card after an update (Q64, TV2). */
    val whatsNew = WhatsNewCard(settings, BuildConfig.VERSION_CODE)

    /** The loading-screen quotes (TQ1): `quotes.json` is an asset copied from docs/ at build time; parsed once, off the main thread. */
    val quotes = QuoteSource({ context.assets.open("quotes.json") }, settings)

    /** When the quote loading screen shows (Q44); the main thread owns it. */
    val quoteGate = QuoteGate()

    /** The Hermes profile the list shows and the watcher follows (Q25, T14), remembered per PC. */
    val profileSelection = ProfileSelection(ApplyingPreferencesStore(context.getSharedPreferences(SETTINGS_FILE, Context.MODE_PRIVATE)))

    /** Session pins on this phone only (T14; Hermes' own pin is not readable over the relay). */
    val pins = SessionPins(ApplyingPreferencesStore(context.getSharedPreferences(PINS_FILE, Context.MODE_PRIVATE)))
    val notifier = Notifier(context)

    /** What this phone keeps per PC, ended together by Unpair and a finished re-pair (TS1). */
    val records = PcRecords(credentials, profileSelection, pins, drafts)
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
        scope.launch(Dispatchers.Default) { quotes.load() }
        link.start()
        LanAutoFind(linkScope, wifi.current, wifi, link.state, { (credentials.state.value as? CredentialState.Paired)?.credentials }, finder).start()
        terminalAccess.start()
        scope.launch { link.state.collect { if (it == LinkState.Revoked) forgetPc(PairNotice.Revoked) } }
        scope.launch { credentials.state.collect { profileSelection.follow((it as? CredentialState.Paired)?.credentials?.pcId) } }
        notifier.createChannels()
        watchSessions()
    }

    /** Notifications from the watched sessions (T10); router and notifier run on the main thread. */
    private fun watchSessions() {
        scope.launch {
            SessionWatcher(link, resumed = resumedSessions, profile = profileSelection.selected, onUnserved = { profileSelection.fallBack(it) }).alerts().flowOn(Dispatchers.Default).collect { alert ->
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
        clearAlerts()
        scope.launch {
            credentials.wipeActive()
            ServerVersions.clearAll()
            clearAlerts()
        }
    }

    /**
     * Settings' Unpair (TS1): the pairing, its addresses, the PC's settings, every draft and its notifications go;
     * back to pairing. The tray is swept again once the pairing is gone, for an alert that raced the wipe (TS1b).
     */
    fun unpair(pcId: String) {
        notice.value = null
        epoch.value += 1
        clearAlerts()
        scope.launch {
            records.unpair(pcId)
            clearAlerts()
        }
    }

    /** The old PC's session notifications leave the tray (main thread, like the router). */
    private fun clearAlerts() {
        alerts.reset().forEach(notifier::apply)
        notifier.cancelAlerts()
    }

    fun clearPairNotice() {
        notice.value = null
    }

    private companion object {
        const val CREDENTIALS_FILE = "credentials"
        const val SETTINGS_FILE = "settings"
        const val DRAFTS_FILE = "drafts"
        const val PINS_FILE = "pins"
        const val TAG = "Aristotle"
    }
}

class AristotleApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(VisibilityCallbacks())
    }

    /** Tells the background controller (T10) and the app lock (TS2) when the app comes on screen and leaves it. */
    private inner class VisibilityCallbacks : ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
            container.appLock.onStarted()
            container.background.onActivityStarted()
        }

        override fun onActivityStopped(activity: Activity) {
            container.appLock.onStopped(activity.isChangingConfigurations)
            container.background.onActivityStopped(activity.isChangingConfigurations)
        }
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }
}
