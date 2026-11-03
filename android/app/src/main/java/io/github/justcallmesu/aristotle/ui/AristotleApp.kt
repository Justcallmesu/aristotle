package io.github.justcallmesu.aristotle.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.justcallmesu.aristotle.AppContainer
import io.github.justcallmesu.aristotle.data.CredentialState
import io.github.justcallmesu.aristotle.data.HttpPairingApi
import io.github.justcallmesu.aristotle.ui.pair.PairScreen
import io.github.justcallmesu.aristotle.ui.pair.PairViewModel
import io.github.justcallmesu.aristotle.ui.sessions.SessionsScreen
import io.github.justcallmesu.aristotle.ui.sessions.SessionsViewModel

/** Top-level route: reading -> blank; no active PC -> pair; paired -> that PC's session list. */
@Composable
fun AristotleApp(container: AppContainer, defaultDeviceName: String) {
    val credentials by container.credentials.state.collectAsStateWithLifecycle()
    val notice by container.pairNotice.collectAsStateWithLifecycle()
    val epoch by container.pairEpoch.collectAsStateWithLifecycle()
    val routes = viewModel { RouteStores() }
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
            RouteScope(routes, "sessions-${pc.pcId}") {
                val sessionsViewModel = viewModel { SessionsViewModel(pc, container.link) }
                SessionsScreen(sessionsViewModel, onPairAgain = { container.forgetPc(reason = null) })
            }
        }
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

    override fun onCleared() = store.clear()
}
