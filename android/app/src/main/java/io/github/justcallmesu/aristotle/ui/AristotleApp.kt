package io.github.justcallmesu.aristotle.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.justcallmesu.aristotle.AppContainer
import io.github.justcallmesu.aristotle.PairNotice
import io.github.justcallmesu.aristotle.ui.pair.PairScreen
import io.github.justcallmesu.aristotle.ui.pair.PairViewModel
import io.github.justcallmesu.aristotle.ui.sessions.SessionsScreen
import io.github.justcallmesu.aristotle.ui.sessions.SessionsViewModel

/** Top-level route: no active PC -> pair; paired -> that PC's session list. */
@Composable
fun AristotleApp(container: AppContainer, defaultDeviceName: String) {
    val active by container.credentials.active.collectAsStateWithLifecycle()
    val notice by container.pairNotice.collectAsStateWithLifecycle()
    val epoch by container.pairEpoch.collectAsStateWithLifecycle()
    val pc = active
    if (pc == null) {
        val pairViewModel = viewModel(key = "pair-$epoch") {
            PairViewModel(container.credentials, container.httpClients, container.certificateProbe, defaultDeviceName)
        }
        PairScreen(pairViewModel, notice)
    } else {
        LaunchedEffect(pc.pcId) { container.clearPairNotice() }
        val sessionsViewModel = viewModel(key = "sessions-${pc.pcId}") {
            SessionsViewModel(pc, container.httpClients, onRevoked = { container.forgetPc(PairNotice.Revoked) })
        }
        SessionsScreen(sessionsViewModel, onPairAgain = { container.forgetPc(reason = null) })
    }
}
