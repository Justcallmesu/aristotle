package io.github.justcallmesu.aristotle.data.stocks

import io.github.justcallmesu.aristotle.data.CredentialState
import io.github.justcallmesu.aristotle.data.DeviceToken
import io.github.justcallmesu.aristotle.data.LinkState
import io.github.justcallmesu.aristotle.data.MemoryStore
import io.github.justcallmesu.aristotle.data.Network
import io.github.justcallmesu.aristotle.data.PcCredentials
import io.github.justcallmesu.aristotle.data.ServerEndpoint
import java.time.OffsetDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** TBA2: the alerts body as the server writes it (§20.4), the list's dedupe, what is notified, the lock-screen words, Q109. */
class AlertsTest {
    private fun at(clock: String) = OffsetDateTime.parse("2026-11-05T$clock+07:00").toInstant().toEpochMilli()

    private fun alert(seq: Long, kind: String = "level", code: String? = "BBCA", clock: String = "10:20:00", silent: Boolean = false, title: String = "$code alert $seq") =
        StockAlert(seq = seq, id = "al_%012d".format(seq), kind = kind, code = code, title = title, text = "Price 9850, your stop 9875", at = "2026-11-05T$clock+07:00", silent = silent)

    @Test
    fun `the server's alerts body parses, unknown fields and kinds included`() {
        val body = StocksParser.decode(ALERTS_JSON, AlertsBody.serializer())!!
        assertEquals(9L, body.seq)
        assertEquals(listOf(8L, 9L), body.items.map { it.seq })
        val level = body.items[0]
        assertEquals(AlertKind.Level, level.alertKind)
        assertEquals("BBCA", level.code)
        assertEquals("2026-11-05T10:10:00+07:00", level.dataAsOf)
        val brief = body.items[1]
        assertEquals(AlertKind.Brief, brief.alertKind)
        assertNull(brief.code)
        assertEquals("2026-11-05", brief.briefDate)
        assertEquals(AutoRuns("2026-11-05", 2, 5, listOf("BBCA", "TLKM")), body.autoRuns)
        assertFalse(body.reset)
        assertEquals(AlertKind.Other, AlertKind.of("future_kind"))
        assertEquals(AlertKind.Other, AlertKind.of(""))
    }

    @Test
    fun `the list is newest first, each alert once, and a reset replaces it`() {
        val held = listOf(alert(5), alert(3))
        val merged = AlertFeeds.merge(held, listOf(alert(5, title = "newer copy"), alert(6)), reset = false)
        assertEquals(listOf(6L, 5L, 3L), merged.map { it.seq })
        assertEquals("newer copy", merged[1].title)
        assertEquals(listOf(2L), AlertFeeds.merge(held, listOf(alert(2)), reset = true).map { it.seq })
        val many = (1L..150L).map { alert(it) }
        assertEquals(AlertFeeds.MAX, AlertFeeds.merge(emptyList(), many, reset = false).size)
        assertEquals(150L, AlertFeeds.merge(emptyList(), many, reset = false).first().seq)
    }

    @Test
    fun `only new, loud and fresh alerts are notified - the first read and a reset notify nothing`() {
        val now = at("10:30:00")
        val body = AlertsBody(seq = 9, items = listOf(alert(6), alert(7, silent = true), alert(8, clock = "09:40:00"), alert(9)))
        assertEquals(listOf(9L), AlertFeeds.toNotify(body, after = 6, nowMillis = now).map { it.seq })
        assertTrue(AlertFeeds.toNotify(body, after = null, nowMillis = now).isEmpty())
        assertTrue(AlertFeeds.toNotify(body.copy(reset = true), after = 20, nowMillis = now).isEmpty())
        // 30 min is still fresh; an unreadable time is not.
        assertTrue(AlertFeeds.fresh(alert(1, clock = "10:00:00"), now))
        assertFalse(AlertFeeds.fresh(alert(1).copy(at = "soon"), now))
        // A PC clock a little ahead is fresh, not a negative age.
        assertTrue(AlertFeeds.fresh(alert(1, clock = "10:31:00"), now))
    }

