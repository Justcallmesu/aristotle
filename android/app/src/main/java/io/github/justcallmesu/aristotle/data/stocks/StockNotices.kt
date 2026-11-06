package io.github.justcallmesu.aristotle.data.stocks

/** The lock-screen words (resources), so the pure builder can be checked for what it puts on the lock screen. */
data class StockNoticeWords(val appName: String, val publicAlert: String, val publicBrief: String)

/**
 * One stock notification, as posted. Grouped per code: one notification per [tag] (`stock:BBCA`), the newest alert's
 * [title]/[text], and up to three of the code's recent alert titles as [lines]. The brief and the over-cap summary have
 * their own tags. [publicTitle]/[publicText] are all the lock screen shows (VISIBILITY_PRIVATE): no code, no number.
 */
data class StockNotice(
    val tag: String,
    val title: String,
    val text: String,
    val lines: List<String>,
    val publicTitle: String,
    val publicText: String,
    val open: StockOpen,
    val atMillis: Long?,
)

object StockNotices {
    const val MAX_LINES = 3

    /** A tray copy younger than this makes the next alert of its tag a silent update (one scan arrives in several answers). */
    const val QUIET_MILLIS = 60_000L

    /**
     * Whether the notification for a tag only updates the tray copy, with no sound or heads-up: only when that copy was
     * posted under [QUIET_MILLIS] before [nowMillis] (the same burst). No copy, or an older one (a setup alert left unread
     * since the morning), means the new alert is a normal heads-up (Q104).
     */
    fun shouldStayQuiet(lastPostedAtMillis: Long?, nowMillis: Long): Boolean =
        lastPostedAtMillis != null && nowMillis - lastPostedAtMillis in 0 until QUIET_MILLIS

    fun tagOf(alert: StockAlert): String = when (alert.alertKind) {
        AlertKind.Brief -> "stock:brief"
        AlertKind.Summary -> "stock:summary"
        else -> alert.code?.takeIf { StockCodes.valid(it) }?.let { "stock:$it" } ?: "stock:other"
    }

    /**
     * The notification for [alert]; [recent] are the alerts this phone holds (any order), from which the same tag's
     * newest titles become the expanded lines. The server's words are used as they are: plain text.
     */
    fun of(alert: StockAlert, recent: List<StockAlert>, words: StockNoticeWords): StockNotice {
        val tag = tagOf(alert)
        val sameTag = (listOf(alert) + recent).distinctBy { it.key }.filter { tagOf(it) == tag && it.seq <= alert.seq }.sortedByDescending { it.seq }
        val lines = sameTag.take(MAX_LINES).map { line(it) }
        return StockNotice(
            tag = tag,
            title = alert.title.ifBlank { words.publicAlert },
            text = alert.text,
            lines = if (lines.size > 1) lines else emptyList(),
            publicTitle = words.appName,
            publicText = if (alert.alertKind == AlertKind.Brief) words.publicBrief else words.publicAlert,
            open = StockOpen.of(alert),
            atMillis = StockTimes.parse(alert.at)?.toInstant()?.toEpochMilli(),
        )
    }

    /**
     * What to post for one long-poll answer's [alerts] (oldest first): one notice per tag, from the tag's newest alert
     * (its [StockNotice.lines] already list the older ones), in the order the tags first appeared. A burst for one code
     * is one heads-up, not three. The lock-screen text does not depend on any of this.
     */
    fun toPost(alerts: List<StockAlert>, recent: List<StockAlert>, words: StockNoticeWords): List<StockNotice> =
        alerts.groupBy(::tagOf).values.map { group -> of(group.maxBy { it.seq }, recent, words) }

    /** One line of the expanded notification: the title, and the text when there is one. */
    private fun line(alert: StockAlert): String = if (alert.text.isBlank()) alert.title else "${alert.title} · ${alert.text}"
}
