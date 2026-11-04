package io.github.justcallmesu.aristotle.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.justcallmesu.aristotle.R
import io.github.justcallmesu.aristotle.ui.chat.ChatMarkdown
import io.github.justcallmesu.aristotle.ui.components.SubPageTopBar
import io.github.justcallmesu.aristotle.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "What's new" (Q64): the `changelog.md` asset, which the build cuts from the repo's CHANGELOG.md, rendered with
 * the chat's Markdown renderer (read-only: no streaming, no cursor).
 */
@Composable
fun WhatsNewScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val text by produceState<String?>(null) {
        value = withContext(Dispatchers.IO) {
            runCatching { context.assets.open("changelog.md").bufferedReader().use { it.readText() } }.getOrDefault("")
        }
    }
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            SubPageTopBar(stringResource(R.string.settings_whats_new), onBack)
            val loaded = text
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(start = Spacing.xxl, end = Spacing.xxl, top = Spacing.xs, bottom = Spacing.xxl),
            ) {
                when {
                    loaded == null -> Unit
                    loaded.isBlank() -> Text(
                        text = stringResource(R.string.whats_new_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> ChatMarkdown(loaded, streaming = false)
                }
            }
        }
    }
}
