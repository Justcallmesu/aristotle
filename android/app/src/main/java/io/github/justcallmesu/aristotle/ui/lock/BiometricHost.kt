package io.github.justcallmesu.aristotle.ui.lock

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import io.github.justcallmesu.aristotle.R
import io.github.justcallmesu.aristotle.data.AuthHost
import io.github.justcallmesu.aristotle.data.AuthPurpose
import io.github.justcallmesu.aristotle.data.AuthResult
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine

/** The phone has a PIN, pattern or password (without one there is nothing to check, Q61). */
fun isScreenLockSet(context: Context): Boolean = context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

/**
 * The system's fingerprint / screen-lock prompt for [activity] (Q61, TS2): a strong biometric or the device PIN,
 * pattern or password. Built in the activity's onCreate, as BiometricPrompt asks; [cancel] when it is destroyed.
 * A wrong fingerprint lets the prompt try again; an error or Cancel ends the check as [AuthResult.Failed].
 */
class BiometricHost(private val activity: FragmentActivity) : AuthHost {
    private var pending: CancellableContinuation<AuthResult>? = null

    private val prompt = BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = finish(AuthResult.Passed)
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = finish(AuthResult.Failed)
        },
    )

    override suspend fun authenticate(purpose: AuthPurpose): AuthResult {
        if (!isScreenLockSet(activity)) return AuthResult.NoScreenLock
        if (pending != null) return AuthResult.Failed
        // A check starts only on a resumed, visible activity. After onSaveInstanceState androidx drops authenticate()
        // silently, and nothing would ever call back (TS2b).
        if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || activity.supportFragmentManager.isStateSaved) {
            return AuthResult.Failed
        }
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(activity.getString(title(purpose)))
            .setAllowedAuthenticators(AUTHENTICATORS)
            .build()
        return suspendCancellableCoroutine { continuation ->
            pending = continuation
            continuation.invokeOnCancellation {
                pending = null
                prompt.cancelAuthentication()
            }
            prompt.authenticate(info)
        }
    }

    /** The activity is going away: a check still up ends as failed (nothing waits for a prompt that is gone). */
    fun cancel() {
        if (pending == null) return
        prompt.cancelAuthentication()
        finish(AuthResult.Failed)
    }

    private fun finish(result: AuthResult) {
        val continuation = pending ?: return
        pending = null
        if (continuation.isActive) continuation.resume(result)
    }

    private fun title(purpose: AuthPurpose): Int = when (purpose) {
        AuthPurpose.Unlock -> R.string.lock_auth_unlock
        AuthPurpose.TurnOn -> R.string.lock_auth_turn_on
        AuthPurpose.TurnOff -> R.string.lock_auth_turn_off
        AuthPurpose.Terminal -> R.string.lock_auth_terminal
        AuthPurpose.AlwaysAllow -> R.string.lock_auth_always
        AuthPurpose.SecretValue -> R.string.lock_auth_secret
    }

    private companion object {
        /**
         * Strong biometrics or the screen lock. Android 10 (API 29, minSdk) cannot combine BIOMETRIC_STRONG with
         * DEVICE_CREDENTIAL (androidx.biometric `BiometricManager.Authenticators` docs); there the screen lock
         * comes with the weak class, the pre-11 "device credential allowed" prompt.
         */
        val AUTHENTICATORS = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) BIOMETRIC_STRONG or DEVICE_CREDENTIAL else BIOMETRIC_WEAK or DEVICE_CREDENTIAL
    }
}
