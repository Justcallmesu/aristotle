package io.github.justcallmesu.aristotle.ui.cron

import io.github.justcallmesu.aristotle.data.HermesJson
import io.github.justcallmesu.aristotle.data.InboundFrame
import io.github.justcallmesu.aristotle.data.LinkState
import io.github.justcallmesu.aristotle.data.Network
import io.github.justcallmesu.aristotle.data.RelayLink
import io.github.justcallmesu.aristotle.data.RpcError
import io.github.justcallmesu.aristotle.data.RpcException
import io.github.justcallmesu.aristotle.data.ServerEndpoint
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The "Scheduled" page's ViewModel over a fake link: listing, refresh, the arm, controls and refusals (T18). */
class CronViewModelTest {
    private val lan = ServerEndpoint("192.168.1.61", 8760, Network.Lan)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private var now = 0L

    @After
    fun stop() = scope.cancel()

    private class Call(val method: String, val params: JsonObject) {
        fun text(key: String): String? = (params[key] as? JsonPrimitive)?.content
        val action: String? get() = text("action")
    }

    private class FakeLink(var answer: (Call) -> JsonElement) : RelayLink {
        override val state = MutableStateFlow<LinkState>(LinkState.Connecting)
        override val events = MutableSharedFlow<InboundFrame>(extraBufferCapacity = 64)
        val calls = CopyOnWriteArrayList<Call>()

        override suspend fun call(method: String, params: JsonObject): JsonElement {
            val call = Call(method, params)
            calls += call
            return answer(call)
        }

        override fun respond(requestId: String, result: JsonObject) = false
        override fun retryNow() = Unit

        fun actions(name: String) = calls.filter { it.action == name }
    }

    private fun json(text: String): JsonElement = HermesJson.parseToJsonElement(text)

    /** Hermes' store: [active] says whether job `a1` runs; answers changes with [change]. */
    private var active = true
    private var extra = "" // more top-level fields of the list answer, e.g. `"scoped":"pm",`
    private var jobName = "Digest"
    private var jobPrompt = "Read mail"
    private var change: (Call) -> JsonElement = { json("""{"success":true}""") }

    private fun hermes(call: Call): JsonElement = when (call.action) {
        "list" -> json(
            """{$extra"success":true,"count":1,"jobs":[{"job_id":"a1","name":"$jobName","prompt_preview":"$jobPrompt","schedule":"0 9 * * *",
               "enabled":$active,"state":"${if (active) "scheduled" else "paused"}","next_run_at":"2026-11-05T09:00:00+07:00"}]}""",
        )
        else -> change(call)
    }

    private fun viewModel(link: FakeLink, profile: String? = null, timings: CronTimings = CronTimings(changeWindowMillis = 100, pollMillis = 60_000)) =
        CronViewModel(link, profile, timings, clock = { now }, scope = scope)

    private fun waitFor(what: String, condition: () -> Boolean) = runBlocking {
        try {
            withTimeout(5_000) { while (!condition()) delay(5) }
        } catch (e: Exception) {
            throw AssertionError(what, e)
        }
    }

    private fun listed(link: FakeLink, profile: String? = null): CronViewModel {
        val vm = viewModel(link, profile)
        link.state.value = LinkState.Online(lan, 1)
        waitFor("listed") { vm.uiState.value.jobs != null }
        return vm
    }

    @Test
    fun listsOnConnectInTheSelectedProfile() {
        val link = FakeLink(::hermes)
        val vm = listed(link, profile = "pm")
        val list = link.calls.single()
        assertEquals("cron.manage", list.method)
        assertEquals(json("""{"action":"list","include_disabled":true,"profile":"pm"}"""), list.params)
        assertEquals(listOf("Digest"), vm.uiState.value.jobs!!.map { it.name })
        // The launch profile sends no `profile`.
        val launch = FakeLink(::hermes)
        listed(launch)
        assertNull(launch.calls.single().text("profile"))
    }

    @Test
    fun theSwitchCountsOnlyAfterTheArm() {
        val link = FakeLink(::hermes)
        val vm = listed(link)
        now = 599
        vm.toggle("a1")
        assertTrue(link.actions("pause").isEmpty())
        now = 600
        vm.toggle("a1")
        waitFor("paused and listed again") { link.actions("list").size == 2 }
        assertEquals(json("""{"action":"pause","name":"a1"}"""), link.actions("pause").single().params)
        assertTrue(vm.uiState.value.busy.isEmpty())
    }

