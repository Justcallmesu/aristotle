package io.github.justcallmesu.aristotle

import android.app.Application
import android.content.Context
import io.github.justcallmesu.aristotle.data.AesGcmTokenCipher
import io.github.justcallmesu.aristotle.data.CertificateProbe
import io.github.justcallmesu.aristotle.data.CredentialCodec
import io.github.justcallmesu.aristotle.data.CredentialStore
import io.github.justcallmesu.aristotle.data.HttpClients
import io.github.justcallmesu.aristotle.data.KeystoreTokenKey
import io.github.justcallmesu.aristotle.data.SharedPreferencesStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Why the app went back to the pair screen on its own. */
enum class PairNotice { Revoked }

/** The app's long-lived objects, built once per process. No DI framework. */
class AppContainer(context: Context) {
    val credentials = CredentialStore(
        SharedPreferencesStore(context.getSharedPreferences(CREDENTIALS_FILE, Context.MODE_PRIVATE)),
        CredentialCodec(AesGcmTokenCipher(KeystoreTokenKey::getOrCreate)),
    )
    val httpClients = HttpClients()
    val certificateProbe = CertificateProbe()

    private val notice = MutableStateFlow<PairNotice?>(null)
    val pairNotice: StateFlow<PairNotice?> = notice.asStateFlow()

    /** Bumped on every forget, so the pair screen starts from a fresh form. */
    private val epoch = MutableStateFlow(0)
    val pairEpoch: StateFlow<Int> = epoch.asStateFlow()

    /** Forgets the active PC and returns to pairing, optionally telling the user why. */
    fun forgetPc(reason: PairNotice?) {
        notice.value = reason
        epoch.value += 1
        credentials.wipeActive()
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
