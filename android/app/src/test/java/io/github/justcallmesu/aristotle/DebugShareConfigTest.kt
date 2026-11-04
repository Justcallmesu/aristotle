package io.github.justcallmesu.aristotle

import io.github.justcallmesu.aristotle.data.Diagnostics
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * TL2 (Q80b): the FileProvider shares the debug log and crash report from the cache's `share/` only, under the
 * authority the code asks for; the log and crash files stay out of cloud backup and device transfer.
 */
class DebugShareConfigTest {
    private val ns = "http://schemas.android.com/apk/res/android"

    private fun res(path: String): File = listOf(File("src/main/$path"), File("app/src/main/$path")).first { it.exists() }

    private fun parse(path: String) = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(res(path))

    private fun org.w3c.dom.Document.elements(tag: String): List<Element> =
        getElementsByTagName(tag).let { list -> (0 until list.length).map { list.item(it) as Element } }

    @Test
    fun theProviderAuthorityIsTheOneTheShareUses() {
        val provider = parse("AndroidManifest.xml").elements("provider").single { it.getAttributeNS(ns, "name") == "androidx.core.content.FileProvider" }
        assertEquals("\${applicationId}" + Diagnostics.PROVIDER_SUFFIX, provider.getAttributeNS(ns, "authorities"))
        assertEquals("false", provider.getAttributeNS(ns, "exported"))
        assertEquals("true", provider.getAttributeNS(ns, "grantUriPermissions"))
        val meta = provider.getElementsByTagName("meta-data").item(0) as Element
        assertEquals("@xml/file_paths", meta.getAttributeNS(ns, "resource"))
    }

    @Test
    fun onlyTheCacheShareFolderIsExposedForIt() {
        val paths = parse("res/xml/file_paths.xml").documentElement.childNodes.let { list -> (0 until list.length).mapNotNull { list.item(it) as? Element } }
        // Every exposed path is a cache sub-folder (no files/, no root, no external): the log's own folder never is.
        assertTrue(paths.all { it.tagName == "cache-path" && it.getAttribute("path").endsWith("/") && it.getAttribute("path") != "/" })
        assertTrue(paths.any { it.getAttribute("path") == Diagnostics.SHARE_DIR + "/" })
        assertTrue(paths.none { it.getAttribute("path").startsWith(Diagnostics.LOG_DIR) || it.getAttribute("path").startsWith(Diagnostics.CRASH_DIR) })
    }

    @Test
    fun theLogStaysOutOfBackupAndDeviceTransfer() {
        val manifest = parse("AndroidManifest.xml").elements("application").single()
        assertEquals("false", manifest.getAttributeNS(ns, "allowBackup"))
        val rules = parse("res/xml/data_extraction_rules.xml")
        listOf("cloud-backup", "device-transfer").forEach { section ->
            val excludes = rules.elements(section).single().getElementsByTagName("exclude").let { list -> (0 until list.length).map { list.item(it) as Element } }
            // noBackupFilesDir is under the app's root; files/ for anything else app-private.
            listOf("root", "file").forEach { domain ->
                assertTrue("$section excludes $domain", excludes.any { it.getAttribute("domain") == domain && it.getAttribute("path") == "." })
            }
        }
        val legacy = parse("res/xml/backup_rules.xml").elements("exclude")
        listOf("root", "file").forEach { domain -> assertTrue(legacy.any { it.getAttribute("domain") == domain && it.getAttribute("path") == "." }) }
    }
}
