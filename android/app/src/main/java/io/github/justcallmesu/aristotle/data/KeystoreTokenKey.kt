package io.github.justcallmesu.aristotle.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** The token key does not exist (app data cleared, or the key was deleted): sealed tokens are lost. */
class TokenKeyMissingException : GeneralSecurityException("device-token key not in the Keystore")

/** The AES-256-GCM key for device tokens, generated inside the Android Keystore and never exported. */
object KeystoreTokenKey {
    private const val PROVIDER = "AndroidKeyStore"
    private const val ALIAS = "aristotle.device-token.v1"
    private const val KEY_BITS = 256

    /** For decrypting: never creates a key, since a fresh key could not open an old token anyway. */
    @Synchronized
    fun existing(): SecretKey = find() ?: throw TokenKeyMissingException()

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
