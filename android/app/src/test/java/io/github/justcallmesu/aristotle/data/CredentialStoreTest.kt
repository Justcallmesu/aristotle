package io.github.justcallmesu.aristotle.data

import java.security.KeyStoreException
import java.security.ProviderException
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenCipherTest {
    private val key = softwareKey()
    private val cipher = AesGcmTokenCipher { key }
    private val aad = "pc\u0000dev".toByteArray()

    @Test
    fun roundTripsWithAFreshIvEachTime() {
        val plain = "t0k3n".toByteArray()
        val a = cipher.encrypt(plain, aad)
        val b = cipher.encrypt(plain, aad)
        assertFalse(a.contentEquals(b))
        assertArrayEquals(plain, cipher.decrypt(a, aad))
    }

    @Test
    fun wrongAadOrTamperingFails() {
        val sealed = cipher.encrypt("t0k3n".toByteArray(), aad)
        assertThrows(AEADBadTagException::class.java) { cipher.decrypt(sealed, "other".toByteArray()) }
        val tampered = sealed.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertThrows(AEADBadTagException::class.java) { cipher.decrypt(tampered, aad) }
    }

    @Test
    fun wrongKeyFails() {
        val sealed = cipher.encrypt("t0k3n".toByteArray(), aad)
        val other = softwareKey()
        assertThrows(AEADBadTagException::class.java) { AesGcmTokenCipher { other }.decrypt(sealed, aad) }
    }

    @Test
    fun decryptUsesItsOwnKeyProvider() {
        val sealed = cipher.encrypt("t0k3n".toByteArray(), aad)
        val decryptOnly = AesGcmTokenCipher(encryptKey = { error("encrypt key asked while decrypting") }, decryptKey = { key })
        assertArrayEquals("t0k3n".toByteArray(), decryptOnly.decrypt(sealed, aad))
    }
}

class CredentialStoreTest {
    private val key = softwareKey()
    private val codec = CredentialCodec(AesGcmTokenCipher { key })
    private val creds = PcCredentials(
        pcId = "dev_0123456789ab",
        deviceId = "dev_0123456789ab",
        baseUrls = listOf("https://192.168.1.23:8760"),
        fingerprint = "7E54 D87B 19C0 A4F2 5B3E 0D96 C1A8 72FE 3E09 B6D4 8A15 F0C3 62D9 4E7A BCA7 EAA7",
        token = DeviceToken("very-secret-token-value"),
    )

    private fun store(storage: KeyValueStore, codec: CredentialCodec = this.codec) =
        CredentialStore(storage, codec, io = Dispatchers.Unconfined)

    private fun loaded(storage: KeyValueStore, codec: CredentialCodec = this.codec): CredentialState =
        store(storage, codec).also { runBlocking { it.load() } }.state.value

    /** A key provider whose decrypt side fails the way [failure] says. */
    private fun codecFailingWith(failure: () -> Exception) = CredentialCodec(AesGcmTokenCipher({ key }, { throw failure() }))

    private fun savedStorage() = MemoryStore().also { runBlocking { store(it).save(creds) } }

    @Test
    fun stateIsLoadingUntilTheFirstLoad() {
        assertEquals(CredentialState.Loading, store(MemoryStore()).state.value)
        assertEquals(CredentialState.Unpaired, loaded(MemoryStore()))
    }

    @Test
    fun savedCredentialsSurviveARestartAndTheTokenIsNotStoredInClear() {
        val storage = savedStorage()
        assertFalse(storage.map.values.any { "very-secret-token-value" in it })
        assertEquals(CredentialState.Paired(creds), loaded(storage))
    }

    @Test
    fun wipeActiveForgetsThePc() = runBlocking {
        val storage = savedStorage()
        val store = store(storage)
        store.wipeActive()
        assertEquals(CredentialState.Unpaired, store.state.value)
        assertEquals(emptyMap<String, String>(), storage.map)
    }

    @Test
    fun aLostKeyCountsAsNotPairedAndTheEntryIsRemoved() {
        val storage = savedStorage()
        val lostKey = softwareKey()
        assertEquals(CredentialState.Unpaired, loaded(storage, CredentialCodec(AesGcmTokenCipher { lostKey })))
        assertEquals(emptyMap<String, String>(), storage.map)
    }

    @Test
    fun aMissingKeyOrACorruptEntryIsRemoved() {
        val missing = savedStorage()
        assertEquals(CredentialState.Unpaired, loaded(missing, codecFailingWith { TokenKeyMissingException() }))
        assertEquals(emptyMap<String, String>(), missing.map)

        val corrupt = savedStorage().apply { map["pc.dev_0123456789ab"] = "{not json" }
        assertEquals(CredentialState.Unpaired, loaded(corrupt))
        assertEquals(emptyMap<String, String>(), corrupt.map)
    }

