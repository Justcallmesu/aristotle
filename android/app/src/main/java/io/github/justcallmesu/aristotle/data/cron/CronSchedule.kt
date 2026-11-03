package io.github.justcallmesu.aristotle.data.cron

import java.time.DayOfWeek
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeParseException

/** A time unit of an interval or a one-shot delay, with its letter in Hermes' duration grammar (`cron/jobs.py:677-686`). */
enum class TimeUnit(val letter: String, val minutes: Int) {
    Minutes("m", 1), Hours("h", 60), Days("d", 1440)
}

/**
 * A Hermes schedule string (`schedule_display`) read for people. The shapes are the ones Hermes itself writes
 * (`cron/jobs.py:770-860`): an interval as `every <N>m`, a cron expression as given, a one-shot as `once in …` /
 * `once at …`; natural phrases (`every monday 9am`) keep their own words ([Raw]). Clock times are the PC's: Hermes
 * runs cron expressions in its configured zone (`cron/jobs.py:820-828`).
 */
sealed interface ScheduleWords {
    data class Every(val count: Int, val unit: TimeUnit) : ScheduleWords
    data class Daily(val hour: Int, val minute: Int) : ScheduleWords
    data class Weekdays(val hour: Int, val minute: Int) : ScheduleWords
    data class Weekly(val day: DayOfWeek, val hour: Int, val minute: Int) : ScheduleWords
    data class Monthly(val day: Int, val hour: Int, val minute: Int) : ScheduleWords
    data object Once : ScheduleWords
    data class Raw(val text: String) : ScheduleWords
}

/** How the picker asks: the Desktop's frequencies (`apps/desktop/src/plugins/hermes-bots/cron.tsx:613`, `:632-643`). */
enum class Frequency { Once, Hourly, Daily, Weekdays, Weekly, Monthly, Interval, Advanced }

/**
 * The create form's schedule picker, the Desktop's `ScheduleState` (`cron.tsx:617-628`, defaults `:931-944`). Numbers
 * stay text as typed; [CronSchedule.compose] turns them into a Hermes schedule string.
 */
data class SchedulePlan(
    val frequency: Frequency = Frequency.Daily,
    val hour: Int = 9,
    val minute: Int = 0,
    val weekday: DayOfWeek = DayOfWeek.MONDAY,
    val monthDay: String = "1",
    val intervalCount: String = "2",
    val intervalUnit: TimeUnit = TimeUnit.Hours,
    val onceCount: String = "30",
    val onceUnit: TimeUnit = TimeUnit.Minutes,
    val raw: String = "",
    /** "Stop after N runs"; blank = never. Only for repeating frequencies (`cron.tsx:910-925`, `:1008-1011`). */
    val repeat: String = "",
) {
    val repeats: Boolean get() = frequency != Frequency.Once && frequency != Frequency.Advanced
    val needsTime: Boolean get() = frequency in TIMED
}

private val TIMED = setOf(Frequency.Daily, Frequency.Weekdays, Frequency.Weekly, Frequency.Monthly)

/** Schedule strings both ways and the next-run time on this phone. Pure. */
object CronSchedule {
    /** The picker's times: every half hour, as the Desktop offers them (`cron.tsx:651-665`). */
    val halfHours: List<Pair<Int, Int>> = (0 until 24).flatMap { h -> listOf(h to 0, h to 30) }

    /**
     * The Hermes schedule string for [plan], as the Desktop composes it (`cron.tsx:668-701`) with one difference: a
     * one-shot is sent as `in <N><unit>`, Hermes' explicit one-shot form. The Desktop sends a bare `30m`, which Hermes
     * parses as a recurring interval (`cron/jobs.py:837-851`). Blank when there is nothing to send.
     */
    fun compose(plan: SchedulePlan): String {
        val m = plan.minute
        val h = plan.hour
        return when (plan.frequency) {
            Frequency.Once -> "in ${positive(plan.onceCount)}${plan.onceUnit.letter}"
            Frequency.Hourly -> "every 1h"
            Frequency.Daily -> "$m $h * * *"
            Frequency.Weekdays -> "$m $h * * 1-5"
            Frequency.Weekly -> "$m $h * * ${cronDay(plan.weekday)}"
            Frequency.Monthly -> "$m $h ${plan.monthDay.ifBlank { "1" }} * *"
            Frequency.Interval -> "every ${positive(plan.intervalCount)}${plan.intervalUnit.letter}"
            Frequency.Advanced -> plan.raw.trim()
        }
    }

