package io.github.justcallmesu.aristotle.ui.pair

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.justcallmesu.aristotle.PairNotice
import io.github.justcallmesu.aristotle.R
import io.github.justcallmesu.aristotle.ui.components.SubPageTopBar
import io.github.justcallmesu.aristotle.ui.theme.AristotleTextStyles
import io.github.justcallmesu.aristotle.ui.theme.AtkinsonMonoFamily
import io.github.justcallmesu.aristotle.ui.theme.ButtonShape
import io.github.justcallmesu.aristotle.ui.theme.Spacing

class PairActions(
    val onAddressChange: (String) -> Unit,
    val onCodeChange: (String) -> Unit,
    val onDeviceNameChange: (String) -> Unit,
    val onContinue: () -> Unit,
    val onFingerprintMatches: () -> Unit,
    val onFingerprintMismatch: () -> Unit,
    val onPair: () -> Unit,
    val onScan: () -> Unit,
)

/** Pairing again from Settings (TS1): back cancels, and [note] says the current pairing stays until this one works. */
class RepairMode(val note: String, val onCancel: () -> Unit)

@Composable
fun PairScreen(viewModel: PairViewModel, notice: PairNotice?, repair: RepairMode? = null) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scanner: QrScanner = remember(context) { GmsQrScanner(context) }
    PairContent(
        state = state,
        notice = notice,
        actions = PairActions(
            onAddressChange = viewModel::onAddressChange,
            onCodeChange = viewModel::onCodeChange,
            onDeviceNameChange = viewModel::onDeviceNameChange,
            onContinue = viewModel::onContinue,
            onFingerprintMatches = viewModel::onFingerprintMatches,
            onFingerprintMismatch = viewModel::onFingerprintMismatch,
            onPair = viewModel::onPair,
            onScan = { scanner.scan(viewModel::onScanResult) },
        ),
        repair = repair,
    )
}

@Composable
fun PairContent(state: PairUiState, notice: PairNotice?, actions: PairActions, repair: RepairMode? = null) {
    val addressFocus = remember { FocusRequester() }
    // No scanner: the form below is the way in, so the cursor goes there.
    LaunchedEffect(state.scanError) { if (state.scanError == ScanError.Unavailable) addressFocus.requestFocus() }
    repair?.let { BackHandler(onBack = it.onCancel) }
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            repair?.let { SubPageTopBar(stringResource(R.string.repair_title), it.onCancel) }
            PairForm(state, notice, actions, repair, addressFocus)
        }
    }
}

@Composable
private fun PairForm(state: PairUiState, notice: PairNotice?, actions: PairActions, repair: RepairMode?, addressFocus: FocusRequester) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.formGutter, vertical = Spacing.m),
    ) {
        if (repair == null) {
            Text(
                text = stringResource(R.string.app_name),
                style = AristotleTextStyles.wordmarkSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            NoticeBanner(repair.note)
        }
        notice?.let {
            Spacer(Modifier.height(Spacing.l))
            NoticeBanner(stringResource(noticeText(it)))
        }
        Spacer(Modifier.height(Spacing.xl))
        Text(text = stringResource(R.string.pair_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(Spacing.s))
        Text(
            text = withCommand(R.string.pair_lead),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Spacing.xl))
        ScanButton(enabled = !state.busy, onClick = actions.onScan)
        state.scanError?.let { error ->
            Spacer(Modifier.height(Spacing.s))
            Text(
                text = scanErrorText(error),
                style = MaterialTheme.typography.bodyMedium,
                color = if (error == ScanError.Unavailable) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        Spacer(Modifier.height(Spacing.xl))
        OrTypeItIn()
        Spacer(Modifier.height(Spacing.xl))
        AddressField(state, actions.onAddressChange, addressFocus)
        Spacer(Modifier.height(Spacing.l))
        CodeField(state, actions.onCodeChange)
        Spacer(Modifier.height(Spacing.l))
        DeviceNameField(state, actions.onDeviceNameChange)
        Spacer(Modifier.height(Spacing.xl))
        StepArea(state, actions)
        state.message?.let { message ->
            Spacer(Modifier.height(Spacing.l))
            Text(
                text = messageText(message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

@Composable
private fun AddressField(state: PairUiState, onChange: (String) -> Unit, focus: FocusRequester) {
    val error = state.addressError
    OutlinedTextField(
        value = state.address,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth().focusRequester(focus),
        label = { Text(stringResource(R.string.pair_address_label)) },
        supportingText = { Text(if (error == null) stringResource(R.string.pair_address_help) else addressErrorText(error)) },
        isError = error != null,
        trailingIcon = if (error != null) ({ ErrorIcon() }) else null,
        singleLine = true,
        enabled = !state.busy,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Next),
    )
}

@Composable
private fun CodeField(state: PairUiState, onChange: (String) -> Unit) {
    val error = state.codeError
    // Reformatting adds a space after 4 characters; keep the cursor at the end so typing appends.
    OutlinedTextField(
        value = TextFieldValue(state.code, TextRange(state.code.length)),
        onValueChange = { onChange(it.text) },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.pair_code_label)) },
        supportingText = { Text(codeSupportText(error)) },
        isError = error != null,
        trailingIcon = if (error != null) ({ ErrorIcon() }) else null,
        singleLine = true,
        enabled = !state.busy,
        textStyle = AristotleTextStyles.pairingCode,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Characters,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Ascii,
            imeAction = ImeAction.Next,
        ),
    )
    if (error == CodeFieldError.Invalid) {
        Text(
            text = withCommand(R.string.code_error_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = Spacing.l, top = Spacing.xs, end = Spacing.l),
        )
    }
}

@Composable
private fun DeviceNameField(state: PairUiState, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = state.deviceName,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.pair_name_label)) },
        supportingText = { Text(stringResource(R.string.pair_name_help)) },
        singleLine = true,
        enabled = !state.busy,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
    )
}

