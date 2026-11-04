package io.github.justcallmesu.aristotle.data

/** What a fingerprint / screen-lock check is for (Q61); the prompt's title says it. */
enum class AuthPurpose { Unlock, TurnOn, TurnOff, Terminal, AlwaysAllow, SecretValue }

/** How a check ended. [NoScreenLock]: the phone has no PIN, pattern or password, so nothing can be checked. */
enum class AuthResult { Passed, Failed, NoScreenLock }

/**
 * A fresh check before a sensitive step (Q61): opening the terminal, "Always allow", sending a sudo password or
 * secret. True to go ahead; false (failed, cancelled, nothing to ask) sends nothing. With the app lock off it is true
 * at once.
 */
fun interface FreshAuth {
    suspend fun confirm(purpose: AuthPurpose): Boolean

    companion object {
        /** No app lock: every step goes ahead (tests, and screens built without one). */
        val None = FreshAuth { true }
    }
}

/** The activity on screen, which can show the system's fingerprint / PIN prompt (BiometricPrompt in the app). */
fun interface AuthHost {
    suspend fun authenticate(purpose: AuthPurpose): AuthResult
}

/**
 * Hands checks to the activity on screen ([attach]) and tells [lock] about them. One check at a time; with no
 * activity attached nothing can be asked, so the answer is no. A check that passes also unlocks the app.
 *
 * If the phone's screen lock was removed while the app lock was on, nothing can be checked any more. Removing it
 * needs the current PIN, so whoever did knows it: the app lock turns itself off ([turnOff]) rather than lock the
 * user out of the app for good.
 *
 * Main thread only.
 */
class AuthBroker(
    private val lock: AppLock,
    private val lockOn: () -> Boolean,
    private val turnOff: () -> Unit,
) : FreshAuth {
    private var host: AuthHost? = null
    private var busy = false

    fun attach(host: AuthHost) {
        this.host = host
    }

    fun detach(host: AuthHost) {
        if (this.host === host) this.host = null
    }

    override suspend fun confirm(purpose: AuthPurpose): Boolean {
        if (!lockOn()) return true
        return when (prove(purpose)) {
            AuthResult.Passed -> true
            AuthResult.Failed -> false
            AuthResult.NoScreenLock -> {
                turnOff()
                lock.unlocked()
                true
            }
        }
    }

    /** Asks whatever the app lock's setting: turning the lock on or off needs a check too. */
    suspend fun prove(purpose: AuthPurpose): AuthResult {
        val current = host ?: return AuthResult.Failed
        if (busy) return AuthResult.Failed
        busy = true
        lock.authStarted()
        try {
            val result = current.authenticate(purpose)
            if (result == AuthResult.Passed) lock.unlocked()
            return result
        } finally {
            busy = false
            lock.authEnded()
        }
    }
}
