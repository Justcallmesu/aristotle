package io.github.justcallmesu.aristotle.data.update

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** UP2 (P18, P20): what `GET /v1/app/update` means to a phone at versionCode [OWN]. */
class AppUpdateRulesTest {
    private val sha = "ab".repeat(32)
    private fun body(code: Any = 20101, version: Any = "\"2.1.1\"", sha256: Any = "\"$sha\"", size: Any = 35375245) =
        """{"version":$version,"version_code":$code,"sha256":$sha256,"size":$size}"""

    @Test
    fun `a higher versionCode is an update`() {
        assertEquals(UpdateCheck.Available(AppUpdate("2.1.1", 20101, sha, 35375245)), AppUpdateRules.check(200, body(), OWN))
    }

    @Test
    fun `the same versionCode is up to date`() {
        assertEquals(UpdateCheck.UpToDate, AppUpdateRules.check(200, body(code = OWN), OWN))
    }

    @Test
    fun `a server older than the phone is up to date, never a downgrade (P20)`() {
        assertEquals(UpdateCheck.UpToDate, AppUpdateRules.check(200, body(code = 20000, version = "\"2.0.0\""), OWN))
    }

    @Test
    fun `204 is up to date`() {
        assertEquals(UpdateCheck.UpToDate, AppUpdateRules.check(204, null, OWN))
        assertEquals(UpdateCheck.UpToDate, AppUpdateRules.check(204, "", OWN))
    }

    @Test
    fun `any other status is a failed check`() {
        listOf(401, 403, 404, 500, 503).forEach { assertEquals("status $it", UpdateCheck.Failed, AppUpdateRules.check(it, body(), OWN)) }
    }

    @Test
    fun `a malformed body is a failed check`() {
        val bad = listOf(
            "", "not json", "[]", "null", "{}",
            body(code = "\"20101\""),
            body(code = 2.5),
            body(code = 0),
            body(code = -1),
            body(code = 99999999999),
            body(version = "\"\""),
            body(version = "\"2.1\""),
            body(version = "\"2.1.1\\n\""),
            body(version = 2),
            body(sha256 = "\"${"ab".repeat(31)}\""),
            body(sha256 = "\"${"zz".repeat(32)}\""),
            body(sha256 = "null"),
            body(size = 0),
            body(size = -5),
            body(size = "\"35375245\""),
            body(size = AppUpdateRules.MAX_SIZE + 1),
            """{"version":"2.1.1","version_code":20101,"sha256":"$sha"}""",
        )
        bad.forEach { assertEquals(it, UpdateCheck.Failed, AppUpdateRules.check(200, it, OWN)) }
    }

    @Test
    fun `the checksum is kept in lowercase and unknown fields are ignored`() {
        val upper = """{"version":"2.1.1","version_code":20101,"sha256":"${"AB".repeat(32)}","size":10,"notes":"x"}"""
        assertEquals(sha, AppUpdateRules.parse(upper)?.sha256)
    }

    @Test
    fun `one notification per new version`() {
        val update = AppUpdate("2.1.1", 20101, sha, 1)
        assertTrue("never notified", AppUpdateRules.shouldNotify(update, null))
        assertFalse("this version was notified", AppUpdateRules.shouldNotify(update, 20101))
        assertTrue("a newer version", AppUpdateRules.shouldNotify(update, 20100))
        assertFalse("a newer one was notified", AppUpdateRules.shouldNotify(update, 20200))
    }

    @Test
    fun `an install recorded before the restart reads as done only when this app is that version or newer`() {
        assertEquals("2.1.1", AppUpdateRules.updatedTo(installing = 20101, ownVersionCode = 20101, ownVersionName = "2.1.1"))
        assertNull(AppUpdateRules.updatedTo(installing = 20101, ownVersionCode = 20100, ownVersionName = "2.1.0"))
        assertNull(AppUpdateRules.updatedTo(installing = null, ownVersionCode = 20101, ownVersionName = "2.1.1"))
    }

    @Test
    fun `sizes show in megabytes in the phone's language`() {
        assertEquals("35.4 MB", AppUpdateRules.megabytes(35375245, Locale.ENGLISH))
        assertEquals("35,4 MB", AppUpdateRules.megabytes(35375245, Locale.forLanguageTag("id")))
        assertEquals("0.0 MB", AppUpdateRules.megabytes(0, Locale.ENGLISH))
    }

    private companion object {
        const val OWN = 20100
    }

    @Test
    fun `a reconnect within the minimum gap does not ask again, a new PC or a later time does`() {
        val gap = AppUpdateRules.MIN_CHECK_GAP_MILLIS
        assertEquals(10 * 60 * 1000L, gap)
        assertTrue("never asked", AppUpdateRules.checkOnConnect(lastCheckAt = null, now = 5_000))
        assertFalse("just asked", AppUpdateRules.checkOnConnect(lastCheckAt = 1_000, now = 1_000 + gap - 1))
        assertTrue("gap over", AppUpdateRules.checkOnConnect(lastCheckAt = 1_000, now = 1_000 + gap))
        assertTrue("the clock went back", AppUpdateRules.checkOnConnect(lastCheckAt = 10_000, now = 5_000))
    }

    @Test
    fun `an install with no status gives up after 2 minutes`() {
        assertEquals(2 * 60 * 1000L, AppUpdateRules.INSTALL_TIMEOUT_MILLIS)
    }
}
