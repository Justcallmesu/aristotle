package io.github.justcallmesu.aristotle.data.cron

import io.github.justcallmesu.aristotle.data.HermesJson
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `cron.manage` frames against Hermes' handler and contract, and the list mapping (T18). */
class CronFramesTest {
    private fun json(text: String): JsonElement = HermesJson.parseToJsonElement(text)

    // `CronManageParams` (tui_gateway/contracts/tools_commands.py:446-455): action, name, include_disabled, schedule,
    // prompt, repeat, continuity, deliver, profile. Nothing else may be sent.
    private val accepted = setOf("action", "name", "include_disabled", "schedule", "prompt", "repeat", "continuity", "deliver", "profile")

    private fun assertAccepted(params: JsonObject) = assertTrue("${params.keys - accepted}", params.keys.all { it in accepted })

    @Test
    fun listAsksForPausedJobsToo() {
        val call = CronFrames.list()
        assertEquals("cron.manage", call.method)
        assertEquals(json("""{"action":"list","include_disabled":true}"""), call.params)
        assertAccepted(call.params)
    }

    @Test
    fun pauseResumeRemoveNameTheJobById() {
        assertEquals(json("""{"action":"pause","name":"a1b2"}"""), CronFrames.pause("a1b2").params)
        assertEquals(json("""{"action":"resume","name":"a1b2"}"""), CronFrames.resume("a1b2").params)
        assertEquals(json("""{"action":"remove","name":"a1b2"}"""), CronFrames.remove("a1b2").params)
        listOf(CronFrames.pause("x"), CronFrames.resume("x"), CronFrames.remove("x")).forEach {
            assertEquals("cron.manage", it.method)
            assertAccepted(it.params)
        }
    }

    @Test
    fun theSwitchPausesAnActiveJobAndResumesAnyOther() {
        assertEquals("pause", CronFrames.toggle(CronJob("j", "J")).params.action())
        assertEquals("resume", CronFrames.toggle(CronJob("j", "J", enabled = false, state = "paused")).params.action())
        // Enabled but stored paused reads as paused (the Desktop's switch, cron.tsx:500).
        assertEquals("resume", CronFrames.toggle(CronJob("j", "J", state = "paused")).params.action())
    }

    @Test
    fun addSendsOnlyWhatIsSetAndNoDeliver() {
        val plain = CronFrames.add(CronDraft("Digest", "Summarise mail", "0 9 * * *"))
        assertEquals(json("""{"action":"add","name":"Digest","schedule":"0 9 * * *","prompt":"Summarise mail"}"""), plain.params)
        val full = CronFrames.add(CronDraft("Digest", "Summarise mail", "every 2h", repeat = 3, continuity = true))
        assertEquals(
            json("""{"action":"add","name":"Digest","schedule":"every 2h","prompt":"Summarise mail","repeat":3,"continuity":true}"""),
            full.params,
        )
        assertAccepted(full.params)
    }

    // A `_format_job` row as `tools/cronjob_job_args.py:476-526` builds it.
    private val listAnswer = """{"success":true,"count":2,"jobs":[
        {"job_id":"a1","name":"Morning digest","skill":null,"skills":["mail"],"prompt_preview":"Read my mail","model":null,
         "provider":null,"pinned":false,"base_url":null,"schedule":"0 9 * * 1-5","repeat":"forever","deliver":"local",
         "next_run_at":"2026-11-05T09:00:00+07:00","last_run_at":"2026-11-04T09:00:12+07:00","last_status":"delivery_failed",
         "last_delivery_error":"telegram: chat not found","last_delivery_unverified":null,"last_fire_error":null,
         "last_error":null,"enabled":true,"state":"scheduled","paused_at":null,"paused_reason":null,"continuity":true},
        {"job_id":"b2","name":"","prompt_preview":"x","schedule":"every 120m","repeat":3,"enabled":false,"state":"paused",
         "paused_reason":"paused from the app"},
        {"name":"no id"}],
        "gateway_running":false,"warning":"No gateway is running: jobs will not fire."}"""

