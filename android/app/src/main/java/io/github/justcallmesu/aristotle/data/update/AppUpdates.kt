package io.github.justcallmesu.aristotle.data.update

import io.github.justcallmesu.aristotle.data.AppLog
import io.github.justcallmesu.aristotle.data.KeyValueStore
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Where the update runs now (Settings › About row). */
sealed interface UpdateStep {
    data object Idle : UpdateStep

    /** Android's "Install unknown apps" is off for this app: the row explains and opens that page (P19). */
    data object NeedsPermission : UpdateStep
    data class Downloading(val bytes: Long, val total: Long) : UpdateStep
    data object Verifying : UpdateStep

    /** Handed to Android's installer, no status yet. Gives up after [AppUpdateRules.INSTALL_TIMEOUT_MILLIS]. */
    data object Installing : UpdateStep

    /** Android waits for the user's OK (`STATUS_PENDING_USER_ACTION`): the screen is kept and shown again when the app is in front. */
    data object Confirming : UpdateStep
    data class Failed(val reason: UpdateFailure) : UpdateStep
}

/** Why the last update attempt stopped, as the row says it. */
enum class UpdateFailure {
    Unreachable, CertificateChanged, BadStatus, TooLarge, Truncated, Storage,
    HashMismatch, Unreadable, OtherPackage, OtherSigner, NotNewer,
    InstallAborted, InstallBlocked, InstallStorage, InstallRefused, InstallFailed, InstallNotFinished, WrongVersion,
    ;

    companion object {
        fun of(problem: DownloadProblem): UpdateFailure = when (problem) {
            DownloadProblem.Unreachable -> Unreachable
            DownloadProblem.CertificateChanged -> CertificateChanged
            DownloadProblem.BadStatus -> BadStatus
            DownloadProblem.TooLarge -> TooLarge
            DownloadProblem.Truncated -> Truncated
            DownloadProblem.Storage -> Storage
        }

        /** Null for [ApkVerdict.Ok]. */
        fun of(verdict: ApkVerdict): UpdateFailure? = when (verdict) {
            ApkVerdict.Ok -> null
            ApkVerdict.HashMismatch -> HashMismatch
            ApkVerdict.Unreadable -> Unreadable
            ApkVerdict.OtherPackage -> OtherPackage
            ApkVerdict.OtherSigner -> OtherSigner
            ApkVerdict.NotNewer -> NotNewer
            ApkVerdict.WrongVersion -> WrongVersion
        }

        /** Null while the install goes on ([InstallOutcome.Success], [InstallOutcome.PendingUserAction]). */
        fun of(outcome: InstallOutcome): UpdateFailure? = when (outcome) {
            InstallOutcome.Success, InstallOutcome.PendingUserAction -> null
            InstallOutcome.Aborted -> InstallAborted
            InstallOutcome.Blocked -> InstallBlocked
            InstallOutcome.Storage -> InstallStorage
            InstallOutcome.Refused -> InstallRefused
            InstallOutcome.Failed -> InstallFailed
        }
    }
}

/** A `PackageInstaller` session's status (`EXTRA_STATUS`), grouped by what the row tells the user. */
enum class InstallOutcome {
    Success, PendingUserAction, Aborted, Blocked, Storage, Refused, Failed;

    companion object {
        /** The values of `PackageInstaller.STATUS_*` (API 21+, stable). */
        fun of(status: Int): InstallOutcome = when (status) {
            0 -> Success
            -1 -> PendingUserAction
            2 -> Blocked
            3 -> Aborted
            4, 5, 7 -> Refused // invalid, conflict, incompatible
            6 -> Storage
            else -> Failed
        }
    }
}

/**
 * What the About row shows (Q132): [available] the offered update (null: up to date), [step] the running attempt,
 * [checking] a check under way, [checkFailed] the last check got no answer, [updatedTo] this run follows an update.
 */
data class UpdateView(
    val available: AppUpdate? = null,
    val step: UpdateStep = UpdateStep.Idle,
    val checking: Boolean = false,
    val checkFailed: Boolean = false,
    val updatedTo: String? = null,
)

