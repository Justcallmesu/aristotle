package io.github.justcallmesu.aristotle.data

import java.io.File
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.IdentityHashMap
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.concurrent.thread

/** The build and the phone, for the top of a crash report or a shared log. Never anything of the user's. */
data class AppInfo(
    val versionName: String,
    val versionCode: Int,
    val androidRelease: String,
    val sdk: Int,
    val manufacturer: String,
    val model: String,
) {
    fun header(): List<String> = listOf(
        "App: Aristotle $versionName ($versionCode)",
        "Android: $androidRelease (SDK $sdk)",
        "Device: $manufacturer $model",
    )
}

/**
 * The last crash, on disk (Q80b, TL2). The crash handler [write]s `crash.txt`; the dialog's first composition [takeOffer]s it once
 * (renamed to `crash-offered.txt`; an offer left from an earlier launch is deleted first, so a report is never offered
 * twice). Send and Dismiss both end it ([dismiss]).
 */
class CrashStore(private val dir: File) {
    private val fresh get() = File(dir, "crash.txt")
    private val offered get() = File(dir, "crash-offered.txt")
    private val partial get() = File(dir, "crash.tmp")

    /** Replaces any earlier unread crash; written whole or not at all. */
    fun write(report: String) {
        dir.mkdirs()
        partial.writeText(report, Charsets.UTF_8)
        if (!partial.renameTo(fresh)) {
            fresh.delete()
            partial.renameTo(fresh)
        }
    }

    /** At launch: true when a crash is waiting to be offered now. */
    fun takeOffer(): Boolean {
        offered.delete()
        partial.delete()
        return fresh.isFile && fresh.renameTo(offered)
    }

    /** The report on offer, or null. */
    fun offered(): String? = offered.takeIf { it.isFile }?.readText(Charsets.UTF_8)

    fun dismiss() {
        offered.delete()
    }

    fun clear() {
        fresh.delete()
        offered.delete()
        partial.delete()
    }
}

/** The text of a crash report: build and phone, the throwable (class, redacted message, stack), the log's last lines. */
object CrashText {
    const val MAX_FRAMES = 80
    const val MAX_CAUSES = 8

    /** Exception messages are cut short: they can quote input, and redaction cannot recognise content. */
    const val MAX_MESSAGE = 300

    fun build(info: AppInfo, at: Long, threadName: String, error: Throwable, logTail: List<String>): String = buildString {
        appendLine("Aristotle crash report")
        appendLine("Time: ${stamp(at)}")
        info.header().forEach(::appendLine)
        appendLine("Thread: ${LogRedactor.redact(threadName)}")
        appendLine()
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        var current: Throwable? = error
        var depth = 0
        while (current != null && seen.add(current) && depth <= MAX_CAUSES) {
            appendLine((if (depth == 0) "" else "Caused by: ") + describe(current))
            current.stackTrace.take(MAX_FRAMES).forEach { appendLine("    at $it") }
            if (current.stackTrace.size > MAX_FRAMES) appendLine("    … ${current.stackTrace.size - MAX_FRAMES} more")
            current = current.cause
            depth++
        }
        appendLine()
        appendLine("Last ${logTail.size} app log lines:")
        logTail.forEach(::appendLine)
    }

    const val OWN_PACKAGE = "io.github.justcallmesu.aristotle"

    /**
     * `class: message` for the app's own exception classes (message redacted, cut to [MAX_MESSAGE]); the class name alone
     * for every other class, since a library's message can quote its input.
     */
    fun describe(error: Throwable): String {
        val own = error.javaClass.name.startsWith("$OWN_PACKAGE.")
        val message = error.message?.takeIf { own }?.let { LogRedactor.redact(it) }?.let { if (it.length > MAX_MESSAGE) it.take(MAX_MESSAGE) + "…" else it }
        return if (message.isNullOrBlank()) error.javaClass.name else "${error.javaClass.name}: $message"
    }