@Composable
private fun StepArea(state: PairUiState, actions: PairActions) {
    when (val step = state.step) {
        PairStep.Entry -> PrimaryRow(
            label = if (state.busy) R.string.pair_connecting else R.string.pair_continue,
            enabled = !state.busy,
            onClick = actions.onContinue,
        )
        is PairStep.Compare -> FingerprintCard(
            address = step.endpoint.displayAddress,
            fingerprint = step.fingerprint,
            busy = state.busy,
            onMatch = actions.onFingerprintMatches,
            onMismatch = actions.onFingerprintMismatch,
        )
        is PairStep.Confirmed -> {
            if (step.scanned) FingerprintFromQr(step.fingerprint) else FingerprintConfirmed(step.fingerprint)
            Spacer(Modifier.height(Spacing.xl))
            PrimaryRow(
                label = when {
                    state.busy -> R.string.pair_pairing
                    state.codeError == CodeFieldError.Invalid -> R.string.pair_try_again
                    else -> R.string.pair_action
                },
                enabled = !state.busy,
                onClick = actions.onPair,
            )
        }
    }
}

/** design.md: Scan QR is the tonal primary path, above the manual form. */
@Composable
private fun ScanButton(enabled: Boolean, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, enabled = enabled, shape = ButtonShape, modifier = Modifier.fillMaxWidth()) {
        Icon(
            painter = painterResource(R.drawable.ic_qr_code_scanner),
            contentDescription = null,
            modifier = Modifier.size(ButtonDefaults.IconSize),
        )
        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
        Text(stringResource(R.string.pair_scan))
    }
}

/** The divider between the scan path and the manual form. */
@Composable
private fun OrTypeItIn() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
        HorizontalDivider(modifier = Modifier.weight(1f))
        Text(
            text = stringResource(R.string.pair_or_manual),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider(modifier = Modifier.weight(1f))
    }
}

@Composable
private fun PrimaryRow(@StringRes label: Int, enabled: Boolean, onClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Button(onClick = onClick, enabled = enabled, shape = ButtonShape) {
            Text(stringResource(label))
        }
    }
}

@StringRes
private fun noticeText(notice: PairNotice): Int = when (notice) {
    PairNotice.Revoked -> R.string.pair_notice_revoked
    PairNotice.PairAgain -> R.string.pair_notice_pair_again
}

@Composable
private fun NoticeBanner(text: String) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .padding(horizontal = Spacing.l, vertical = Spacing.m)
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

@Composable
private fun ErrorIcon() {
    Icon(painter = painterResource(R.drawable.ic_error), contentDescription = null)
}

/** [res] formatted with the `aristotle-server pair` command, the command set in mono. */
@Composable
private fun withCommand(@StringRes res: Int): AnnotatedString {
    val command = stringResource(R.string.pair_command)
    val text = stringResource(res, command)
    return buildAnnotatedString {
        append(text)
        val start = text.indexOf(command)
        if (start >= 0) addStyle(SpanStyle(fontFamily = AtkinsonMonoFamily), start, start + command.length)
    }
}

@Composable
private fun addressErrorText(error: AddressFieldError): String = stringResource(
    when (error) {
        AddressFieldError.Empty -> R.string.address_error_empty
        AddressFieldError.NotAnIpAddress -> R.string.address_error_not_ip
        AddressFieldError.BadPort -> R.string.address_error_port
        AddressFieldError.NotPrivate -> R.string.address_error_not_private
        AddressFieldError.Unreachable -> R.string.address_error_unreachable
        AddressFieldError.NotAllowed -> R.string.address_error_not_allowed
        AddressFieldError.NoCertificate -> R.string.address_error_no_certificate
    },
)

@Composable
private fun scanErrorText(error: ScanError): String {
    val command = stringResource(R.string.pair_command)
    return when (error) {
        ScanError.Unavailable -> stringResource(R.string.scan_error_unavailable)
        ScanError.NotPairing -> stringResource(R.string.scan_error_not_pairing, command)
        ScanError.Version -> stringResource(R.string.scan_error_version)
        ScanError.Code -> stringResource(R.string.scan_error_code, command)
        ScanError.Fingerprint -> stringResource(R.string.scan_error_fingerprint, command)
        ScanError.Address -> stringResource(R.string.scan_error_address)
        ScanError.Malformed -> stringResource(R.string.scan_error_malformed)
    }
}

@Composable
private fun codeSupportText(error: CodeFieldError?): String = stringResource(
    when (error) {
        null -> R.string.pair_code_help
        CodeFieldError.Incomplete -> R.string.code_error_incomplete
        CodeFieldError.Invalid -> R.string.code_error_invalid
    },
)

@Composable
private fun messageText(message: PairMessage): String = when (message) {
    PairMessage.FingerprintRejected -> stringResource(R.string.pair_message_fp_rejected)
    PairMessage.CertificateChanged -> stringResource(R.string.pair_message_cert_changed)
    PairMessage.ServerError -> stringResource(R.string.pair_message_server_error, stringResource(R.string.pair_command))
    PairMessage.StoreFailed -> stringResource(R.string.pair_message_store_failed, stringResource(R.string.pair_command))
    is PairMessage.Rejected -> stringResource(R.string.pair_message_rejected, message.status)
}
