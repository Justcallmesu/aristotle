package io.github.justcallmesu.aristotle.data

import java.util.Base64
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

    /** Null when the entry is unreadable (corrupt, or the Keystore key is gone). */
    fun decode(text: String): PcCredentials? = runCatching {
        val stored = json.decodeFromString(StoredPc.serializer(), text)
        val sealed = Base64.getDecoder().decode(stored.tokenSealed)
        val token = cipher.decrypt(sealed, aad(stored.pcId, stored.deviceId)).toString(Charsets.UTF_8)
        PcCredentials(stored.pcId, stored.deviceId, stored.baseUrls, stored.fingerprint, DeviceToken(token))
    }.getOrNull()

    private fun aad(pcId: String, deviceId: String): ByteArray = "aristotle-token\u0000$pcId\u0000$deviceId".toByteArray(Charsets.UTF_8)
}
