package io.github.justcallmesu.aristotle.ui.chat

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.compose.elements.MarkdownTableBasicText
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.markdownAnnotator
import com.mikepenz.markdown.model.markdownDimens
import com.mikepenz.markdown.model.markdownInlineContent
import com.mikepenz.markdown.model.markdownPadding
import com.mikepenz.markdown.model.rememberMarkdownState
import com.mikepenz.markdown.utils.getUnescapedTextInNode
import io.github.justcallmesu.aristotle.R
import io.github.justcallmesu.aristotle.ui.theme.AristotleTextStyles
import io.github.justcallmesu.aristotle.ui.theme.Sizes
import io.github.justcallmesu.aristotle.ui.theme.Spacing
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMTokenTypes

private const val CURSOR_ID = "aristotle.cursor"
private val CURSOR_WIDTH = 8.sp
private val CURSOR_HEIGHT = 17.sp

/**
 * Code blocks and tables use `shapes.small` (design.md §3: code/output blocks, 12 dp Soft). The renderer's own code
 * background takes a Dp, so this mirrors `small`; composables here draw with the shape itself.
 */
private val CODE_CORNER = 12.dp

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
    // C7: inline code at codeInline (13 sp) on its background, thin-space padded instead of the renderer's spaces.
    val codeSpan = AristotleTextStyles.codeInline.toSpanStyle().copy(background = MaterialTheme.colorScheme.surfaceContainerHigh)
    val cursor = cursorText.place == CursorPlace.Inline
    val annotator = remember(codeSpan, cursor) { chatAnnotator(codeSpan, cursor) }
    Column(modifier = modifier.fillMaxWidth().semantics { description?.let { contentDescription = it } }) {
        CompositionLocalProvider(LocalUriHandler provides links) {
            Markdown(
                markdownState = state,
                colors = chatMarkdownColors(),
                typography = chatMarkdownTypography(),
                modifier = Modifier.fillMaxWidth(),
                padding = chatMarkdownPadding(),
                dimens = markdownDimens(codeBackgroundCornerSize = CODE_CORNER, blockQuoteThickness = 0.dp, tableCornerSize = CODE_CORNER),
                annotator = annotator,
                inlineContent = markdownInlineContent(cursorInline),
                components = chatComponents,
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

/**
 * Code spans as [codeSpan] without the renderer's padding spaces ([InlineCode]); with [cursor], swaps the cursor
 * marker in a text run for the inline cursor. Every other node renders as usual.
 */
private fun chatAnnotator(codeSpan: SpanStyle, cursor: Boolean) = markdownAnnotator { content, child ->
    when {
        child.type == MarkdownElementTypes.CODE_SPAN -> {
            withStyle(codeSpan) { append(InlineCode.padded(child.getTextInNode(content).toString())) }
            true
        }
        cursor && child.type == MarkdownTokenTypes.TEXT -> {
            val run = child.getUnescapedTextInNode(content)
            val at = run.indexOf(StreamCursor.MARK)
            if (at < 0) return@markdownAnnotator false
            append(run.substring(0, at))
            appendInlineContent(CURSOR_ID, "▍")
            append(run.substring(at + 1))
            true
        }
        else -> false
    }
}

/** C5: 12 dp between blocks, 4 around a list, 2 between its items, 16 indent; C9: code scrolls with a 16 dp end inset. */
@Composable
private fun chatMarkdownPadding() = markdownPadding(
    block = Spacing.m,
    list = Spacing.xs,
    listItemTop = Spacing.xxs,
    listItemBottom = Spacing.xxs,
    listIndent = Spacing.l,
    codeBlock = PaddingValues(start = Spacing.m, end = Spacing.l, top = Spacing.s, bottom = Spacing.s),
)

/** One instance for the process, like [cursorInline]. */
private val chatComponents = markdownComponents(
    table = { model -> ChatTable(model) },
    codeFence = { model -> CodeWithCopy(model) { MarkdownCodeFence(model.content, model.node, model.typography.code) } },
    codeBlock = { model -> CodeWithCopy(model) { MarkdownCodeBlock(model.content, model.node, model.typography.code) } },
)

/**
 * Q80a: a code block with a Copy icon at its top-right (48 dp touch). Copies the code only ([CodeBlocks.extract]): no fences, no language tag.
 */
@Composable
private fun CodeWithCopy(model: MarkdownComponentModel, block: @Composable () -> Unit) {
    val context = LocalContext.current
    val host = LocalMessageActions.current
    val code = remember(model.content, model.node) { CodeBlocks.extract(model.content.substring(model.node.startOffset, model.node.endOffset)) }
    val toast = stringResource(R.string.msg_copied)
    val label = stringResource(R.string.code_copy)
    Box(modifier = Modifier.fillMaxWidth()) {
        block()
        IconButton(
            onClick = { copyWithFeedback(context, code, host, toast) },
            modifier = Modifier.align(Alignment.TopEnd),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_content_copy),
                contentDescription = label,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(Sizes.chipIcon),
            )
        }
    }
}

/**
 * C6: a table whose cells wrap ([ChatTables]: 120–240 dp columns, sideways scroll when they do not fit); more than
 * three columns show as the table's source in a mono block, which keeps every cell readable.
 */
@Composable
private fun ChatTable(model: MarkdownComponentModel) {
    val node = model.node
    val columns = remember(node) { node.findChildOfType(GFMElementTypes.HEADER)?.children?.count { it.type == GFMTokenTypes.CELL } ?: 0 }
    if (columns == 0 || ChatTables.asMono(columns)) {
        MonoBlock(model.content.substring(node.startOffset, node.endOffset).trimEnd())
        return
    }
    val style = model.typography.table
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val cell = ChatTables.cellWidth(maxWidth, columns)
        val scrolls = cell * columns > maxWidth
        Column(
            modifier = Modifier
                .then(if (scrolls) Modifier.horizontalScroll(rememberScrollState()) else Modifier)
                .background(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.shapes.small),
        ) {
            node.children.forEach { child ->
                when (child.type) {
                    GFMElementTypes.HEADER -> TableRow(model.content, child, cell, style.copy(fontWeight = FontWeight.W600))
                    GFMElementTypes.ROW -> TableRow(model.content, child, cell, style)
                    GFMTokenTypes.TABLE_SEPARATOR -> HorizontalDivider(modifier = Modifier.width(cell * columns), color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@Composable
private fun TableRow(content: String, row: ASTNode, cell: Dp, style: TextStyle) {
    Row(verticalAlignment = Alignment.Top) {
        row.children.filter { it.type == GFMTokenTypes.CELL }.forEach { node ->
            Box(modifier = Modifier.width(cell).padding(horizontal = Spacing.m, vertical = Spacing.s)) {
                MarkdownTableBasicText(content = content, cell = node, style = style, maxLines = Int.MAX_VALUE, overflow = TextOverflow.Clip)
            }
        }
    }
}

/** Mono source on the code background, scrolling sideways with a 16 dp end inset (C9; no fade, §7). */
@Composable
private fun MonoBlock(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.s)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.shapes.small),
    ) {
        Text(
            text = text,
            style = AristotleTextStyles.codeInline,
            color = MaterialTheme.colorScheme.onSurface,
            softWrap = false,
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(start = Spacing.m, end = Spacing.l, top = Spacing.s, bottom = Spacing.s),
        )
    }
}

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
        inlineCode = AristotleTextStyles.codeInline,
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