    @Test
    fun aRefreshThatFlipsTheJobReArmsTheSwitch() {
        val link = FakeLink(::hermes)
        val vm = listed(link)
        now = 1_000
        active = false // paused on the PC
        vm.refresh()
        waitFor("relisted") { vm.uiState.value.jobs!!.single().active.not() }
        now = 1_500
        vm.toggle("a1")
        assertTrue("a switch that changed meaning 500 ms ago must not count", link.actions("resume").isEmpty())
        now = 1_600
        vm.toggle("a1")
        waitFor("resumed") { link.actions("resume").size == 1 }
    }

    @Test
    fun comingBackToTheScreenReArms() {
        val link = FakeLink(::hermes)
        val vm = listed(link)
        now = 5_000
        vm.onResume()
        now = 5_300
        vm.toggle("a1")
        assertTrue(link.actions("pause").isEmpty())
        vm.onPause()
    }

    @Test
    fun aRefusalIsShownAndNeverRetried() {
        change = { json("""{"success":false,"error":"Job with ID or name 'a1' not found."}""") }
        val link = FakeLink(::hermes)
        val vm = listed(link)
        now = 600
        vm.toggle("a1")
        waitFor("refused") { vm.uiState.value.message != null }
        assertEquals(CronMessage.Refused("Job with ID or name 'a1' not found."), vm.uiState.value.message)
        assertEquals(1, link.actions("pause").size)
        assertTrue(vm.uiState.value.busy.isEmpty())
    }

    @Test
    fun anRpcErrorALostAnswerAndANotSentCall() {
        val link = FakeLink(::hermes)
        val vm = listed(link)
        now = 600
        change = { throw RpcException(RpcError(4064, "profile 'ghost' not found"), "cron.manage failed: 4064") }
        vm.toggle("a1")
        waitFor("rpc error") { vm.uiState.value.message == CronMessage.Refused("profile 'ghost' not found") }
        vm.messageShown()
        change = { throw RpcException(null, "cron.manage: no answer in 15 s") }
        vm.toggle("a1")
        waitFor("no answer") { vm.uiState.value.message == CronMessage.NoAnswer }
        vm.messageShown()
        val lists = link.actions("list").size
        change = { throw RpcException(null, "cron.manage: socket not open", linkClosed = true, written = false) }
        vm.toggle("a1")
        waitFor("not sent") { vm.uiState.value.message == CronMessage.NotSent }
        assertEquals(3, link.actions("pause").size)
        assertEquals("nothing reached Hermes, so nothing to relist", lists, link.actions("list").size)
    }

    @Test
    fun deleteAsksFirstAndEachStepIsArmed() {
        val link = FakeLink(::hermes)
        val vm = listed(link)
        vm.open("a1")
        assertEquals("a1", vm.uiState.value.openJob?.id)
        now = 100
        vm.askDelete("a1")
        assertNull("the page's Delete arms from when the page showed", vm.uiState.value.confirmDelete)
        now = 700
        vm.askDelete("a1")
        assertEquals("a1", vm.uiState.value.confirmDelete)
        now = 1_000
        vm.confirmDelete()
        assertTrue(link.actions("remove").isEmpty())
        now = 1_300
        vm.confirmDelete()
        waitFor("removed") { vm.uiState.value.message != null }
        assertEquals(json("""{"action":"remove","name":"a1"}"""), link.actions("remove").single().params)
        assertEquals(CronMessage.Deleted("Digest"), vm.uiState.value.message)
        assertNull(vm.uiState.value.open)
        assertNull(vm.uiState.value.confirmDelete)
    }

    @Test
    fun cancellingTheConfirmationSendsNothing() {
        val link = FakeLink(::hermes)
        val vm = listed(link)
        vm.open("a1")
        now = 600
        vm.askDelete("a1")
        vm.cancelDelete()
        now = 5_000
        vm.confirmDelete()
        assertTrue(link.actions("remove").isEmpty())
        assertEquals("a1", vm.uiState.value.open)
    }

