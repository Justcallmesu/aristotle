package io.github.justcallmesu.aristotle

import android.app.Application
import android.content.Context
import io.github.justcallmesu.aristotle.data.AesGcmTokenCipher
import io.github.justcallmesu.aristotle.data.AndroidVpnRoutes
import io.github.justcallmesu.aristotle.data.CertificateProbe
import io.github.justcallmesu.aristotle.data.CredentialCodec
import io.github.justcallmesu.aristotle.data.CredentialStore
import io.github.justcallmesu.aristotle.data.HttpClients
import io.github.justcallmesu.aristotle.data.KeystoreTokenKey
import io.github.justcallmesu.aristotle.data.SharedPreferencesStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Why the app went back to the pair screen on its own. */
enum class PairNotice { Revoked }

/** The app's long-lived objects, built once per process. No DI framework. */
class AppContainer(context: Context) {
    /** Process-lifetime work (credential reads and wipes); the store moves it off the main thread. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val credentials = CredentialStore(
        SharedPreferencesStore(context.getSharedPreferences(CREDENTIALS_FILE, Context.MODE_PRIVATE)),
        CredentialCodec(AesGcmTokenCipher(encryptKey = KeystoreTokenKey::getOrCreate, decryptKey = KeystoreTokenKey::existing)),
    )
    val httpClients = HttpClients(AndroidVpnRoutes(context))
    val certificateProbe = CertificateProbe()

    private val notice = MutableStateFlow<PairNotice?>(null)
    val pairNotice: StateFlow<PairNotice?> = notice.asStateFlow()

    /** Bumped on every forget, so the pair screen starts from a fresh form. */
    private val epoch = MutableStateFlow(0)
    val pairEpoch: StateFlow<Int> = epoch.asStateFlow()

    init {
        reloadCredentials()
    }

    /** Reads the stored pairing again (at start, and Retry after the Keystore was unavailable). */
    fun reloadCredentials() {
        scope.launch { credentials.load() }
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
    }
}

class AristotleApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}
