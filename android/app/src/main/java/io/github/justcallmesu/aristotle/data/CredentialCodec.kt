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
    /** T09b: the address typed at pairing; never dropped from [addresses]. */
    val typed: String? = null,
    /** D29: the last address that worked per kind (`lan`, `tailscale`). */
    @SerialName("last_good_kinds") val lastGoodKinds: Map<String, String> = emptyMap(),
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

/** An entry after [CredentialCodec.withLink]: its new text, and the addresses and records it now holds. */
class LinkedEntry(
    val text: String,
    val addresses: List<ServerEndpoint>,
    val typed: ServerEndpoint? = null,
    val lastGoodByKind: Map<Network, ServerEndpoint> = emptyMap(),
)

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
            typed = credentials.typed?.baseUrl,
            lastGoodKinds = credentials.lastGoodByKind.entries.associate { (kind, endpoint) -> kind.wire to endpoint.baseUrl },
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
            val typed = addresses.firstOrNull { it.baseUrl == stored.typed }
            val byKind = stored.lastGoodKinds.mapNotNull { (kind, url) -> addresses.firstOrNull { it.baseUrl == url && it.network.wire == kind }?.let { it.network to it } }.toMap()
            DecodeResult.Ok(PcCredentials(stored.pcId, stored.deviceId, addresses, pin, DeviceToken(token), lastGood, typed, byKind))
        }
    } catch (e: Exception) {
        if (KeystoreFailures.isPermanent(e)) DecodeResult.Unreadable else DecodeResult.Unavailable
    }

    /**
     * The entry in its current form when it comes from an earlier build: the one T07c `base_urls` entry
     * becomes the only address ([httpsBaseUrl] for a pre-Q49 Tailscale URL), and the typed one, until
     * `GET /v1/addresses` adds the rest. The pin and the sealed token are kept byte for byte (no Keystore
     * call). Null when nothing changes or the entry does not parse ([decode] then decides).
     */
    fun upgrade(text: String): String? {
        val stored = parse(text) ?: return null
        if (stored.baseUrls.isEmpty()) return null
        val legacy = legacyAddresses(stored.baseUrls)
        val addresses = (stored.addresses + legacy).distinct()
        return encodeStored(stored.copy(baseUrls = emptyList(), addresses = addresses, typed = stored.typed ?: legacy.firstOrNull()?.url))
    }

    /**
     * The entry with what the link learnt: [fresh] (the PC's addresses now; empty when unknown) merged into
     * the stored list ([PcAddresses.merge], keeping the typed and every last-good address) and [lastGood], also
     * recorded as its kind's last good one (D29). A pairing from a T09 build has no typed address; the first
     * last-good address it records stands in for it, so that address stays protected after [lastGood] moves on
     * (D29). The token stays sealed as it was. Null when the entry does not parse.
     */
    fun withLink(text: String, fresh: List<ServerEndpoint>, lastGood: ServerEndpoint?): LinkedEntry? {
        val stored = parse(text) ?: return null
        val known = PcAddresses.endpointsOf(stored.addresses)
        val typedUrl = stored.typed ?: stored.lastGood ?: lastGood?.baseUrl
        val typed = (known + listOfNotNull(lastGood)).firstOrNull { it.baseUrl == typedUrl }
        // An entry from before D29 knows only its last good address: that one is its kind's last good one.
        val previous = known.firstOrNull { it.baseUrl == stored.lastGood }?.let { mapOf(it.network.wire to it.baseUrl) }.orEmpty()
        val kinds = previous + stored.lastGoodKinds + listOfNotNull(lastGood).associate { it.network.wire to it.baseUrl }
        val kindGood = (known + listOfNotNull(lastGood)).filter { it.baseUrl in kinds.values }
        val addresses = PcAddresses.merge(fresh, known, keep = listOf(typed, lastGood) + kindGood)
        val wire = addresses.map(PcAddresses::wireOf)
        val updated = stored.copy(baseUrls = emptyList(), addresses = wire, lastGood = lastGood?.baseUrl, typed = typed?.baseUrl, lastGoodKinds = kinds)
        val byKind = addresses.filter { kinds[it.network.wire] == it.baseUrl }.associateBy { it.network }
        return LinkedEntry(encodeStored(updated), addresses, typed, byKind)
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