    @Test
    fun createSendsOneAddAndShowsHermesOwnRefusal() {
        val usage = "Invalid schedule 'sometimes'. Use:\n  - Interval: '30m', 'every 30m', 'every 2h' (recurring)"
        change = { call -> if (call.text("schedule") == "sometimes") json("""{"success":false,"error":${JsonPrimitive(usage)}}""") else json("""{"success":true,"job_id":"n1"}""") }
        val link = FakeLink(::hermes)
        val vm = listed(link)
        now = 50
        vm.newJob()
        vm.editForm { it.copy(name = "Weekly report", prompt = "Write the report", plan = it.plan.copy(frequency = io.github.justcallmesu.aristotle.data.cron.Frequency.Advanced, raw = "sometimes")) }
        now = 600
        vm.create()
        assertTrue("Create arms from when the form opened", link.actions("add").isEmpty())
        now = 650
        vm.create()
        waitFor("refused") { vm.uiState.value.form?.error != null }
        assertEquals(usage, vm.uiState.value.form!!.error)
        assertEquals(1, link.actions("add").size)
        // The user fixes the schedule and creates again: one more add, the form closes.
        vm.editForm { it.copy(plan = it.plan.copy(frequency = io.github.justcallmesu.aristotle.data.cron.Frequency.Daily, hour = 7, minute = 30)) }
        assertNull(vm.uiState.value.form!!.error)
        now = 1_249 // the button moved back up when the refusal went (at 650): it arms again (T18b)
        vm.create()
        assertEquals(1, link.actions("add").size)
        now = 1_250
        vm.create()
        waitFor("created") { vm.uiState.value.form == null }
        assertEquals(
            json("""{"action":"add","name":"Weekly report","schedule":"30 7 * * *","prompt":"Write the report"}"""),
            link.actions("add").last().params,
        )
        assertEquals(CronMessage.Created("Weekly report"), vm.uiState.value.message)
    }

    @Test
    fun aBurstOfCronChangedRefetchesOnceAfterTheWindow() {
        val link = FakeLink(::hermes)
        val vm = listed(link)
        repeat(10) { link.events.tryEmit(InboundFrame.Event("cron.changed", "", JsonObject(emptyMap()))) }
        assertEquals(1, link.actions("list").size)
        waitFor("one refetch") { link.actions("list").size == 2 }
        runBlocking { delay(300) }
        assertEquals(2, link.actions("list").size)
        assertTrue(vm.uiState.value.jobs!!.isNotEmpty())
    }

    @Test
    fun theListIsPolledOnlyWhileResumed() {
        val link = FakeLink(::hermes)
        val vm = viewModel(link, timings = CronTimings(changeWindowMillis = 100, pollMillis = 50))
        link.state.value = LinkState.Online(lan, 1)
        waitFor("listed") { vm.uiState.value.jobs != null }
        runBlocking { delay(200) }
        assertEquals("no poll before the screen resumes", 1, link.actions("list").size)
        vm.onResume()
        waitFor("polled") { link.actions("list").size >= 3 }
        vm.onPause()
        val seen = link.actions("list").size
        runBlocking { delay(200) }
        assertEquals(seen, link.actions("list").size)
    }

    @Test
    fun aFailedListIsAProblemAndRetryRecovers() {
        val link = FakeLink { call -> if (call.action == "list") throw RpcException(RpcError(4064, "profile 'ghost' not found"), "x") else json("{}") }
        val vm = viewModel(link, profile = "ghost")
        link.state.value = LinkState.Online(lan, 1)
        waitFor("problem") { vm.uiState.value.problem != null }
        assertEquals("profile 'ghost' not found", vm.uiState.value.problem)
        link.answer = ::hermes
        vm.retry()
        waitFor("listed") { vm.uiState.value.jobs != null && vm.uiState.value.problem == null }
    }

    @Test
    fun offlineNothingIsArmedOrSent() {
        val link = FakeLink(::hermes)
        val vm = listed(link)
        link.state.value = LinkState.Connecting
        now = 10_000
        vm.toggle("a1")
        vm.newJob()
        assertTrue(link.actions("pause").isEmpty())
        assertNull(vm.uiState.value.form)
        assertTrue(vm.uiState.value.armed.isEmpty())
        assertEquals(1, vm.uiState.value.jobs!!.size) // the last list stays
    }

    private fun changed(link: FakeLink) = link.events.tryEmit(InboundFrame.Event("cron.changed", "", JsonObject(emptyMap())))

