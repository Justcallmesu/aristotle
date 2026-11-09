package io.github.justcallmesu.aristotle.data.update

import org.junit.Assert.assertEquals
import org.junit.Test

/** UP2 (P18): the downloaded APK is installed only when its checksum, package and signer match. */
class ApkCheckTest {
    private val sha = "cd".repeat(32)
    private val update = AppUpdate("2.1.1", 20101, sha, 100)
    private val own = ApkFacts(PACKAGE, 20100, setOf(SIGNER))
    private val good = ApkFacts(PACKAGE, 20101, setOf(SIGNER))

    @Test
    fun `the same package, signer and checksum, newer, passes`() {
        assertEquals(ApkVerdict.Ok, ApkCheck.verdict(update, sha, good, own))
        assertEquals("hex case does not matter", ApkVerdict.Ok, ApkCheck.verdict(update, sha.uppercase(), good, own))
    }

    @Test
    fun `another checksum is refused first`() {
        assertEquals(ApkVerdict.HashMismatch, ApkCheck.verdict(update, "ce".repeat(32), good, own))
        assertEquals(ApkVerdict.HashMismatch, ApkCheck.verdict(update, "", null, own))
    }

    @Test
    fun `a file Android cannot read as an app is refused`() {
        assertEquals(ApkVerdict.Unreadable, ApkCheck.verdict(update, sha, null, own))
    }

    @Test
    fun `another package is refused`() {
        assertEquals(ApkVerdict.OtherPackage, ApkCheck.verdict(update, sha, good.copy(packageName = "com.example.other"), own))
    }

    @Test
    fun `another signer is refused`() {
        assertEquals(ApkVerdict.OtherSigner, ApkCheck.verdict(update, sha, good.copy(signers = setOf("ff".repeat(32))), own))
        assertEquals("an extra signer", ApkVerdict.OtherSigner, ApkCheck.verdict(update, sha, good.copy(signers = setOf(SIGNER, "ff".repeat(32))), own))
        assertEquals("no signer read", ApkVerdict.OtherSigner, ApkCheck.verdict(update, sha, good.copy(signers = emptySet()), own))
        assertEquals("own signer unknown", ApkVerdict.OtherSigner, ApkCheck.verdict(update, sha, good, own.copy(signers = emptySet())))
    }

    @Test
    fun `an APK that is not newer than this app is refused`() {
        assertEquals(ApkVerdict.NotNewer, ApkCheck.verdict(update, sha, good.copy(versionCode = 20100), own))
        assertEquals(ApkVerdict.NotNewer, ApkCheck.verdict(update, sha, good.copy(versionCode = 20000), own))
    }

    @Test
    fun `an APK whose versionCode is not the announced one is refused`() {
        assertEquals(ApkVerdict.WrongVersion, ApkCheck.verdict(update, sha, good.copy(versionCode = 20102), own))
        assertEquals(ApkVerdict.WrongVersion, ApkCheck.verdict(update.copy(versionCode = 20102), sha, good, own))
    }

    private companion object {
        const val PACKAGE = "io.github.justcallmesu.aristotle"
        val SIGNER = "01".repeat(32)
    }
}
