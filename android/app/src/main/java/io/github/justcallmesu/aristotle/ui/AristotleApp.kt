package io.github.justcallmesu.aristotle.ui

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.justcallmesu.aristotle.AppContainer
import io.github.justcallmesu.aristotle.data.CredentialState
import io.github.justcallmesu.aristotle.data.GateInput
import io.github.justcallmesu.aristotle.data.HomeLimitsSource
import io.github.justcallmesu.aristotle.data.HttpPairingApi
import io.github.justcallmesu.aristotle.data.LinkState
import io.github.justcallmesu.aristotle.data.PcCredentials
import io.github.justcallmesu.aristotle.ui.chat.ChatScreen
import io.github.justcallmesu.aristotle.ui.chat.ChatViewModel
import io.github.justcallmesu.aristotle.ui.cron.CronScreen
import io.github.justcallmesu.aristotle.ui.cron.CronViewModel
import io.github.justcallmesu.aristotle.ui.quote.QuoteLoadingScreen
import io.github.justcallmesu.aristotle.ui.running.ProvideRunningNav
import io.github.justcallmesu.aristotle.ui.running.RunningOpen
import io.github.justcallmesu.aristotle.ui.running.RunningScreen
import io.github.justcallmesu.aristotle.ui.running.RunningViewModel
import io.github.justcallmesu.aristotle.ui.pair.PairScreen
import io.github.justcallmesu.aristotle.ui.pair.PairViewModel
import io.github.justcallmesu.aristotle.ui.sessions.HomeStocks
import io.github.justcallmesu.aristotle.ui.sessions.ScheduledRequest
import io.github.justcallmesu.aristotle.data.HomeMode
import io.github.justcallmesu.aristotle.data.stocks.StockOpen
import io.github.justcallmesu.aristotle.ui.stocks.AlertsScreen
import io.github.justcallmesu.aristotle.ui.stocks.BriefScreen
import io.github.justcallmesu.aristotle.ui.stocks.BriefViewModel
import io.github.justcallmesu.aristotle.ui.stocks.HoldingsScreen
import io.github.justcallmesu.aristotle.ui.stocks.StockAlertsViewModel
import io.github.justcallmesu.aristotle.ui.stocks.NewsScreen
import io.github.justcallmesu.aristotle.ui.stocks.ScreenerScreen
import io.github.justcallmesu.aristotle.ui.stocks.ScreenerSettingsScreen
import io.github.justcallmesu.aristotle.ui.stocks.ScreenerViewModel
import io.github.justcallmesu.aristotle.ui.stocks.StockDetailScreen
import io.github.justcallmesu.aristotle.ui.stocks.StockDetailViewModel
import io.github.justcallmesu.aristotle.ui.stocks.StocksHomeBody
import io.github.justcallmesu.aristotle.ui.stocks.StocksNav
import io.github.justcallmesu.aristotle.ui.stocks.StocksSettingsSection
import io.github.justcallmesu.aristotle.ui.stocks.StocksViewModel
import io.github.justcallmesu.aristotle.ui.sessions.SessionsScreen
import io.github.justcallmesu.aristotle.ui.sessions.SessionsViewModel
import io.github.justcallmesu.aristotle.ui.settings.BatteryGuideScreen
import io.github.justcallmesu.aristotle.ui.settings.NotificationPermissionPrompt
import io.github.justcallmesu.aristotle.ui.settings.ConnectionHistoryScreen
import io.github.justcallmesu.aristotle.ui.settings.ConnectionRows
import io.github.justcallmesu.aristotle.ui.settings.SettingsActions
import io.github.justcallmesu.aristotle.ui.settings.SettingsPage
import io.github.justcallmesu.aristotle.ui.settings.SettingsScreen
import io.github.justcallmesu.aristotle.ui.settings.SettingsViewModel
import io.github.justcallmesu.aristotle.ui.settings.WhatsNewScreen
import io.github.justcallmesu.aristotle.ui.sessions.WhatsNewCard
import io.github.justcallmesu.aristotle.BuildConfig
import io.github.justcallmesu.aristotle.background.AppLocales
import io.github.justcallmesu.aristotle.ui.pair.RepairMode
import androidx.compose.ui.res.stringResource
import io.github.justcallmesu.aristotle.ui.terminal.TerminalScreen
import io.github.justcallmesu.aristotle.ui.terminal.TerminalViewModel
import io.github.justcallmesu.aristotle.R
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import io.github.justcallmesu.aristotle.data.AuthPurpose
import io.github.justcallmesu.aristotle.data.terminal.TerminalEntry
import io.github.justcallmesu.aristotle.ui.lock.LockScreen
import io.github.justcallmesu.aristotle.ui.lock.isScreenLockSet
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import io.github.justcallmesu.aristotle.background.DebugShare

