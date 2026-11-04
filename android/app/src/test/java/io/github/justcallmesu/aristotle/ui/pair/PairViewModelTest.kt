package io.github.justcallmesu.aristotle.ui.pair

import io.github.justcallmesu.aristotle.data.AddressInfo
import io.github.justcallmesu.aristotle.data.AddressSource
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
import io.github.justcallmesu.aristotle.data.PcCredentials
import io.github.justcallmesu.aristotle.data.ProbeResult
import io.github.justcallmesu.aristotle.data.ServerEndpoint
import io.github.justcallmesu.aristotle.data.softwareKey
import java.security.ProviderException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    private val paired = PairResult.Paired("dev_0123456789ab", DeviceToken("t0k3n"), reported)
    private var answer: PairResult = paired
    private var answerFor: (ServerEndpoint) -> PairResult = { answer }
    private val pairing = PairingApi { endpoint, pin, code, name ->
        calls += Call(endpoint, pin.copyOf(), code, name)
        answerFor(endpoint)
    }
    private var probes = 0
    private val probe = CertificateSource {
        probes++
        ProbeResult.Certificate(probedPin.copyOf())
    }
    private val key = softwareKey()

    private fun store(cipher: AesGcmTokenCipher = AesGcmTokenCipher { key }) =
        CredentialStore(MemoryStore(), CredentialCodec(cipher), io = Dispatchers.Unconfined)

    private fun viewModel(credentials: CredentialStore = store()) =
        PairViewModel(credentials, pairing, probe, "POCO X5 Pro 5G", CoroutineScope(Dispatchers.Unconfined), clock = { PAIRED_AT })

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
        assertEquals("https://192.168.1.23:8760", saved.typed?.baseUrl)
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

    // --- T08: pairing by QR code ---

    private val scannedPin = ByteArray(32) { (it * 3 + 1).toByte() }
    private val qrLan = ServerEndpoint("192.168.1.23", 8760, Network.Lan)
    private val qrTailnet = ServerEndpoint("100.74.61.96", 8760, Network.Tailscale)

    private fun qr(code: String = "K7Q2M9XD", pin: ByteArray = scannedPin, urls: List<String> = listOf(qrLan.baseUrl, qrTailnet.baseUrl)) =
        "aristotle://pair?v=1&c=$code&fp=${Fingerprint.format(pin).replace(" ", "")}" + urls.joinToString("") { "&a=$it" }

    private fun saved(credentials: CredentialStore): PcCredentials = (credentials.state.value as CredentialState.Paired).credentials

    @Test
    fun aScannedCodePairsWithItsPinWithoutACompare() {
        val credentials = store()
        val vm = viewModel(credentials)
        vm.onScanResult(ScanOutcome.Scanned(qr()))
        assertEquals(0, probes) // no certificate fetched: the pin came from the laptop's screen
        val call = calls.single()
        assertArrayEquals(scannedPin, call.pin)
        assertEquals(qrLan, call.endpoint)
        assertEquals("K7Q2 M9XD", call.code)
        assertEquals("POCO X5 Pro 5G", call.name)
        val pc = saved(credentials)
        assertEquals(Fingerprint.format(scannedPin), pc.fingerprint)
        assertEquals(qrLan, pc.typed)
        assertEquals(qrLan, pc.lastGood)
        // The address that paired, the PC's reported list, then the rest of the scanned list.
        assertEquals(
            listOf("https://192.168.1.23:8760", "https://192.168.1.61:8760", "https://100.74.61.96:8760"),
            pc.addresses.map { it.baseUrl },
        )
        assertEquals(qrLan.displayAddress, vm.uiState.value.address)
        assertEquals("K7Q2 M9XD", vm.uiState.value.code)
    }

    // Requirement 4: typed = the first scanned address that pairs; the scanned list seeds the stored one.
    @Test
    fun theFirstScannedAddressThatPairsIsTheTypedOne() {
        answerFor = { if (it == qrLan) PairResult.Failed(ConnectionProblem.Unreachable) else PairResult.Paired("dev_0123456789ab", DeviceToken("t0k3n"), emptyList()) }
        val credentials = store()
        viewModel(credentials).onScanResult(ScanOutcome.Scanned(qr()))
        assertEquals(listOf(qrLan, qrTailnet), calls.map { it.endpoint })
        assertTrue(calls.all { it.pin.contentEquals(scannedPin) })
        val pc = saved(credentials)
        assertEquals(qrTailnet, pc.typed)
        assertEquals(qrTailnet, pc.lastGood)
        assertEquals(listOf(qrTailnet, qrLan), pc.addresses)
    }

    @Test
    fun aWrongNetworkOrAnotherCertificateTriesTheNextAddressButARefusedCodeStops() {
        answerFor = { if (it == qrLan) PairResult.Forbidden else PairResult.Failed(ConnectionProblem.CertificateChanged) }
        val vm = viewModel()
        vm.onScanResult(ScanOutcome.Scanned(qr()))
        assertEquals(2, calls.size)
        // Every address failed: the first one's outcome is shown, on the address field (which holds it).
        assertEquals(AddressFieldError.NotAllowed, vm.uiState.value.addressError)
        assertEquals(PairStep.Entry, vm.uiState.value.step)
        assertFalse(vm.uiState.value.busy)

        calls.clear()
        answerFor = { PairResult.InvalidCode }
        val again = viewModel()
        again.onScanResult(ScanOutcome.Scanned(qr()))
        assertEquals(1, calls.size) // a 401 is the code, not the address: no second try with it
        assertEquals(CodeFieldError.Invalid, again.uiState.value.codeError)
        val step = again.uiState.value.step as PairStep.Confirmed
        assertTrue(step.scanned)
        // A fresh code typed in pairs again with the scanned pin over the scanned addresses.
        answerFor = { answer }
        again.onCodeChange("ab12cd34")
        again.onPair()
        assertEquals("AB12 CD34", calls.last().code)
        assertArrayEquals(scannedPin, calls.last().pin)
    }

    // One code path after parsing: the scan reaches the same PairingApi call, with the same arguments, and
    // stores the same pairing as the manual path with the same address, code and (compared) pin.
    @Test
    fun scannedAndManualPathsMakeTheSamePairingCall() {
        val manualStore = store()
        val manual = viewModel(manualStore)
        manual.fill("192.168.1.23")
        manual.onContinue()
        manual.onFingerprintMatches()
        val manualCall = calls.single()
        calls.clear()

        val scannedStore = store()
        viewModel(scannedStore).onScanResult(ScanOutcome.Scanned(qr(pin = probedPin, urls = listOf(qrLan.baseUrl))))
        val scannedCall = calls.single()
        assertEquals(manualCall.endpoint, scannedCall.endpoint)
        assertArrayEquals(manualCall.pin, scannedCall.pin)
        assertEquals(manualCall.code, scannedCall.code)
        assertEquals(manualCall.name, scannedCall.name)
        // Q73: only where the address came from differs: typed by hand, or from the QR code.
        val typedAt = AddressInfo(AddressSource.Typed, PAIRED_AT)
        assertEquals(typedAt, saved(manualStore).infoOf(manualCall.endpoint))
        assertEquals(typedAt.copy(source = AddressSource.Pairing), saved(scannedStore).infoOf(scannedCall.endpoint))
        assertEquals(saved(manualStore).copy(addressInfo = emptyMap()), saved(scannedStore).copy(addressInfo = emptyMap()))
    }

    @Test
    fun aRefusedScanSaysWhyAndPairsNothing() {
        val vm = viewModel()
        val cases = listOf(
            "https://example.com" to ScanError.NotPairing,
            qr().replace("v=1", "v=2") to ScanError.Version,
            qr(code = "k7q2m9xd") to ScanError.Code,
            qr().replace("&a=https://192.168.1.23:8760", "&a=http://192.168.1.23:8760") to ScanError.Address,
            qr() + "&token=x" to ScanError.Malformed,
        )
        for ((text, error) in cases) {
            vm.onScanResult(ScanOutcome.Scanned(text))
            assertEquals(text, error, vm.uiState.value.scanError)
            assertEquals(PairStep.Entry, vm.uiState.value.step)
        }
        assertTrue(calls.isEmpty())
        assertEquals("", vm.uiState.value.address)
        vm.onAddressChange("192.168.1.2")
        assertNull(vm.uiState.value.scanError)
    }

    @Test
    fun noScannerPointsToTheManualFormAndCancelDoesNothing() {
        val vm = viewModel()
        vm.onScanResult(ScanOutcome.Cancelled)
        assertEquals(PairUiState(deviceName = "POCO X5 Pro 5G"), vm.uiState.value)
        vm.onScanResult(ScanOutcome.Unavailable)
        assertEquals(ScanError.Unavailable, vm.uiState.value.scanError)
        // The manual path still works as before.
        vm.fill("192.168.1.23")
        vm.onContinue()
        vm.onFingerprintMatches()
        assertArrayEquals(probedPin, calls.single().pin)
    }
}

private const val PAIRED_AT = 1_700_000_000_000L
