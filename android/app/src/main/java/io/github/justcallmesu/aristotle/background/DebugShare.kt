package io.github.justcallmesu.aristotle.background

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import io.github.justcallmesu.aristotle.BuildConfig
import io.github.justcallmesu.aristotle.data.AppInfo
import io.github.justcallmesu.aristotle.data.AppLog
import io.github.justcallmesu.aristotle.data.Diagnostics
import io.github.justcallmesu.aristotle.data.LogLevel
import java.io.File

/**
 * The Android side of the debug log and crash report (Q80b, TL2). This is the one file that talks to logcat (debug
 * builds only, lines already redacted); the rest of the app logs through [AppLog].
 */
object DebugShare {
    private const val TAG = "Aristotle"

    /**
     * Built in `Application.onCreate`, before anything else, so the crash handler covers the whole process. The log and
     * crash files live in `noBackupFilesDir` (app-private, never backed up; the backup rules exclude every domain too).
     */
    fun diagnostics(app: Context): Diagnostics = Diagnostics(
        logDir = File(app.noBackupFilesDir, Diagnostics.LOG_DIR),
        crashDir = File(app.noBackupFilesDir, Diagnostics.CRASH_DIR),
        shareDir = File(app.cacheDir, Diagnostics.SHARE_DIR),
        info = AppInfo(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, Build.VERSION.RELEASE.orEmpty(), Build.VERSION.SDK_INT, Build.MANUFACTURER.orEmpty(), Build.MODEL.orEmpty()),
        logcat = if (BuildConfig.DEBUG) ::toLogcat else null,
    )

    private fun toLogcat(level: LogLevel, line: String) {
        val priority = when (level) {
            LogLevel.Debug -> Log.DEBUG
            LogLevel.Info -> Log.INFO
            LogLevel.Warn -> Log.WARN
            LogLevel.Error -> Log.ERROR
        }
        Log.println(priority, TAG, line)
    }

    /**
     * Opens the share sheet for [file] (in the cache's `share/`), readable by the app the user picks through a one-off
     * grant. Nothing is sent unless the user sends it there. False when no app can take it.
     */
    fun open(activity: Activity, file: File, subject: String, chooserTitle: String): Boolean {
        val uri = FileProvider.getUriForFile(activity, activity.packageName + Diagnostics.PROVIDER_SUFFIX, file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, subject)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(subject, uri)
        return try {
            activity.startActivity(Intent.createChooser(send, chooserTitle).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            true
        } catch (e: ActivityNotFoundException) {
            AppLog.w("share sheet not opened", e)
            false
        }
    }
}