/**
 * Top-level route: locked -> the lock screen; reading -> blank; no active PC -> pair; paired -> that PC's pages.
 * While locked (Q61, TS2) nothing of the app is composed; its saveable state (page, open chat) is kept in a
 * [rememberSaveableStateHolder] and comes back on unlock, and the route ViewModels stay ([ChatRoute], [CronRoute]).
 */
@Composable
fun AristotleApp(container: AppContainer, defaultDeviceName: String) {
    val locked by container.appLock.locked.collectAsStateWithLifecycle()
    val saved = rememberSaveableStateHolder()
    if (locked) {
        val scope = rememberCoroutineScope()
        LockScreen(onUnlock = { scope.launch { container.auth.confirm(AuthPurpose.Unlock) } })
        return
    }
    saved.SaveableStateProvider(UNLOCKED_CONTENT) { UnlockedApp(container, defaultDeviceName) }
    // After a crash (Q80b): over the unlocked app only, never above the lock screen.
    CrashReportDialog(container.diagnostics)
}

@Composable
private fun UnlockedApp(container: AppContainer, defaultDeviceName: String) {
    val credentials by container.credentials.state.collectAsStateWithLifecycle()
    val notice by container.pairNotice.collectAsStateWithLifecycle()
    val epoch by container.pairEpoch.collectAsStateWithLifecycle()
    val routes = viewModel { RouteStores() }
    val chatRoutes = viewModel(key = CHAT_ROUTES) { RouteStores() }
    val cronRoutes = viewModel(key = CRON_ROUTES) { RouteStores() }
    if (quoteGateVisible(container, credentials)) {
        QuoteLoadingScreen(container.quotes)
        return
    }
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
            PairedPages(container, pc, routes, chatRoutes, cronRoutes, defaultDeviceName)
        }
    }
}

/**
 * Pair again from Settings (TS1): the pair form over the paired PC, whose pairing stays stored (and the link keeps
 * using it) until the new pairing is saved, which then replaces it ([io.github.justcallmesu.aristotle.data.PcRecords.replace]);
 * the new PC id then opens its session list. Back or the arrow cancels and changes nothing.
 */
@Composable
private fun RepairRoute(container: AppContainer, routes: RouteStores, pc: PcCredentials, key: String, defaultDeviceName: String, onCancel: () -> Unit) {
    RouteScope(routes, key) {
        val pairViewModel = viewModel {
            PairViewModel(
                container.credentials,
                HttpPairingApi(container.httpClients),
                container.certificateProbe,
                defaultDeviceName,
                savePairing = { paired, wanted -> container.records.replace(pc.pcId, paired, wanted) },
            )
        }
        val note = stringResource(R.string.repair_note, ConnectionRows.revokeCommand(pc.deviceId))
        // Back cancels a re-pair under way too (TS1b): a success after it is not stored.
        PairScreen(
            pairViewModel,
            notice = null,
            repair = RepairMode(note) {
                pairViewModel.cancel()
                onCancel()
            },
        )
    }
}

/**
 * True while the quote loading screen covers the app (Q44). Time only moves the gate through its own recheck, so
 * the min display holds even when nothing else changes.
 */
@Composable
private fun quoteGateVisible(container: AppContainer, credentials: CredentialState): Boolean {
    val link by container.link.state.collectAsStateWithLifecycle()
    val input = GateInput(
        reading = credentials == CredentialState.Loading,
        paired = credentials is CredentialState.Paired,
        linkPending = link == LinkState.Idle || link == LinkState.Connecting,
    )
    val gate = container.quoteGate
    var visible by remember { mutableStateOf(gate.update(input, SystemClock.elapsedRealtime()).visible) }
    LaunchedEffect(input) {
        while (true) {
            val out = gate.update(input, SystemClock.elapsedRealtime())
            visible = out.visible
            if (out.recheckAfterMillis <= 0) break
            delay(out.recheckAfterMillis)
        }
    }
    return visible
}

/**
 * The pages under a paired PC. Back from a sub-page goes to [back]. The Settings category list is [Settings]; each
 * category is its own page ([settingsPage] names it), so a restored page keeps the sub-page open (Q72).
 */
