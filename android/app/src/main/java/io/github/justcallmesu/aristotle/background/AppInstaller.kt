package io.github.justcallmesu.aristotle.background

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.IntentCompat
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.net.toUri
import io.github.justcallmesu.aristotle.AristotleApplication
import io.github.justcallmesu.aristotle.data.AppLog
import io.github.justcallmesu.aristotle.data.update.ApkFacts
import io.github.justcallmesu.aristotle.data.update.InstallOutcome
import io.github.justcallmesu.aristotle.data.update.UpdateDevice
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The install side of the in-app updater (P18, P19, Q133): reads an APK's package and signers, and installs it through
 * a `PackageInstaller` session. On API 31+ the session asks for no user action, so Android may update without a tap
 * (a self-update qualifies from the first one, with `UPDATE_PACKAGES_WITHOUT_USER_ACTION`); otherwise it reports
 * `STATUS_PENDING_USER_ACTION` and [UpdateInstallReceiver] opens its confirm screen.
 */
class AndroidUpdateDevice(private val context: Context) : UpdateDevice {
    private val packages: PackageManager get() = context.packageManager

    override fun canInstall(): Boolean = packages.canRequestPackageInstalls()

    override suspend fun inspect(file: File): ApkFacts? = withContext(Dispatchers.IO) {
        runCatching { archiveInfo(file)?.let(::facts) }.getOrNull()
    }

    override fun own(): ApkFacts? = runCatching { facts(ownInfo()) }.getOrNull()

    override fun confirm(): Boolean {
        val screen = PendingConfirm.screen ?: return false
        // A copy per launch: the held Intent stays as Android sent it, so it can be shown again.
        return runCatching { context.startActivity(Intent(screen).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { AppLog.w("update: confirm screen failed", it) }
            .isSuccess
    }

    override fun dropConfirm() {
        PendingConfirm.screen = null
    }

    override fun abandon(sessionId: Int) {
        runCatching { packages.packageInstaller.abandonSession(sessionId) }
    }

    override suspend fun install(file: File, onSession: (Int) -> Unit): Boolean = withContext(Dispatchers.IO) {
        try {
            commit(file, onSession)
            true
        } catch (e: Exception) {
            // IOException from the session, SecurityException without REQUEST_INSTALL_PACKAGES granted.
            AppLog.w("update: install session failed", e)
            false
        }
    }

    private fun commit(file: File, onSession: (Int) -> Unit) {
        val installer = packages.packageInstaller
        // An earlier run that never got a status (timed out) must not leave its session behind.
        installer.mySessions.forEach { runCatching { installer.abandonSession(it.sessionId) } }
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(file.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        onSession(id)
        try {
            installer.openSession(id).use { session ->
                session.openWrite(SESSION_FILE, 0, file.length()).use { out ->
                    file.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                session.commit(statusReceiver(id).intentSender)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(id) }
            throw e
        }
    }

    /** Mutable on API 31+: the installer fills in the status extras. Explicit, to a receiver that is not exported. */
    private fun statusReceiver(sessionId: Int): PendingIntent {
        val mutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val intent = Intent(context, UpdateInstallReceiver::class.java).setPackage(context.packageName)
        return PendingIntent.getBroadcast(context, sessionId, intent, PendingIntent.FLAG_UPDATE_CURRENT or mutable)
    }

    private fun archiveInfo(file: File): PackageInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packages.getPackageArchiveInfo(file.path, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
        } else {
            @Suppress("DEPRECATION")
            packages.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES)
        }

    private fun ownInfo(): PackageInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packages.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
        } else {
            @Suppress("DEPRECATION")
            packages.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        }

    /** The current signers only (`apkContentsSigners`): what Android compares when it replaces the installed app. */
    private fun facts(info: PackageInfo): ApkFacts? {
        val signing = info.signingInfo ?: return null
        val signers = signing.apkContentsSigners.orEmpty().map { sha256Hex(it.toByteArray()) }.toSet()
        return ApkFacts(info.packageName, PackageInfoCompat.getLongVersionCode(info), signers)
    }

    private fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        private const val SESSION_FILE = "base.apk"

        /** Android's "Install unknown apps" page for this app (P19): the user allows it once. */
        fun openInstallPermission(context: Context) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())
            runCatching { context.startActivity(intent) }.onFailure { AppLog.w("update: unknown sources page missing", it) }
        }
    }
}

/**
 * Android's confirm screen from the last `STATUS_PENDING_USER_ACTION`, held in memory so it can be shown again (MAJOR-2):
 * with the app in the background Android silently refuses to open it, and the user may leave it with Home.
 */
object PendingConfirm {
    @Volatile
    var screen: Intent? = null
}

/**
 * The installer session's status (P19). `STATUS_PENDING_USER_ACTION` carries Android's confirm screen: it is held in
 * [PendingConfirm] and [io.github.justcallmesu.aristotle.data.update.AppUpdates] shows it (now, and again when the app
 * is in front). Every status goes to the About row. Not exported: only the session's own PendingIntent reaches it.
 */
class UpdateInstallReceiver : BroadcastReceiver() {
    private companion object {
        const val NO_SESSION = -1
    }

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, NO_SESSION)
        val updates = (context.applicationContext as AristotleApplication).container.appUpdates
        // A status of an earlier (abandoned, timed-out) session must not touch the current install.
        if (!updates.acceptsStatus(sessionId)) {
            AppLog.i("update: dropped a stale install status=$status")
            return
        }
        AppLog.i("update: install status=$status")
        val outcome = InstallOutcome.of(status)
        if (outcome == InstallOutcome.PendingUserAction) {
            PendingConfirm.screen = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
        }
        updates.installFinished(outcome, sessionId)
    }
}

/**
 * After the app was replaced (an update) the process is new and nothing runs: Android's own relaunch rules let a
 * receiver for `ACTION_MY_PACKAGE_REPLACED` start a foreground service from the background (FGS background-start
 * exemptions, Android 12+), so the link service comes back when "Keep connected" is on, without opening the app.
 */
class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        AppLog.i("update: app replaced, resuming the link service if wanted")
        (context.applicationContext as AristotleApplication).container.background.resumeAfterUpdate()
    }
}
