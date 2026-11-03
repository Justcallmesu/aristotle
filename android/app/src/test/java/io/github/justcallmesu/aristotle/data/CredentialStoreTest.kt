package io.github.justcallmesu.aristotle.data

import javax.crypto.AEADBadTagException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

private fun softwareKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

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
}

class CredentialStoreTest {
    private class MemoryStore : KeyValueStore {
        val map = mutableMapOf<String, String>()
        override fun get(key: String) = map[key]
        override fun put(key: String, value: String) {
            map[key] = value
        }
        override fun remove(key: String) {
            map.remove(key)
        }
    }

    private val key = softwareKey()
    private val codec = CredentialCodec(AesGcmTokenCipher { key })
    private val creds = PcCredentials(
        pcId = "dev_0123456789ab",
        deviceId = "dev_0123456789ab",
        baseUrls = listOf("https://192.168.1.23:8760"),
        fingerprint = "7E54 D87B 19C0 A4F2 5B3E 0D96 C1A8 72FE 3E09 B6D4 8A15 F0C3 62D9 4E7A BCA7 EAA7",
        token = DeviceToken("very-secret-token-value"),
    )

    @Test
    fun savedCredentialsSurviveARestartAndTheTokenIsNotStoredInClear() {
        val storage = MemoryStore()
        CredentialStore(storage, codec).save(creds)
        assertFalse(storage.map.values.any { "very-secret-token-value" in it })
        assertEquals(creds, CredentialStore(storage, codec).active.value)
    }

    @Test
    fun wipeActiveForgetsThePc() {
        val storage = MemoryStore()
        val store = CredentialStore(storage, codec)
        store.save(creds)
        store.wipeActive()
        assertNull(store.active.value)
        assertEquals(emptyMap<String, String>(), storage.map)
    }

    @Test
    fun anUnreadableEntryCountsAsNotPairedAndIsRemoved() {
        val storage = MemoryStore()
        CredentialStore(storage, codec).save(creds)
        val lostKey = softwareKey()
        val reopened = CredentialStore(storage, CredentialCodec(AesGcmTokenCipher { lostKey }))
        assertNull(reopened.active.value)
        assertEquals(emptyMap<String, String>(), storage.map)
    }

    @Test
    fun tokenNeverAppearsInToString() {
        assertFalse("very-secret-token-value" in creds.toString())
        assertFalse("very-secret-token-value" in creds.token.toString())
        assertNotNull(codec.decode(codec.encode(creds)))
    }
}