internal enum class PairedPage(val settingsPage: SettingsPage? = null) {
    Sessions, Settings, FirstRunGuide, SettingsGuide, Chat, Scheduled, Terminal, Repair, WhatsNew, SettingsWhatsNew,
    SettingsHistory, Running,
    /** TS2: Holdings, News and a stock's page (route `stock/{code}`, the code kept beside the page). */
    StocksHoldings, StocksNews, StockDetail,
    /** TSC3: the Screener page and Settings › Stocks › Screener (both on the stocks back stack). */
    StocksScreener, StocksScreenerSettings,
    /** TBA2: the 08:30 brief and the stock alerts list (both on the stocks back stack). */
    StocksBrief, StocksAlerts,
    SettingsConnection(SettingsPage.Connection),
    SettingsNotifications(SettingsPage.Notifications),
    SettingsAppearance(SettingsPage.Appearance),
    SettingsLanguage(SettingsPage.Language),
    SettingsSecurity(SettingsPage.Security),
    SettingsStocks(SettingsPage.Stocks),
    SettingsAbout(SettingsPage.About);

    /** The category page that opens this one (battery guide, \"Pair again\", What's new, connection history), or the list. */
    val back: PairedPage
        get() = when (this) {
            SettingsConnection, SettingsNotifications, SettingsAppearance, SettingsLanguage, SettingsSecurity, SettingsStocks, SettingsAbout -> Settings
            SettingsGuide -> SettingsNotifications
            Repair, SettingsHistory -> SettingsConnection
            SettingsWhatsNew -> SettingsAbout
            else -> Sessions
        }

    /**
     * [back], except Running opened from a chat's "See all" ([runningFrom] = [Chat]) returns to that chat (TY7). The
     * stocks pages go back by their own stack ([StocksBackStack]).
     */
    fun backFrom(runningFrom: PairedPage): PairedPage = when {
        this == Running && runningFrom == Chat -> Chat
        else -> back
    }

    companion object {
        fun of(settingsPage: SettingsPage): PairedPage = entries.first { it.settingsPage == settingsPage }
    }
}

/** The stocks back stack ([StocksBackStack.below]) in the saved state, one string a page. */
private val StockStackSaver = listSaver<List<StockPlace>, String>(
    save = { stack -> stack.map(StockPlace::encode) },
    restore = { saved -> saved.mapNotNull(StockPlace::decode) },
)

/**
 * Session list, a session's chat, settings and the battery guide; the guide opens once on its own after pairing
 * a Xiaomi-family phone. The chat keeps its own route store ([chatRoutes]), so opening a chat does not clear
 * the session list's ViewModel; so does the "Scheduled" page ([cronRoutes], T18).
 */