    @Test
    fun `the store keeps a cursor per PC, counts what is new since the last look, and Unpair clears it`() {
        val kv = MemoryStore()
        val store = StockAlertStore(kv)
        val now = at("10:30:00")
        assertNull(store.cursor("pc1"))
        assertTrue(store.take("pc1", null, AlertsBody(seq = 4, items = listOf(alert(3), alert(4))), now, store.generation).isEmpty())
        assertEquals(4L, store.cursor("pc1"))
        assertEquals(0, store.feed.value.unseen)
        assertNull("another PC never uses this cursor", store.cursor("pc2"))
        val fresh = store.take("pc1", 4, AlertsBody(seq = 6, items = listOf(alert(5), alert(6, silent = true))), now, store.generation)
        assertEquals(listOf(5L), fresh.map { it.seq })
        assertEquals(2, store.feed.value.unseen)
        // A restart reads the cursor back.
        assertEquals(6L, StockAlertStore(kv).cursor("pc1"))
        store.markSeen()
        assertEquals(0, store.feed.value.unseen)
        assertEquals("6", kv.map["alerts_seen"])
        store.list(AlertsBody(seq = 6, items = listOf(alert(2))))
        assertEquals(listOf(6L, 5L, 4L, 3L, 2L), store.feed.value.items.map { it.seq })
        assertEquals(6L, store.cursor("pc1"))
        store.clear()
        assertNull(store.cursor("pc1"))
        assertTrue(store.feed.value.items.isEmpty())
        assertTrue(kv.map.isEmpty())
    }

    @Test
    fun `a notification per code - the lock screen shows no code and no number`() {
        val words = StockNoticeWords(appName = "Aristotle", publicAlert = "Stock alert", publicBrief = "Today's brief is ready")
        val first = alert(4, title = "BBCA hit your stop")
        val second = alert(7, title = "BBCA reached your target").copy(text = "Price 10250, your target 10200")
        val other = alert(6, code = "TLKM")
        val notice = StockNotices.of(second, listOf(first, other), words)
        assertEquals("stock:BBCA", notice.tag)
        assertEquals("BBCA reached your target", notice.title)
        assertEquals(StockOpen.Stock("BBCA"), notice.open)
        assertEquals(listOf("BBCA reached your target · Price 10250, your target 10200", "BBCA hit your stop · Price 9850, your stop 9875"), notice.lines)
        assertEquals(at("10:20:00"), notice.atMillis)
        // The private version carries the numbers; the public one never a digit or the code.
        for (n in listOf(notice, StockNotices.of(other, emptyList(), words), StockNotices.of(alert(9, kind = "brief", code = null), emptyList(), words))) {
            assertFalse(n.publicTitle + n.publicText, (n.publicTitle + n.publicText).any(Char::isDigit))
            assertFalse(n.publicText.contains("BBCA") || n.publicText.contains("TLKM"))
        }
        val brief = StockNotices.of(alert(9, kind = "brief", code = null), emptyList(), words)
        assertEquals("stock:brief", brief.tag)
        assertEquals("Today's brief is ready", brief.publicText)
        assertEquals(StockOpen.Brief, brief.open)
        assertTrue("a single alert has no expanded list", brief.lines.isEmpty())
        assertEquals(StockOpen.Alerts, StockNotices.of(alert(10, kind = "summary", code = null), emptyList(), words).open)
        assertEquals("stock:summary", StockNotices.tagOf(alert(10, kind = "summary", code = null)))
        assertEquals("stock:other", StockNotices.tagOf(alert(11, code = "bad")))
        assertEquals(StockOpen.Alerts, StockOpen.of(alert(11, code = "bad")))
        assertEquals(StockOpen.Stock("BBCA"), StockOpen.of(alert(12, kind = "news")))
    }

    @Test
    fun `Q109 - the long-poll runs while the app is open, or while keep connected keeps the process`() {
        assertTrue(AlertPolicy.polling(paired = true, serverReachable = true, appVisible = true, keepRunning = false))
        assertTrue(AlertPolicy.polling(paired = true, serverReachable = true, appVisible = false, keepRunning = true))
        assertFalse("keep connected off and the app closed", AlertPolicy.polling(paired = true, serverReachable = true, appVisible = false, keepRunning = false))
        assertFalse("unpaired", AlertPolicy.polling(paired = false, serverReachable = true, appVisible = true, keepRunning = true))
        assertFalse("the PC does not answer", AlertPolicy.polling(paired = true, serverReachable = false, appVisible = true, keepRunning = true))
    }

    private val lan = ServerEndpoint("192.168.1.61", 8760, Network.Lan)
    private val pc = PcCredentials("pc1", "dev1", listOf(lan), "ff", DeviceToken("t"))
    private val paired = CredentialState.Paired(pc)
    private val online = LinkState.Online(lan, 1)

    @Test
    fun `Q109 target - the PC to poll, from the real inputs`() {
        val target = AlertTarget(pc, lan)
        assertEquals("keep connected on, app closed", target, AlertPolicy.target(paired, online, visible = false, keep = true))
        assertEquals("app open, keep connected off", target, AlertPolicy.target(paired, online, visible = true, keep = false))
        assertNull("keep connected off and the app closed", AlertPolicy.target(paired, online, visible = false, keep = false))
        assertEquals("Stop from the notification, app open", target, AlertPolicy.target(paired, online, visible = true, keep = false))
        assertNull("unpaired", AlertPolicy.target(CredentialState.Unpaired, online, visible = true, keep = true))
        assertNull("keystore unavailable", AlertPolicy.target(CredentialState.Unavailable, online, visible = true, keep = true))
        assertNull("PC out of reach", AlertPolicy.target(paired, LinkState.Connecting, visible = true, keep = true))
        assertNull("link stopped", AlertPolicy.target(paired, LinkState.Stopped, visible = true, keep = true))
        // Hermes down: aristotle-server still answers, the alerts need only it.
        assertEquals(target, AlertPolicy.target(paired, LinkState.HermesDown("4503", lan), visible = true, keep = false))
    }

