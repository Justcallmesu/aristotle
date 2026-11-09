package io.github.justcallmesu.aristotle.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import io.github.justcallmesu.aristotle.R
import io.github.justcallmesu.aristotle.data.update.AppUpdateRules
import io.github.justcallmesu.aristotle.data.update.UpdateFailure
import io.github.justcallmesu.aristotle.data.update.UpdateStep
import io.github.justcallmesu.aristotle.data.update.UpdateView
import io.github.justcallmesu.aristotle.ui.theme.ButtonShape
import io.github.justcallmesu.aristotle.ui.theme.Sizes
import io.github.justcallmesu.aristotle.ui.theme.Spacing

/** The one action the About update row offers (Q132). */
enum class UpdateAction { Update, CheckNow, Cancel, Allow, Retry, Confirm, None }

/** Pure rules of the About update row. */
object UpdateRowLogic {
    fun action(view: UpdateView): UpdateAction = when (view.step) {
        UpdateStep.Idle -> if (view.available != null) UpdateAction.Update else UpdateAction.CheckNow
        UpdateStep.NeedsPermission -> UpdateAction.Allow
        is UpdateStep.Downloading -> UpdateAction.Cancel
        UpdateStep.Verifying, UpdateStep.Installing -> UpdateAction.None
        UpdateStep.Confirming -> UpdateAction.Confirm
        is UpdateStep.Failed -> if (view.available != null) UpdateAction.Retry else UpdateAction.CheckNow
    }

    /** The download's fill, 0..1; null outside a download. */
    fun progress(step: UpdateStep): Float? =
        (step as? UpdateStep.Downloading)?.let { if (it.total <= 0) 0f else (it.bytes.toFloat() / it.total).coerceIn(0f, 1f) }
}

/** What the About row's buttons do; [onUpdate] also serves Try again, [onAllow] opens Android's "Install unknown apps", [onConfirm] shows the install confirmation again. */
class UpdateRowActions(val onUpdate: () -> Unit, val onCheck: () -> Unit, val onCancel: () -> Unit, val onAllow: () -> Unit, val onConfirm: () -> Unit)

/**
 * Settings › About's update row (Q132): "Update x.y.z available" with Update, or "Up to date · x.y.z" with Check now;
 * while it runs, the download's progress, the checks and the install, and why it stopped.
 */
@Composable
internal fun UpdateRow(view: UpdateView, ownVersion: String, actions: UpdateRowActions) {
    val locale = LocalLocale.current.platformLocale
    val version = view.available?.version.orEmpty()
    val step = view.step
    val title = when {
        step == UpdateStep.NeedsPermission -> stringResource(R.string.update_permission_title)
        step is UpdateStep.Downloading -> stringResource(R.string.update_downloading, version)
        step == UpdateStep.Verifying -> stringResource(R.string.update_verifying)
        step == UpdateStep.Installing || step == UpdateStep.Confirming -> stringResource(R.string.update_installing, version)
        view.available != null -> stringResource(R.string.update_available, version)
        step is UpdateStep.Failed -> stringResource(R.string.update_stopped)
        view.updatedTo != null -> stringResource(R.string.update_updated, view.updatedTo)
        else -> stringResource(R.string.update_up_to_date, ownVersion)
    }
    val body = when {
        step == UpdateStep.NeedsPermission -> stringResource(R.string.update_permission_body)
        step is UpdateStep.Downloading -> stringResource(
            R.string.update_progress,
            AppUpdateRules.megabytes(step.bytes, locale),
            AppUpdateRules.megabytes(step.total, locale),
        )
        step == UpdateStep.Verifying -> stringResource(R.string.update_verifying_body)
        step == UpdateStep.Installing -> stringResource(R.string.update_installing_body)
        step == UpdateStep.Confirming -> stringResource(R.string.update_confirming_body)
        step is UpdateStep.Failed -> failureText(step.reason)
        view.checking -> stringResource(R.string.update_checking)
        view.checkFailed -> stringResource(R.string.update_check_failed)
        view.available != null -> stringResource(R.string.update_available_body, AppUpdateRules.megabytes(view.available.size, locale))
        else -> stringResource(R.string.update_up_to_date_body)
    }
    val progress = UpdateRowLogic.progress(step)
    ListItem(
        modifier = Modifier.heightIn(min = Sizes.listRowHeight),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface) },
        supportingContent = {
            Column {
                Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (progress != null) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth().padding(top = Spacing.s).height(Sizes.progressLine),
                    )
                }
            }
        },
        trailingContent = { UpdateButton(UpdateRowLogic.action(view), view.checking, actions) },
    )
}

@Composable
private fun UpdateButton(action: UpdateAction, checking: Boolean, actions: UpdateRowActions) {
    val (label, onClick) = when (action) {
        UpdateAction.Update -> R.string.update_button to actions.onUpdate
        UpdateAction.Retry -> R.string.update_retry to actions.onUpdate
        UpdateAction.CheckNow -> R.string.update_check_now to actions.onCheck
        UpdateAction.Cancel -> R.string.update_cancel to actions.onCancel
        UpdateAction.Allow -> R.string.update_allow to actions.onAllow
        UpdateAction.Confirm -> R.string.update_confirm to actions.onConfirm
        UpdateAction.None -> return
    }
    TextButton(onClick = onClick, enabled = !(action == UpdateAction.CheckNow && checking), shape = ButtonShape) { Text(stringResource(label)) }
}

@Composable
private fun failureText(reason: UpdateFailure): String = stringResource(
    when (reason) {
        UpdateFailure.Unreachable -> R.string.update_failed_unreachable
        UpdateFailure.CertificateChanged -> R.string.update_failed_certificate
        UpdateFailure.BadStatus -> R.string.update_failed_status
        UpdateFailure.TooLarge -> R.string.update_failed_too_large
        UpdateFailure.Truncated -> R.string.update_failed_truncated
        UpdateFailure.Storage -> R.string.update_failed_storage
        UpdateFailure.HashMismatch -> R.string.update_failed_checksum
        UpdateFailure.Unreadable -> R.string.update_failed_unreadable
        UpdateFailure.OtherPackage -> R.string.update_failed_other_package
        UpdateFailure.OtherSigner -> R.string.update_failed_other_signer
        UpdateFailure.NotNewer -> R.string.update_failed_not_newer
        UpdateFailure.InstallAborted -> R.string.update_failed_install_aborted
        UpdateFailure.InstallBlocked -> R.string.update_failed_install_blocked
        UpdateFailure.InstallStorage -> R.string.update_failed_install_storage
        UpdateFailure.InstallRefused -> R.string.update_failed_install_refused
        UpdateFailure.InstallFailed -> R.string.update_failed_install
        UpdateFailure.InstallNotFinished -> R.string.update_failed_install_not_finished
        UpdateFailure.WrongVersion -> R.string.update_failed_wrong_version
    },
)