@Composable
private fun PairedPages(
    container: AppContainer,
    pc: PcCredentials,
    routes: RouteStores,
    chatRoutes: RouteStores,
    cronRoutes: RouteStores,
    defaultDeviceName: String,
) {
    val terminalRoutes = viewModel(key = TERMINAL_ROUTES) { RouteStores() }
    val runningRoutes = viewModel(key = RUNNING_ROUTES) { RouteStores() }
    val stocksRoutes = viewModel(key = STOCKS_ROUTES) { RouteStores() }
    val stockRoutes = viewModel(key = STOCK_ROUTES) { RouteStores() }
    val terminalTarget by container.terminalAccess.target.collectAsStateWithLifecycle()
    val settings = container.settings
    val showTerminal by settings.showTerminal.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var page by rememberSaveable(pc.pcId) {
        mutableStateOf(if (container.isXiaomiFamily && !settings.batteryGuideShown) PairedPage.FirstRunGuide else PairedPage.Sessions)
    }
    var chatId by rememberSaveable(pc.pcId) { mutableStateOf<String?>(null) }
    var chatTitle by rememberSaveable(pc.pcId) { mutableStateOf<String?>(null) }
    // The chat's Hermes profile (null = the relay's launch profile): it keeps it whatever the list shows later (T14).
    var chatProfile by rememberSaveable(pc.pcId) { mutableStateOf<String?>(null) }
    var focusComposer by rememberSaveable(pc.pcId) { mutableStateOf(false) }
    // Where the Running page was opened from, so its back goes there (Home's ⋮ menu, or a chat's "See all").
    var runningFrom by rememberSaveable(pc.pcId) { mutableStateOf(PairedPage.Sessions) }
    // The "Scheduled" page's profile (null = launch) and its name, taken from the list when it was opened (T18).
    var cronProfile by rememberSaveable(pc.pcId) { mutableStateOf<String?>(null) }
    var cronLabel by rememberSaveable(pc.pcId) { mutableStateOf<String?>(null) }
    // TS2: the stock a stock's page shows. TM3a2: the stocks pages under the shown one (Holdings, News, stocks), a real
    // back stack, so Back unwinds each once and ends on Home ([StocksBackStack]).
    var stockCode by rememberSaveable(pc.pcId) { mutableStateOf<String?>(null) }
    var stockStack by rememberSaveable(pc.pcId, stateSaver = StockStackSaver) { mutableStateOf(emptyList<StockPlace>()) }
    val homeMode by settings.homeMode.collectAsStateWithLifecycle()
    fun stocksStack() = StocksBackStack(page, stockCode.takeIf { page == PairedPage.StockDetail }, stockStack)
    fun show(nav: StocksBackStack) {
        if (nav.code != null) stockCode = nav.code
        stockStack = nav.below
        page = nav.page
    }
    val openStock: (String) -> Unit = { code -> show(stocksStack().openStock(code)) }
    // TBA2: a stock notification's tap: Stocks mode, then the stock's page, the brief or the alerts list. From a stocks
    // page it is one more step on its stack; from anywhere else a new stack over Home.
    val stockRequest by container.stockOpen.collectAsStateWithLifecycle()
    val briefOpened = briefViewModel(container, stocksRoutes, pc)::opened
    LaunchedEffect(stockRequest) {
        val request = stockRequest ?: return@LaunchedEffect
        settings.setHomeMode(HomeMode.Stocks)
        if (request == StockOpen.Brief) briefOpened()
        val from = if (page in StocksBackStack.PAGES) stocksStack() else StocksBackStack(PairedPage.Sessions)
        show(from.open(request))
        container.stockOpen.compareAndSet(request, null)
    }
    val openStockTarget: (StockOpen) -> Unit = { target ->
        if (target == StockOpen.Brief) briefOpened()
        show(stocksStack().open(target))
    }
    // Each "Pair again" starts from an empty form.
    var repairCount by rememberSaveable(pc.pcId) { mutableIntStateOf(0) }
    LaunchedEffect(page) { if (page == PairedPage.FirstRunGuide) settings.markBatteryGuideShown() }
    // A notification tap: that session's chat (ChatRoute raises the prompt sheet and takes the request).
    val openRequest by container.openChat.collectAsStateWithLifecycle()
    LaunchedEffect(openRequest) {
        val request = openRequest ?: return@LaunchedEffect
        if (chatId != request.sessionId || chatProfile != request.profile) chatTitle = request.title
        chatId = request.sessionId
        chatProfile = request.profile
        focusComposer = false
        page = PairedPage.Chat
    }
    // "Show Terminal on this phone" off (Q62): the terminal page is never shown, whatever was restored.
    LaunchedEffect(page, showTerminal) { if (page == PairedPage.Terminal && !showTerminal) page = PairedPage.Sessions }
    // System back and every top-bar arrow that has a page of its own to return to share this one step.
    val goBack: () -> Unit = { if (page in StocksBackStack.PAGES) show(stocksStack().back()) else page = page.backFrom(runningFrom) }
    BackHandler(enabled = page != PairedPage.Sessions) { goBack() }
    // TY1: Home's ⋮ menu and a chat's status stack open the Running page.
    ProvideRunningNav(container.link, onOpen = {
        runningFrom = if (page == PairedPage.Chat) PairedPage.Chat else PairedPage.Sessions
        page = PairedPage.Running
    }) {
        when (page) {
            PairedPage.Sessions -> RouteScope(routes, "sessions-${pc.pcId}") {
                val sessionsViewModel = viewModel {
                    SessionsViewModel(pc, container.link, container.drafts, selection = container.profileSelection, pins = container.pins, views = container.homeViews, statusSource = container.pcStatus, limitsSource = HomeLimitsSource(container.link)::read)
                }
                SessionsScreen(
                    sessionsViewModel,
                    onPairAgain = { container.forgetPc(reason = null) },
                    onSettings = { page = PairedPage.Settings },
                    pcId = pc.pcId,
                    onOpen = { request ->
                        chatId = request.sessionId
                        chatTitle = request.title
                        chatProfile = request.profile
                        focusComposer = request.focusComposer
                        page = PairedPage.Chat
                    },
                    onScheduled = { request: ScheduledRequest ->
                        cronProfile = request.profile
                        cronLabel = request.label
                        page = PairedPage.Scheduled
                    },
                    whatsNew = {
                        val due by container.whatsNew.visible.collectAsStateWithLifecycle()
                        if (due) {
                            WhatsNewCard(
                                version = BuildConfig.VERSION_NAME,
                                onOpen = {
                                    container.whatsNew.dismiss()
                                    page = PairedPage.WhatsNew
                                },
                                onDismiss = container.whatsNew::dismiss,
                            )
                        }
                    },
                    terminalAvailable = TerminalEntry.shown(serverAllows = terminalTarget != null, showOnPhone = showTerminal),
                    // Q89 / Q92: the Chats | Stocks title words; the Stocks view keeps its ViewModel with the stocks pages.
                    stocks = run {
                        val stocksViewModel = stocksViewModel(container, stocksRoutes, pc)
                        val screenerViewModel = screenerViewModel(container, stocksRoutes, pc)
                        val briefViewModel = briefViewModel(container, stocksRoutes, pc)
                        val alertsViewModel = alertsViewModel(container, stocksRoutes, pc)
                        val nav = StocksNav(
                            openStock = openStock,
                            openHoldings = { show(stocksStack().openHoldings()) },
                            openNews = { show(stocksStack().openNews()) },
                            openFees = { page = PairedPage.SettingsStocks },
                            openSettings = { page = PairedPage.Settings },
                            openScreener = { show(stocksStack().openScreener()) },
                            openBrief = { openStockTarget(StockOpen.Brief) },
                            openAlerts = { openStockTarget(StockOpen.Alerts) },
                        )
                        HomeStocks(homeMode, settings::setHomeMode) {
                            StocksHomeBody(stocksViewModel, nav, sessionsViewModel::timeText, screenerViewModel, briefViewModel, alertsViewModel)
                        }
                    },
                    // With the app lock on, a fresh check first (Q61); a failed or cancelled one opens nothing.
                    onTerminal = {
                        scope.launch {
                            TerminalEntry.open(container.auth, shown = { container.terminalAccess.target.value != null && settings.showTerminal.value }) {
                                page = PairedPage.Terminal
                            }
                        }
                    },
                )
                NotificationPermissionPrompt(settings, container.notifier::canPost)
            }
            PairedPage.Settings, PairedPage.SettingsConnection, PairedPage.SettingsNotifications, PairedPage.SettingsAppearance,
            PairedPage.SettingsLanguage, PairedPage.SettingsSecurity, PairedPage.SettingsStocks, PairedPage.SettingsAbout -> SettingsRoute(
                container,
                pc,
                page = page.settingsPage,
                onOpen = { page = PairedPage.of(it) },
                onBack = { page = page.back },
                onBatteryGuide = { page = PairedPage.SettingsGuide },
                onWhatsNew = {
                    container.whatsNew.dismiss()
                    page = PairedPage.SettingsWhatsNew
                },
                onRepair = {
                    repairCount += 1
                    page = PairedPage.Repair
                },
                onHistory = { page = PairedPage.SettingsHistory },
                stocksSection = {
                    StocksSettingsSection(
                        stocksViewModel(container, stocksRoutes, pc),
                        onOpenScreener = { show(stocksStack().openScreenerSettings()) },
                        alerts = alertsViewModel(container, stocksRoutes, pc),
                    )
                },
            )
            PairedPage.SettingsHistory -> ConnectionHistoryScreen(settingsViewModel(container), onBack = { page = page.back })
            PairedPage.Repair -> RepairRoute(container, routes, pc, "repair-${pc.pcId}-$repairCount", defaultDeviceName, onCancel = { page = page.back })
            PairedPage.FirstRunGuide, PairedPage.SettingsGuide ->
                BatteryGuideScreen(settings = settings, xiaomi = container.isXiaomiFamily, onBack = { page = page.back })
            PairedPage.WhatsNew, PairedPage.SettingsWhatsNew -> WhatsNewScreen(onBack = { page = page.back })
            PairedPage.Scheduled -> CronRoute(container, cronRoutes, cronProfile, cronLabel, onBack = { page = page.back })
            PairedPage.StocksHoldings -> HoldingsScreen(stocksViewModel(container, stocksRoutes, pc), onBack = goBack, screener = screenerViewModel(container, stocksRoutes, pc))
            // TSC3: a candidate opens its stock through [openStock] (show(...)), never a plain page change (MINOR-B).
            PairedPage.StocksScreener -> ScreenerScreen(
                screenerViewModel(container, stocksRoutes, pc),
                onBack = goBack,
                onOpenStock = openStock,
                onOpenSettings = { show(stocksStack().openScreenerSettings()) },
            )
            PairedPage.StocksScreenerSettings -> ScreenerSettingsScreen(screenerViewModel(container, stocksRoutes, pc), onBack = goBack)
            PairedPage.StocksNews -> NewsScreen(stocksViewModel(container, stocksRoutes, pc), onBack = goBack, onOpenStock = openStock)
            PairedPage.StocksBrief -> BriefScreen(briefViewModel(container, stocksRoutes, pc), onBack = goBack, onOpenStock = openStock)
            PairedPage.StocksAlerts -> AlertsScreen(alertsViewModel(container, stocksRoutes, pc), onBack = goBack, onOpen = openStockTarget)
            PairedPage.StockDetail -> {
                val code = stockCode
                if (code != null) {
                    StockRoute(
                        container,
                        stockRoutes,
                        pc,
                        code,
                        stocks = stocksViewModel(container, stocksRoutes, pc),
                        onBack = goBack,
                        onOpenNews = { show(stocksStack().openNews()) },
                    )
                } else {
                    LaunchedEffect(Unit) { page = PairedPage.Sessions }
                }
            }
            PairedPage.Terminal -> TerminalRoute(
                container,
                terminalRoutes,
                pc.pcId,
                onBack = { page = page.back },
                onLeftWithLock = { if (page == PairedPage.Terminal) page = PairedPage.Sessions },
            )
            PairedPage.Running -> RunningRoute(
                container,
                runningRoutes,
                onBack = goBack,
                // A row opens its chat as a Home row does (T14: the chat keeps the row's profile).
                onOpen = { open ->
                    chatId = open.sessionId
                    chatTitle = open.title
                    chatProfile = open.profile
                    focusComposer = false
                    page = PairedPage.Chat
                },
            )
            PairedPage.Chat -> {
                val id = chatId
                if (id != null) {
                    ChatRoute(container, chatRoutes, ChatTarget(id, chatTitle, chatProfile, focusComposer), onBack = { page = page.back })
                } else {
                    LaunchedEffect(Unit) { page = PairedPage.Sessions }
                }
            }
        }
    }
}

