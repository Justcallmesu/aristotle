package io.github.justcallmesu.aristotle.data

import java.security.UnrecoverableKeyException
import java.util.Base64
import javax.crypto.AEADBadTagException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One stored address. [source] and [lastSuccessAt] are format 2 (Q73); a format-1 entry has neither, and its
 * addresses read as [AddressSource.Earlier].
 */
@Serializable
private data class StoredAddress(
    val url: String,
    val kind: String,
    val source: String? = null,
    @SerialName("last_success_at") val lastSuccessAt: Long? = null,
)

/** The on-disk form of one PC entry: everything in clear except the token. */
@Serializable
private data class StoredPc(
    @SerialName("pc_id") val pcId: String,
    @SerialName("device_id") val deviceId: String,
    /** 1 (absent): T09 to TL1e. 2: each address carries its source and last success (Q73). */
    val format: Int = 1,
    /** T07c and earlier: the one address typed at pairing. Read only; [CredentialCodec.upgrade] moves it. */
    @SerialName("base_urls") val baseUrls: List<String> = emptyList(),
    val addresses: List<StoredAddress> = emptyList(),
    @SerialName("last_good") val lastGood: String? = null,
    /** T09b: the address typed at pairing; never dropped from [addresses]. */
    val typed: String? = null,
    /** D29: the last address that worked per kind (`lan`, `tailscale`). */
    @SerialName("last_good_kinds") val lastGoodKinds: Map<String, String> = emptyMap(),
    /** Q73: addresses the user deleted, oldest first; the PC's list does not bring them back ([CredentialCodec.withLink]). */
    val deleted: List<String> = emptyList(),
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

/**
 * An entry after [CredentialCodec.withLink] or [CredentialCodec.withoutAddress]: its new text, and the addresses and
 * records it now holds. [added]: addresses it did not hold before, with where they came from.
 */
class LinkedEntry(
    val text: String,
    val addresses: List<ServerEndpoint>,
    val typed: ServerEndpoint? = null,
    val lastGoodByKind: Map<Network, ServerEndpoint> = emptyMap(),
    val lastGood: ServerEndpoint? = null,
    val addressInfo: Map<ServerEndpoint, AddressInfo> = emptyMap(),
    val added: List<Pair<ServerEndpoint, AddressSource>> = emptyList(),
)

/** Serialises [PcCredentials] with the token sealed by [cipher] (AAD = pc id + device id). */
class CredentialCodec(private val cipher: TokenCipher) {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(credentials: PcCredentials): String {
        val sealed = cipher.encrypt(credentials.token.value.toByteArray(Charsets.UTF_8), aad(credentials.pcId, credentials.deviceId))
        val stored = StoredPc(
            pcId = credentials.pcId,
            deviceId = credentials.deviceId,
            format = FORMAT,
            addresses = credentials.addresses.map { endpoint ->
                val info = credentials.infoOf(endpoint)
                StoredAddress(endpoint.baseUrl, endpoint.network.wire, info.source.wire, info.lastSuccessAt)
            },
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
        val legacy = legacyAddresses(stored.baseUrls).map { StoredAddress(it.url, it.kind) }
        val view = viewOf(stored.copy(addresses = (stored.addresses + legacy).distinctBy { it.url }))
        if (pin == null || view.addresses.isEmpty()) {
            DecodeResult.Unreadable
        } else {
            val sealed = Base64.getDecoder().decode(stored.tokenSealed)
            val token = cipher.decrypt(sealed, aad(stored.pcId, stored.deviceId)).toString(Charsets.UTF_8)
            DecodeResult.Ok(
                PcCredentials(stored.pcId, stored.deviceId, view.addresses, pin, DeviceToken(token), view.lastGood, view.typed, view.lastGoodByKind, view.addressInfo),
            )
        }
    } catch (e: Exception) {
        if (KeystoreFailures.isPermanent(e)) DecodeResult.Unreadable else DecodeResult.Unavailable
    }

    /**
     * The entry in its current form when it comes from an earlier build: the one T07c `base_urls` entry becomes the
     * only address ([httpsBaseUrl] for a pre-Q49 Tailscale URL), and the typed one, until `GET /v1/addresses` adds the
     * rest; a format-1 entry (T09 to TL1e) becomes format 2 with every address's source [AddressSource.Earlier] and no
     * last success (Q73). Nothing else changes: the pin and the sealed token are kept byte for byte (no Keystore call).
     * Null when nothing changes or the entry does not parse ([decode] then decides).
     */
    fun upgrade(text: String): String? {
        val stored = parse(text) ?: return null
        if (stored.baseUrls.isEmpty() && stored.format >= FORMAT) return null
        val legacy = legacyAddresses(stored.baseUrls).map { StoredAddress(it.url, it.kind) }
        val addresses = (stored.addresses + legacy).distinctBy { it.url }.map { it.copy(source = it.source ?: AddressSource.Earlier.wire) }
        val typed = stored.typed ?: legacy.firstOrNull()?.url
        return encodeStored(stored.copy(format = FORMAT, baseUrls = emptyList(), addresses = addresses, typed = typed))
    }

    /**
     * The entry with what the link learnt: [fresh] (the PC's addresses now; empty when unknown) merged into
     * the stored list ([PcAddresses.merge], keeping the typed and every last-good address) and [lastGood], also
     * recorded as its kind's last good one (D29) and, at [now], as the time it last worked (Q73; moved on at most once an
     * hour per address, so a connect does not rewrite the entry each time). A pairing from a T09
     * build has no typed address; the first last-good address it records stands in for it, so that address stays
     * protected after [lastGood] moves on (D29). An address the user deleted is not brought back by the PC's list nor
     * by a relay that was still on it; only [found] (LAN discovery verified it with the pin, Q65) brings it back. A new
     * address's source is [AddressSource.FoundOnWifi] for [found], else [AddressSource.ServerList]. The token stays
     * sealed as it was. Null when the entry does not parse.
     */
    fun withLink(text: String, fresh: List<ServerEndpoint>, lastGood: ServerEndpoint?, found: ServerEndpoint? = null, now: Long? = null): LinkedEntry? {
        val stored = parse(text) ?: return null
        val deleted = stored.deleted.filter { it != found?.baseUrl }
        val good = lastGood?.takeIf { it.baseUrl !in deleted }
        val before = stored.addresses.mapNotNull { address -> endpointOf(address)?.let { it to address } }
        val known = before.map { it.first }
        val typedUrl = stored.typed ?: stored.lastGood ?: good?.baseUrl
        val typed = (known + listOfNotNull(good)).firstOrNull { it.baseUrl == typedUrl }
        // An entry from before D29 knows only its last good address: that one is its kind's last good one.
        val previous = known.firstOrNull { it.baseUrl == stored.lastGood }?.let { mapOf(it.network.wire to it.baseUrl) }.orEmpty()
        val kinds = previous + stored.lastGoodKinds + listOfNotNull(good).associate { it.network.wire to it.baseUrl }
        val kindGood = (known + listOfNotNull(good)).filter { it.baseUrl in kinds.values }
        val addresses = PcAddresses.merge(fresh.filter { it.baseUrl !in deleted }, known, keep = listOf(typed, good) + kindGood)
        val held = before.associate { (endpoint, address) -> endpoint.baseUrl to address }
        val wire = addresses.map { endpoint ->
            val old = held[endpoint.baseUrl]
            val source = old?.let { it.source ?: AddressSource.Earlier.wire }
                ?: (if (endpoint == found) AddressSource.FoundOnWifi else AddressSource.ServerList).wire
            val before = old?.lastSuccessAt
            val stale = before == null || now == null || before > now || now - before >= LAST_SUCCESS_STEP_MILLIS
            val last = if (endpoint == good && now != null && stale) now else before
            StoredAddress(endpoint.baseUrl, endpoint.network.wire, source, last)
        }
        val lastGoodUrl = if (good != null) good.baseUrl else stored.lastGood.takeIf { lastGood != null }
        val updated = stored.copy(
            format = FORMAT,
            baseUrls = emptyList(),
            addresses = wire,
            lastGood = lastGoodUrl,
            typed = typed?.baseUrl,
            lastGoodKinds = kinds,
            deleted = deleted,
        )
        val added = wire.filter { it.url !in held }.mapNotNull { address -> endpointOf(address)?.let { it to AddressSource.ofWire(address.source) } }
        return linkedOf(updated, added)
    }

    /**
     * The entry without [endpoint], which the user deleted (Q73): it stops being the typed or a last-good address, and
     * it is remembered as deleted (so the PC's list does not bring it back; at most [PcAddresses.MAX_STORED], the oldest
     * go first) only when another address of the same kind stays stored ([remembersDeletion]); otherwise the PC's list
     * may bring it back (a deleted only-Tailscale address could never return, as discovery finds LAN ones only). Null when the
     * entry does not parse, does not hold [endpoint], or [endpoint] is its only usable address (the last one stays).
     */
    fun withoutAddress(text: String, endpoint: ServerEndpoint): LinkedEntry? {
        val stored = parse(text) ?: return null
        val held = stored.addresses.mapNotNull { address -> endpointOf(address)?.let { it to address } }
        if (held.size <= 1 || held.none { it.first == endpoint }) return null
        val url = endpoint.baseUrl
        val updated = stored.copy(
            format = FORMAT,
            addresses = held.filter { it.first != endpoint }.map { it.second },
            lastGood = stored.lastGood?.takeIf { it != url },
            typed = stored.typed?.takeIf { it != url },
            lastGoodKinds = stored.lastGoodKinds.filterValues { it != url },
            deleted = (stored.deleted.filter { it != url } + listOfNotNull(url.takeIf { remembersDeletion(held.map { it.first }, endpoint) })).takeLast(PcAddresses.MAX_STORED),
        )
        return linkedOf(updated, added = emptyList())
    }

    /** Whether deleting [endpoint] from [held] is remembered: another address of its kind (LAN or Tailscale) stays. */
    fun remembersDeletion(held: List<ServerEndpoint>, endpoint: ServerEndpoint): Boolean =
        held.any { it != endpoint && it.network == endpoint.network }

    private fun linkedOf(stored: StoredPc, added: List<Pair<ServerEndpoint, AddressSource>>): LinkedEntry {
        val view = viewOf(stored)
        return LinkedEntry(encodeStored(stored), view.addresses, view.typed, view.lastGoodByKind, view.lastGood, view.addressInfo, added)
    }

    /** What an entry holds, as [PcCredentials] carries it. */
    private class View(
        val addresses: List<ServerEndpoint>,
        val lastGood: ServerEndpoint?,
        val typed: ServerEndpoint?,
        val lastGoodByKind: Map<Network, ServerEndpoint>,
        val addressInfo: Map<ServerEndpoint, AddressInfo>,
    )

    private fun viewOf(stored: StoredPc): View {
        val held = stored.addresses.mapNotNull { address -> endpointOf(address)?.let { it to address } }.distinctBy { it.first }
        val addresses = held.map { it.first }
        val byKind = stored.lastGoodKinds.mapNotNull { (kind, url) -> addresses.firstOrNull { it.baseUrl == url && it.network.wire == kind }?.let { it.network to it } }.toMap()
        // Only what differs from "earlier, never worked" is held ([PcCredentials.infoOf]), so an entry reads back equal.
        val info = held.associate { (endpoint, address) -> endpoint to AddressInfo(AddressSource.ofWire(address.source), address.lastSuccessAt) }
            .filterValues { it != AddressInfo.EARLIER }
        return View(
            addresses = addresses,
            lastGood = addresses.firstOrNull { it.baseUrl == stored.lastGood },
            typed = addresses.firstOrNull { it.baseUrl == stored.typed },
            lastGoodByKind = byKind,
            addressInfo = info,
        )
    }

    private fun endpointOf(address: StoredAddress): ServerEndpoint? = PcAddresses.endpointsOf(listOf(WireAddress(address.url, address.kind))).firstOrNull()

    private fun parse(text: String): StoredPc? = runCatching { json.decodeFromString(StoredPc.serializer(), text) }.getOrNull()

    private fun encodeStored(stored: StoredPc): String = json.encodeToString(StoredPc.serializer(), stored)

    /** A T07c entry's typed address, labelled with the kind its own range gives it. */
    private fun legacyAddresses(baseUrls: List<String>): List<WireAddress> = baseUrls.mapNotNull { url ->
        (ServerAddress.parse(httpsBaseUrl(url)) as? AddressCheck.Ok)?.endpoint?.let(PcAddresses::wireOf)
    }

    private fun aad(pcId: String, deviceId: String): ByteArray = "aristotle-token\u0000$pcId\u0000$deviceId".toByteArray(Charsets.UTF_8)

    private companion object {
        /** The stored format this build writes ([StoredPc.format]). */
        const val FORMAT = 2

        /** An address's last success is moved on at most this often (Q73). */
        const val LAST_SUCCESS_STEP_MILLIS = 60L * 60 * 1000
    }
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