    /** The month-day field after an edit to [text]: digits only, 1-31 (a day Hermes' cron would refuse never gets in); [previous] when [text] is outside. */
    fun monthDay(text: String, previous: String): String {
        val digits = text.filter(Char::isDigit).take(2)
        return if (digits.isEmpty() || digits.toInt() in 1..31) digits else previous
    }

    /** "Stop after N runs" for [plan]: null for a one-shot, the raw string, or a blank field (`cron.tsx:1008-1011`). */
    fun repeat(plan: SchedulePlan): Int? = if (plan.repeats && plan.repeat.isNotBlank()) positive(plan.repeat) else null

    /** Reads Hermes' display string (see [ScheduleWords]); anything else is [ScheduleWords.Raw]. */
    fun describe(schedule: String): ScheduleWords {
        val text = schedule.trim()
        if (text.startsWith("once ")) return ScheduleWords.Once
        INTERVAL.matchEntire(text)?.let { return every(it.groupValues[1].toInt()) }
        return cron(text) ?: ScheduleWords.Raw(text)
    }

    /**
     * Hermes' ISO time ([iso], with its offset) in [zone]; null when absent or unreadable. A naive value (no offset)
     * is read in [zone], which is a guess: Hermes writes aware times (`cron/jobs.py:820-833`).
     */
    fun at(iso: String?, zone: ZoneId): ZonedDateTime? {
        val text = iso?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return try {
            OffsetDateTime.parse(text).atZoneSameInstant(zone)
        } catch (_: DateTimeParseException) {
            try {
                java.time.LocalDateTime.parse(text).atZone(zone)
            } catch (_: DateTimeParseException) {
                null
            }
        }
    }

    /**
     * The next run is overdue: an active job whose slot passed more than the grace ago, which only a stopped scheduler
     * leaves behind; never show it as upcoming (the Desktop's `nextRunOverdueMs`, `apps/desktop/src/app/cron/job-state.ts:42-69`).
     */
    fun overdue(job: CronJob, now: Instant): Boolean {
        if (!job.active || job.state == "completed") return false
        val next = at(job.nextRunAt, ZoneId.of("UTC"))?.toInstant() ?: return false
        return now.toEpochMilli() - next.toEpochMilli() > OVERDUE_GRACE_MS
    }

    /** `hermes_cli/cron.py` `_OVERDUE_GRACE_SECONDS`, mirrored by the Desktop (`job-state.ts:42-43`). */
    const val OVERDUE_GRACE_MS = 15 * 60 * 1000L

    private val INTERVAL = Regex("""every (\d+)m""")
    private val NUMBER = Regex("""\d{1,2}""")

    private fun every(minutes: Int): ScheduleWords = when {
        minutes > 0 && minutes % TimeUnit.Days.minutes == 0 -> ScheduleWords.Every(minutes / TimeUnit.Days.minutes, TimeUnit.Days)
        minutes > 0 && minutes % TimeUnit.Hours.minutes == 0 -> ScheduleWords.Every(minutes / TimeUnit.Hours.minutes, TimeUnit.Hours)
        else -> ScheduleWords.Every(minutes, TimeUnit.Minutes)
    }

    /** The cron shapes the picker writes (`minute hour * * *`, `… * * 1-5`, `… * * D`, `… D * *`); others stay raw. */
    private fun cron(text: String): ScheduleWords? {
        val parts = text.split(Regex("\\s+"))
        if (parts.size != 5) return null
        val (minute, hour, dom, month, dow) = parts
        if (!NUMBER.matches(minute) || !NUMBER.matches(hour) || month != "*") return null
        val m = minute.toInt()
        val h = hour.toInt()
        if (m > 59 || h > 23) return null
        return when {
            dom == "*" && dow == "*" -> ScheduleWords.Daily(h, m)
            dom == "*" && dow == "1-5" -> ScheduleWords.Weekdays(h, m)
            dom == "*" && dow.length == 1 && dow[0] in '0'..'7' -> ScheduleWords.Weekly(dayOf(dow[0] - '0'), h, m)
            dow == "*" && NUMBER.matches(dom) && dom.toInt() in 1..31 -> ScheduleWords.Monthly(dom.toInt(), h, m)
            else -> null
        }
    }

    /** Cron weekday numbers: 0 and 7 are Sunday (croniter, `cron/jobs.py:689-699`). */
    private fun dayOf(n: Int): DayOfWeek = if (n == 0 || n == 7) DayOfWeek.SUNDAY else DayOfWeek.of(n)

    private fun cronDay(day: DayOfWeek): Int = day.value % 7

    private fun positive(text: String): Int = maxOf(1, text.trim().toIntOrNull() ?: 1)
}