/** The settings screen (TS1). Its ViewModel lives in the activity's store: it holds only which sheet is open. */
@Composable
private fun SettingsRoute(
    container: AppContainer,
    pc: PcCredentials,
    page: SettingsPage?,
    onOpen: (SettingsPage) -> Unit,
    onBack: () -> Unit,
    onBatteryGuide: () -> Unit,
    onWhatsNew: () -> Unit,
    onRepair: () -> Unit,
    onHistory: () -> Unit,
    stocksSection: @Composable () -> Unit = {},
) {
    val context = LocalContext.current
    val settingsViewModel = settingsViewModel(container)
    val activity = LocalActivity.current
    val scope = rememberCoroutineScope()
    val logSubject = stringResource(R.string.debug_log_share_subject)
    val chooserTitle = stringResource(R.string.share_chooser_title)
    // Leaving Settings closes its sheet; a rotation keeps it.
    DisposableEffect(settingsViewModel) {
        onDispose { if (activity?.isChangingConfigurations != true) settingsViewModel.close() }
    }
    val actions = remember(pc.pcId, activity) {
        SettingsActions(
            onBack = onBack,
            onBatteryGuide = onBatteryGuide,
            onWhatsNew = onWhatsNew,
            onRepair = onRepair,
            onHistory = onHistory,
            onUnpair = { container.unpair(pc.pcId) },
            // About › Send debug log (Q80b): the log as one file through the share sheet; nothing is uploaded.
            onSendDebugLog = {
                scope.launch {
                    val file = withContext(Dispatchers.IO) { container.diagnostics.debugLogFile() }
                    activity?.let { DebugShare.open(it, file, logSubject, chooserTitle) }
                }
            },
            canPostNotifications = container.notifier::canPost,
            channelOn = container.notifier::isOn,
            currentLanguage = { AppLocales.current(context, container.settings) },
            pickLanguage = { choice -> activity?.let { AppLocales.apply(it, container.settings, choice) } },
            screenLockSet = { isScreenLockSet(context) },
        )
    }
    SettingsScreen(settingsViewModel, pc, actions, page, onOpen, stocksSection)
}

