package io.github.justcallmesu.aristotle.data

import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

enum class LogLevel(val letter: Char) { Debug('D'), Info('I'), Warn('W'), Error('E') }

/** Where [AppLog] hands its lines once the app installed it ([LogPipeline]); must not block. */
fun interface LogSink {
    fun log(level: LogLevel, event: String, error: Throwable?)
}

/**
 * The app's one logging door (Q80b, TL2). Every app log goes through here, never `android.util.Log` directly: the
 * line is redacted ([LogRedactor]) and kept in the bounded on-phone debug log ([RingLog]), and in debug builds also
 * goes to logcat.
 *
 * The rule for callers: log an event name with kinds, counts, states and ids (`"link state=Online"`,
 * `"lan scan: probed=12"`). Never message, prompt, reasoning or tool-output text, never a server's error text, never
 * an exception's message: pass the exception as `error` and only its class name is written. Redaction (tokens,
 * pairing codes, fingerprints, query strings, values after secret keys) is the safety net, not a licence.
 * `AppLogSourceGuardTest` fails the build on a direct `Log.` call or a templated content variable in an `AppLog` call.
 *
 * Before [install] (and in JVM tests) the lines go nowhere.
 */
object AppLog {
    @Volatile
    private var sink: LogSink? = null

    fun install(sink: LogSink?) {
        this.sink = sink
    }

    fun d(event: String) {
        sink?.log(LogLevel.Debug, event, null)
    }

    fun i(event: String) {
        sink?.log(LogLevel.Info, event, null)
    }

    fun w(event: String, error: Throwable? = null) {
        sink?.log(LogLevel.Warn, event, error)
    }

    fun e(event: String, error: Throwable? = null) {
        sink?.log(LogLevel.Error, event, error)
    }
}

/**
 * [AppLog]'s writer: [log] only queues (bounded; the oldest line goes when it is full), one background thread
 * redacts and appends to [ring], so no caller ever waits for the disk. [clear] (Unpair) also drops what is queued:
 * a line queued before it is never written after it. [logcat] gets each redacted line too (debug builds only).
 */
class LogPipeline(
    private val ring: RingLog,
    private val clock: () -> Long = System::currentTimeMillis,
    private val logcat: ((LogLevel, String) -> Unit)? = null,
    queueSize: Int = QUEUE,
) : LogSink {
    private class Pending(val at: Long, val level: LogLevel, val event: String, val error: String?, val generation: Int)

    private val queue = ArrayBlockingQueue<Pending>(queueSize)
    private val generation = AtomicInteger()
    private val lock = Any()
    private val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var writer: Thread? = null

    /** Starts the writer thread; call once. Without it lines wait until [flush]. */
    fun start() {
        if (writer != null) return
        writer = Thread({
            while (true) {
                val first = try {
                    queue.take()
                } catch (e: InterruptedException) {
                    return@Thread
                }
                synchronized(lock) { writeLocked(listOf(first) + drain()) }
            }
        }, "aristotle-log").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    override fun log(level: LogLevel, event: String, error: Throwable?) {
        val pending = Pending(clock(), level, event, error?.javaClass?.name, generation.get())
        while (!queue.offer(pending)) queue.poll()
    }

    /** Writes what is queued now, on the caller's thread (the crash handler, tests). */
    fun flush() {
        synchronized(lock) { writeLocked(drain()) }
    }

    /** Every stored line, oldest first, after writing what is queued. */
    fun lines(): List<String> = synchronized(lock) {
        writeLocked(drain())
        ring.lines()
    }

    /** The newest [count] lines after writing what is queued. */
    fun tail(count: Int): List<String> = synchronized(lock) {
        writeLocked(drain())
        ring.tail(count)
    }

    /** Empties the log and what is queued (Unpair). */
    fun clear() {
        synchronized(lock) {
            generation.incrementAndGet()
            queue.clear()
            ring.clear()
        }
    }

    private fun drain(): List<Pending> = buildList { queue.drainTo(this) }

    private fun writeLocked(batch: List<Pending>) {
        val current = generation.get()
        val lines = batch.filter { it.generation == current }.map { pending ->
            val text = LogRedactor.redact(if (pending.error == null) pending.event else "${pending.event} error=${pending.error}")
            logcat?.invoke(pending.level, text)
            "${time.format(Date(pending.at))} ${pending.level.letter} $text"
        }
        try {
            ring.append(lines)
        } catch (e: IOException) {
            // A full or failing disk loses these lines, never the app.
        }
    }

    companion object {
        const val QUEUE = 512
    }
}
