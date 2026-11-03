package io.github.justcallmesu.aristotle.data

import java.security.UnrecoverableKeyException
import java.util.Base64
import javax.crypto.AEADBadTagException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The on-disk form of one PC entry: everything in clear except the token. */
@Serializable
private data class StoredPc(
    @SerialName("pc_id") val pcId: String,
    @SerialName("device_id") val deviceId: String,
    @SerialName("base_urls") val baseUrls: List<String>,
    val fingerprint: String? = null,
    @SerialName("token_sealed") val tokenSealed: String,
)

sealed interface DecodeResult {
    data class Ok(val credentials: PcCredentials) : DecodeResult

    /** Can never be read again (corrupt entry, wrong or lost key): safe to wipe. */
    data object Unreadable : DecodeResult

    /** The Keystore failed for now (e.g. busy right after boot): keep the entry and retry. */
    data object Unavailable : DecodeResult
}

/** Serialises [PcCredentials] with the token sealed by [cipher] (AAD = pc id + device id). */
class CredentialCodec(private val cipher: TokenCipher) {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(credentials: PcCredentials): String {
        val sealed = cipher.encrypt(credentials.token.value.toByteArray(Charsets.UTF_8), aad(credentials.pcId, credentials.deviceId))
        val stored = StoredPc(
            pcId = credentials.pcId,
            deviceId = credentials.deviceId,
            baseUrls = credentials.baseUrls,
            fingerprint = credentials.fingerprint,
            tokenSealed = Base64.getEncoder().encodeToString(sealed),
        )
        return json.encodeToString(StoredPc.serializer(), stored)
    }

    fun decode(text: String): DecodeResult = try {
        val stored = json.decodeFromString(StoredPc.serializer(), text)
        val sealed = Base64.getDecoder().decode(stored.tokenSealed)
        val token = cipher.decrypt(sealed, aad(stored.pcId, stored.deviceId)).toString(Charsets.UTF_8)
        DecodeResult.Ok(PcCredentials(stored.pcId, stored.deviceId, stored.baseUrls, stored.fingerprint, DeviceToken(token)))
    } catch (e: Exception) {
        if (KeystoreFailures.isPermanent(e)) DecodeResult.Unreadable else DecodeResult.Unavailable
    }

    private fun aad(pcId: String, deviceId: String): ByteArray = "aristotle-token\u0000$pcId\u0000$deviceId".toByteArray(Charsets.UTF_8)
}

/** Which decode failures mean the entry is gone for good (T07 review: a transient error must not wipe a pairing). */
object KeystoreFailures {
    /**
     * Permanent: a bad tag (wrong or regenerated key, tampering), a missing or invalidated key, or an
     * entry that does not parse (bad JSON, Base64 or blob layout, all IllegalArgumentException).
     * Everything else (KeyStoreException, ProviderException, ...) is treated as transient.
     * Android wraps every Keystore daemon error in UnrecoverableKeyException with an
     * android.security.KeyStoreException cause (AndroidKeyStoreProvider.loadAndroidKeyStoreKeyFromKeystore),
     * so only an UnrecoverableKeyException without that cause counts as permanent.
     */
    fun isPermanent(error: Throwable): Boolean = when (error) {
        is AEADBadTagException, is TokenKeyMissingException, is IllegalArgumentException -> true
        is UnrecoverableKeyException -> causes(error).none(::isKeystoreDaemonError)
        else -> causes(error).any { it.javaClass.name == KEY_INVALIDATED }
    }

    private fun causes(error: Throwable): Sequence<Throwable> =
        generateSequence(error) { current -> current.cause?.takeIf { it !== current } }.take(MAX_DEPTH)

    // Matched by name: android.security.KeyStoreException is public only from API 33 (minSdk is 29).
    private fun isKeystoreDaemonError(error: Throwable) = error.javaClass.name == KEYSTORE_DAEMON_ERROR

    private const val KEYSTORE_DAEMON_ERROR = "android.security.KeyStoreException"
    private const val KEY_INVALIDATED = "android.security.keystore.KeyPermanentlyInvalidatedException"
    private const val MAX_DEPTH = 8
}
