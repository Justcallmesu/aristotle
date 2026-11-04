package io.github.justcallmesu.aristotle.data

import io.github.justcallmesu.aristotle.data.chat.PlanLimitsLogic
import io.github.justcallmesu.aristotle.data.chat.PlanUsage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The Claude limits Home shows (Q83): the last reading and when it was taken; [None] until one arrives. */
data class HomeLimits(val usage: PlanUsage = PlanUsage.NONE, val fetchedAtMillis: Long? = null) {
    /** Whether the row shows: there are windows and the reading is younger than [PlanUsage.STALE_MS]. Never a loading state. */
    fun shown(now: Long): Boolean = fetchedAtMillis?.let { PlanLimitsLogic.visible(usage, it, now) } == true

    companion object {
        val None = HomeLimits()
    }
}

/** The one call behind the Home row. */
object HomeLimitsFrames {
    const val METHOD = "model.options"

    /**
     * `model.options {profile?}` with NO `session_id` (it is optional, `tui_gateway/contracts/config_free_tier_control.py:451-455`),
     * so no session is needed. [profile] null = the launch profile (no key). No `explicit_only`: the answer is only read
     * for the Anthropic provider's `usage`.
     */
    fun options(profile: String?): JsonObject = buildJsonObject { profile?.let { put("profile", it) } }
}

/** Q83's refresh rule, pure: a read at most every [EVERY_MS], and one when Home shows with nothing read in that long. */
object LimitsRefresh {
    /** Each `model.options` call makes Hermes refresh its Anthropic numbers in the background, so not oftener. */
    const val EVERY_MS = 5 * 60 * 1000L

    /** [lastAt]: when the last read for this profile started, null = none yet. A clock that went backwards counts as due. */
    fun due(lastAt: Long?, now: Long): Boolean = lastAt == null || now < lastAt || now - lastAt >= EVERY_MS

    /** Milliseconds until the next read is due (0 = now). */
    fun waitMillis(lastAt: Long?, now: Long): Long = if (due(lastAt, now)) 0 else EVERY_MS - (now - lastAt!!)

    /**
     * The reading to show after [fresh]: an answer without the Anthropic provider clears it (not signed in or no
     * Anthropic); one from a provider whose cache is still cold keeps the last good numbers (they age out on their own).
     */
    fun merge(before: HomeLimits, fresh: PlanUsage, now: Long): HomeLimits = when {
        !fresh.present -> HomeLimits.None
        fresh.windows.isEmpty() -> before
        else -> HomeLimits(fresh, now)
    }
}

/**
 * Reads the Claude limits for Home (Q83) only while Home is [visible] AND the link is online: when Home shows (if the last
 * read is older than [LimitsRefresh.EVERY_MS]) and then every 5 minutes. [force] (pull to refresh) reads now. A failed read
 * changes nothing but still counts as a read, so a broken call is not retried faster. [clock] and [pause] are seams for tests.
 */
class HomeLimitsPoller(
    private val scope: CoroutineScope,
    private val link: StateFlow<LinkState>,
    private val visible: StateFlow<Boolean>,
    private val profile: StateFlow<String?>,
    private val read: suspend (String?) -> PlanUsage,
    private val clock: () -> Long = System::currentTimeMillis,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    private data class Key(val generation: Long, val profile: String?)

    private val current = MutableStateFlow(HomeLimits.None)
    private val kicks = Channel<Unit>(Channel.CONFLATED)
    private var lastAt: Long? = null
    private var lastProfile: String? = null
    val view: StateFlow<HomeLimits> = current.asStateFlow()

    /** Pull to refresh: read now, if Home is visible and online (otherwise the next showing reads as usual). */
    fun force() {
        if (visible.value) kicks.trySend(Unit)
    }

    fun start() = scope.launch {
        combine(link, visible, profile) { state, shown, p -> (state as? LinkState.Online)?.takeIf { shown }?.let { Key(it.generation, p) } }
            .distinctUntilChanged()
            .collectLatest { key ->
                if (key == null) {
                    kicks.tryReceive() // a pull while hidden is not a read later
                    return@collectLatest
                }
                // Another profile has other numbers: show none of the old ones, and read.
                if (lastProfile != key.profile) {
                    lastProfile = key.profile
                    lastAt = null
                    current.value = HomeLimits.None
                }
                var forced = kicks.tryReceive().isSuccess
                while (true) {
                    if (forced || LimitsRefresh.due(lastAt, clock())) readOnce(key.profile)
                    forced = wait(LimitsRefresh.waitMillis(lastAt, clock()))
                }
            }
    }

    private suspend fun readOnce(profile: String?) {
        lastAt = clock()
        try {
            val usage = read(profile)
            current.value = LimitsRefresh.merge(current.value, usage, clock())
        } catch (e: CancellationException) {
            lastAt = null // cut short by hiding or going offline: not a read
            throw e
        } catch (_: Exception) {
            // Hermes refused or the answer was unreadable: the numbers shown stay as they were.
        }
        kicks.tryReceive() // a pull that came during the read is covered by it
    }

    /** Sleeps [millis], or until a pull to refresh; true = it was a pull. */
    private suspend fun wait(millis: Long): Boolean = coroutineScope {
        val timer = async { pause(millis) }
        val pulled = select {
            kicks.onReceive { true }
            timer.onAwait { false }
        }
        timer.cancel()
        pulled
    }
}

/** What `model.options` answers for Home: only the Anthropic usage matters. */
class HomeLimitsSource(private val link: RelayLink) {
    suspend fun read(profile: String?): PlanUsage = PlanUsage.of(link.call(HomeLimitsFrames.METHOD, HomeLimitsFrames.options(profile)))
}
