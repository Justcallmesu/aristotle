package io.github.justcallmesu.aristotle

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/** T12b MINOR-2: only Aristotle's own notification PendingIntents can open a session chat with the sheet up. */
class OpenSessionTest {
    @Test
    fun onlyTheNonExportedAliasOpensASession() {
        assertEquals(OpenChat("s1", "T", true), OpenSession.read(OpenSession.ACTION, OpenSession.ALIAS, "s1", "T", prompt = true))
        // Another app can reach only the exported launcher activity.
        assertNull(OpenSession.read(OpenSession.ACTION, MainActivity::class.java.name, "s1", "spoofed", prompt = true))
        assertNull(OpenSession.read(OpenSession.ACTION, null, "s1", null, prompt = true))
        assertNull(OpenSession.read("android.intent.action.MAIN", OpenSession.ALIAS, "s1", null, prompt = true))
        assertNull(OpenSession.read(OpenSession.ACTION, OpenSession.ALIAS, "", null, prompt = false))
        assertNull(OpenSession.read(OpenSession.ACTION, OpenSession.ALIAS, "x".repeat(257), null, prompt = false))
    }

    // T14: a notification opens the chat in the session's profile.
    @Test
    fun theProfileTravelsWithTheIntent() {
        assertEquals(OpenChat("s1", "T", false, "pm"), OpenSession.read(OpenSession.ACTION, OpenSession.ALIAS, "s1", "T", prompt = false, profile = "pm"))
        assertNull(OpenSession.read(OpenSession.ACTION, OpenSession.ALIAS, "s1", "T", prompt = false, profile = ""))
        assertNull(OpenSession.read(OpenSession.ACTION, OpenSession.ALIAS, "s1", "T", prompt = false, profile = "p".repeat(257)))
    }

    // D38: a recents relaunch re-delivers the notification's intent; it must not reopen the chat.
    @Test
    fun anIntentRedeliveredFromRecentsIsIgnored() {
        assertEquals(android.content.Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY, OpenSession.FLAG_LAUNCHED_FROM_HISTORY)
        val flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or OpenSession.FLAG_LAUNCHED_FROM_HISTORY
        assertNull(OpenSession.read(OpenSession.ACTION, OpenSession.ALIAS, "s1", "T", prompt = true, flags = flags))
        assertEquals(OpenChat("s1", "T", true), OpenSession.read(OpenSession.ACTION, OpenSession.ALIAS, "s1", "T", prompt = true, flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    @Test
    fun theManifestAliasIsNotExportedAndTheLauncherStays() {
        val manifest = listOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml")).first { it.exists() }
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(manifest)
        val ns = "http://schemas.android.com/apk/res/android"
        val pkg = "io.github.justcallmesu.aristotle"
        fun Element.name() = getAttributeNS(ns, "name").let { if (it.startsWith(".")) pkg + it else it }
        val aliases = doc.getElementsByTagName("activity-alias").let { list -> (0 until list.length).map { list.item(it) as Element } }
        val alias = aliases.single { it.name() == OpenSession.ALIAS }
        assertEquals("false", alias.getAttributeNS(ns, "exported"))
        assertEquals(MainActivity::class.java.name, alias.getAttributeNS(ns, "targetActivity").let { if (it.startsWith(".")) pkg + it else it })
        assertEquals(0, alias.getElementsByTagName("intent-filter").length)
        val main = doc.getElementsByTagName("activity").let { list -> (0 until list.length).map { list.item(it) as Element } }.single { it.name() == MainActivity::class.java.name }
        assertEquals("true", main.getAttributeNS(ns, "exported"))
        val actions = main.getElementsByTagName("action").let { list -> (0 until list.length).map { (list.item(it) as Element).getAttributeNS(ns, "name") } }
        assertEquals(listOf("android.intent.action.MAIN"), actions)
        assertTrue(OpenSession.ACTION !in actions)
    }
}
