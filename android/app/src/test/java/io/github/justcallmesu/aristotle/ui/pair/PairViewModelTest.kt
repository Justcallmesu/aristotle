package io.github.justcallmesu.aristotle.ui.pair

import io.github.justcallmesu.aristotle.data.AesGcmTokenCipher
import io.github.justcallmesu.aristotle.data.CertificateSource
import io.github.justcallmesu.aristotle.data.ConnectionProblem
import io.github.justcallmesu.aristotle.data.CredentialCodec
import io.github.justcallmesu.aristotle.data.CredentialState
import io.github.justcallmesu.aristotle.data.CredentialStore
import io.github.justcallmesu.aristotle.data.DeviceToken
import io.github.justcallmesu.aristotle.data.Fingerprint
import io.github.justcallmesu.aristotle.data.MemoryStore
import io.github.justcallmesu.aristotle.data.Network
import io.github.justcallmesu.aristotle.data.PairResult
import io.github.justcallmesu.aristotle.data.PairingApi
import io.github.justcallmesu.aristotle.data.ProbeResult
import io.github.justcallmesu.aristotle.data.ServerEndpoint
import io.github.justcallmesu.aristotle.data.softwareKey
import java.security.ProviderException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pair screen's flow with fake probe and server; everything runs inline (Unconfined). */
class PairViewModelTest {
    private val probedPin = ByteArray(32) { (it * 7).toByte() }

    private class Call(val endpoint: ServerEndpoint, val pin: ByteArray, val code: String, val name: String)

    private val calls = mutableListOf<Call>()
    private val reported = listOf(
        ServerEndpoint("192.168.1.61", 8760, Network.Lan),
        ServerEndpoint("192.168.1.23", 8760, Network.Lan),
        ServerEndpoint("100.74.61.96", 8760, Network.Tailscale),
    )
    private var answer: PairResult = PairResult.Paired("dev_0123456789ab", DeviceToken("t0k3n"), reported)
    private val pairing = PairingApi { endpoint, pin, code, name ->
        calls += Call(endpoint, pin.copyOf(), code, name)
        answer
    }
    private val probe = CertificateSource { ProbeResult.Certificate(probedPin.copyOf()) }
    private val key = softwareKey()

    private fun store(cipher: AesGcmTokenCipher = AesGcmTokenCipher { key }) =
        CredentialStore(MemoryStore(), CredentialCodec(cipher), io = Dispatchers.Unconfined)

    private fun viewModel(credentials: CredentialStore = store()) =
        PairViewModel(credentials, pairing, probe, "POCO X5 Pro 5G", CoroutineScope(Dispatchers.Unconfined))

    private fun PairViewModel.fill(address: String) {
        onAddressChange(address)
        onCodeChange("k7q2m9xd")
    }

    @Test
    fun pairingUsesThePinTheUserCompared() {
        val credentials = store()
        val vm = viewModel(credentials)
        vm.fill("192.168.1.23")
        vm.onContinue()
        val compare = vm.uiState.value.step as PairStep.Compare
        assertArrayEquals(probedPin, compare.fingerprint)
        vm.onFingerprintMatches()

        val call = calls.single()
        assertArrayEquals(probedPin, call.pin)
        assertEquals("192.168.1.23", call.endpoint.host)
        assertEquals("K7Q2 M9XD", call.code)
        assertEquals("POCO X5 Pro 5G", call.name)
        val saved = (credentials.state.value as CredentialState.Paired).credentials
        assertEquals(Fingerprint.format(probedPin), saved.fingerprint)
        // T09: the typed address first (it just worked), then every other address the PC reported.
        assertEquals(
            listOf("https://192.168.1.23:8760", "https://192.168.1.61:8760", "https://100.74.61.96:8760"),
            saved.addresses.map { it.baseUrl },
        )
        assertEquals("https://192.168.1.23:8760", saved.lastGood?.baseUrl)
    }

    @Test
    fun editingTheAddressAfterTheCompareDropsThatPin() {
        val vm = viewModel()
        vm.fill("192.168.1.23")
        vm.onContinue()
        vm.onAddressChange("192.168.1.24")
        vm.onFingerprintMatches()
        assertEquals(PairStep.Entry, vm.uiState.value.step)
        assertTrue(calls.isEmpty())
    }

    // Q49: a Tailscale address gets the same fingerprint compare as the LAN, and its pin is stored.
    @Test
    fun aTailnetPairingComparesAndStoresThePin() {
        val credentials = store()
        val vm = viewModel(credentials)
        vm.fill("100.101.102.103")
        vm.onContinue()
        val compare = vm.uiState.value.step as PairStep.Compare
        assertEquals("100.101.102.103", compare.endpoint.host)
        vm.onPair() // nothing to pair before the user compared
        assertTrue(calls.isEmpty())
        vm.onFingerprintMatches()
        assertArrayEquals(probedPin, calls.single().pin)
        val saved = (credentials.state.value as CredentialState.Paired).credentials
        assertEquals("https://100.101.102.103:8760", saved.addresses.first().baseUrl)
        assertEquals(Fingerprint.format(probedPin), saved.fingerprint)
    }

    @Test
    fun anUnreachableTailnetAddressIsShownOnTheAddress() {
        answer = PairResult.Failed(ConnectionProblem.Unreachable)
        val vm = viewModel()
        vm.fill("100.101.102.103")
        vm.onContinue()
        vm.onFingerprintMatches()
        assertEquals(AddressFieldError.Unreachable, vm.uiState.value.addressError)
        assertFalse(vm.uiState.value.busy)
    }

    @Test
    fun aKeystoreFailureWhileSavingIsAMessageNotACrash() {
        val credentials = store(AesGcmTokenCipher { throw ProviderException("keystore2 down") })
        val vm = viewModel(credentials)
        vm.fill("100.101.102.103")
        vm.onContinue()
        vm.onFingerprintMatches()
        assertEquals(PairMessage.StoreFailed, vm.uiState.value.message)
        assertFalse(vm.uiState.value.busy)
        assertEquals(CredentialState.Loading, credentials.state.value)
    }
}