    fun stamp(at: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(Date(at))
}

/**
 * The debug log and crash report as one piece (Q80b, TL2): [AppLog]'s pipeline over a [RingLog] in [logDir], the
 * crash handler and its [CrashStore] in [crashDir], and the one-file shares built in [shareDir] (the app cache's
 * `share/`, the only folder the FileProvider exposes for it). Nothing here sends anything: a share is a file the user
 * hands to the Android share sheet. [logDir] and [crashDir] are app-private and kept from backup and device transfer.
 */
class Diagnostics(
    logDir: File,
    crashDir: File,
    private val shareDir: File,
    private val info: AppInfo,
    private val clock: () -> Long = System::currentTimeMillis,
    logcat: ((LogLevel, String) -> Unit)? = null,
    ring: RingLog = RingLog(logDir),
) {
    val pipeline = LogPipeline(ring, clock, logcat)
    private val crashes = CrashStore(crashDir)
    private val offer = MutableStateFlow(false)
    private val claimed = AtomicBoolean(false)

    /** True while a crash from an earlier run waits for Send report or Dismiss. */
    val crashOffered: StateFlow<Boolean> = offer.asStateFlow()

    /** Starts the log, the crash handler, and (off the main thread) the crash offer and the old-share sweep. */
    fun start() {
        pipeline.start()
        AppLog.install(pipeline)
        Thread.setDefaultUncaughtExceptionHandler(crashHandler(Thread.getDefaultUncaughtExceptionHandler()))
        thread(name = "aristotle-diagnostics", isDaemon = true) { sweepShares() }
        AppLog.i("app start version=${info.versionName} code=${info.versionCode} sdk=${info.sdk}")
    }

    /**
     * Claims the crash from an earlier run, once per process; the crash dialog calls it when it first composes (blocking).
     * A headless start (a service restart) never reaches it, so it cannot use the report up.
     */
    fun claimCrash() {
        if (!claimed.compareAndSet(false, true)) return
        if (crashes.takeOffer()) offer.value = true
    }

    /** Deletes share files left by an earlier run (blocking; [start] runs it on a thread). */
    fun sweepShares() {
        val stale = clock() - SHARE_MAX_AGE_MILLIS
        shareDir.listFiles()?.filter { it.lastModified() < stale }?.forEach { it.delete() }
    }

    /**
     * Writes the crash report, then hands the crash to [previous] (Android's own handler: the app still dies as it
     * would). Writing never throws past here.
     */
    fun crashHandler(previous: Thread.UncaughtExceptionHandler?): Thread.UncaughtExceptionHandler =
        Thread.UncaughtExceptionHandler { thread, error ->
            record(thread.name, error)
            if (previous != null) previous.uncaughtException(thread, error) else Runtime.getRuntime().halt(CRASH_EXIT)
        }

    /** The crash file for [error]; false when it could not be written. */
    fun record(threadName: String, error: Throwable): Boolean = runCatching {
        pipeline.log(LogLevel.Error, "crash thread=$threadName", error)
        crashes.write(CrashText.build(info, clock(), threadName, error, pipeline.tail(CRASH_TAIL_LINES)))
    }.isSuccess

    /** Send report: the offered report as a file to share; the offer ends (it is never offered again). Blocking. */
    fun crashReportFile(): File? {
        val report = crashes.offered()
        crashes.dismiss()
        offer.value = false
        return report?.let { writeShare(CRASH_FILE, it) }
    }

    /** Dismiss: the crash file is deleted. Blocking. */
    fun dismissCrash() {
        crashes.dismiss()
        offer.value = false
    }

    /** Settings › About › Send debug log: the whole log with a header, as a file to share. Blocking. */
    fun debugLogFile(): File {
        val lines = pipeline.lines()
        val text = buildString {
            appendLine("Aristotle debug log")
            appendLine("Created: ${CrashText.stamp(clock())}")
            info.header().forEach(::appendLine)
            appendLine(LOG_NOTE)
            appendLine()
            lines.forEach(::appendLine)
        }
        return writeShare(LOG_FILE, text)
    }

    /** Unpair: the log, anything queued for it, the crash files and the share files go. Blocking. */
    fun clear() {
        pipeline.clear()
        crashes.clear()
        offer.value = false
        shareDir.listFiles()?.forEach { it.delete() }
    }

    private fun writeShare(name: String, text: String): File {
        shareDir.mkdirs()
        shareDir.listFiles()?.forEach { it.delete() }
        return File(shareDir, name).apply { writeText(text, Charsets.UTF_8) }
    }

    companion object {
        /** The app cache's folder for shared files; `res/xml/file_paths.xml` exposes exactly this one. */
        const val SHARE_DIR = "share"

        /** The FileProvider's authority after the package name (the manifest's `applicationId` + `.files`). */
        const val PROVIDER_SUFFIX = ".files"
        const val LOG_DIR = "debug-log"
        const val CRASH_DIR = "crash"
        const val LOG_FILE = "aristotle-debug-log.txt"
        const val CRASH_FILE = "aristotle-crash-report.txt"
        const val CRASH_TAIL_LINES = 200
        const val SHARE_MAX_AGE_MILLIS = 24L * 60 * 60 * 1000
        private const val CRASH_EXIT = 10
        const val LOG_NOTE =
            "Contains: app events, connection states, error types and app/phone versions. " +
                "Never: messages, prompts, replies, tool output, tokens, passwords, pairing codes or full fingerprints."
    }
}
