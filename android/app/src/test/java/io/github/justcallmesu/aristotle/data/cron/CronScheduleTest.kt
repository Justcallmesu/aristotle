package io.github.justcallmesu.aristotle.data.cron

import io.github.justcallmesu.aristotle.ui.cron.CronTimes
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Schedule strings both ways and run times on the phone (T18). */
class CronScheduleTest {
    @Test
    fun intervalsReadInTheLargestWholeUnit() {
        // `_interval_schedule` always displays minutes (cron/jobs.py:783-784).
        assertEquals(ScheduleWords.Every(30, TimeUnit.Minutes), CronSchedule.describe("every 30m"))
        assertEquals(ScheduleWords.Every(1, TimeUnit.Hours), CronSchedule.describe("every 60m"))
        assertEquals(ScheduleWords.Every(2, TimeUnit.Hours), CronSchedule.describe("every 120m"))
        assertEquals(ScheduleWords.Every(1, TimeUnit.Days), CronSchedule.describe("every 1440m"))
        assertEquals(ScheduleWords.Every(90, TimeUnit.Minutes), CronSchedule.describe("every 90m"))
    }

    @Test
    fun thePickersCronShapesReadAsWords() {
        assertEquals(ScheduleWords.Daily(9, 0), CronSchedule.describe("0 9 * * *"))
        assertEquals(ScheduleWords.Weekdays(8, 30), CronSchedule.describe("30 8 * * 1-5"))
        assertEquals(ScheduleWords.Weekly(DayOfWeek.MONDAY, 9, 0), CronSchedule.describe("0 9 * * 1"))
        assertEquals(ScheduleWords.Weekly(DayOfWeek.SUNDAY, 9, 0), CronSchedule.describe("0 9 * * 0"))
        assertEquals(ScheduleWords.Weekly(DayOfWeek.SUNDAY, 9, 0), CronSchedule.describe("0 9 * * 7"))
        assertEquals(ScheduleWords.Monthly(15, 7, 0), CronSchedule.describe("0 7 15 * *"))
    }

    @Test
    fun oneShotsAndEverythingElse() {
        assertEquals(ScheduleWords.Once, CronSchedule.describe("once in 30m"))
        assertEquals(ScheduleWords.Once, CronSchedule.describe("once at 2026-12-01 09:00"))
        assertEquals(ScheduleWords.Raw("*/5 * * * *"), CronSchedule.describe("*/5 * * * *"))
        assertEquals(ScheduleWords.Raw("0 9 * * 1,3"), CronSchedule.describe("0 9 * * 1,3"))
        assertEquals(ScheduleWords.Raw("every monday 9am"), CronSchedule.describe("every monday 9am"))
        assertEquals(ScheduleWords.Raw("?"), CronSchedule.describe("?"))
    }

    @Test
    fun composeWritesWhatHermesParses() {
        val daily = SchedulePlan(frequency = Frequency.Daily, hour = 9, minute = 30)
        assertEquals("30 9 * * *", CronSchedule.compose(daily))
        assertEquals("0 9 * * 1-5", CronSchedule.compose(SchedulePlan(frequency = Frequency.Weekdays)))
        assertEquals("0 9 * * 0", CronSchedule.compose(SchedulePlan(frequency = Frequency.Weekly, weekday = DayOfWeek.SUNDAY)))
        assertEquals("0 9 * * 5", CronSchedule.compose(SchedulePlan(frequency = Frequency.Weekly, weekday = DayOfWeek.FRIDAY)))
        assertEquals("0 9 15 * *", CronSchedule.compose(SchedulePlan(frequency = Frequency.Monthly, monthDay = "15")))
        assertEquals("every 1h", CronSchedule.compose(SchedulePlan(frequency = Frequency.Hourly)))
        assertEquals("every 2h", CronSchedule.compose(SchedulePlan(frequency = Frequency.Interval)))
        assertEquals("every 1d", CronSchedule.compose(SchedulePlan(frequency = Frequency.Interval, intervalCount = "0", intervalUnit = TimeUnit.Days)))
        // A one-shot is Hermes' explicit `in …` form: a bare `30m` would recur (cron/jobs.py:837-851).
        assertEquals("in 30m", CronSchedule.compose(SchedulePlan(frequency = Frequency.Once)))
        assertEquals("in 2d", CronSchedule.compose(SchedulePlan(frequency = Frequency.Once, onceCount = "2", onceUnit = TimeUnit.Days)))
        assertEquals("every monday 9am", CronSchedule.compose(SchedulePlan(frequency = Frequency.Advanced, raw = "  every monday 9am ")))
        assertEquals("", CronSchedule.compose(SchedulePlan(frequency = Frequency.Advanced)))
        // Round trip: what the picker writes reads back as the same words.
        assertEquals(ScheduleWords.Daily(9, 30), CronSchedule.describe(CronSchedule.compose(daily)))
    }

