package io.github.justcallmesu.aristotle.data.update

/**
 * What Android reads from an APK file or from this installed app: package name, versionCode and the SHA-256 (hex) of
 * each certificate that signs the APK contents now (`SigningInfo.apkContentsSigners`).
 */
data class ApkFacts(val packageName: String, val versionCode: Long, val signers: Set<String>)

/** Why a downloaded APK is (not) installed (P18). */
enum class ApkVerdict { Ok, HashMismatch, Unreadable, OtherPackage, OtherSigner, NotNewer, WrongVersion }

/**
 * The checks before the install (P18). Android enforces the same signer and package on an update too; checking first
 * gives the user a clear reason instead of the system's generic failure, and a tampered file never reaches the installer.
 */
object ApkCheck {
    fun verdict(update: AppUpdate, fileSha256: String, archive: ApkFacts?, own: ApkFacts): ApkVerdict = when {
        !fileSha256.equals(update.sha256, ignoreCase = true) -> ApkVerdict.HashMismatch
        archive == null -> ApkVerdict.Unreadable
        archive.packageName != own.packageName -> ApkVerdict.OtherPackage
        // An empty set means Android could not read a signer: never a match, even against another empty set.
        archive.signers.isEmpty() || archive.signers != own.signers -> ApkVerdict.OtherSigner
        archive.versionCode <= own.versionCode -> ApkVerdict.NotNewer
        // The "Updated to x.y.z" line is keyed on the announced code: a file with another code is not the announced release.
        archive.versionCode != update.versionCode.toLong() -> ApkVerdict.WrongVersion
        else -> ApkVerdict.Ok
    }
}
