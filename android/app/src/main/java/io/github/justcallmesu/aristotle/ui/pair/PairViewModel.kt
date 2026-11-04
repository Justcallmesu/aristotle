package io.github.justcallmesu.aristotle.ui.pair

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.justcallmesu.aristotle.data.AddressCheck
import io.github.justcallmesu.aristotle.data.AddressError
import io.github.justcallmesu.aristotle.data.CertificateSource
import io.github.justcallmesu.aristotle.data.ConnectionProblem
import io.github.justcallmesu.aristotle.data.CredentialStore
import io.github.justcallmesu.aristotle.data.Fingerprint
import io.github.justcallmesu.aristotle.data.PairPayload
import io.github.justcallmesu.aristotle.data.PairPayloads
import io.github.justcallmesu.aristotle.data.PairResult
import io.github.justcallmesu.aristotle.data.PairingApi
import io.github.justcallmesu.aristotle.data.PairingCode
import io.github.justcallmesu.aristotle.data.PayloadCheck
import io.github.justcallmesu.aristotle.data.PayloadError
import io.github.justcallmesu.aristotle.data.PcAddresses
import io.github.justcallmesu.aristotle.data.PcCredentials
import io.github.justcallmesu.aristotle.data.ProbeResult
import io.github.justcallmesu.aristotle.data.ServerAddress
import io.github.justcallmesu.aristotle.data.ServerEndpoint
import kotlin.coroutines.cancellation.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Errors shown on the address field. */
enum class AddressFieldError { Empty, NotAnIpAddress, BadPort, NotPrivate, Unreachable, NotAllowed, NoCertificate }

enum class CodeFieldError { Incomplete, Invalid }

/** What the code scanner returned ([QrScanner]). */
sealed interface ScanOutcome {
    data class Scanned(val text: String) : ScanOutcome
    data object Cancelled : ScanOutcome

    /** No Play services, or its scanner module is not installed and could not be. */
    data object Unavailable : ScanOutcome
}

/** Shown under the Scan button: the scanner could not run, or what it read is not a pairing code. */
enum class ScanError { Unavailable, NotPairing, Version, Code, Fingerprint, Address, Malformed }

/** Messages shown under the form, not tied to one field. */
sealed interface PairMessage {
    data object FingerprintRejected : PairMessage
    data object CertificateChanged : PairMessage
    data object ServerError : PairMessage

    /** Paired on the laptop, but the Keystore could not seal the token; the code is spent. */
    data object StoreFailed : PairMessage
    data class Rejected(val status: Int) : PairMessage
}

sealed interface PairStep {
    data object Entry : PairStep

    /** Any address, LAN or Tailscale: the user compares [fingerprint] with what `aristotle-server pair` printed. */
    data class Compare(val endpoint: ServerEndpoint, val fingerprint: ByteArray) : PairStep {
        override fun equals(other: Any?) =
            other is Compare && other.endpoint == endpoint && other.fingerprint.contentEquals(fingerprint)
        override fun hashCode() = 31 * endpoint.hashCode() + fingerprint.contentHashCode()
    }

    /**
     * The pin to pair with: the one the user compared, or the one a scanned QR code carried ([scanned]).
     * [endpoints] are tried in order (one when typed). Stays after a 401 so only the code needs fixing.
     */
    data class Confirmed(val endpoints: List<ServerEndpoint>, val fingerprint: ByteArray, val scanned: Boolean = false) : PairStep {
        override fun equals(other: Any?) = other is Confirmed && other.endpoints == endpoints &&
            other.fingerprint.contentEquals(fingerprint) && other.scanned == scanned
        override fun hashCode() = 31 * (31 * endpoints.hashCode() + fingerprint.contentHashCode()) + scanned.hashCode()
    }
}

data class PairUiState(
    val address: String = "",
    val code: String = "",
    val deviceName: String = "",
    val step: PairStep = PairStep.Entry,
    val busy: Boolean = false,
    val addressError: AddressFieldError? = null,
    val codeError: CodeFieldError? = null,
    val scanError: ScanError? = null,
    val message: PairMessage? = null,
)

/**
 * The pair form. [savePairing] stores a pairing the PC accepted, writing nothing once its second argument says the
 * form was cancelled (returns whether it stored): by default [credentials]' save; a re-pair from Settings (TS1) passes
 * one that replaces the old PC only then, so nothing before it touches the stored pairing.
 */
