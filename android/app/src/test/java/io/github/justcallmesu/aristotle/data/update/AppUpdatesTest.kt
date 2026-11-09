package io.github.justcallmesu.aristotle.data.update

import io.github.justcallmesu.aristotle.data.DeviceToken
import io.github.justcallmesu.aristotle.data.MemoryStore
import io.github.justcallmesu.aristotle.data.Network
import io.github.justcallmesu.aristotle.data.PcCredentials
import io.github.justcallmesu.aristotle.data.ServerEndpoint
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** UP2 (Q132, P18, P19): when the phone asks, what it offers and notifies, and the download -> verify -> install run. */
class AppUpdatesTest {
    private val lan = ServerEndpoint("192.168.1.61", 8760, Network.Lan)
    private val tailnet = ServerEndpoint("100.101.102.103", 8760, Network.Tailscale)
    private val pc = PcCredentials("pc1", "dev1", listOf(lan, tailnet), "ff", DeviceToken("t"))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val target = MutableStateFlow<UpdateTarget?>(null)
    private val store = MemoryStore()
    private val dir: File = Files.createTempDirectory("updates").toFile()
    private val files = UpdateFiles(dir)
    private val sha = "ab".repeat(32)
    private val next = AppUpdate("2.1.1", 20101, sha, 4)

    private val checks = AtomicInteger()
    @Volatile private var answer: UpdateCheck = UpdateCheck.Available(next)
    @Volatile private var download: suspend (File, (Long) -> Unit) -> DownloadResult = { file, progress ->
        file.writeBytes(byteArrayOf(1, 2, 3, 4))
        progress(4)
        DownloadResult.Done(sha)
    }
    @Volatile private var source2: (suspend () -> UpdateCheck)? = null
    private val source = object : AppUpdateSource {
        override suspend fun latestApp(pc: PcCredentials, endpoint: ServerEndpoint, ownVersionCode: Int): UpdateCheck {
            checks.incrementAndGet()
            source2?.let { return it() }
            return answer
        }

        override suspend fun downloadApp(pc: PcCredentials, endpoint: ServerEndpoint, update: AppUpdate, target: File, onProgress: (Long) -> Unit) =
            download(target, onProgress)
    }

    private val ownFacts = ApkFacts(PACKAGE, 20100, setOf(SIGNER))
    @Volatile private var canInstall = true
    @Volatile private var archive: ApkFacts? = ApkFacts(PACKAGE, 20101, setOf(SIGNER))
    private val installs = CopyOnWriteArrayList<Pair<String, Boolean>>()
    private val device = object : UpdateDevice {
        override fun canInstall() = canInstall
        override suspend fun inspect(file: File) = archive
        override fun own() = ownFacts
        override suspend fun install(file: File, onSession: (Int) -> Unit): Boolean {
            installs += file.name to file.exists()
            lastSession = sessionIds.incrementAndGet()
            onSession(lastSession)
            duringInstall?.invoke(lastSession)
            return true
        }

        override fun abandon(sessionId: Int) {
            abandoned += sessionId
        }

        override fun confirm(): Boolean {
            confirms.incrementAndGet()
            return confirmWorks
        }

        override fun dropConfirm() {
            drops.incrementAndGet()
        }
    }
    private val sessionIds = AtomicInteger(100)
    @Volatile private var lastSession = 0
    @Volatile private var duringInstall: ((Int) -> Unit)? = null
    private val abandoned = CopyOnWriteArrayList<Int>()
    private val confirms = AtomicInteger()
    private val drops = AtomicInteger()
    @Volatile private var confirmWorks = true

    private val shown = CopyOnWriteArrayList<AppUpdate>()
    private val cancelled = AtomicInteger()
    private val notice = object : UpdateNotice {
        override fun show(update: AppUpdate) {
            shown += update
        }

        override fun cancel() {
            cancelled.incrementAndGet()
        }
    }

    private val pauses = CopyOnWriteArrayList<Long>()
    private val ticks = Channel<Unit>(Channel.UNLIMITED)

