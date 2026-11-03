package io.github.justcallmesu.aristotle.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.justcallmesu.aristotle.AppContainer
import io.github.justcallmesu.aristotle.data.CredentialState
import io.github.justcallmesu.aristotle.data.HttpPairingApi
import io.github.justcallmesu.aristotle.data.PcCredentials
import io.github.justcallmesu.aristotle.ui.chat.ChatScreen
import io.github.justcallmesu.aristotle.ui.chat.ChatViewModel
import io.github.justcallmesu.aristotle.ui.pair.PairScreen
import io.github.justcallmesu.aristotle.ui.pair.PairViewModel
import io.github.justcallmesu.aristotle.ui.sessions.SessionsScreen
import io.github.justcallmesu.aristotle.ui.sessions.SessionsViewModel
import io.github.justcallmesu.aristotle.ui.settings.BatteryGuideScreen
import io.github.justcallmesu.aristotle.ui.settings.NotificationPermissionPrompt
import io.github.justcallmesu.aristotle.ui.settings.SettingsScreen

/** Top-level route: reading -> blank; no active PC -> pair; paired -> that PC's pages. */
@Composable
fun AristotleApp(container: AppContainer, defaultDeviceName: String) {
    val credentials by container.credentials.state.collectAsStateWithLifecycle()
    val notice by container.pairNotice.collectAsStateWithLifecycle()
    val epoch by container.pairEpoch.collectAsStateWithLifecycle()
    val routes = viewModel { RouteStores() }
    val chatRoutes = viewModel(key = CHAT_ROUTES) { RouteStores() }
    when (val current = credentials) {
        CredentialState.Loading -> BlankPage()
        CredentialState.Unavailable -> CredentialsUnavailablePage(
            onRetry = container::reloadCredentials,
            onPairAgain = { container.forgetPc(reason = null) },
        )
        CredentialState.Unpaired -> RouteScope(routes, "pair-$epoch") {
            val pairViewModel = viewModel {
                PairViewModel(container.credentials, HttpPairingApi(container.httpClients), container.certificateProbe, defaultDeviceName)
            }
            PairScreen(pairViewModel, notice)
        }
        is CredentialState.Paired -> {
            val pc = current.credentials
            LaunchedEffect(pc.pcId) { container.clearPairNotice() }
            PairedPages(container, pc, routes, chatRoutes)
        }
    }
}

/** The pages under a paired PC. Back from a sub-page goes to [back]. */
private enum class PairedPage {
    Sessions, Settings, FirstRunGuide, SettingsGuide, Chat;

    val back: PairedPage get() = if (this == SettingsGuide) Settings else Sessions
}

/**
 * Session list, a session's chat, settings and the battery guide; the guide opens once on its own after pairing
 * a Xiaomi-family phone. The chat keeps its own route store ([chatRoutes]), so opening a chat does not clear
 * the session list's ViewModel.
 */
@Composable
private fun PairedPages(container: AppContainer, pc: PcCredentials, routes: RouteStores, chatRoutes: RouteStores) {
    val settings = container.settings
    var page by rememberSaveable(pc.pcId) {
        mutableStateOf(if (container.isXiaomiFamily && !settings.batteryGuideShown) PairedPage.FirstRunGuide else PairedPage.Sessions)
    }
    var chatId by rememberSaveable(pc.pcId) { mutableStateOf<String?>(null) }
    var chatTitle by rememberSaveable(pc.pcId) { mutableStateOf<String?>(null) }
    LaunchedEffect(page) { if (page == PairedPage.FirstRunGuide) settings.markBatteryGuideShown() }
    BackHandler(enabled = page != PairedPage.Sessions) { page = page.back }
    when (page) {
        PairedPage.Sessions -> RouteScope(routes, "sessions-${pc.pcId}") {
            val sessionsViewModel = viewModel { SessionsViewModel(pc, container.link) }
            SessionsScreen(
                sessionsViewModel,
                onPairAgain = { container.forgetPc(reason = null) },
                onSettings = { page = PairedPage.Settings },
                onOpen = { row ->
                    chatId = row.key
                    chatTitle = row.title
                    page = PairedPage.Chat
                },
            )
            NotificationPermissionPrompt(settings, container.notifier::canPost)
        }
        PairedPage.Settings -> SettingsScreen(
            settings = settings,
            canPostNotifications = container.notifier::canPost,
            onBack = { page = page.back },
            onBatteryGuide = { page = PairedPage.SettingsGuide },
        )
        PairedPage.FirstRunGuide, PairedPage.SettingsGuide ->
            BatteryGuideScreen(settings = settings, xiaomi = container.isXiaomiFamily, onBack = { page = page.back })
        PairedPage.Chat -> {
            val id = chatId
            if (id != null) ChatRoute(container, chatRoutes, id, chatTitle, onBack = { page = page.back }) else LaunchedEffect(Unit) { page = PairedPage.Sessions }
        }
    }
}

/**
 * One session's chat. While it is on screen it is [AppContainer.openSession] (no notifications for it, T10);
 * leaving it clears its ViewModel, which ends its subscription work. A rotation keeps both.
 */
@Composable
private fun ChatRoute(container: AppContainer, chatRoutes: RouteStores, sessionId: String, title: String?, onBack: () -> Unit) {
    val activity = LocalActivity.current
    DisposableEffect(sessionId) {
        container.openSession.value = sessionId
        onDispose {
            container.openSession.compareAndSet(sessionId, null)
            if (activity?.isChangingConfigurations != true) chatRoutes.clear()
        }
    }
    RouteScope(chatRoutes, "chat-$sessionId") {
        val chatViewModel = viewModel { ChatViewModel(sessionId, container.link, container.resumedSessions) }
        ChatScreen(chatViewModel, fallbackTitle = title, onBack = onBack)
    }
}

/** Gives the current route's ViewModels their own store. */
@Composable
private fun RouteScope(routes: RouteStores, key: String, content: @Composable () -> Unit) {
    val owner = remember(key) { routes.ownerFor(key) }
    CompositionLocalProvider(LocalViewModelStoreOwner provides owner, content = content)
}

/**
 * One ViewModelStore for the current route, cleared when the route key changes. Without it, ViewModels
 * keyed by pairing epoch or PC id piled up in the activity's store, and an old PC's session ViewModel
 * stayed alive. Itself a ViewModel, so a rotation keeps the route's ViewModels.
 */
class RouteStores : ViewModel() {
    private var key: String? = null
    private var store = ViewModelStore()

    fun ownerFor(routeKey: String): ViewModelStoreOwner {
        if (routeKey != key) {
            store.clear()
            store = ViewModelStore()
            key = routeKey
        }
        val current = store
        return object : ViewModelStoreOwner {
            override val viewModelStore: ViewModelStore = current
        }
    }

    /** Drops the current route's ViewModels now (the route left the screen for good). */
    fun clear() {
        store.clear()
        store = ViewModelStore()
        key = null
    }

    override fun onCleared() = store.clear()
}

private const val CHAT_ROUTES = "chat-routes"
