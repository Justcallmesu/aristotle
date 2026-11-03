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
    /** T07c and earlier: the one address typed at pairing. Read only; [CredentialCodec.upgrade] moves it. */
    @SerialName("base_urls") val baseUrls: List<String> = emptyList(),
    val addresses: List<WireAddress> = emptyList(),
    @SerialName("last_good") val lastGood: String? = null,
    val fingerprint: String? = null,
    @SerialName("token_sealed") val tokenSealed: String,
)

sealed interface DecodeResult {
    data class Ok(val credentials: PcCredentials) : DecodeResult

    /**
     * Can never be read or used again: safe to wipe. A corrupt entry, a wrong or lost key, no usable pin
     * (a Tailscale pairing from before spec Q49 stored none; that PC must be paired again) or no usable address.
     */
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
            addresses = credentials.addresses.map(PcAddresses::wireOf),
            lastGood = credentials.lastGood?.baseUrl,
            fingerprint = credentials.fingerprint,
            tokenSealed = Base64.getEncoder().encodeToString(sealed),
        )
        return encodeStored(stored)
    }

    fun decode(text: String): DecodeResult = try {
        val stored = json.decodeFromString(StoredPc.serializer(), text)
        val pin = stored.fingerprint?.takeIf { Fingerprint.parse(it) != null }
        val addresses = PcAddresses.endpointsOf(stored.addresses + legacyAddresses(stored.baseUrls))
        if (pin == null || addresses.isEmpty()) {
            DecodeResult.Unreadable
        } else {
            val sealed = Base64.getDecoder().decode(stored.tokenSealed)
            val token = cipher.decrypt(sealed, aad(stored.pcId, stored.deviceId)).toString(Charsets.UTF_8)
            val lastGood = addresses.firstOrNull { it.baseUrl == stored.lastGood }
            DecodeResult.Ok(PcCredentials(stored.pcId, stored.deviceId, addresses, pin, DeviceToken(token), lastGood))
        }
    } catch (e: Exception) {
        if (KeystoreFailures.isPermanent(e)) DecodeResult.Unreadable else DecodeResult.Unavailable
    }

    /**
     * The entry in its current form when it comes from an earlier build: the one T07c `base_urls` entry
     * becomes the only address ([httpsBaseUrl] for a pre-Q49 Tailscale URL) until `GET /v1/addresses` adds
     * the rest. The pin and the sealed token are kept byte for byte (no Keystore call). Null when nothing
     * changes or the entry does not parse ([decode] then decides).
     */
    fun upgrade(text: String): String? {
        val stored = parse(text) ?: return null
        if (stored.baseUrls.isEmpty()) return null
        val addresses = (stored.addresses + legacyAddresses(stored.baseUrls)).distinct()
        return encodeStored(stored.copy(baseUrls = emptyList(), addresses = addresses))
    }

    /** The entry with the link's latest [addresses] and [lastGood]; the token stays sealed as it was. */
    fun withLink(text: String, addresses: List<ServerEndpoint>, lastGood: ServerEndpoint?): String? {
        val stored = parse(text) ?: return null
        val wire = addresses.map(PcAddresses::wireOf)
        return encodeStored(stored.copy(baseUrls = emptyList(), addresses = wire, lastGood = lastGood?.baseUrl))
    }

    private fun parse(text: String): StoredPc? = runCatching { json.decodeFromString(StoredPc.serializer(), text) }.getOrNull()

    private fun encodeStored(stored: StoredPc): String = json.encodeToString(StoredPc.serializer(), stored)

    /** A T07c entry's typed address, labelled with the kind its own range gives it. */
    private fun legacyAddresses(baseUrls: List<String>): List<WireAddress> = baseUrls.mapNotNull { url ->
        (ServerAddress.parse(httpsBaseUrl(url)) as? AddressCheck.Ok)?.endpoint?.let(PcAddresses::wireOf)
    }

    private fun aad(pcId: String, deviceId: String): ByteArray = "aristotle-token\u0000$pcId\u0000$deviceId".toByteArray(Charsets.UTF_8)
}

/**
 * A base URL as spec Q49 stores it. Builds before Q49 stored Tailscale addresses as plain-HTTP URLs; the
 * server now serves HTTPS with the same certificate on the same host and port, so only the scheme changes.
 */
internal fun httpsBaseUrl(url: String): String =
    if (url.startsWith(LEGACY_SCHEME, ignoreCase = true)) "https://" + url.substring(LEGACY_SCHEME.length) else url

private const val LEGACY_SCHEME = "http://" // read from old entries only; never used to connect

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