/** The phone side of the install (P19): Android's `PackageManager` and `PackageInstaller` in the app, fakes in tests. */
interface UpdateDevice {
    /** `canRequestPackageInstalls()`: "Install unknown apps" is on for this app. */
    fun canInstall(): Boolean

    /** The file's package, versionCode and signers; null when Android cannot read it as an app. */
    suspend fun inspect(file: File): ApkFacts?
    fun own(): ApkFacts?

    /**
     * Writes [file] into a new installer session and commits it; false when that failed. The status comes later.
     * [onSession] gets the new session's id before the commit, so every status that follows can be matched to it.
     */
    suspend fun install(file: File, onSession: (Int) -> Unit): Boolean

    /** Abandons an installer session this app created; no status of it is wanted any more. */
    fun abandon(sessionId: Int)

    /** Shows the confirm screen Android sent with `STATUS_PENDING_USER_ACTION`; false when none is held or it cannot start. */
    fun confirm(): Boolean

    /** Forgets the held confirm screen. */
    fun dropConfirm()
}

/** The "App updates" notification (Q132). */
interface UpdateNotice {
    fun show(update: AppUpdate)
    fun cancel()
}

/** The download's folder in the app cache (P18): one file at a time, emptied at start and after every attempt. */
class UpdateFiles(private val dir: File) {
    fun fileFor(update: AppUpdate): File = File(dir.apply { mkdirs() }, "aristotle-${update.versionCode}.apk")

    fun delete(file: File) {
        file.delete()
    }

    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }
}

/**
 * The in-app updater (Q132, Q133, P17-P20). Checks the PC on connect and every [AppUpdateRules.CHECK_INTERVAL_MILLIS]
 * while connected ([target] non-null; another address of the same PC is the same connection), notifies once per new
 * version, and on [update] downloads to the cache, verifies ([ApkCheck]) and hands the file to [device]. Nothing runs
 * in the background without a tap. [pause], [wait] and [now] are seams: tests drive the 6 h wait, the install timer and
 * the clock by hand. The row never sticks: an install with no status, or an unanswered confirmation, ends after
 * [AppUpdateRules.INSTALL_TIMEOUT_MILLIS] with [UpdateFailure.InstallNotFinished] and the Update button back.
 */
