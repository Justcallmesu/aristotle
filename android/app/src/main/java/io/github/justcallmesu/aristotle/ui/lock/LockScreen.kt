package io.github.justcallmesu.aristotle.ui.lock

import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import io.github.justcallmesu.aristotle.R
import io.github.justcallmesu.aristotle.ui.quote.LauncherIcon
import io.github.justcallmesu.aristotle.ui.theme.ButtonShape
import io.github.justcallmesu.aristotle.ui.theme.Spacing

/**
 * The app lock screen (Q61, TS2): palette surface, the app icon (as on the quote screen, the app's one other
 * whole-window moment) and Unlock. It replaces the app's content, which is not composed under it, and the window is
 * FLAG_SECURE while it shows, so neither a screenshot nor the recents preview can show what was behind it. Unlock
 * asks only when tapped: the check never starts on its own.
 */
@Composable
fun LockScreen(onUnlock: () -> Unit) {
    SecureWhileShown()
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Column(
                modifier = Modifier.align(Alignment.Center).padding(horizontal = Spacing.xxxl),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.l),
            ) {
                LauncherIcon()
                Text(
                    text = stringResource(R.string.lock_title),
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    text = stringResource(R.string.lock_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Button(onClick = onUnlock, shape = ButtonShape) { Text(stringResource(R.string.lock_unlock)) }
            }
        }
    }
}

@Composable
private fun SecureWhileShown() {
    val window = LocalActivity.current?.window
    DisposableEffect(window) {
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}
