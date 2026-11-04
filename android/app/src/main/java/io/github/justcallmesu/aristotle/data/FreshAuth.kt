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
 * activity attached nothing can be asked, so the answer is no. A check that passes also unlocks the app. A check
 * that never calls back stops blocking the next one after [AppLock.CHECK_CAP_MILLIS] ([AppLock.checkRunning]), so
 * Unlock always works again.
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
    // Every activity that is attached, the most recent last: a second window that closes leaves the first one asking.
    private val hosts = ArrayList<AuthHost>()
    /** The running check's id ([AppLock.authStarted]); a check given up at the cap that ends late leaves a newer one's. */
    private var busy: Long? = null

    /** The activity came to the front (onResume); it becomes the one that asks. */
    fun attach(host: AuthHost) {
        hosts.remove(host)
        hosts.add(host)
    }

    /** Only [host] goes; another activity's attachment stays. */
    fun detach(host: AuthHost) {
        hosts.remove(host)
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
        val current = hosts.lastOrNull() ?: return AuthResult.Failed
        if (busy != null && lock.checkRunning()) return AuthResult.Failed
        val check = lock.authStarted()
        busy = check
        var passed = false
        try {
            val result = current.authenticate(purpose)
            passed = result == AuthResult.Passed
            if (passed) lock.unlocked()
            return result
        } finally {
            if (busy == check) busy = null
            lock.authEnded(check, passed)
        }
    }
}