    // ── T18b ──────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun cronChangedDoesNotRefetchInTheBackgroundAndComingBackListsAtOnce() {
        val link = FakeLink(::hermes)
        val vm = listed(link)
        vm.onStop()
        repeat(5) { changed(link) }
        runBlocking { delay(300) }
        assertEquals("nothing is listed while the app is in the background", 1, link.actions("list").size)
        vm.onStart()
        waitFor("listed on ON_START") { link.actions("list").size == 2 }
        // Back in front: cron.changed refetches again, once per window.
        changed(link)
        waitFor("refetch after the window") { link.actions("list").size == 3 }
    }

    @Test
    fun aWindowOpenWhenTheAppStopsDoesNotFire() {
        val link = FakeLink(::hermes)
        val vm = listed(link)
        changed(link)
        vm.onStop()
        runBlocking { delay(300) }
        assertEquals(1, link.actions("list").size)
    }

    @Test
    fun startingWithoutAStopListsNothingExtra() {
        val link = FakeLink(::hermes)
        val vm = listed(link)
        vm.onStart()
        runBlocking { delay(150) }
        assertEquals(1, link.actions("list").size)
    }

    @Test
    fun aProfileListWithoutScopedIsReadOnlyAndSendsNothing() {
        val link = FakeLink(::hermes) // an older gateway: no `scoped`
        val vm = listed(link, profile = "pm")
        assertTrue(vm.uiState.value.unscoped)
        now = 5_000
        vm.toggle("a1")
        vm.open("a1")
        now = 6_000
        vm.askDelete("a1")
        vm.newJob()
        assertTrue(vm.uiState.value.armed.isEmpty())
        assertNull(vm.uiState.value.form)
        assertNull(vm.uiState.value.confirmDelete)
        assertEquals(listOf("list"), link.calls.map { it.action })
        assertTrue(!vm.uiState.value.canToggle(vm.uiState.value.jobs!!.single()))
    }

    @Test
    fun aListScopedToTheProfileKeepsTheControls() {
        extra = """"scoped":"pm","""
        val link = FakeLink(::hermes)
        val vm = listed(link, profile = "pm")
        assertTrue(!vm.uiState.value.unscoped)
        now = 700
        vm.toggle("a1")
        waitFor("paused") { link.actions("pause").size == 1 }
        // Scoped to another profile is not a proof either.
        extra = """"scoped":"other","""
        val wrong = listed(FakeLink(::hermes), profile = "pm")
        assertTrue(wrong.uiState.value.unscoped)
    }

    @Test
    fun theLaunchProfileNeedsNoScopedProof() {
        val vm = listed(FakeLink(::hermes))
        assertTrue(!vm.uiState.value.unscoped)
    }

    @Test
    fun aDelegatedBotRoutineKeepsItsSwitchOff() {
        jobName = "[bot:research] Audit"
        jobPrompt = "You are running the scheduled routine \\\"Audit\\\" for agent"
        active = false
        val link = FakeLink(::hermes)
        val vm = listed(link)
        now = 5_000
        vm.toggle("a1")
        assertTrue(vm.uiState.value.jobs!!.single().legacyDelegated)
        assertTrue(!vm.uiState.value.canToggle(vm.uiState.value.jobs!!.single()))
        assertTrue(link.actions("resume").isEmpty())
        // A bot-tagged routine of the safe shape, and a legacy prompt without the tag, are ordinary jobs.
        jobPrompt = "[bot-mode:routine:v2] Audit"
        val safe = listed(FakeLink(::hermes))
        now = 20_000
        safe.refresh()
        now = 21_000
        assertTrue(!safe.uiState.value.jobs!!.single().legacyDelegated)
    }

    @Test
    fun theListCarriesHermesTimezoneWhenItNamesOne() {
        extra = """"timezone":"Asia/Jakarta","""
        assertEquals("Asia/Jakarta", listed(FakeLink(::hermes)).uiState.value.timezone)
        extra = ""
        assertNull(listed(FakeLink(::hermes)).uiState.value.timezone)
    }

    @Test
    fun createArmsAgainWhenARefusalMovesTheButton() {
        change = { json("""{"success":false,"error":"Invalid schedule"}""") }
        val link = FakeLink(::hermes)
        val vm = listed(link)
        now = 100
        vm.newJob()
        vm.editForm { it.copy(name = "N", prompt = "P") }
        now = 700
        vm.create()
        waitFor("refused") { vm.uiState.value.form?.error != null }
        // The refusal block pushed the button down: a tap in the next 600 ms is not counted.
        now = 750
        vm.create()
        assertEquals(1, link.actions("add").size)
        now = 1_300
        vm.create()
        waitFor("second add") { link.actions("add").size == 2 }
    }
}