    /** The install timer and the confirm settle wait: one gate per duration, opened by [fire]. */
    private val gates = ConcurrentHashMap<Long, Channel<Unit>>()
    private val waiting = CopyOnWriteArrayList<Long>()
    private fun gate(millis: Long) = gates.getOrPut(millis) { Channel(Channel.UNLIMITED) }
    private fun fire(millis: Long) {
        waitFor("a timer of $millis ms is waiting") { millis in waiting }
        gate(millis).trySend(Unit)
    }

    @Volatile private var clock = 1_000_000L

    private fun updates(own: OwnVersion = OwnVersion(20100, "2.1.0")) = AppUpdates(
        scope, target, own, source, store, files, device, notice,
        pause = { pauses += it; ticks.receive() },
        now = { clock },
        wait = { waiting += it; gate(it).receive() },
    )

    @After
    fun stop() {
        scope.cancel()
        dir.deleteRecursively()
    }

    private fun waitFor(what: String, condition: () -> Boolean) = runBlocking {
        try {
            withTimeout(5_000) { while (!condition()) delay(5) }
        } catch (e: Exception) {
            throw AssertionError(what, e)
        }
    }

    private fun settle() = runBlocking { delay(100) }

    @Test
    fun `checks on connect and every 6 hours while connected, never while offline`() {
        updates().start()
        settle()
        assertEquals("offline: no check", 0, checks.get())
        target.value = UpdateTarget(pc, lan)
        waitFor("the check on connect") { checks.get() == 1 && pauses.size == 1 }
        assertEquals(AppUpdateRules.CHECK_INTERVAL_MILLIS, pauses.single())
        ticks.trySend(Unit)
        waitFor("the 6 h check") { checks.get() == 2 }
    }

    @Test
    fun `another address of the same PC does not check again, a reconnect does`() {
        updates().start()
        target.value = UpdateTarget(pc, lan)
        waitFor("first check") { checks.get() == 1 }
        target.value = UpdateTarget(pc, tailnet)
        settle()
        assertEquals(1, checks.get())
        target.value = null
        settle()
        clock += AppUpdateRules.MIN_CHECK_GAP_MILLIS
        target.value = UpdateTarget(pc, lan)
        waitFor("the reconnect's check") { checks.get() == 2 }
    }

    @Test
    fun `a reconnect loop asks at most once per minimum gap, Check now always asks`() {
        val updates = updates()
        updates.start()
        repeat(4) {
            target.value = UpdateTarget(pc, lan)
            settle()
            target.value = null
            settle()
            clock += 1_000
        }
        assertEquals("one check for four reconnects", 1, checks.get())
        target.value = UpdateTarget(pc, lan)
        settle()
        updates.checkNow()
        waitFor("Check now asks") { checks.get() == 2 }
        target.value = null
        settle()
        clock += AppUpdateRules.MIN_CHECK_GAP_MILLIS
        target.value = UpdateTarget(pc, lan)
        waitFor("asks again after the gap") { checks.get() == 3 }
    }

    @Test
    fun `a disconnect during a check clears Asking your PC`() {
        val started = CompletableDeferred<Unit>()
        source2 = { started.complete(Unit); awaitCancellation() }
        val updates = updates()
        updates.start()
        target.value = UpdateTarget(pc, lan)
        runBlocking { withTimeout(5_000) { started.await() } }
        assertTrue(updates.view.value.checking)
        target.value = null
        waitFor("checking cleared") { !updates.view.value.checking }
    }

    @Test
    fun `a new version is offered and notified once, also across restarts`() {
        val updates = updates()
        updates.start()
        target.value = UpdateTarget(pc, lan)
        waitFor("offered") { updates.view.value.available == next }
        assertEquals(listOf(next), shown)
        updates.checkNow()
        waitFor("second check") { checks.get() == 2 }
        settle()
        assertEquals(listOf(next), shown)

        val again = updates().also { it.start() }
        again.checkNow()
        waitFor("offered after a restart") { again.view.value.available == next }
        assertEquals("same version, no second notification", listOf(next), shown)

        val newer = next.copy(version = "2.1.2", versionCode = 20102)
        answer = UpdateCheck.Available(newer)
        again.checkNow()
        waitFor("the newer version notified") { shown.size == 2 }
        assertEquals(newer, shown.last())
    }