/**
 * The stocks pages' one ViewModel (TS2): Home's Stocks view, Holdings, News, a stock's page and Settings › Stocks share
 * it, in a store of its own per PC, so going between them keeps the lists; its price reads run only while the Stocks
 * view is on screen.
 */
@Composable
private fun stocksViewModel(container: AppContainer, stocksRoutes: RouteStores, pc: PcCredentials): StocksViewModel {
    val owner = remember(pc.pcId) { stocksRoutes.ownerFor("stocks-${pc.pcId}") }
    return viewModel(viewModelStoreOwner = owner) {
        StocksViewModel(pc, container.link.state, container.stocksTransport, retryLink = container.link::retryNow)
    }
}

/** The screener's ViewModel (TSC3), beside the stocks one in the stocks pages' store: Home's section, the page and its settings share it. */
@Composable
private fun screenerViewModel(container: AppContainer, stocksRoutes: RouteStores, pc: PcCredentials): ScreenerViewModel {
    val owner = remember(pc.pcId) { stocksRoutes.ownerFor("stocks-${pc.pcId}") }
    return viewModel(viewModelStoreOwner = owner) {
        ScreenerViewModel(pc, container.link.state, container.stocksTransport, retryLink = container.link::retryNow)
    }
}

/** TBA2: the brief's card and page share this one, in the stocks pages' store. */
@Composable
private fun briefViewModel(container: AppContainer, stocksRoutes: RouteStores, pc: PcCredentials): BriefViewModel {
    val owner = remember(pc.pcId) { stocksRoutes.ownerFor("stocks-${pc.pcId}") }
    return viewModel(viewModelStoreOwner = owner) { BriefViewModel(pc, container.link.state, container.stocksTransport) }
}

