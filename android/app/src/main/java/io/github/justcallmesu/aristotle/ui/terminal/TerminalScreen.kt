package io.github.justcallmesu.aristotle.ui.terminal

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.os.PersistableBundle
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.justcallmesu.aristotle.R
import io.github.justcallmesu.aristotle.data.Network
import io.github.justcallmesu.aristotle.data.terminal.TerminalEnd
import io.github.justcallmesu.aristotle.data.terminal.TerminalState
import io.github.justcallmesu.aristotle.ui.theme.AristotleTextStyles
import io.github.justcallmesu.aristotle.ui.theme.AtkinsonMonoFamily
import io.github.justcallmesu.aristotle.ui.theme.ButtonShape
import io.github.justcallmesu.aristotle.ui.theme.LocalDarkTheme
import io.github.justcallmesu.aristotle.ui.theme.Sizes
import io.github.justcallmesu.aristotle.ui.theme.Spacing
import io.github.justcallmesu.aristotle.ui.theme.TerminalPalette

/**
 * The remote terminal (T19b): top bar with the PC's address, connection and Close; tabs; the Termux-rendered
 * terminal; the extra-keys row. FLAG_SECURE while it is on screen (no screenshots, blank recents; T19 red-team M4).
 */
@Composable
fun TerminalScreen(viewModel: TerminalViewModel, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    SecureWindow()
    val modifiers = remember { StickyModifiers() }
    var view by remember { mutableStateOf<RemoteTerminalView?>(null) }
    val current = state.current
    val tabState = current?.state?.collectAsStateWithLifecycle()?.value
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            TerminalTopBar(
                address = state.address?.displayAddress.orEmpty(),
                network = state.address?.network,
                tabState = tabState,
                onBack = onBack,
                onClose = { if (viewModel.closeCurrent()) onBack() },
                onCopy = { current?.visibleText()?.let { copySensitive(view?.context, it) } },
                onPaste = { view?.context?.let(::clipboardText)?.let { current?.paste(it) } },
                canAddTab = state.canAddTab && state.tabs.isNotEmpty(),
                onAddTab = viewModel::addTab,
            )
            if (state.tabs.size > 1) TabsRow(state.tabs.size, state.selected, viewModel::select)
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                TerminalSurface(state.textSizeSp, modifiers, current, viewModel::zoom) { view = it }
                when {
                    current == null -> EndPanel(stringResource(R.string.terminal_unavailable), null, onReopen = null)
                    tabState is TerminalState.Ended -> {
                        val end = tabState.end
                        // Reopen found no terminal to open (link down, or switched off since): say so instead.
                        val body = if (state.unavailable) stringResource(R.string.terminal_unavailable) else endBody(end)
                        EndPanel(endTitle(end), body, onReopen = if (end.canReopen) ({ viewModel.reopen(current) }) else null)
                    }
                }
            }
            ExtraKeysRow(modifiers, onKey = { key -> view?.let { sendExtraKey(it, key) } })
        }
    }
}

private fun sendExtraKey(view: RemoteTerminalView, key: ExtraKey) {
    when (key) {
        is ExtraKey.Special -> view.extraKey(key.keyCode)
        is ExtraKey.Character -> view.extraChar(key.char)
        ExtraKey.Ctrl, ExtraKey.Alt -> Unit
    }
}

/** The Termux view in Compose: font Atkinson Hyperlegible Mono at the zoomed size, palette A light/dark. */
@Composable
private fun TerminalSurface(textSizeSp: Float, modifiers: StickyModifiers, tab: TerminalTab?, onZoom: (Float) -> Unit, onView: (RemoteTerminalView) -> Unit) {
    val dark = LocalDarkTheme.current
    val background = MaterialTheme.colorScheme.surface.toArgb()
    val resolver = LocalFontFamilyResolver.current
    val typeface = remember(resolver) { resolver.resolve(AtkinsonMonoFamily, FontWeight.W400) }.value as? Typeface ?: Typeface.MONOSPACE
    val sizePx = with(LocalDensity.current) { textSizeSp.sp.roundToPx() }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            RemoteTerminalView(context, modifiers, onZoom).also { view ->
                onView(view)
                view.post(view::showKeyboard)
            }
        },
        update = { view ->
            tab?.setPalette(TerminalPalette.of(dark))
            view.setBackgroundColour(background)
            view.setFont(typeface, sizePx)
            view.attach(tab)
        },
        onRelease = { it.attach(null) },
    )
}

