package io.github.justcallmesu.aristotle.data.chat

import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

/** One Claude plan window (`ProviderUsageWindow`, `apps/shared/src/gateway-contract.generated.ts:879-890`); [usedPercent] is 0..100. */
data class PlanWindow(val label: String, val usedPercent: Double, val resetsAt: Instant?, val scope: String?)

/**
 * The Anthropic provider's subscription usage from `model.options` (TX1 §5): [present] = the provider is in the answer,
 * [windows] = the four known windows in display order (empty while Hermes' cache is cold), [accounts] > 1 = a credential
 * pool, where [account] (its label, may be blank) is the one account shown.
 */
data class PlanUsage(
    val present: Boolean,
    val windows: List<PlanWindow>,
    val account: String? = null,
    val accounts: Int = 0,
) {
    companion object {
        val NONE = PlanUsage(present = false, windows = emptyList())

        /** Hermes' labels (`agent/account_usage.py:866-867`), in display order. No Fable row: Hermes drops that window (D5). */
        val LABELS = listOf("Current session", "Current week", "Opus week", "Sonnet week")

        /** Hermes drops snapshots older than this (`agent/account_usage_cache.py:26-30`); the phone hides what it has held that long. */
        const val STALE_MS = 15 * 60 * 1000L

        const val PROVIDER = "anthropic"

        fun of(result: JsonElement?): PlanUsage {
            val row = (result.asObject()?.get("providers") as? JsonArray).orEmpty()
                .mapNotNull { it.asObject() }.firstOrNull { it.text("slug") == PROVIDER } ?: return NONE
            val usage = row["usage"].asObject() ?: return PlanUsage(present = true, windows = emptyList())
            val accounts = (usage["accounts"] as? JsonArray).orEmpty().mapNotNull { it.asObject() }
            if (accounts.isNotEmpty()) {
                // A pool: the first account that has numbers (ready first), never an average across logins.
                val shown = accounts.firstOrNull { it.text("state") == "ready" && windowsOf(it["windows"]).isNotEmpty() }
                    ?: accounts.firstOrNull { windowsOf(it["windows"]).isNotEmpty() }
                    ?: return PlanUsage(present = true, windows = emptyList(), accounts = accounts.size)
                return PlanUsage(true, windowsOf(shown["windows"]), shown.text("label")?.takeIf { it.isNotBlank() } ?: shown.text("id"), accounts.size)
            }
            return PlanUsage(present = true, windows = windowsOf(usage["windows"]))
        }

        private fun windowsOf(element: JsonElement?): List<PlanWindow> {
            val parsed = (element as? JsonArray).orEmpty().mapNotNull { item ->
                val w = item.asObject() ?: return@mapNotNull null
                val label = w.text("label") ?: return@mapNotNull null
                if (label !in LABELS) return@mapNotNull null
                // The wire value is already a percent (Hermes scaled the fraction); a NaN or a stray negative is not a gauge.
                val used = w.number("used_percent")?.takeIf { it.isFinite() } ?: return@mapNotNull null
                PlanWindow(label, used.coerceIn(0.0, 100.0), instant(w.text("resets_at")), w.text("scope"))
            }
            return parsed.distinctBy { it.label }.sortedBy { LABELS.indexOf(it.label) }
        }

        private fun instant(iso: String?): Instant? = iso?.takeIf { it.isNotBlank() }?.let {
            runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull()
                ?: runCatching { Instant.parse(it) }.getOrNull()
        }
    }
}

/** When a window rolls over, before it is turned into a string. */
sealed interface PlanReset {
    data object Unknown : PlanReset
    data object Passed : PlanReset

    /** Under a day away: hours and minutes (hours 0 under an hour; minutes at least 1). */
    data class In(val hours: Int, val minutes: Int) : PlanReset

    /** A day or more away: a short local weekday and time, e.g. "Mon 09:00". */
    data class At(val text: String) : PlanReset
}

/** Pure rules of the limits section. */
object PlanLimitsLogic {
    fun reset(resetsAt: Instant?, now: Instant, zone: ZoneId, locale: Locale): PlanReset {
        if (resetsAt == null) return PlanReset.Unknown
        val left = Duration.between(now, resetsAt)
        if (left.isNegative || left.isZero) return PlanReset.Passed
        if (left < Duration.ofDays(1)) {
            val total = maxOf(1L, left.toMinutes() + if (left.seconds % 60 > 0) 1 else 0)
            return PlanReset.In((total / 60).toInt(), (total % 60).toInt())
        }
        return PlanReset.At(DateTimeFormatter.ofPattern("EEE HH:mm", locale).format(resetsAt.atZone(zone)))
    }

    /** Hidden once held past [PlanUsage.STALE_MS], and while there is nothing to show. */
    fun visible(usage: PlanUsage, fetchedAt: Long, now: Long): Boolean =
        usage.windows.isNotEmpty() && now - fetchedAt < PlanUsage.STALE_MS

    /** Loading: the provider is signed in but Hermes' cache sent nothing yet. */
    fun loading(usage: PlanUsage): Boolean = usage.present && usage.windows.isEmpty()

    /** One automatic retry, 3 s after an empty first answer. */
    const val RETRY_MS = 3_000L

    fun shouldRetry(usage: PlanUsage, retried: Boolean): Boolean = loading(usage) && !retried

    fun fraction(w: PlanWindow): Float = (w.usedPercent / 100.0).toFloat()
}
