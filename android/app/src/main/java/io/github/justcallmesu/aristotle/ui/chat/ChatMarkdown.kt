package io.github.justcallmesu.aristotle.ui.chat

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.markdownAnnotator
import com.mikepenz.markdown.model.markdownDimens
import com.mikepenz.markdown.model.markdownInlineContent
import com.mikepenz.markdown.model.rememberMarkdownState
import com.mikepenz.markdown.utils.getUnescapedTextInNode
import io.github.justcallmesu.aristotle.R
import io.github.justcallmesu.aristotle.ui.theme.AristotleTextStyles
import org.intellij.markdown.MarkdownTokenTypes

private const val CURSOR_ID = "aristotle.cursor"
private val CURSOR_WIDTH = 8.sp
private val CURSOR_HEIGHT = 17.sp

/**
 * Assistant text as Markdown (mikepenz multiplatform-markdown-renderer, Material 3), styled from the theme:
 * chat text 15 / 23, code in Atkinson Hyperlegible Mono, no quote rail (design.md §7). While [streaming], the
 * verdigris block cursor sits at the text end ([StreamCursor]). The previous parse stays on screen while the
 * next chunk parses, so a stream does not flicker. A tapped link opens only when it is a web page ([ChatLinks]).
 */
@Composable
fun ChatMarkdown(text: String, streaming: Boolean, modifier: Modifier = Modifier) {
    val cursorText = remember(text, streaming) { StreamCursor.place(text, streaming) }
    val state = rememberMarkdownState(cursorText.content, retainState = true)
    val description = if (streaming) stringResource(R.string.chat_streaming) else null
    val context = LocalContext.current
    val links = remember(context) { WebLinks(context) }
    Column(modifier = modifier.fillMaxWidth().semantics { description?.let { contentDescription = it } }) {
        CompositionLocalProvider(LocalUriHandler provides links) {
            Markdown(
                markdownState = state,
                colors = chatMarkdownColors(),
                typography = chatMarkdownTypography(),
                modifier = Modifier.fillMaxWidth(),
                dimens = markdownDimens(codeBackgroundCornerSize = 6.dp, blockQuoteThickness = 0.dp, tableCornerSize = 6.dp),
                annotator = if (cursorText.place == CursorPlace.Inline) cursorAnnotator else plainAnnotator,
                inlineContent = markdownInlineContent(cursorInline),
            )
        }
        if (cursorText.place == CursorPlace.Below) StreamingCursor()
    }
}

/** Opens http(s) links in a browser (`ACTION_VIEW`, `BROWSABLE`); any other scheme does nothing. */
private class WebLinks(private val context: Context) : UriHandler {
    override fun openUri(uri: String) {
        val target = ChatLinks.webTarget(uri) ?: return
        val intent = Intent(Intent.ACTION_VIEW, target.toUri()).addCategory(Intent.CATEGORY_BROWSABLE)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // No browser: the tap does nothing.
        }
    }
}

/** The cursor block: tertiary = live work (design.md §1); it does not blink (§4). */
@Composable
fun StreamingCursor(modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val width = with(density) { CURSOR_WIDTH.toDp() }
    val height = with(density) { CURSOR_HEIGHT.toDp() }
    Box(modifier.size(width, height).background(MaterialTheme.colorScheme.tertiary, MaterialTheme.shapes.extraSmall))
}

/** One instance for the process: a new map per recomposition would make every text block rebuild its spans. */
private val cursorInline: Map<String, InlineTextContent> = mapOf(
    CURSOR_ID to InlineTextContent(Placeholder(CURSOR_WIDTH, CURSOR_HEIGHT, PlaceholderVerticalAlign.TextCenter)) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.tertiary, MaterialTheme.shapes.extraSmall))
    },
)

/** Swaps the cursor marker in a text run for the inline cursor; every other node renders as usual. */
private val cursorAnnotator = markdownAnnotator { content, child ->
    if (child.type != MarkdownTokenTypes.TEXT) return@markdownAnnotator false
    val run = child.getUnescapedTextInNode(content)
    val at = run.indexOf(StreamCursor.MARK)
    if (at < 0) return@markdownAnnotator false
    append(run.substring(0, at))
    appendInlineContent(CURSOR_ID, "▍")
    append(run.substring(at + 1))
    true
}

private val plainAnnotator = markdownAnnotator()

@Composable
private fun chatMarkdownColors() = markdownColor(
    text = MaterialTheme.colorScheme.onSurface,
    codeBackground = MaterialTheme.colorScheme.surfaceContainerHighest,
    inlineCodeBackground = MaterialTheme.colorScheme.surfaceContainerHigh,
    dividerColor = MaterialTheme.colorScheme.outlineVariant,
    tableBackground = MaterialTheme.colorScheme.surfaceContainerLow,
)

@Composable
private fun chatMarkdownTypography() = with(MaterialTheme.typography) {
    val text = AristotleTextStyles.chatText
    markdownTypography(
        h1 = titleLarge,
        h2 = titleLarge,
        h3 = titleMedium,
        h4 = titleSmall,
        h5 = titleSmall,
        h6 = titleSmall,
        text = text,
        code = AristotleTextStyles.codeInline,
        inlineCode = text.copy(fontFamily = AristotleTextStyles.codeInline.fontFamily),
        quote = text.copy(fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant),
        paragraph = text,
        ordered = text,
        bullet = text,
        list = text,
        // Links stay ink: underline and weight, no accent colour (design.md §1 role rules).
        textLink = TextLinkStyles(style = SpanStyle(fontWeight = FontWeight.W600, textDecoration = TextDecoration.Underline)),
        table = text,
    )
}