/** TBA2: Stocks home's alerts row, the alerts page and Settings › Stocks › Alerts, over the process-wide alert list. */
@Composable
private fun alertsViewModel(container: AppContainer, stocksRoutes: RouteStores, pc: PcCredentials): StockAlertsViewModel {
    val owner = remember(pc.pcId) { stocksRoutes.ownerFor("stocks-${pc.pcId}") }
    return viewModel(viewModelStoreOwner = owner) { StockAlertsViewModel(pc, container.link.state, container.stocksTransport, container.stockAlerts) }
}

/** The one Settings ViewModel, in the activity's store, shared by the Settings pages and the connection history (Q73). */
@Composable
private fun settingsViewModel(container: AppContainer): SettingsViewModel = viewModel(key = SETTINGS_VIEW_MODEL) {
    SettingsViewModel(
        container.settings,
        container.link.state,
        prove = container.auth::prove,
        search = container.pcSearch,
        history = container.connectionLog,
        deleteAddress = container::deleteAddress,
    )
}

/** The chat to show: its session, a title until Hermes sends one, its profile, and whether to focus the composer. */
private data class ChatTarget(val sessionId: String, val title: String?, val profile: String?, val focusComposer: Boolean)

/**
 * One session's chat. While it is on screen it is [AppContainer.openSession] (no notifications for it, T10);
 * leaving it clears its ViewModel, which ends its subscription work. A rotation keeps both.
 */
@Composable
private fun ChatRoute(container: AppContainer, chatRoutes: RouteStores, target: ChatTarget, onBack: () -> Unit) {
    val sessionId = target.sessionId
    val activity = LocalActivity.current
    DisposableEffect(sessionId) {
        container.openSession.value = sessionId
        onDispose {
            container.openSession.compareAndSet(sessionId, null)
            // Hidden by the app lock is not leaving: the chat (and a picked attachment) is there again on unlock.
            if (activity?.isChangingConfigurations != true && !container.appLock.locked.value) chatRoutes.clear()
        }
    }
    RouteScope(chatRoutes, "chat-${target.profile.orEmpty()}-$sessionId") {
        val chatViewModel = viewModel {
            ChatViewModel(
                sessionId,
                container.link,
                container.resumedSessions,
                container.drafts,
                profile = target.profile,
                attachments = container.attachments,
                freshAuth = container.auth,
            )
        }
        val openRequest by container.openChat.collectAsStateWithLifecycle()
        LaunchedEffect(openRequest) {
            val request = openRequest?.takeIf { it.sessionId == sessionId } ?: return@LaunchedEffect
            if (request.showPrompt) chatViewModel.openSheet()
            container.openChat.compareAndSet(request, null)
        }
        ChatScreen(
            chatViewModel,
            fallbackTitle = target.title,
            fallbackProfile = target.profile,
            onBack = onBack,
            focusComposer = target.focusComposer,
            quotes = container.quotes,
        )
    }
}