    @Test
    fun listMapsEachRowAndHermesWarning() {
        val list = CronFrames.jobs(json(listAnswer))
        assertEquals(listOf("a1", "b2"), list.jobs.map { it.id })
        val first = list.jobs[0]
        assertEquals("Morning digest", first.name)
        assertEquals("0 9 * * 1-5", first.schedule)
        assertEquals("forever", first.repeat)
        assertEquals("2026-11-05T09:00:00+07:00", first.nextRunAt)
        assertEquals("delivery_failed", first.lastStatus)
        assertEquals("telegram: chat not found", first.issue)
        assertEquals(listOf("mail"), first.skills)
        assertTrue(first.active)
        assertTrue(first.continuity)
        val second = list.jobs[1]
        assertEquals("b2", second.name) // blank name -> the id
        assertEquals("3", second.repeat)
        assertFalse(second.active)
        assertEquals("paused from the app", second.issue)
        assertEquals("No gateway is running: jobs will not fire.", list.warning)
    }

    @Test
    fun aRunningGatewayHasNoWarning() {
        val list = CronFrames.jobs(json("""{"success":true,"count":0,"jobs":[],"gateway_running":true,"warning":"x"}"""))
        assertTrue(list.jobs.isEmpty())
        assertNull(list.warning)
    }

    @Test(expected = IllegalArgumentException::class)
    fun aRefusedListThrowsWithHermesText() {
        CronFrames.jobs(json("""{"success":false,"error":"Cron store unreadable"}"""))
    }

    @Test
    fun answersTellDoneFromRefused() {
        assertEquals(CronAnswer.Done(CronJob("a1", "A", schedule = "every 30m")),
            CronFrames.answer(json("""{"success":true,"job":{"job_id":"a1","name":"A","schedule":"every 30m"}}""")))
        assertEquals(CronAnswer.Done(), CronFrames.answer(json("""{"success":true,"message":"removed","removed_job":{"id":"a1"}}""")))
        // `tool_error(...)` = {"error": …, "success": false} (cronjob_tools.py:1043, :1102-1103).
        assertEquals(
            CronAnswer.Refused("Job with ID or name 'zz' not found. Use cronjob(action='list') to inspect jobs."),
            CronFrames.answer(json("""{"success":false,"error":"Job with ID or name 'zz' not found. Use cronjob(action='list') to inspect jobs."}""")),
        )
        assertEquals(CronAnswer.Refused("refused"), CronFrames.answer(json("""{"success":false}""")))
        assertTrue(CronFrames.answer(json("[]")) is CronAnswer.Refused)
    }

    @Test
    fun aLongPromptIsMarkedCut() {
        val cut = "x".repeat(100) + "..."
        assertTrue(CronJob("a", "A", promptPreview = cut).promptCut)
        assertFalse(CronJob("a", "A", promptPreview = "short...").promptCut)
    }

    @Test
    fun scopedIsHermesProofOfTheProfile() {
        val jobs = """"success":true,"jobs":[]"""
        assertFalse(CronFrames.jobs(json("{$jobs,\"scoped\":\"pm\"}")).unscopedFor("pm"))
        assertTrue(CronFrames.jobs(json("{$jobs,\"scoped\":\"other\"}")).unscopedFor("pm"))
        assertTrue(CronFrames.jobs(json("{$jobs}")).unscopedFor("pm"))
        assertFalse(CronFrames.jobs(json("{$jobs,\"scoped\":true}")).unscopedFor("pm"))
        assertFalse(CronFrames.jobs(json("{$jobs}")).unscopedFor(null))
        assertEquals("Asia/Jakarta", CronFrames.jobs(json("{$jobs,\"timezone\":\"Asia/Jakarta\"}")).timezone)
    }

    @Test
    fun legacyDelegatedBotRoutinesAreTheDesktopsShape() {
        val prompt = "You are running the scheduled routine \"Audit\" for agent 'research'."
        assertTrue(CronJob("a", "[bot:research] Audit", promptPreview = prompt).legacyDelegated)
        assertTrue(CronJob("a", "[BOT:Research] Audit", promptPreview = prompt).legacyDelegated)
        assertFalse(CronJob("a", "Audit", promptPreview = prompt).legacyDelegated)
        assertFalse(CronJob("a", "[bot:research] Audit", promptPreview = "[bot-mode:routine:v2] Audit").legacyDelegated)
    }

    private fun JsonObject.action(): String = (this["action"] as kotlinx.serialization.json.JsonPrimitive).content
}