    @Test
    fun repeatOnlyForRepeatingFrequencies() {
        assertNull(CronSchedule.repeat(SchedulePlan(frequency = Frequency.Daily)))
        assertEquals(3, CronSchedule.repeat(SchedulePlan(frequency = Frequency.Daily, repeat = "3")))
        assertEquals(1, CronSchedule.repeat(SchedulePlan(frequency = Frequency.Hourly, repeat = "0")))
        assertNull(CronSchedule.repeat(SchedulePlan(frequency = Frequency.Once, repeat = "3")))
        assertNull(CronSchedule.repeat(SchedulePlan(frequency = Frequency.Advanced, repeat = "3")))
    }

    private val jakarta = ZoneId.of("Asia/Jakarta")

    @Test
    fun runTimesAreShownInThePhonesZone() {
        val now = ZonedDateTime.of(2026, 11, 4, 10, 0, 0, 0, jakarta)
        // Hermes' time with its own offset (UTC here) lands on the phone's clock.
        val next = CronSchedule.at("2026-11-05T02:00:00+00:00", jakarta)!!
        assertEquals("Thu 5 Nov 09:00", CronTimes.at(next, now, Locale.ENGLISH))
        assertEquals("14:30", CronTimes.at(CronSchedule.at("2026-11-04T14:30:00+07:00", jakarta)!!, now, Locale.ENGLISH))
        assertEquals("Fri 1 Jan 2027 09:00", CronTimes.at(CronSchedule.at("2027-01-01T09:00:00+07:00", jakarta)!!, now, Locale.ENGLISH))
        assertNull(CronSchedule.at(null, jakarta))
        assertNull(CronSchedule.at("soon", jakarta))
        assertEquals("09:05", CronTimes.clock(9, 5, Locale.ENGLISH))
    }

    @Test
    fun theMonthDayFieldTakesOnlyOneToThirtyOne() {
        assertEquals("1", CronSchedule.monthDay("1", ""))
        assertEquals("31", CronSchedule.monthDay("31", "3"))
        assertEquals("3", CronSchedule.monthDay("32", "3"))
        assertEquals("", CronSchedule.monthDay("0", ""))
        assertEquals("1", CronSchedule.monthDay("00", "1"))
        assertEquals("", CronSchedule.monthDay("", "1"))
        assertEquals("12", CronSchedule.monthDay("1a2", "1"))
        assertEquals("1", CronSchedule.monthDay("99", "1"))
    }

    @Test
    fun aSlotPastTheGraceIsOverdueNeverNext() {
        val now = Instant.parse("2026-11-04T03:00:00Z")
        val job = CronJob("a", "A", nextRunAt = "2026-11-04T09:30:00+07:00") // 02:30Z, 30 min ago
        assertTrue(CronSchedule.overdue(job, now))
        assertFalse(CronSchedule.overdue(job.copy(nextRunAt = "2026-11-04T09:50:00+07:00"), now)) // 10 min: within grace
        assertFalse(CronSchedule.overdue(job.copy(enabled = false, state = "paused"), now))
        assertFalse(CronSchedule.overdue(job.copy(state = "completed"), now))
    }
}
