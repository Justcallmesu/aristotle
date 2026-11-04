package io.github.justcallmesu.aristotle.ui.lock

import android.view.Window
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import java.util.WeakHashMap

/**
 * Who wants a window FLAG_SECURE right now (TS2b). Reference-counted per owner, so the lock screen, the secret sheet,
 * the terminal and the app lock's own "paused" hold cannot clear each other's flag. Pure; main thread only.
 */
class SecureOwners {
    private val counts = HashMap<Any, Int>()

    val secure: Boolean get() = counts.isNotEmpty()

    /** Returns true when the window just became secure. */
    fun acquire(owner: Any): Boolean {
        val was = secure
        counts[owner] = (counts[owner] ?: 0) + 1
        return !was
    }

    /** Returns true when the window just stopped being secure. An owner that holds nothing changes nothing. */
    fun release(owner: Any): Boolean {
        val n = counts[owner] ?: return false
        if (n <= 1) counts.remove(owner) else counts[owner] = n - 1
        return !secure
    }
}

/** FLAG_SECURE on a [Window], held through [SecureOwners] so several screens can ask at once. */
object WindowSecure {
    private val owners = WeakHashMap<Window, SecureOwners>()

    fun acquire(window: Window, owner: Any) {
        val set = owners.getOrPut(window) { SecureOwners() }
        if (set.acquire(owner)) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    fun release(window: Window, owner: Any) {
        val set = owners[window] ?: return
        if (set.release(owner)) window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}

/** FLAG_SECURE on the activity window while this composable is in the composition. */
@Composable
fun SecureWhileShown() {
    val window = LocalActivity.current?.window
    val owner = remember { Any() }
    DisposableEffect(window) {
        window?.let { WindowSecure.acquire(it, owner) }
        onDispose { window?.let { WindowSecure.release(it, owner) } }
    }
}