    @Test
    fun `an answer read before Unpair and taken after it is dropped - no cursor, no feed, no notice`() {
        val kv = MemoryStore()
        val store = StockAlertStore(kv)
        val now = at("10:30:00")
        store.take("pc1", null, AlertsBody(seq = 4, items = listOf(alert(4))), now, store.generation)
        val gen = store.generation
        store.clear()
        val stale = store.take("pc1", 4, AlertsBody(seq = 7, items = listOf(alert(7))), now, gen)
        assertTrue("nothing to notify", stale.isEmpty())
        assertNull("no cursor", store.cursor("pc1"))
        assertTrue("no feed", store.feed.value.items.isEmpty())
        assertNull(store.feed.value.seenSeq)
        assertTrue("nothing stored", kv.map.isEmpty())
        // The next poll captures the new generation and is taken as usual.
        assertEquals(listOf(8L), store.take("pc2", 4, AlertsBody(seq = 8, items = listOf(alert(8))), now, store.generation).map { it.seq })
        assertEquals(8L, store.cursor("pc2"))
    }

    @Test
    fun `MAJOR-2 - a later alert of a code is quiet only when the tray copy is under a minute old`() {
        val now = at("14:00:00")
        assertFalse("no tray copy: a normal heads-up", StockNotices.shouldStayQuiet(null, now))
        assertTrue("10 s: the same burst", StockNotices.shouldStayQuiet(now - 10_000, now))
        assertFalse("exactly a minute", StockNotices.shouldStayQuiet(now - 60_000, now))
        assertFalse("a setup alert from 09:10 left unread, then a stop hit at 14:00", StockNotices.shouldStayQuiet(at("09:10:00"), now))
        assertFalse("a post time in the future is not trusted", StockNotices.shouldStayQuiet(now + 5_000, now))
    }

    @Test
    fun `a burst for one code is one heads-up with the others in its lines`() {
        val words = StockNoticeWords(appName = "Aristotle", publicAlert = "Stock alert", publicBrief = "Today's brief is ready")
        val a4 = alert(4, title = "BBCA alert 4")
        val a5 = alert(5, title = "BBCA alert 5")
        val a6 = alert(6, title = "BBCA alert 6")
        val t7 = alert(7, code = "TLKM")
        val brief = alert(8, kind = "brief", code = null)
        val recent = listOf(brief, t7, a6, a5, a4)
        val posts = StockNotices.toPost(listOf(a4, a5, a6, t7, brief), recent, words)
        assertEquals(listOf("stock:BBCA", "stock:TLKM", "stock:brief"), posts.map { it.tag })
        assertEquals("the newest alert of the tag", "BBCA alert 6", posts[0].title)
        assertEquals(3, posts[0].lines.size)
        assertTrue(posts[0].lines.any { it.startsWith("BBCA alert 4") })
        assertEquals(StockOpen.Stock("TLKM"), posts[1].open)
        // The lock screen stays free of codes and digits for every post.
        for (n in posts) {
            assertFalse(n.publicTitle + n.publicText, (n.publicTitle + n.publicText).any(Char::isDigit))
            assertFalse(n.publicText.contains("BBCA") || n.publicText.contains("TLKM"))
        }
        assertTrue(StockNotices.toPost(emptyList(), recent, words).isEmpty())
        assertEquals(1, StockNotices.toPost(listOf(a5), recent, words).size)
    }

    companion object {
        const val ALERTS_JSON = """{"seq":9,"items":[
            {"seq":8,"id":"al_0123456789ab","kind":"level","code":"BBCA","title":"BBCA hit your stop","text":"Price 9850, your stop 9875",
             "at":"2026-11-05T10:20:00+07:00","data_as_of":"2026-11-05T10:10:00+07:00","silent":false,
             "detail":{"level":"stop","source":"holding","price":9850,"value":9875},"extra":1},
            {"seq":9,"id":"al_0123456789ac","kind":"brief","code":null,"title":"Brief ready","text":"","at":"2026-11-05T08:31:00+07:00",
             "data_as_of":null,"silent":false,"detail":{"date":"2026-11-05","late":false}}],
            "auto_runs":{"date":"2026-11-05","used":2,"cap":5,"codes":["BBCA","TLKM"]},"reset":false}"""
    }
}