    // D22: a Keystore that is busy right after boot must not cost the user the pairing.
    @Test
    fun aTransientKeystoreFailureKeepsThePairingForARetry() = runBlocking {
        val storage = savedStorage()
        val before = storage.map.toMap()
        for (failure in listOf<() -> Exception>({ KeyStoreException("busy") }, { ProviderException("keystore2 down") })) {
            assertEquals(CredentialState.Unavailable, loaded(storage, codecFailingWith(failure)))
            assertEquals(before, storage.map)
        }
        assertEquals(CredentialState.Paired(creds), loaded(storage))
    }

    @Test
    fun aFailedSaveStoresNothing() {
        val storage = MemoryStore()
        val failing = CredentialCodec(AesGcmTokenCipher { throw ProviderException("keystore2 down") })
        assertThrows(ProviderException::class.java) { runBlocking { store(storage, failing).save(creds) } }
        assertEquals(emptyMap<String, String>(), storage.map)
    }

    /** The entry an earlier build stored: [creds] with [baseUrl] and, when given, [fingerprint]. */
    private fun legacyStorage(baseUrl: String, fingerprint: String?): MemoryStore {
        val storage = savedStorage()
        val entry = Json.parseToJsonElement(storage.map.getValue("pc.dev_0123456789ab")).jsonObject
        val legacy = JsonObject(
            entry.filterKeys { it != "fingerprint" } + ("base_urls" to JsonArray(listOf(JsonPrimitive(baseUrl)))) +
                (fingerprint?.let { mapOf("fingerprint" to JsonPrimitive(it)) } ?: emptyMap()),
        )
        storage.map["pc.dev_0123456789ab"] = legacy.toString()
        return storage
    }

    // Q49: an http:// base URL from an earlier build becomes https:// on the same host and port; the pin stays.
    @Test
    fun aStoredHttpUrlIsMigratedToHttpsAndKeepsThePin() {
        val storage = legacyStorage("http://100.101.102.103:8760", creds.fingerprint)
        val sealedBefore = Json.parseToJsonElement(storage.map.getValue("pc.dev_0123456789ab")).jsonObject["token_sealed"]
        val expected = creds.copy(baseUrls = listOf("https://100.101.102.103:8760"))
        assertEquals(CredentialState.Paired(expected), loaded(storage))
        val stored = Json.parseToJsonElement(storage.map.getValue("pc.dev_0123456789ab")).jsonObject
        assertEquals(JsonArray(listOf(JsonPrimitive("https://100.101.102.103:8760"))), stored["base_urls"])
        assertEquals(sealedBefore, stored["token_sealed"])
        assertEquals(CredentialState.Paired(expected), loaded(storage))
    }

    // Q49: a pairing without a pin (Tailscale, earlier build) cannot be used over HTTPS: pair again.
    @Test
    fun aStoredPairingWithoutAPinMustPairAgain() {
        val storage = legacyStorage("http://100.101.102.103:8760", fingerprint = null)
        assertEquals(CredentialState.Unpaired, loaded(storage))
        assertEquals(emptyMap<String, String>(), storage.map)
    }

    // Q49: one pin per PC, used for its tailnet address as for the LAN.
    @Test
    fun thePcPinAppliesToItsTailnetAddress() {
        val target = PcTarget.of(creds.copy(baseUrls = listOf("https://100.101.102.103:8760")))!!
        assertEquals(Network.Tailscale, target.endpoint.network)
        assertEquals("https://100.101.102.103:8760", target.endpoint.baseUrl)
        assertArrayEquals(Fingerprint.parse(creds.fingerprint), target.pin)
        assertNull(PcTarget.of(creds.copy(fingerprint = "not a pin")))
    }

    @Test
    fun tokenNeverAppearsInToString() {
        assertFalse("very-secret-token-value" in creds.toString())
        assertFalse("very-secret-token-value" in creds.token.toString())
        assertTrue(codec.decode(codec.encode(creds)) is DecodeResult.Ok)
    }
}

// The Keystore daemon case (UnrecoverableKeyException caused by android.security.KeyStoreException) needs
// the real class, which the JVM test android.jar cannot construct; it is covered only by reading the code.
class KeystoreFailuresTest {
    @Test
    fun permanentFailures() {
        assertTrue(KeystoreFailures.isPermanent(AEADBadTagException()))
        assertTrue(KeystoreFailures.isPermanent(TokenKeyMissingException()))
        assertTrue(KeystoreFailures.isPermanent(IllegalArgumentException("malformed ciphertext")))
        assertTrue(KeystoreFailures.isPermanent(UnrecoverableKeyException("key permanently invalidated")))
    }

    @Test
    fun transientFailures() {
        assertFalse(KeystoreFailures.isPermanent(KeyStoreException("busy")))
        assertFalse(KeystoreFailures.isPermanent(ProviderException("keystore2 down")))
        assertFalse(KeystoreFailures.isPermanent(IllegalStateException("not initialised")))
    }
}