/** The Running page (TY1); leaving it clears its ViewModel, which ends its polling. */
@Composable
private fun RunningRoute(container: AppContainer, runningRoutes: RouteStores, onBack: () -> Unit, onOpen: (RunningOpen) -> Unit) {
    val activity = LocalActivity.current
    DisposableEffect(Unit) {
        onDispose { if (activity?.isChangingConfigurations != true && !container.appLock.locked.value) runningRoutes.clear() }
    }
    RouteScope(runningRoutes, "running") {
        val runningViewModel = viewModel { RunningViewModel(container.link) }
        RunningScreen(runningViewModel, onBack, onOpen)
    }
}

/**
 * A stock's page (TS3): its chart, quote and news live in a ViewModel of their own per code, so each range's bars stay
 * cached while the page lives; leaving the page clears it, which ends its 60 s refresh.
 */
@Composable
private fun StockRoute(
    container: AppContainer,
    stockRoutes: RouteStores,
    pc: PcCredentials,
    code: String,
    stocks: StocksViewModel,
    onBack: () -> Unit,
    onOpenNews: () -> Unit,
) {
    val activity = LocalActivity.current
    DisposableEffect(Unit) {
        onDispose { if (activity?.isChangingConfigurations != true && !container.appLock.locked.value) stockRoutes.clear() }
    }
    RouteScope(stockRoutes, "stock-${pc.pcId}-$code") {
        val detail = viewModel { StockDetailViewModel(code, pc, container.link.state, container.stocksTransport) }
        StockDetailScreen(code, stocks, detail, onBack, onOpenNews)
    }
}

/** The "Scheduled" page of [profile]; leaving it clears its ViewModel, which ends its listing and polling. */
@Composable
private fun CronRoute(container: AppContainer, cronRoutes: RouteStores, profile: String?, label: String?, onBack: () -> Unit) {
    val activity = LocalActivity.current
    DisposableEffect(Unit) {
        onDispose { if (activity?.isChangingConfigurations != true && !container.appLock.locked.value) cronRoutes.clear() }
    }
    RouteScope(cronRoutes, "cron-${profile.orEmpty()}") {
        val cronViewModel = viewModel { CronViewModel(container.link, profile) }
        CronScreen(cronViewModel, label, onBack)
    }
}

/**
 * The PC's terminal screen (T19b). Its shells live only while it is on screen: leaving the page clears its ViewModel,
 * and the app going to the background (not a rotation) closes every terminal; the background service keeps none.
 * With the app lock on, going to the background also leaves the page ([onLeftWithLock]), so the next shell needs a
 * fresh check from the menu (Q61).
 */
@Composable
private fun TerminalRoute(container: AppContainer, terminalRoutes: RouteStores, pcId: String, onBack: () -> Unit, onLeftWithLock: () -> Unit) {
    val activity = LocalActivity.current
    DisposableEffect(Unit) {
        onDispose { if (activity?.isChangingConfigurations != true) terminalRoutes.clear() }
    }
    RouteScope(terminalRoutes, "terminal-$pcId") {
        // The application context: the ViewModel outlives a rotated activity.
        val appContext = LocalContext.current.applicationContext
        val terminalViewModel = viewModel {
            TerminalViewModel(container.terminalAccess, container.terminalDialer, container.network) { bytes ->
                appContext.getString(R.string.terminal_output_skipped, bytes.toString())
            }
        }
        LifecycleStartEffect(terminalViewModel) {
            onStopOrDispose { if (activity?.isChangingConfigurations != true) terminalViewModel.leave() }
        }
        LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
            if (activity?.isChangingConfigurations != true && container.settings.appLock.value) onLeftWithLock()
        }
        TerminalScreen(terminalViewModel, onBack)
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
private const val CRON_ROUTES = "cron-routes"
private const val TERMINAL_ROUTES = "terminal-routes"
private const val RUNNING_ROUTES = "running-routes"
private const val STOCKS_ROUTES = "stocks-routes"
private const val STOCK_ROUTES = "stock-routes"
private const val SETTINGS_VIEW_MODEL = "settings"
private const val UNLOCKED_CONTENT = "unlocked"
