package io.github.justcallmesu.aristotle

import android.app.Application
import android.content.Context
import android.util.Log
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
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Why the app went back to the pair screen on its own. */
enum class PairNotice {
    /** The laptop refused the token (revoked). */
    Revoked,

    /** The stored pairing could never be used again (no pin from an old build, lost key) and was removed (D28). */
    PairAgain,
}

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

    private val notice = MutableStateFlow<PairNotice?>(null)
    val pairNotice: StateFlow<PairNotice?> = notice.asStateFlow()

    /** Bumped on every forget, so the pair screen starts from a fresh form. */
    private val epoch = MutableStateFlow(0)
    val pairEpoch: StateFlow<Int> = epoch.asStateFlow()

    init {
        reloadCredentials()
        link.start()
        scope.launch { link.state.collect { if (it == LinkState.Revoked) forgetPc(PairNotice.Revoked) } }
    }

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
        const val TAG = "Aristotle"
    }
}

class AristotleApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}
