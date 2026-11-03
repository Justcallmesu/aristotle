package io.github.justcallmesu.aristotle.ui.cron

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import io.github.justcallmesu.aristotle.R
import io.github.justcallmesu.aristotle.data.cron.ScheduleWords
import io.github.justcallmesu.aristotle.data.cron.TimeUnit
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** Clock and date text for the cron pages, in the phone's zone and language. Pure. */
object CronTimes {
    /** A schedule's clock time (the PC's clock), 24-hour as the rest of the app. */
    fun clock(hour: Int, minute: Int, locale: Locale): String = LocalTime.of(hour, minute).format(DateTimeFormatter.ofPattern(TIME, locale))

    /** A run time: "HH:mm" today, else "Mon 4 Nov HH:mm" (another year adds it). */
    fun at(time: ZonedDateTime, now: ZonedDateTime, locale: Locale): String {
        val local = time.withZoneSameInstant(now.zone)
        val pattern = when {
            local.toLocalDate() == now.toLocalDate() -> TIME
            local.year == now.year -> "EEE d MMM $TIME"
            else -> "EEE d MMM yyyy $TIME"
        }
        return local.format(DateTimeFormatter.ofPattern(pattern, locale))
    }

    fun weekday(day: DayOfWeek, locale: Locale): String = day.getDisplayName(TextStyle.FULL, locale)

    private const val TIME = "HH:mm"
}

/** [words] in the phone's language; clock times are the PC's (the page says so). */
@Composable
fun scheduleText(words: ScheduleWords, locale: Locale): String = when (words) {
    is ScheduleWords.Every -> when (words.unit) {
        TimeUnit.Minutes -> pluralStringResource(R.plurals.cron_every_minutes, words.count, words.count)
        TimeUnit.Hours -> pluralStringResource(R.plurals.cron_every_hours, words.count, words.count)
        TimeUnit.Days -> pluralStringResource(R.plurals.cron_every_days, words.count, words.count)
    }
    is ScheduleWords.Daily -> stringResource(R.string.cron_daily, CronTimes.clock(words.hour, words.minute, locale))
    is ScheduleWords.Weekdays -> stringResource(R.string.cron_weekdays, CronTimes.clock(words.hour, words.minute, locale))
    is ScheduleWords.Weekly -> stringResource(R.string.cron_weekly, CronTimes.weekday(words.day, locale), CronTimes.clock(words.hour, words.minute, locale))
    is ScheduleWords.Monthly -> stringResource(R.string.cron_monthly, words.day, CronTimes.clock(words.hour, words.minute, locale))
    ScheduleWords.Once -> stringResource(R.string.cron_once)
    is ScheduleWords.Raw -> words.text
}

/**
 * Hermes' `last_status` literals (`ok`, `error`, `delivery_failed`, `blocked_config`) as the Desktop spells them out
 * (`apps/desktop/src/plugins/hermes-bots/cron.tsx:341-364`); an unknown one is shown as it is. Null: never ran.
 */
@Composable
fun lastResultText(status: String?): String? {
    val raw = status?.trim().orEmpty()
    return when (raw) {
        "" -> null
        "ok" -> stringResource(R.string.cron_last_ok)
        "error" -> stringResource(R.string.cron_last_failed)
        "delivery_failed" -> stringResource(R.string.cron_last_delivery_failed)
        "blocked_config" -> stringResource(R.string.cron_last_blocked)
        else -> stringResource(R.string.cron_last_other, raw)
    }
}

/** A last result that must read as a failure (design.md §1: red only for failures). */
fun lastResultFailed(status: String?): Boolean = status?.trim() in setOf("error", "delivery_failed", "blocked_config")

@Composable
fun cronMessageText(message: CronMessage): String = when (message) {
    is CronMessage.Refused -> stringResource(R.string.cron_refused, message.reason)
    CronMessage.NotSent -> stringResource(R.string.cron_not_sent)
    CronMessage.NoAnswer -> stringResource(R.string.cron_no_answer)
    is CronMessage.Created -> stringResource(R.string.cron_created, message.name)
    is CronMessage.Deleted -> stringResource(R.string.cron_deleted, message.name)
}