@Composable
private fun TerminalTopBar(
    address: String,
    network: Network?,
    tabState: TerminalState?,
    onBack: () -> Unit,
    onClose: () -> Unit,
    onCopy: () -> Unit,
    onPaste: () -> Unit,
    canAddTab: Boolean,
    onAddTab: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = Sizes.topBarHeight).padding(horizontal = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(painter = painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.nav_back))
        }
        Column(modifier = Modifier.weight(1f).padding(start = Spacing.xs)) {
            Text(
                text = stringResource(R.string.terminal_title, address),
                style = AristotleTextStyles.chatTitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = connectionLine(network, tabState),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(onClick = onClose, shape = ButtonShape, modifier = Modifier.heightIn(min = Sizes.minTouchTarget)) {
            Text(stringResource(R.string.terminal_close))
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(painter = painterResource(R.drawable.ic_expand_more), contentDescription = stringResource(R.string.terminal_more))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.terminal_copy_screen)) }, onClick = { menu = false; onCopy() })
                DropdownMenuItem(text = { Text(stringResource(R.string.terminal_paste)) }, onClick = { menu = false; onPaste() })
                if (canAddTab) DropdownMenuItem(text = { Text(stringResource(R.string.terminal_new_tab)) }, onClick = { menu = false; onAddTab() })
            }
        }
    }
}

@Composable
private fun connectionLine(network: Network?, tabState: TerminalState?): String {
    val via = when (network) {
        Network.Tailscale -> stringResource(R.string.terminal_via_tailscale)
        Network.Lan -> stringResource(R.string.terminal_via_wifi)
        null -> null
    }
    val status = stringResource(
        when (tabState) {
            TerminalState.Open -> R.string.terminal_state_open
            is TerminalState.Ended -> R.string.terminal_state_closed
            TerminalState.Connecting, null -> R.string.terminal_state_connecting
        },
    )
    return if (via == null) status else stringResource(R.string.terminal_line, via, status)
}

@Composable
private fun TabsRow(count: Int, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        repeat(count) { index ->
            FilterChip(
                selected = index == selected,
                onClick = { onSelect(index) },
                label = { Text(stringResource(R.string.terminal_tab, index + 1)) },
                shape = MaterialTheme.shapes.small,
            )
        }
    }
}

/** Why the terminal ended, over the bottom of its last screen (design.md banner: Highest surface, onSurface text). */
@Composable
private fun EndPanel(title: String, body: String?, onReopen: (() -> Unit)?) {
    Box(modifier = Modifier.fillMaxSize().padding(Spacing.l), contentAlignment = Alignment.BottomCenter) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                Text(text = title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                if (body != null) Text(text = body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                if (onReopen != null) {
                    Button(onClick = onReopen, shape = ButtonShape, modifier = Modifier.heightIn(min = Sizes.minTouchTarget)) {
                        Text(stringResource(R.string.terminal_reopen))
                    }
                }
            }
        }
    }
}

@Composable
private fun endTitle(end: TerminalEnd): String = when (end) {
    is TerminalEnd.Exited -> end.code?.let { stringResource(R.string.terminal_end_exited, it) } ?: stringResource(R.string.terminal_end_exited_unknown)
    TerminalEnd.Revoked -> stringResource(R.string.terminal_end_revoked)
    TerminalEnd.TurnedOff -> stringResource(R.string.terminal_end_turned_off)
    TerminalEnd.NotGranted -> stringResource(R.string.terminal_end_not_granted)
    TerminalEnd.ConnectionChanged -> stringResource(R.string.terminal_end_connection)
    TerminalEnd.Idle -> stringResource(R.string.terminal_end_idle)
    TerminalEnd.MaxLength -> stringResource(R.string.terminal_end_max)
    TerminalEnd.TooMany -> stringResource(R.string.terminal_end_too_many)
    TerminalEnd.ServerStopping -> stringResource(R.string.terminal_end_server_stopping)
    TerminalEnd.ShellFailed -> stringResource(R.string.terminal_end_shell_failed)
    is TerminalEnd.Refused -> stringResource(R.string.terminal_end_refused)
    TerminalEnd.Unreachable -> stringResource(R.string.terminal_end_unreachable)
    TerminalEnd.ClosedHere -> stringResource(R.string.terminal_end_closed_here)
    is TerminalEnd.Other -> stringResource(R.string.terminal_end_other)
}

@Composable
private fun endBody(end: TerminalEnd): String? = when (end) {
    TerminalEnd.Revoked -> stringResource(R.string.terminal_end_revoked_body)
    TerminalEnd.TurnedOff -> stringResource(R.string.terminal_end_turned_off_body)
    TerminalEnd.NotGranted -> stringResource(R.string.terminal_end_not_granted_body)
    TerminalEnd.ConnectionChanged -> stringResource(R.string.terminal_end_connection_body)
    TerminalEnd.ClosedHere -> stringResource(R.string.terminal_end_closed_here_body)
    is TerminalEnd.Refused -> end.details
    is TerminalEnd.Other -> end.details
    else -> null
}

/** FLAG_SECURE on the activity window while the terminal is on screen (no screenshots, blank recents snapshot). */
@Composable
private fun SecureWindow() {
    val window = LocalActivity.current?.window
    DisposableEffect(window) {
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

/** Copy, only when the user asks: marked sensitive, so Android's clipboard preview does not show it. */
private fun copySensitive(context: Context?, text: String) {
    val clipboard = context?.getSystemService(ClipboardManager::class.java) ?: return
    val clip = ClipData.newPlainText("terminal", text)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    }
    clipboard.setPrimaryClip(clip)
}

private fun clipboardText(context: Context): String? =
    context.getSystemService(ClipboardManager::class.java)?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
