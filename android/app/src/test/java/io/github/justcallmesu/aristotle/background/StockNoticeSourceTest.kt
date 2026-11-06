package io.github.justcallmesu.aristotle.background

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TBA2: what a stock alert puts on the lock screen. The notification is VISIBILITY_PRIVATE with a public version built
 * from two strings only; those strings (both languages) hold no digit, so no price or holding value can show locked.
 */
class StockNoticeSourceTest {
    private fun file(path: String): String = listOf(File(path), File("app/$path")).first { it.exists() }.readText()

    private val notifier by lazy { file("src/main/java/io/github/justcallmesu/aristotle/background/Notifier.kt") }

    @Test
    fun `the stock notification is private with a public version`() {
        val body = notifier.substring(notifier.indexOf("fun postStock("), notifier.indexOf("private fun openStock("))
        assertTrue(body.contains(".setVisibility(NotificationCompat.VISIBILITY_PRIVATE)"))
        assertTrue(body.contains(".setPublicVersion(public)"))
        val public = body.substring(body.indexOf("val public"), body.indexOf(".build()", body.indexOf("val public")))
        assertTrue(public, public.contains("notice.publicTitle") && public.contains("notice.publicText"))
        assertFalse("the public version never shows the alert's own words", public.contains("notice.title") || public.contains("notice.text"))
    }

    @Test
    fun `the lock-screen strings hold no number in either language`() {
        for (values in listOf("values", "values-in")) {
            val xml = file("src/main/res/$values/strings.xml")
            for (name in listOf("notify_stock_public", "notify_brief_public", "app_name")) {
                val text = Regex("<string name=\"$name\"[^>]*>([^<]*)</string>").find(xml)?.groupValues?.get(1)
                    ?: if (name == "app_name") continue else error("$name missing in $values")
                assertFalse("$values/$name: $text", text.any(Char::isDigit))
            }
        }
    }
}