class AppUpdates(
    private val scope: CoroutineScope,
    private val target: StateFlow<UpdateTarget?>,
    private val own: OwnVersion,
    private val source: AppUpdateSource,
    private val store: KeyValueStore,
    private val files: UpdateFiles,
    private val device: UpdateDevice,
    private val notice: UpdateNotice,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val now: () -> Long = System::currentTimeMillis,
    private val wait: suspend (Long) -> Unit = { delay(it) },
) {
    private val state = MutableStateFlow(UpdateView())
    val view: StateFlow<UpdateView> = state.asStateFlow()

    private val checks = Mutex()
    private var running: Job? = null
    private var deadline: Job? = null
    private var settling: Job? = null
    private var lastCheck: Pair<String, Long>? = null

    /** The installer session of the current install (also in [store]); statuses of any other session are stale. */
    @Volatile
    private var session: Int? = null

    /** Reads what the last run left (an install that finished, files), then checks on every connect. */
    fun start(): Job {
        val installing = store.get(INSTALLING)?.toIntOrNull()
        store.remove(INSTALLING)
        store.remove(SESSION)
        state.update { it.copy(updatedTo = AppUpdateRules.updatedTo(installing, own.code, own.name)) }
        files.clear()
        return scope.launch {
            target.map { it?.pc?.pcId }.distinctUntilChanged().collectLatest { pcId ->
                if (pcId == null) return@collectLatest
                if (AppUpdateRules.checkOnConnect(lastCheck?.takeIf { it.first == pcId }?.second, now())) check()
                while (true) {
                    pause(AppUpdateRules.CHECK_INTERVAL_MILLIS)
                    check()
                }
            }
        }
    }

    /** About's "Check now". */
    fun checkNow() {
        scope.launch { check() }
    }

    private suspend fun check() = checks.withLock {
        val where = target.value
        state.update { it.copy(checking = true) }
        try {
            val result = where?.let { source.latestApp(it.pc, it.endpoint, own.code) } ?: UpdateCheck.Failed
            // Only an answer from the PC counts: a failed or cut-off check must not block the next on-connect check.
            if (where != null && result != UpdateCheck.Failed) lastCheck = where.pc.pcId to now()
            apply(result)
        } finally {
            // Also when a disconnect cancels the check: the row must not stay on "Asking your PC".
            state.update { it.copy(checking = false) }
        }
    }

    private fun apply(result: UpdateCheck) {
        when (result) {
            is UpdateCheck.Available -> offer(result.update)
            UpdateCheck.UpToDate -> {
                // A running attempt keeps its update; otherwise there is nothing to offer.
                state.update { view -> view.copy(available = view.available.takeIf { busy(view.step) }, checking = false, checkFailed = false) }
                notice.cancel()
            }
            UpdateCheck.Failed -> state.update { it.copy(checking = false, checkFailed = true) }
        }
    }

    private fun offer(update: AppUpdate) {
        state.update { it.copy(available = if (busy(it.step)) it.available else update, checking = false, checkFailed = false) }
        if (AppUpdateRules.shouldNotify(update, store.get(NOTIFIED)?.toIntOrNull())) {
            store.put(NOTIFIED, update.versionCode.toString())
            notice.show(update)
        }
    }

    /** About's Update button: asks for the install permission first (P19), then downloads, verifies and installs. */
    fun update() {
        val offer = state.value.available ?: return
        if (busy(state.value.step)) return
        if (!device.canInstall()) {
            state.update { it.copy(step = UpdateStep.NeedsPermission) }
            return
        }
        // Claimed before the launch, so a double tap (or a tap during permissionReturned) starts one run.
        var claimed = false
        state.update { view ->
            claimed = view.available == offer && !busy(view.step)
            if (claimed) view.copy(step = UpdateStep.Downloading(0, offer.size)) else view
        }
        if (claimed) running = scope.launch { run(offer) }
    }

    /** Back from Android's "Install unknown apps" page: go on when it was allowed. */
    fun permissionReturned() {
        if (state.value.step == UpdateStep.NeedsPermission && device.canInstall()) {
            state.update { it.copy(step = UpdateStep.Idle) }
            update()
        }
    }

    /** Stops a download; its file goes. */
    fun cancel() {
        if (state.value.step is UpdateStep.Downloading) running?.cancel()
    }

    /**
     * The installer's status ([InstallOutcome.of]), from the session's receiver. A pending confirmation is held and shown
     * at once (it only opens while the app is in front); [appResumed] and [confirmInstall] show it again. A status of
     * another session than the current one (an earlier attempt, one given up on) is ignored.
     */
    /** True when [sessionId] is the current install's session and the row still waits for Android. */
    fun acceptsStatus(sessionId: Int): Boolean = session == sessionId && awaitingInstaller(state.value.step)

    fun installFinished(outcome: InstallOutcome, sessionId: Int) {
        if (!acceptsStatus(sessionId)) return
        val failure = UpdateFailure.of(outcome)
        when {
            failure != null -> giveUp(failure)
            outcome == InstallOutcome.PendingUserAction -> {
                state.update { it.copy(step = UpdateStep.Confirming) }
                showConfirm()
            }
            else -> state.update { it.copy(step = UpdateStep.Installing) }
        }
    }

    /** The app came to the front: a held confirm screen opens again, unless the screen's own status (Cancel) arrives first. */
    fun appResumed() {
        if (state.value.step != UpdateStep.Confirming) return
        synchronized(this) {
            settling?.cancel()
            settling = scope.launch {
                wait(AppUpdateRules.CONFIRM_SETTLE_MILLIS)
                if (state.value.step == UpdateStep.Confirming) showConfirm()
            }
        }
    }

    /** About's Confirm install: the held screen again. */
    fun confirmInstall() {
        if (state.value.step == UpdateStep.Confirming) showConfirm()
    }

    private fun sessionStarted(id: Int) {
        session = id
        store.put(SESSION, id.toString())
    }

    private fun showConfirm() {
        if (device.confirm()) arm() else giveUp(UpdateFailure.InstallFailed)
    }

    private fun awaitingInstaller(step: UpdateStep) = step == UpdateStep.Installing || step == UpdateStep.Confirming

    /** Starts (or restarts) the timer that ends an install Android never answers. */
    @Synchronized
    private fun arm() {
        deadline?.cancel()
        deadline = scope.launch {
            wait(AppUpdateRules.INSTALL_TIMEOUT_MILLIS)
            if (awaitingInstaller(state.value.step)) {
                AppLog.i("update: no install status, giving up")
                giveUp(UpdateFailure.InstallNotFinished)
            }
        }
    }

    @Synchronized
    private fun disarm() {
        deadline?.cancel()
        settling?.cancel()
    }

    private fun giveUp(failure: UpdateFailure) {
        disarm()
        device.dropConfirm()
        store.remove(INSTALLING)
        store.remove(SESSION)
        // Cleared first: the abandoned session's own Aborted status is then stale and dropped.
        session?.let { stale ->
            session = null
            device.abandon(stale)
        }
        state.update { it.copy(step = UpdateStep.Failed(failure)) }
    }

    private suspend fun run(update: AppUpdate) {
        val file = files.fileFor(update)
        try {
            val failure = downloadAndVerify(update, file)
            if (failure != null) {
                fail(failure, file)
                return
            }
            state.update { it.copy(step = UpdateStep.Installing) }
            // Read at the next start: the app is replaced and restarts, so a success is seen only then.
            // Durable: Android kills this process to replace the app, so an async write could be lost.
            store.edit(mapOf(INSTALLING to update.versionCode.toString()), durable = true)
            session = null
            val committed = device.install(file, ::sessionStarted)
            files.delete(file)
            if (committed) arm() else giveUp(UpdateFailure.InstallFailed)
        } catch (e: CancellationException) {
            withContext(NonCancellable) { files.delete(file) }
            state.update { it.copy(step = UpdateStep.Idle) }
            throw e
        } catch (e: Exception) {
            AppLog.w("update: attempt failed", e)
            fail(UpdateFailure.InstallFailed, file)
        }
    }

    /** Null when the file is the announced APK and may be installed. */
    private suspend fun downloadAndVerify(update: AppUpdate, file: File): UpdateFailure? {
        val where = target.value ?: return UpdateFailure.Unreachable
        state.update { it.copy(step = UpdateStep.Downloading(0, update.size)) }
        val result = source.downloadApp(where.pc, where.endpoint, update, file) { bytes ->
            state.update { it.copy(step = UpdateStep.Downloading(bytes, update.size)) }
        }
        val sha = when (result) {
            is DownloadResult.Failed -> return UpdateFailure.of(result.problem)
            is DownloadResult.Done -> result.sha256
        }
        state.update { it.copy(step = UpdateStep.Verifying) }
        val own = device.own() ?: return UpdateFailure.OtherSigner
        return UpdateFailure.of(ApkCheck.verdict(update, sha, device.inspect(file), own))
    }

    private fun fail(failure: UpdateFailure, file: File) {
        files.delete(file)
        AppLog.i("update: stopped reason=$failure")
        state.update { it.copy(step = UpdateStep.Failed(failure)) }
        // The release on the PC changed since the check: the offer is stale. Drop it and ask again (the row then offers the new one).
        if (failure == UpdateFailure.HashMismatch) {
            state.update { it.copy(available = null) }
            scope.launch { check() }
        }
    }

    private fun busy(step: UpdateStep): Boolean = step is UpdateStep.Downloading || step == UpdateStep.Verifying || awaitingInstaller(step)

    private companion object {
        const val NOTIFIED = "update_notified_version_code"
        const val INSTALLING = "update_installing_version_code"
        const val SESSION = "update_install_session_id"
    }
}