    @Test
    fun `up to date clears the offer and its notification, a failed check keeps the offer and says so`() {
        val updates = updates()
        updates.start()
        target.value = UpdateTarget(pc, lan)
        waitFor("offered") { updates.view.value.available == next }
        answer = UpdateCheck.Failed
        updates.checkNow()
        waitFor("failed check shown") { updates.view.value.checkFailed }
        assertEquals(next, updates.view.value.available)
        answer = UpdateCheck.UpToDate
        updates.checkNow()
        waitFor("up to date") { updates.view.value.available == null && !updates.view.value.checking }
        assertFalse(updates.view.value.checkFailed)
        assertTrue(cancelled.get() >= 1)
    }

    @Test
    fun `check now offline says it could not ask`() {
        val updates = updates()
        updates.start()
        updates.checkNow()
        waitFor("failed") { updates.view.value.checkFailed }
        assertEquals(0, checks.get())
    }

    @Test
    fun `download, verify and install, and the file is gone afterwards`() {
        val updates = offered()
        updates.update()
        waitFor("installing") { installs.size == 1 }
        assertEquals("aristotle-20101.apk" to true, installs.single())
        waitFor("step installing") { updates.view.value.step == UpdateStep.Installing }
        assertTrue("no file left", dir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `without the install permission the update waits for it, then runs`() {
        canInstall = false
        val updates = offered()
        updates.update()
        waitFor("asks") { updates.view.value.step == UpdateStep.NeedsPermission }
        updates.permissionReturned()
        settle()
        assertEquals("still not allowed", UpdateStep.NeedsPermission, updates.view.value.step)
        canInstall = true
        updates.permissionReturned()
        waitFor("installed after the permission") { installs.size == 1 }
    }

    @Test
    fun `an APK that fails a check is deleted and never installed`() {
        val cases = listOf(
            Triple(ApkFacts(PACKAGE, 20101, setOf("ee".repeat(32))), sha, UpdateFailure.OtherSigner),
            Triple(ApkFacts("com.example.other", 20101, setOf(SIGNER)), sha, UpdateFailure.OtherPackage),
            Triple(null, sha, UpdateFailure.Unreadable),
            Triple(ApkFacts(PACKAGE, 20101, setOf(SIGNER)), "cd".repeat(32), UpdateFailure.HashMismatch),
        )
        val updates = offered()
        cases.forEach { (facts, got, failure) ->
            archive = facts
            download = { file, _ -> file.writeBytes(byteArrayOf(1, 2, 3, 4)); DownloadResult.Done(got) }
            updates.update()
            waitFor("refused: $failure") { updates.view.value.step == UpdateStep.Failed(failure) }
            assertTrue("$failure: no file left", dir.listFiles().orEmpty().isEmpty())
        }
        assertTrue(installs.isEmpty())
    }

    @Test
    fun `a failed download is deleted`() {
        download = { file, _ -> file.writeBytes(byteArrayOf(1, 2)); DownloadResult.Failed(DownloadProblem.Truncated) }
        val updates = offered()
        updates.update()
        waitFor("failed") { updates.view.value.step == UpdateStep.Failed(UpdateFailure.Truncated) }
        assertTrue(dir.listFiles().orEmpty().isEmpty())
        assertTrue(installs.isEmpty())
    }

    @Test
    fun `cancel stops the download and deletes the file`() {
        val started = CompletableDeferred<Unit>()
        download = { file, progress ->
            file.writeBytes(byteArrayOf(1, 2))
            progress(2)
            started.complete(Unit)
            awaitCancellation()
        }
        val updates = offered()
        updates.update()
        runBlocking { withTimeout(5_000) { started.await() } }
        assertEquals(UpdateStep.Downloading(2, 4), updates.view.value.step)
        updates.cancel()
        waitFor("idle") { updates.view.value.step == UpdateStep.Idle }
        assertTrue(dir.listFiles().orEmpty().isEmpty())
        assertTrue(installs.isEmpty())
    }

    @Test
    fun `an install the user cancelled or Android refused shows in the row`() {
        val updates = installing()
        updates.installFinished(InstallOutcome.Aborted, lastSession)
        assertEquals(UpdateStep.Failed(UpdateFailure.InstallAborted), updates.view.value.step)
        assertEquals("the held confirmation is dropped", 1, drops.get())
        updates.update()
        waitFor("a second run") { installs.size == 2 && updates.view.value.step == UpdateStep.Installing }
        updates.installFinished(InstallOutcome.Storage, lastSession)
        assertEquals(UpdateStep.Failed(UpdateFailure.InstallStorage), updates.view.value.step)
    }

    @Test
    fun `a pending confirmation is shown, kept, and shown again when the app comes to the front`() {
        val updates = installing()
        updates.installFinished(InstallOutcome.PendingUserAction, lastSession)
        assertEquals(UpdateStep.Confirming, updates.view.value.step)
        assertEquals("shown at once", 1, confirms.get())
        updates.appResumed()
        fire(AppUpdateRules.CONFIRM_SETTLE_MILLIS)
        waitFor("shown again on resume") { confirms.get() == 2 }
        assertEquals(UpdateStep.Confirming, updates.view.value.step)
        updates.confirmInstall()
        assertEquals("the Confirm install button shows it too", 3, confirms.get())
    }

    @Test
    fun `coming back from the confirm screen after Cancel does not show it again`() {
        val updates = installing()
        updates.installFinished(InstallOutcome.PendingUserAction, lastSession)
        updates.appResumed()
        waitFor("settling") { AppUpdateRules.CONFIRM_SETTLE_MILLIS in waiting }
        updates.installFinished(InstallOutcome.Aborted, lastSession)
        fire(AppUpdateRules.CONFIRM_SETTLE_MILLIS)
        settle()
        assertEquals(1, confirms.get())
        assertEquals(UpdateStep.Failed(UpdateFailure.InstallAborted), updates.view.value.step)
    }

    @Test
    fun `a resume with nothing pending shows nothing`() {
        val updates = installing()
        updates.appResumed()
        settle()
        assertEquals(0, confirms.get())
    }

    @Test
    fun `a confirmation that cannot be shown fails the row`() {
        confirmWorks = false
        val updates = installing()
        updates.installFinished(InstallOutcome.PendingUserAction, lastSession)
        assertEquals(UpdateStep.Failed(UpdateFailure.InstallFailed), updates.view.value.step)
    }

    @Test
    fun `an install with no status returns the row to Update after the timeout, and Update works again`() {
        val updates = installing()
        fire(AppUpdateRules.INSTALL_TIMEOUT_MILLIS)
        waitFor("timed out") { updates.view.value.step == UpdateStep.Failed(UpdateFailure.InstallNotFinished) }
        assertEquals("the offer stays, so the button is Try again", next, updates.view.value.available)
        updates.update()
        waitFor("a second install") { installs.size == 2 }
        assertEquals(UpdateStep.Installing, updates.view.value.step)
    }

    @Test
    fun `a pending confirmation nobody answers times out too, and a late status is ignored`() {
        val updates = installing()
        updates.installFinished(InstallOutcome.PendingUserAction, lastSession)
        fire(AppUpdateRules.INSTALL_TIMEOUT_MILLIS)
        waitFor("timed out") { updates.view.value.step == UpdateStep.Failed(UpdateFailure.InstallNotFinished) }
        updates.installFinished(InstallOutcome.Aborted, lastSession)
        assertEquals(UpdateStep.Failed(UpdateFailure.InstallNotFinished), updates.view.value.step)
        assertNull("the marker is gone", store.get("update_installing_version_code"))
    }

    @Test
    fun `a status before the timeout stops the timer`() {
        val updates = installing()
        updates.installFinished(InstallOutcome.Blocked, lastSession)
        fire(AppUpdateRules.INSTALL_TIMEOUT_MILLIS)
        settle()
        assertEquals(UpdateStep.Failed(UpdateFailure.InstallBlocked), updates.view.value.step)
    }

    @Test
    fun `a double tap on Update starts one download`() {
        val started = AtomicInteger()
        val release = CompletableDeferred<Unit>()
        download = { file, _ ->
            started.incrementAndGet()
            release.await()
            file.writeBytes(byteArrayOf(1, 2, 3, 4))
            DownloadResult.Done(sha)
        }
        val updates = offered()
        updates.update()
        updates.update()
        waitFor("one download running") { started.get() == 1 }
        settle()
        assertEquals(1, started.get())
        release.complete(Unit)
        waitFor("installed once") { installs.size == 1 }
        settle()
        assertEquals(1, installs.size)
    }

    @Test
    fun `a checksum mismatch drops the stale offer and asks the PC again`() {
        val newer = AppUpdate("2.1.2", 20102, "cd".repeat(32), 4)
        val updates = offered()
        download = { file, _ -> file.writeBytes(byteArrayOf(1, 2, 3, 4)); DownloadResult.Done("ee".repeat(32)) }
        answer = UpdateCheck.Available(newer)
        updates.update()
        waitFor("the re-check offers the new release") { updates.view.value.available == newer }
        assertEquals(UpdateStep.Failed(UpdateFailure.HashMismatch), updates.view.value.step)
        assertEquals("one check on connect, one after the mismatch", 2, checks.get())
        assertTrue(installs.isEmpty())
        assertTrue(dir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `a checksum mismatch with no newer release leaves Check now, not a stale Try again`() {
        val updates = offered()
        download = { file, _ -> file.writeBytes(byteArrayOf(1, 2, 3, 4)); DownloadResult.Done("ee".repeat(32)) }
        answer = UpdateCheck.UpToDate
        updates.update()
        waitFor("offer dropped") { updates.view.value.available == null && checks.get() == 2 && !updates.view.value.checking }
        assertEquals(UpdateStep.Failed(UpdateFailure.HashMismatch), updates.view.value.step)
    }

    @Test
    fun `after the restart into the new version the row says updated, and leftovers are deleted`() {
        val updates = offered()
        updates.update()
        waitFor("installing") { installs.size == 1 }
        File(dir, "aristotle-20101.apk").writeBytes(byteArrayOf(9))

        val restarted = updates(OwnVersion(20101, "2.1.1"))
        restarted.start()
        assertEquals("2.1.1", restarted.view.value.updatedTo)
        assertTrue(dir.listFiles().orEmpty().isEmpty())
        assertNull("shown once", updates(OwnVersion(20101, "2.1.1")).also { it.start() }.view.value.updatedTo)
    }

    @Test
    fun `a stale Aborted status while a new install is Installing does not fail it`() {
        val updates = installing()
        val old = lastSession
        updates.installFinished(InstallOutcome.Aborted, old)
        assertEquals(UpdateStep.Failed(UpdateFailure.InstallAborted), updates.view.value.step)
        updates.update()
        waitFor("second run") { installs.size == 2 && updates.view.value.step == UpdateStep.Installing }
        updates.installFinished(InstallOutcome.Aborted, old)
        assertEquals("the old session's status is dropped", UpdateStep.Installing, updates.view.value.step)
        updates.installFinished(InstallOutcome.Aborted, 9999)
        assertEquals("an unknown session is dropped", UpdateStep.Installing, updates.view.value.step)
        updates.installFinished(InstallOutcome.PendingUserAction, lastSession)
        assertEquals(UpdateStep.Confirming, updates.view.value.step)
    }

    @Test
    fun `give up, Try again, the stale Aborted arrives while committing, the new confirmation is shown once`() {
        val updates = installing()
        val old = lastSession
        fire(AppUpdateRules.INSTALL_TIMEOUT_MILLIS)
        waitFor("timed out") { updates.view.value.step == UpdateStep.Failed(UpdateFailure.InstallNotFinished) }
        assertEquals("give-up abandons its session", listOf(old), abandoned.toList())
        assertNull("the session id is gone", store.get("update_install_session_id"))
        duringInstall = { updates.installFinished(InstallOutcome.Aborted, old) }
        updates.update()
        waitFor("second run") { installs.size == 2 && updates.view.value.step == UpdateStep.Installing }
        assertEquals("the new session is kept", lastSession.toString(), store.get("update_install_session_id"))
        updates.installFinished(InstallOutcome.PendingUserAction, lastSession)
        assertEquals(UpdateStep.Confirming, updates.view.value.step)
        assertEquals(1, confirms.get())
    }

    @Test
    fun `a pending confirmation of an abandoned session after the give-up is not shown`() {
        val updates = installing()
        val old = lastSession
        fire(AppUpdateRules.INSTALL_TIMEOUT_MILLIS)
        waitFor("timed out") { updates.view.value.step == UpdateStep.Failed(UpdateFailure.InstallNotFinished) }
        assertFalse(updates.acceptsStatus(old))
        updates.installFinished(InstallOutcome.PendingUserAction, old)
        assertEquals(0, confirms.get())
        assertEquals(UpdateStep.Failed(UpdateFailure.InstallNotFinished), updates.view.value.step)
    }

    @Test
    fun `a check that failed does not block the next on-connect check, an answer does`() {
        answer = UpdateCheck.Failed
        updates().start()
        target.value = UpdateTarget(pc, lan)
        waitFor("the failed check") { checks.get() == 1 && pauses.size == 1 }
        target.value = null
        settle()
        clock += 1_000
        target.value = UpdateTarget(pc, lan)
        waitFor("asks again at once after a failure") { checks.get() == 2 }
        answer = UpdateCheck.UpToDate
        ticks.trySend(Unit)
        waitFor("answered") { checks.get() == 3 }
        settle()
        target.value = null
        settle()
        clock += 1_000
        target.value = UpdateTarget(pc, lan)
        settle()
        assertEquals("answered a moment ago: no new check", 3, checks.get())
    }

    @Test
    fun `a check cut off by a disconnect does not block the next on-connect check`() {
        val started = CompletableDeferred<Unit>()
        source2 = { started.complete(Unit); awaitCancellation() }
        updates().start()
        target.value = UpdateTarget(pc, lan)
        runBlocking { withTimeout(5_000) { started.await() } }
        target.value = null
        settle()
        source2 = null
        target.value = UpdateTarget(pc, lan)
        waitFor("asks again") { checks.get() == 2 }
    }

    @Test
    fun `install outcomes from the system status`() {
        assertEquals(InstallOutcome.Success, InstallOutcome.of(0))
        assertEquals(InstallOutcome.PendingUserAction, InstallOutcome.of(-1))
        assertEquals(InstallOutcome.Failed, InstallOutcome.of(1))
        assertEquals(InstallOutcome.Blocked, InstallOutcome.of(2))
        assertEquals(InstallOutcome.Aborted, InstallOutcome.of(3))
        assertEquals(InstallOutcome.Refused, InstallOutcome.of(4))
        assertEquals(InstallOutcome.Refused, InstallOutcome.of(5))
        assertEquals(InstallOutcome.Storage, InstallOutcome.of(6))
        assertEquals(InstallOutcome.Refused, InstallOutcome.of(7))
        assertEquals(InstallOutcome.Failed, InstallOutcome.of(42))
    }

    /** Offered, tapped, committed: the row says Installing and waits for Android's status. */
    private fun installing(): AppUpdates = offered().also {
        it.update()
        waitFor("installing") { installs.isNotEmpty() && it.view.value.step == UpdateStep.Installing }
        waitFor("the install timer runs") { AppUpdateRules.INSTALL_TIMEOUT_MILLIS in waiting }
    }

    /** Started, connected, with [next] offered. */
    private fun offered(): AppUpdates = updates().also {
        it.start()
        target.value = UpdateTarget(pc, lan)
        waitFor("offered") { it.view.value.available == next }
    }

    private companion object {
        const val PACKAGE = "io.github.justcallmesu.aristotle"
        val SIGNER = "01".repeat(32)
    }
}
