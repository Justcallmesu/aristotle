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
    private val lan = ServerEndpoint("192.168.1.23", 8760, Network.Lan)
    private val tailnet = ServerEndpoint("100.101.102.103", 8760, Network.Tailscale)
    private val creds = PcCredentials(
        pcId = "dev_0123456789ab",
        deviceId = "dev_0123456789ab",
        addresses = listOf(lan, tailnet),
        fingerprint = "7E54 D87B 19C0 A4F2 5B3E 0D96 C1A8 72FE 3E09 B6D4 8A15 F0C3 62D9 4E7A BCA7 EAA7",
        token = DeviceToken("very-secret-token-value"),
        lastGood = tailnet,
        typed = lan,
    )

    private val events = mutableListOf<ConnectionEvent>()
    private var clockNow = NOW
    private val lan2 = ServerEndpoint("192.168.1.45", 8760, Network.Lan)

    private fun store(storage: KeyValueStore, codec: CredentialCodec = this.codec) =
        CredentialStore(storage, codec, io = Dispatchers.Unconfined, events = { events += it }, clock = { clockNow })

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

    private fun entryOf(storage: MemoryStore) = Json.parseToJsonElement(storage.map.getValue(ENTRY)).jsonObject

    /** The entry an earlier build stored: one `base_urls` entry with [baseUrl] and, when given, [fingerprint]. */
    private fun legacyStorage(baseUrl: String, fingerprint: String?): MemoryStore {
        val storage = savedStorage()
        val entry = entryOf(storage)
        val legacy = JsonObject(
            entry.filterKeys { it !in setOf("fingerprint", "addresses", "last_good", "typed") } +
                ("base_urls" to JsonArray(listOf(JsonPrimitive(baseUrl)))) +
                (fingerprint?.let { mapOf("fingerprint" to JsonPrimitive(it)) } ?: emptyMap()),
        )
        storage.map[ENTRY] = legacy.toString()
        return storage
    }

    // T09 migration: a T07c entry's one URL becomes the only address (kind from its range); the pin and the
    // sealed token stay byte for byte; the entry is rewritten in the new form.
    @Test
    fun aT07cEntryKeepsItsOneAddressUntilTheListArrives() {
        val storage = legacyStorage("https://192.168.1.23:8760", creds.fingerprint)
        val sealedBefore = entryOf(storage)["token_sealed"]
        val expected = creds.copy(addresses = listOf(lan), lastGood = null)
        assertEquals(CredentialState.Paired(expected), loaded(storage))
        val stored = entryOf(storage)
        assertNull(stored["base_urls"])
        assertEquals("""[{"url":"https://192.168.1.23:8760","kind":"lan","source":"earlier"}]""", stored["addresses"].toString())
        assertEquals(sealedBefore, stored["token_sealed"])
        assertEquals(CredentialState.Paired(expected), loaded(storage))
    }

    // Q49: an http:// base URL from an earlier build becomes https:// on the same host and port; the pin stays.
    @Test
    fun aStoredHttpUrlIsMigratedToHttpsAndKeepsThePin() {
        val storage = legacyStorage("http://100.101.102.103:8760", creds.fingerprint)
        val expected = creds.copy(addresses = listOf(tailnet), lastGood = null, typed = tailnet)
        assertEquals(CredentialState.Paired(expected), loaded(storage))
        assertEquals("""[{"url":"https://100.101.102.103:8760","kind":"tailscale","source":"earlier"}]""", entryOf(storage)["addresses"].toString())
    }

    // Q49 + D28: a pairing without a pin (Tailscale, earlier build) must pair again; it is removed without
    // being rewritten first, and load() reports the removal so the pair screen can say so.
    @Test
    fun aStoredPairingWithoutAPinMustPairAgain() {
        val storage = legacyStorage("http://100.101.102.103:8760", fingerprint = null)
        val writes = mutableListOf<String>()
        val watched = object : KeyValueStore by storage {
            override fun put(key: String, value: String) {
                writes += key
                storage.put(key, value)
            }
        }
        val store = store(watched)
        assertTrue(runBlocking { store.load() })
        assertEquals(CredentialState.Unpaired, store.state.value)
        assertEquals(emptyMap<String, String>(), storage.map)
        assertEquals(emptyList<String>(), writes)
        assertFalse(runBlocking { store(MemoryStore()).load() })
    }

    // T09: what the link learnt is stored without a Keystore call; the token stays sealed as it was.
    // T09b: the PC's current list goes first; addresses it does not serve right now stay behind it.
    @Test
    fun rememberMergesTheAddressesAndStoresTheLastGoodOne() = runBlocking {
        val storage = savedStorage()
        val sealedBefore = entryOf(storage)["token_sealed"]
        val noKeystore = CredentialCodec(AesGcmTokenCipher({ error("encrypt") }, { key }))
        val store = store(storage, noKeystore).also { it.load() }
        val wifi = ServerEndpoint("192.168.1.61", 8760, Network.Lan)
        store.remember(creds.pcId, listOf(wifi, tailnet), wifi, null)
        // D29: the last good address of each kind is kept too (tailnet from before, wifi now). Q73: the new one came
        // from the PC's list and worked now.
        val expected = creds.copy(
            addresses = listOf(wifi, tailnet, lan),
            lastGood = wifi,
            lastGoodByKind = mapOf(Network.Lan to wifi, Network.Tailscale to tailnet),
            addressInfo = mapOf(wifi to AddressInfo(AddressSource.ServerList, NOW)),
        )
        assertEquals(CredentialState.Paired(expected), store.state.value)
        assertEquals(sealedBefore, entryOf(storage)["token_sealed"])
        assertEquals(CredentialState.Paired(expected), loaded(storage))
        assertEquals(listOf<ConnectionEvent>(ConnectionEvent.Saved(wifi, AddressSource.ServerList)), events)
        store.remember("dev_other", listOf(wifi), wifi, null) // not stored: ignored
        assertEquals(CredentialState.Paired(expected), store.state.value)
    }

    // T09b: past the cap the address unseen longest goes first; the typed one stays even then.
    @Test
    fun theStoredListIsCappedButKeepsTheTypedAddress() = runBlocking {
        val storage = savedStorage()
        val store = store(storage).also { it.load() }
        val seen = (1..PcAddresses.MAX_STORED).map { ServerEndpoint("10.0.0.$it", 8760, Network.Lan) }
        for (address in seen) store.remember(creds.pcId, listOf(address), address, null)
        val stored = (store.state.value as CredentialState.Paired).credentials
        // The typed address and the last good one of each kind (D29: the tailnet one from before) stay; the LAN
        // addresses unseen longest went.
        assertEquals(seen.reversed().take(PcAddresses.MAX_STORED - 2) + lan + tailnet, stored.addresses)
        assertEquals(CredentialState.Paired(stored), loaded(storage))
    }

    // D29: a pairing from a T09 build has no typed address; its first last-good address stands in for it, so it is
    // still kept after the link moved on to other addresses and the list hit the cap.
    @Test
    fun aT09PairingKeepsItsFirstLastGoodAddressProtected() = runBlocking {
        val t09 = creds.copy(addresses = listOf(lan), lastGood = lan, typed = null)
        val storage = MemoryStore()
        store(storage).save(t09)
        val store = store(storage).also { it.load() }
        val seen = (1..PcAddresses.MAX_STORED + 2).map { ServerEndpoint("10.0.0.$it", 8760, Network.Lan) }
        for (address in seen) store.remember(creds.pcId, listOf(address), address, null)
        val stored = (store.state.value as CredentialState.Paired).credentials
        assertEquals(lan, stored.typed)
        assertTrue(lan in stored.addresses)
        assertTrue(seen.last() in stored.addresses)
        assertEquals(PcAddresses.MAX_STORED, stored.addresses.size)
        assertEquals(CredentialState.Paired(stored), loaded(storage))
    }

    // Q73: an entry stored before the format bump (format 1, as T09-TL1e wrote it) reads with every address
    // "earlier" and no last success; it is rewritten as format 2 without losing anything, the token byte for byte.
    @Test
    fun aFormat1EntryReadsAsEarlierAndIsUpgradedWithoutLoss() {
        val sealed = Json.parseToJsonElement(codec.encode(creds)).jsonObject.getValue("token_sealed").toString()
        val old = """{"pc_id":"dev_0123456789ab","device_id":"dev_0123456789ab",""" +
            """"addresses":[{"url":"https://192.168.1.23:8760","kind":"lan","iface":"wlan0"},{"url":"https://100.101.102.103:8760","kind":"tailscale"}],""" +
            """"last_good":"https://100.101.102.103:8760","typed":"https://192.168.1.23:8760",""" +
            """"last_good_kinds":{"tailscale":"https://100.101.102.103:8760"},"fingerprint":"${creds.fingerprint}","token_sealed":$sealed}"""
        val storage = MemoryStore().apply {
            map[ENTRY] = old
            map["active_pc"] = creds.pcId
        }
        val expected = creds.copy(lastGoodByKind = mapOf(Network.Tailscale to tailnet))
        assertEquals(CredentialState.Paired(expected), loaded(storage))
        assertTrue(expected.addresses.all { expected.infoOf(it) == AddressInfo.EARLIER })
        val upgraded = entryOf(storage)
        assertEquals(JsonPrimitive(2), upgraded["format"])
        assertEquals(
            """[{"url":"https://192.168.1.23:8760","kind":"lan","source":"earlier"},{"url":"https://100.101.102.103:8760","kind":"tailscale","source":"earlier"}]""",
            upgraded["addresses"].toString(),
        )
        assertEquals(Json.parseToJsonElement(sealed), upgraded["token_sealed"])
        assertNull(codec.upgrade(storage.map.getValue(ENTRY))) // already current
        assertEquals(CredentialState.Paired(expected), loaded(storage))
    }

    // Q73: the source and the last success survive a restart.
    @Test
    fun sourcesAndLastSuccessRoundTrip() {
        val withInfo = creds.copy(addressInfo = mapOf(lan to AddressInfo(AddressSource.Typed, 42L), tailnet to AddressInfo(AddressSource.Pairing)))
        val storage = MemoryStore().also { runBlocking { store(it).save(withInfo) } }
        assertEquals(CredentialState.Paired(withInfo), loaded(storage))
    }

    // Q73: delete one address; the last one stays; a deleted one does not come back from the PC's list (nor from a
    // relay that was still on it), only when LAN discovery finds it again, as another LAN address stays. The deletion
    // goes with the entry (unpair).
    @Test
    fun aDeletedAddressStaysGoneUntilFoundOnTheWifi() = runBlocking {
        val twoLan = creds.copy(addresses = listOf(lan, lan2), lastGood = lan2, typed = lan)
        val storage = MemoryStore().also { store(it).save(twoLan) }
        val store = store(storage).also { it.load() }
        assertTrue(store.forgetAddress(twoLan.pcId, lan))
        var now = (store.state.value as CredentialState.Paired).credentials
        assertEquals(listOf(lan2), now.addresses)
        assertNull("the typed address was deleted", now.typed)
        assertEquals(ConnectionEvent.Deleted(lan), events.last())
        assertFalse("the last address stays", store.forgetAddress(twoLan.pcId, lan2))
        assertFalse("not stored", store.forgetAddress(twoLan.pcId, lan))
        assertFalse("another PC", store.forgetAddress("dev_other", lan2))

        store.remember(twoLan.pcId, listOf(lan, lan2), lan2, null) // the PC still lists it; a refresh raced the delete
        now = (store.state.value as CredentialState.Paired).credentials
        assertEquals(listOf(lan2), now.addresses)
        assertEquals(CredentialState.Paired(now), loaded(storage))

        store.remember(twoLan.pcId, listOf(lan), lan, found = lan) // verified on the Wi-Fi and connected
        now = (store.state.value as CredentialState.Paired).credentials
        assertEquals(setOf(lan, lan2), now.addresses.toSet())
        assertEquals(AddressInfo(AddressSource.FoundOnWifi, NOW), now.infoOf(lan))
        assertTrue(store.forgetAddress(twoLan.pcId, lan2))
        store.wipeActive()
        assertEquals(emptyMap<String, String>(), storage.map)
    }

    // MAJOR-1 (TH1): deleting an address that is the last of its kind is not remembered, so the PC's list can bring
    // it back (discovery finds only LAN addresses, so a remembered Tailscale deletion could never be undone).
    @Test
    fun deletingTheOnlyTailscaleAddressIsNotRememberedAndTheListBringsItBack() = runBlocking {
        val storage = savedStorage()
        val store = store(storage).also { it.load() }
        assertTrue(store.forgetAddress(creds.pcId, tailnet))
        var now = (store.state.value as CredentialState.Paired).credentials
        assertEquals(listOf(lan), now.addresses)
        assertFalse(codec.remembersDeletion(listOf(lan, tailnet), tailnet))

        store.remember(creds.pcId, listOf(lan, tailnet), lan, null)
        now = (store.state.value as CredentialState.Paired).credentials
        assertEquals(setOf(lan, tailnet), now.addresses.toSet())
        assertEquals(AddressSource.ServerList, now.infoOf(tailnet).source)
        assertEquals(CredentialState.Paired(now), loaded(storage))
    }

    @Test
    fun deletingOneOfTwoLanAddressesIsRemembered() {
        assertTrue(codec.remembersDeletion(listOf(lan, lan2, tailnet), lan))
        assertFalse(codec.remembersDeletion(listOf(lan, tailnet), lan))
        assertFalse(codec.remembersDeletion(listOf(lan, lan2, tailnet), tailnet))
        val text = codec.encode(creds.copy(addresses = listOf(lan, lan2, tailnet)))
        val linked = codec.withoutAddress(text, lan)!!
        assertEquals(listOf(lan2, tailnet), linked.addresses)
        // The PC lists it again: it is not added back.
        val again = codec.withLink(linked.text, listOf(lan, lan2, tailnet), tailnet, null, NOW)!!
        assertEquals(setOf(lan2, tailnet), again.addresses.toSet())
        assertFalse(lan in again.addresses)
    }

    // TH1 NIT-2: the last success moves on at most once an hour per address, so a connect does not rewrite the entry.
    @Test
    fun lastSuccessIsWrittenAtMostOncePerHourPerAddress() = runBlocking {
        val storage = savedStorage()
        val store = store(storage).also { it.load() }
        store.remember(creds.pcId, listOf(lan, tailnet), tailnet, null)
        val first = storage.map.toMap()
        clockNow = NOW + 59 * 60_000
        store.remember(creds.pcId, listOf(lan, tailnet), tailnet, null)
        assertEquals("no rewrite within the hour", first, storage.map)
        clockNow = NOW + 60 * 60_000
        store.remember(creds.pcId, listOf(lan, tailnet), tailnet, null)
        val now = (store.state.value as CredentialState.Paired).credentials
        assertEquals(NOW + 60 * 60_000, now.infoOf(tailnet).lastSuccessAt)
        assertTrue(first != storage.map)
    }

    @Test
    fun anEntryWithoutAUsableAddressIsRemoved() {
        val storage = legacyStorage("https://8.8.8.8:8760", creds.fingerprint)
        assertEquals(CredentialState.Unpaired, loaded(storage))
        assertEquals(emptyMap<String, String>(), storage.map)
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
private const val ENTRY = "pc.dev_0123456789ab"
private const val NOW = 1_000_000L

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

// D24: on Android 10-11 a key lookup that finds nothing may be a Keystore daemon hiccup, so the first miss
// keeps the pairing (transient) and only a second miss in a row wipes it. Android 12+ trusts the first miss.
class MissingKeyPolicyTest {
    @Test
    fun keystore1ExcusesOneMissUntilTheKeyIsFoundAgain() {
        val policy = MissingKeyPolicy(sdkInt = 29)
        assertFalse(KeystoreFailures.isPermanent(policy.onMissing()))
        assertTrue(KeystoreFailures.isPermanent(policy.onMissing()))
        policy.onFound()
        assertFalse(KeystoreFailures.isPermanent(policy.onMissing()))
        assertFalse(KeystoreFailures.isPermanent(MissingKeyPolicy(sdkInt = 30).onMissing()))
    }

    @Test
    fun keystore2MissIsPermanentAtOnce() {
        assertTrue(KeystoreFailures.isPermanent(MissingKeyPolicy(sdkInt = 31).onMissing()))
        assertTrue(KeystoreFailures.isPermanent(MissingKeyPolicy(sdkInt = 34).onMissing()))
    }

    @Test
    fun aFirstMissOnAndroid11KeepsThePairingForARetry() {
        val key = softwareKey()
        val policy = MissingKeyPolicy(sdkInt = 30)
        val codec = CredentialCodec(AesGcmTokenCipher({ key }, { throw policy.onMissing() }))
        val creds = PcCredentials(
            "dev_1", "dev_1", listOf(ServerEndpoint("192.168.1.23", 8760, Network.Lan)),
            "7E54 D87B 19C0 A4F2 5B3E 0D96 C1A8 72FE 3E09 B6D4 8A15 F0C3 62D9 4E7A BCA7 EAA7", DeviceToken("t"),
        )
        val storage = MemoryStore()
        val store = CredentialStore(storage, codec, io = Dispatchers.Unconfined)
        runBlocking { store.save(creds) }
        assertFalse(runBlocking { store.load() })
        assertEquals(CredentialState.Unavailable, store.state.value)
        assertTrue(storage.map.isNotEmpty())
        assertTrue(runBlocking { store.load() }) // the second miss in a row: the key is really gone
        assertEquals(CredentialState.Unpaired, store.state.value)
    }
}