class PairViewModel(
    credentials: CredentialStore,
    private val pairing: PairingApi,
    private val probe: CertificateSource,
    defaultDeviceName: String,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val savePairing: suspend (PcCredentials, wanted: () -> Boolean) -> Boolean = { paired, wanted -> credentials.save(paired, commitIf = wanted) },
) : ViewModel(scope) {
    private val state = MutableStateFlow(PairUiState(deviceName = defaultDeviceName.take(MAX_NAME)))
    val uiState: StateFlow<PairUiState> = state.asStateFlow()

    /** Set by [cancel]; read by the store under its lock, so a late success after Back writes nothing (TS1b). */
    private val cancelled = AtomicBoolean(false)
    private var work: Job? = null

    /**
     * Back or the arrow on a re-pair (TS1b): the probe or pairing under way stops, and a pairing the PC accepted
     * anyway is not stored, so the old pairing stays as it was.
     */
    fun cancel() {
        cancelled.set(true)
        work?.cancel()
        state.update { it.copy(busy = false) }
    }

    fun onAddressChange(value: String) = state.update {
        it.copy(address = value, addressError = null, scanError = null, message = null, step = PairStep.Entry)
    }

    fun onCodeChange(value: String) = state.update { it.copy(code = PairingCode.grouped(value), codeError = null, scanError = null) }

    fun onDeviceNameChange(value: String) = state.update { it.copy(deviceName = value.take(MAX_NAME)) }

    /** Validates the form, then fetches the certificate to compare (every address, LAN or Tailscale). */
    fun onContinue() {
        val current = state.value
        if (current.busy) return
        val endpoint = validEndpoint(current) ?: return
        state.update { it.copy(busy = true, message = null) }
        work = viewModelScope.launch { showFingerprint(endpoint, probe.fetch(endpoint)) }
    }

    fun onFingerprintMatches() {
        val step = state.value.step as? PairStep.Compare ?: return
        pair(PairStep.Confirmed(listOf(step.endpoint), step.fingerprint))
    }

    /**
     * A scanned QR code (protocol §11.4) fills the form and pairs at once: its fingerprint came from the
     * laptop's screen, so there is nothing to compare. From here on it is the manual path's [pair].
     */
    fun onScanResult(outcome: ScanOutcome) {
        if (state.value.busy) return
        when (outcome) {
            ScanOutcome.Cancelled -> Unit
            ScanOutcome.Unavailable -> state.update { it.copy(scanError = ScanError.Unavailable) }
            is ScanOutcome.Scanned -> when (val check = PairPayloads.parse(outcome.text)) {
                is PayloadCheck.Refused -> state.update { it.copy(scanError = check.error.toScanError()) }
                is PayloadCheck.Ok -> pairScanned(check.payload)
            }
        }
    }

    private fun pairScanned(payload: PairPayload) {
        state.update {
            it.copy(
                address = payload.addresses.first().displayAddress,
                code = PairingCode.grouped(payload.code),
                addressError = null,
                codeError = null,
                scanError = null,
                message = null,
            )
        }
        pair(PairStep.Confirmed(payload.addresses, payload.pin, scanned = true))
    }

    fun onFingerprintMismatch() = state.update { it.copy(step = PairStep.Entry, message = PairMessage.FingerprintRejected) }

    /** Try again after a refused code. */
    fun onPair() {
        val step = state.value.step as? PairStep.Confirmed ?: return
        pair(step)
    }

    private fun validEndpoint(current: PairUiState): ServerEndpoint? {
        val address = ServerAddress.parse(current.address)
        val codeOk = PairingCode.isComplete(current.code)
        state.update {
            it.copy(
                addressError = (address as? AddressCheck.Refused)?.error?.toFieldError(),
                codeError = if (codeOk) null else CodeFieldError.Incomplete,
            )
        }
        return (address as? AddressCheck.Ok)?.endpoint?.takeIf { codeOk }
    }

    private fun showFingerprint(endpoint: ServerEndpoint, result: ProbeResult) = state.update {
        when (result) {
            is ProbeResult.Certificate -> it.copy(busy = false, step = PairStep.Compare(endpoint, result.fingerprint))
            ProbeResult.Unreachable -> it.copy(busy = false, addressError = AddressFieldError.Unreachable)
            ProbeResult.NoCertificate -> it.copy(busy = false, addressError = AddressFieldError.NoCertificate)
        }
    }

    private fun pair(step: PairStep.Confirmed) {
        if (state.value.busy) return
        if (!PairingCode.isComplete(state.value.code)) {
            state.update { it.copy(step = step, codeError = CodeFieldError.Incomplete) }
            return
        }
        state.update { it.copy(step = step, busy = true, codeError = null, scanError = null, message = null) }
        val form = state.value
        work = viewModelScope.launch {
            val (endpoint, result) = pairFirst(step, form)
            onPairResult(step, endpoint, result)
        }
    }

    /**
     * `POST /v1/pair` at each of [step]'s addresses in order, pinned to its fingerprint (the compared or
     * scanned one, never a fresh probe), until one answers. A wrong network (403), no connection or another
     * certificate at that address tries the next; nothing was redeemed there. Else the first address's outcome.
     */
    private suspend fun pairFirst(step: PairStep.Confirmed, form: PairUiState): Pair<ServerEndpoint, PairResult> {
        var first: Pair<ServerEndpoint, PairResult>? = null
        for (endpoint in step.endpoints) {
            val result = pairing.pair(endpoint, step.fingerprint, form.code, form.deviceName)
            if (result !is PairResult.Failed && result != PairResult.Forbidden) return endpoint to result
            if (first == null) first = endpoint to result
        }
        return checkNotNull(first) { "no address to pair with" }
    }

    private suspend fun onPairResult(step: PairStep.Confirmed, endpoint: ServerEndpoint, result: PairResult) {
        when (result) {
            // The address that paired first (it just worked, T09), then every address the PC reported, then
            // the rest of a scanned list.
            is PairResult.Paired -> store(
                PcCredentials(
                    pcId = result.deviceId,
                    deviceId = result.deviceId,
                    addresses = PcAddresses.merge(listOf(endpoint) + result.addresses, step.endpoints, keep = listOf(endpoint)),
                    fingerprint = Fingerprint.format(step.fingerprint),
                    token = result.token,
                    lastGood = endpoint,
                    typed = endpoint,
                ),
            )
            PairResult.InvalidCode -> state.update { it.copy(busy = false, codeError = CodeFieldError.Invalid) }
            PairResult.Forbidden -> backToEntry(addressError = AddressFieldError.NotAllowed)
            PairResult.ServerError -> state.update { it.copy(busy = false, message = PairMessage.ServerError) }
            is PairResult.Rejected -> state.update { it.copy(busy = false, message = PairMessage.Rejected(result.status)) }
            is PairResult.Failed -> when (result.problem) {
                ConnectionProblem.CertificateChanged -> backToEntry(message = PairMessage.CertificateChanged)
                ConnectionProblem.Unreachable -> backToEntry(addressError = AddressFieldError.Unreachable)
            }
        }
    }

    /** On success the app leaves this screen (the store's state turns Paired); the form stays busy until then. */
    private suspend fun store(paired: PcCredentials) {
        if (cancelled.get()) return
        try {
            savePairing(paired) { !cancelled.get() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Keystore failures arrive checked (GeneralSecurityException) and unchecked (ProviderException).
            backToEntry(message = PairMessage.StoreFailed)
        }
    }

    private fun backToEntry(addressError: AddressFieldError? = null, message: PairMessage? = null) = state.update {
        it.copy(busy = false, step = PairStep.Entry, addressError = addressError, message = message)
    }

    private fun PayloadError.toScanError(): ScanError = when (this) {
        PayloadError.NotPairing -> ScanError.NotPairing
        PayloadError.Version -> ScanError.Version
        PayloadError.Code -> ScanError.Code
        PayloadError.Fingerprint -> ScanError.Fingerprint
        PayloadError.Address -> ScanError.Address
        PayloadError.Malformed -> ScanError.Malformed
    }

    private fun AddressError.toFieldError(): AddressFieldError = when (this) {
        AddressError.Empty -> AddressFieldError.Empty
        AddressError.NotAnIpAddress -> AddressFieldError.NotAnIpAddress
        AddressError.BadPort -> AddressFieldError.BadPort
        AddressError.NotPrivate -> AddressFieldError.NotPrivate
    }

    private companion object {
        const val MAX_NAME = 64
    }
}
