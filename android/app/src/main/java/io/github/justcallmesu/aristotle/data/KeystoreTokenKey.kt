package io.github.justcallmesu.aristotle.data

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.KeyStoreException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** The token key does not exist (app data cleared, or the key was deleted): sealed tokens are lost. */
class TokenKeyMissingException : GeneralSecurityException("device-token key not in the Keystore")

/**
 * D24: what a key lookup that found nothing means. On Android 10-11 (keystore1) the lookup also returns
 * nothing when the call to the Keystore daemon fails (a daemon restarting after boot), so there the first
 * miss is reported as a transient [KeyStoreException] (the pairing is kept, Retry reads again) and only a
 * second miss in a row counts as a lost key. Keystore2 (Android 12+) says "not found" only when it is.
 */
class MissingKeyPolicy(private val sdkInt: Int) {
    private var excused = false

    fun onFound() {
        excused = false
    }

    fun onMissing(): GeneralSecurityException {
        if (sdkInt < KEYSTORE2_SDK && !excused) {
            excused = true
            return KeyStoreException("device-token key not found; the Keystore may be restarting")
        }
        return TokenKeyMissingException()
    }

    private companion object {
        const val KEYSTORE2_SDK = 31
    }
}

/** The AES-256-GCM key for device tokens, generated inside the Android Keystore and never exported. */
object KeystoreTokenKey {
    private const val PROVIDER = "AndroidKeyStore"
    private const val ALIAS = "aristotle.device-token.v1"
    private const val KEY_BITS = 256
    private val missing = MissingKeyPolicy(Build.VERSION.SDK_INT)

    /** For decrypting: never creates a key, since a fresh key could not open an old token anyway. */
    @Synchronized
    fun existing(): SecretKey = find()?.also { missing.onFound() } ?: throw missing.onMissing()

    @Synchronized
    fun getOrCreate(): SecretKey {
        find()?.let { return it }
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_BITS)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).run {
            init(spec)
            generateKey()
        }
    }

    private fun find(): SecretKey? {
        val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
        return store.getKey(ALIAS, null) as? SecretKey
    }
}
