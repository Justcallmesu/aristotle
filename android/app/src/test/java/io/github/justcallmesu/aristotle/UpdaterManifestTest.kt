package io.github.justcallmesu.aristotle

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element

/** UP2b (Q133, P19, MAJOR-1): the manifest declares what a tap-less self-update and the relaunch after it need. */
class UpdaterManifestTest {
    private val ns = "http://schemas.android.com/apk/res/android"
    private val pkg = "io.github.justcallmesu.aristotle"

    private fun manifest(): Document {
        val file = listOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml")).first { it.exists() }
        return DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(file)
    }

    private fun Document.all(tag: String): List<Element> = getElementsByTagName(tag).let { list -> (0 until list.length).map { list.item(it) as Element } }

    private fun Element.name() = getAttributeNS(ns, "name").let { if (it.startsWith(".")) pkg + it else it }

    @Test
    fun `the install permissions are declared`() {
        val permissions = manifest().all("uses-permission").map { it.name() }
        // Without UPDATE_PACKAGES_WITHOUT_USER_ACTION every update asks for a tap (setRequireUserAction is ignored).
        assertTrue("UPDATE_PACKAGES_WITHOUT_USER_ACTION", "android.permission.UPDATE_PACKAGES_WITHOUT_USER_ACTION" in permissions)
        assertTrue("REQUEST_INSTALL_PACKAGES", "android.permission.REQUEST_INSTALL_PACKAGES" in permissions)
    }

    @Test
    fun `the link service is restarted after an update by a receiver that is not exported`() {
        val receiver = manifest().all("receiver").single { it.name() == "$pkg.background.PackageReplacedReceiver" }
        assertEquals("false", receiver.getAttributeNS(ns, "exported"))
        val actions = receiver.getElementsByTagName("action").let { list -> (0 until list.length).map { (list.item(it) as Element).name() } }
        assertEquals(listOf("android.intent.action.MY_PACKAGE_REPLACED"), actions)
    }
}
