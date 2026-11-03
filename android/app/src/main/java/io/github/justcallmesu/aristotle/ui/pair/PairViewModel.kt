package io.github.justcallmesu.aristotle.ui.pair

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.justcallmesu.aristotle.data.AddressCheck
import io.github.justcallmesu.aristotle.data.AddressError
import io.github.justcallmesu.aristotle.data.CertificateProbe
import io.github.justcallmesu.aristotle.data.ConnectionProblem
import io.github.justcallmesu.aristotle.data.CredentialStore
import io.github.justcallmesu.aristotle.data.Fingerprint
import io.github.justcallmesu.aristotle.data.HttpClients
import io.github.justcallmesu.aristotle.data.Network
import io.github.justcallmesu.aristotle.data.PairResult
import io.github.justcallmesu.aristotle.data.PairingCode
import io.github.justcallmesu.aristotle.data.PcCredentials
import io.github.justcallmesu.aristotle.data.ProbeResult
import io.github.justcallmesu.aristotle.data.ServerAddress
import io.github.justcallmesu.aristotle.data.ServerClient
import io.github.justcallmesu.aristotle.data.ServerEndpoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Errors shown on the address field. */
enum class AddressFieldError { Empty, NotAnIpAddress, BadPort, NotPrivate, Unreachable, NotAllowed, NoCertificate }

enum class CodeFieldError { Incomplete, Invalid }

/** Messages shown under the form, not tied to one field. */
sealed interface PairMessage {
    data object FingerprintRejected : PairMessage
    data object CertificateChanged : PairMessage
    data object ServerError : PairMessage
    data class Rejected(val status: Int) : PairMessage
}

sealed interface PairStep {
    data object Entry : PairStep

    /** LAN: the user compares [fingerprint] with what `aristotle-server pair` printed. */
    data class Compare(val endpoint: ServerEndpoint, val fingerprint: ByteArray) : PairStep {
        override fun equals(other: Any?) =
            other is Compare && other.endpoint == endpoint && other.fingerprint.contentEquals(fingerprint)
        override fun hashCode() = 31 * endpoint.hashCode() + fingerprint.contentHashCode()
    }

    /** Tailscale: no certificate; one line explains why, then Pair. */
    data class Tailnet(val endpoint: ServerEndpoint) : PairStep

    /** Fingerprint confirmed (or tailnet); stays after a 401 so only the code needs fixing. */
    data class Confirmed(val endpoint: ServerEndpoint, val fingerprint: ByteArray?) : PairStep {
        override fun equals(other: Any?) = other is Confirmed && other.endpoint == endpoint &&
            (other.fingerprint?.contentEquals(fingerprint) ?: (fingerprint == null))
        override fun hashCode() = 31 * endpoint.hashCode() + (fingerprint?.contentHashCode() ?: 0)
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
    val message: PairMessage? = null,
)

class PairViewModel(
    private val credentials: CredentialStore,
    private val httpClients: HttpClients,
    private val probe: CertificateProbe,
    defaultDeviceName: String,
) : ViewModel() {
    private val state = MutableStateFlow(PairUiState(deviceName = defaultDeviceName.take(MAX_NAME)))
    val uiState: StateFlow<PairUiState> = state.asStateFlow()

    fun onAddressChange(value: String) = state.update {
        it.copy(address = value, addressError = null, message = null, step = PairStep.Entry)
    }

    fun onCodeChange(value: String) = state.update { it.copy(code = PairingCode.grouped(value), codeError = null) }

    fun onDeviceNameChange(value: String) = state.update { it.copy(deviceName = value.take(MAX_NAME)) }

    /** Validates the form, then fetches the certificate (LAN) or explains Tailscale. */
    fun onContinue() {
        val current = state.value
        if (current.busy) return
        val endpoint = validEndpoint(current) ?: return
        if (endpoint.network == Network.Tailscale) {
            state.update { it.copy(step = PairStep.Tailnet(endpoint), message = null) }
            return
        }
        state.update { it.copy(busy = true, message = null) }
        viewModelScope.launch { showFingerprint(endpoint, probe.fetch(endpoint)) }
    }

    fun onFingerprintMatches() {
        val step = state.value.step as? PairStep.Compare ?: return
        pair(PairStep.Confirmed(step.endpoint, step.fingerprint))
    }

    fun onFingerprintMismatch() = state.update { it.copy(step = PairStep.Entry, message = PairMessage.FingerprintRejected) }

    /** Pair on a Tailscale address, or try again after a refused code. */
    fun onPair() {
        when (val step = state.value.step) {
            is PairStep.Tailnet -> pair(PairStep.Confirmed(step.endpoint, null))
            is PairStep.Confirmed -> pair(step)
            else -> Unit
        }
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
        state.update { it.copy(step = step, busy = true, codeError = null, message = null) }
        val form = state.value
        viewModelScope.launch {
            val client = ServerClient(httpClients.forEndpoint(step.endpoint, step.fingerprint), step.endpoint.baseUrl)
            onPairResult(step, client.pair(form.code, form.deviceName))
        }
    }

    private fun onPairResult(step: PairStep.Confirmed, result: PairResult) {
        when (result) {
            is PairResult.Paired -> credentials.save(
                PcCredentials(
                    pcId = result.deviceId,
                    deviceId = result.deviceId,
                    baseUrls = listOf(step.endpoint.baseUrl),
                    fingerprint = step.fingerprint?.let(Fingerprint::format),
                    token = result.token,
                ),
            )
            PairResult.InvalidCode -> state.update { it.copy(busy = false, codeError = CodeFieldError.Invalid) }
            PairResult.Forbidden -> backToEntry(addressError = AddressFieldError.NotAllowed)
            PairResult.ServerError -> state.update { it.copy(busy = false, message = PairMessage.ServerError) }
            is PairResult.Rejected -> state.update { it.copy(busy = false, message = PairMessage.Rejected(result.status)) }
            is PairResult.Failed -> when (result.problem) {
                ConnectionProblem.CertificateChanged -> backToEntry(message = PairMessage.CertificateChanged)
                ConnectionProblem.Unreachable, ConnectionProblem.CleartextRefused ->
                    backToEntry(addressError = AddressFieldError.Unreachable)
            }
        }
    }

    private fun backToEntry(addressError: AddressFieldError? = null, message: PairMessage? = null) = state.update {
        it.copy(busy = false, step = PairStep.Entry, addressError = addressError, message = message)
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
