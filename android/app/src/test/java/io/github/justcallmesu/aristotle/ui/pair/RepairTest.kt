package io.github.justcallmesu.aristotle.ui.pair

import io.github.justcallmesu.aristotle.data.AesGcmTokenCipher
import io.github.justcallmesu.aristotle.data.CertificateSource
import io.github.justcallmesu.aristotle.data.CredentialCodec
import io.github.justcallmesu.aristotle.data.CredentialState
import io.github.justcallmesu.aristotle.data.CredentialStore
import io.github.justcallmesu.aristotle.data.DeviceToken
import io.github.justcallmesu.aristotle.data.MemoryStore
import io.github.justcallmesu.aristotle.data.Network
import io.github.justcallmesu.aristotle.data.PairResult
import io.github.justcallmesu.aristotle.data.PairingApi
import io.github.justcallmesu.aristotle.data.PcCredentials
import io.github.justcallmesu.aristotle.data.PcRecords
import io.github.justcallmesu.aristotle.data.ProbeResult
import io.github.justcallmesu.aristotle.data.ProfileSelection
import io.github.justcallmesu.aristotle.data.ServerEndpoint
import io.github.justcallmesu.aristotle.data.SessionPins
import io.github.justcallmesu.aristotle.data.chat.ChatDrafts
import io.github.justcallmesu.aristotle.data.softwareKey
import java.security.ProviderException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** TS1 re-pair from Settings: the old pairing stays stored and active until the new one is saved, then goes. */
class RepairTest {
    private val lan = ServerEndpoint("192.168.1.23", 8760, Network.Lan)
    private val old = PcCredentials(
        pcId = "dev_old000000000",
        deviceId = "dev_old000000000",
        addresses = listOf(lan),
        fingerprint = "7E54 D87B 19C0 A4F2 5B3E 0D96 C1A8 72FE 3E09 B6D4 8A15 F0C3 62D9 4E7A BCA7 EAA7",
        token = DeviceToken("old-token"),
        lastGood = lan,
    )
    private val key = softwareKey()
    private val storage = MemoryStore()
    private var sealFails = false
    private val cipher = AesGcmTokenCipher(
        encryptKey = { if (sealFails) throw ProviderException("keystore busy") else key },
        decryptKey = { key },
    )
    private val credentials = CredentialStore(storage, CredentialCodec(cipher), io = Dispatchers.Unconfined)
    private val settingsStore = MemoryStore()
    private val pinsStore = MemoryStore()
    private val draftsStore = MemoryStore()
    private val selection = ProfileSelection(settingsStore)
    private val pins = SessionPins(pinsStore)
    private val drafts = ChatDrafts(draftsStore)
    private val records = PcRecords(credentials, selection, pins, drafts)

    private var answer: PairResult = PairResult.Paired("dev_new000000000", DeviceToken("new-token"), listOf(lan))

    /** When set, the PC's answer waits for it (a slow `POST /v1/pair`). */
    private var slow: CompletableDeferred<Unit>? = null
    private val pairing = PairingApi { _, _, _, _ ->
        slow?.await()
        answer
    }
    private val probe = CertificateSource { ProbeResult.Certificate(ByteArray(32) { 1 }) }

    init {
        runBlocking { credentials.save(old) }
        selection.follow(old.pcId)
        selection.select("work")
        pins.set(old.pcId, null, "s1", pinned = true)
        drafts.put("s1", "half a thought")
    }

    private fun repair(): PairViewModel {
        val vm = PairViewModel(
            credentials,
            pairing,
            probe,
            "POCO X5 Pro 5G",
            CoroutineScope(Dispatchers.Unconfined),
            savePairing = { paired, wanted -> records.replace(old.pcId, paired, wanted) },
        )
        vm.onAddressChange("192.168.1.23")
        vm.onCodeChange("k7q2m9xd")
        vm.onContinue()
        vm.onFingerprintMatches()
        return vm
    }

    private fun reloaded(): CredentialState {
        val fresh = CredentialStore(storage, CredentialCodec(cipher), io = Dispatchers.Unconfined)
        runBlocking { fresh.load() }
        return fresh.state.value
    }

    private fun assertOldIntact() {
        assertEquals(old.pcId, (credentials.state.value as CredentialState.Paired).credentials.pcId)
        assertEquals(old.pcId, (reloaded() as CredentialState.Paired).credentials.pcId)
        assertEquals("work", settingsStore.get("profile.${old.pcId}"))
        assertEquals(setOf("s1"), pins.of(old.pcId, null))
    }

    @Test
    fun aRefusedCodeLeavesTheOldPairingAsItWas() {
        answer = PairResult.InvalidCode
        val vm = repair()
        assertEquals(CodeFieldError.Invalid, vm.uiState.value.codeError)
        assertOldIntact()
    }

    @Test
    fun aServerErrorLeavesTheOldPairingAsItWas() {
        answer = PairResult.ServerError
        repair()
        assertOldIntact()
    }

    @Test
    fun aFailedSaveLeavesTheOldPairingAsItWas() {
        sealFails = true
        val vm = repair()
        assertEquals(PairMessage.StoreFailed, vm.uiState.value.message)
        assertOldIntact()
        assertNull(storage.get("pc.dev_new000000000"))
    }

    @Test
    fun successReplacesTheOldPairingAndItsSettingsButKeepsDrafts() {
        repair()
        assertEquals("dev_new000000000", (credentials.state.value as CredentialState.Paired).credentials.pcId)
        assertEquals("dev_new000000000", (reloaded() as CredentialState.Paired).credentials.pcId)
        assertNull(storage.get("pc.${old.pcId}"))
        assertNotNull(storage.get("pc.dev_new000000000"))
        assertNull(settingsStore.get("profile.${old.pcId}"))
        assertEquals(emptySet<String>(), pins.of(old.pcId, null))
        assertEquals("half a thought", drafts.get("s1"))
    }

    @Test
    fun backDuringABusyRepairCancelsItAndALateSuccessChangesNothing() {
        slow = CompletableDeferred()
        val vm = repair()
        assertEquals(true, vm.uiState.value.busy)
        vm.cancel()
        assertFalse(vm.uiState.value.busy)
        slow!!.complete(Unit) // the PC accepted after Back
        assertOldIntact()
        assertNull(storage.get("pc.dev_new000000000"))
    }

    @Test
    fun aCancelThatLandsWhileTheSaveRunsWritesNothing() {
        lateinit var vm: PairViewModel
        val racing = PairViewModel(
            credentials,
            pairing,
            probe,
            "POCO X5 Pro 5G",
            CoroutineScope(Dispatchers.Unconfined),
            savePairing = { paired, wanted ->
                vm.cancel() // Back, between the PC's answer and the commit
                records.replace(old.pcId, paired, wanted)
            },
        )
        vm = racing
        racing.onAddressChange("192.168.1.23")
        racing.onCodeChange("k7q2m9xd")
        racing.onContinue()
        racing.onFingerprintMatches()
        assertOldIntact()
        assertNull(storage.get("pc.dev_new000000000"))
    }
}
