package io.github.justcallmesu.aristotle.data.update

import io.github.justcallmesu.aristotle.data.CredentialState
import io.github.justcallmesu.aristotle.data.HermesJson
import io.github.justcallmesu.aristotle.data.LinkState
import io.github.justcallmesu.aristotle.data.PcCredentials
import io.github.justcallmesu.aristotle.data.ServerEndpoint
import io.github.justcallmesu.aristotle.data.serverEndpoint
import java.util.Locale
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * The APK the PC offers (`GET /v1/app/update`, spec P17): its version name, versionCode, SHA-256 (64 lowercase hex)
 * and size in bytes. The size is also the download's cap.
 */
data class AppUpdate(val version: String, val versionCode: Int, val sha256: String, val size: Long)

/** This app's own version (BuildConfig), a seam so tests can be any version. */
data class OwnVersion(val code: Int, val name: String)

/** The PC and the address the updater talks to: aristotle-server only, Hermes may be down ([serverEndpoint]). */
data class UpdateTarget(val pc: PcCredentials, val endpoint: ServerEndpoint) {
    companion object {
        fun of(credentials: CredentialState, link: LinkState): UpdateTarget? {
            val pc = (credentials as? CredentialState.Paired)?.credentials ?: return null
            return link.serverEndpoint?.let { UpdateTarget(pc, it) }
        }
    }
}

/** One answer of `GET /v1/app/update`, already held against this phone's versionCode (P18, P20). */
sealed interface UpdateCheck {
    data class Available(val update: AppUpdate) : UpdateCheck

    /** 204, or the PC's APK is not newer than this app (the same, or a rolled-back server: P20). */
    data object UpToDate : UpdateCheck

    /** No answer, another status, or a body that is not the contract. What the phone knew stays. */
    data object Failed : UpdateCheck
}

/** Pure rules of the in-app updater (Q132, P18, P20). */
object AppUpdateRules {
    /** P18: on connect, then at most every 6 h while connected. */
    const val CHECK_INTERVAL_MILLIS = 6 * 60 * 60 * 1000L

    /** A reconnect loop must not ask the PC on every flap: on connect, at most one check per PC in this time (Check now ignores it). */
    const val MIN_CHECK_GAP_MILLIS = 10 * 60 * 1000L

    /** An install that reports no status for this long is given up: the row goes back to Update (the session may still finish). */
    const val INSTALL_TIMEOUT_MILLIS = 2 * 60 * 1000L

    /** Back in front, wait this long for the confirm screen's own status (Cancel) before showing the screen again. */
    const val CONFIRM_SETTLE_MILLIS = 1_000L

    /** A check on connect is due when none ran for this PC in the last [MIN_CHECK_GAP_MILLIS] (a clock set back counts as due). */
    fun checkOnConnect(lastCheckAt: Long?, now: Long): Boolean = lastCheckAt == null || now < lastCheckAt || now - lastCheckAt >= MIN_CHECK_GAP_MILLIS

    /** A size above this is a broken answer, not an APK (the real one is ~35 MB). */
    const val MAX_SIZE = 512L * 1024 * 1024

    private val VERSION = Regex("""[0-9]{1,4}\.[0-9]{1,4}\.[0-9]{1,4}""")
    private val SHA256 = Regex("""[0-9a-fA-F]{64}""")

    fun check(status: Int, body: String?, ownVersionCode: Int): UpdateCheck = when (status) {
        204 -> UpdateCheck.UpToDate
        200 -> parse(body)?.let { offer(it, ownVersionCode) } ?: UpdateCheck.Failed
        else -> UpdateCheck.Failed
    }

    /** Only a higher versionCode is an update: Android refuses a downgrade, and an equal one is this app (P20). */
    fun offer(update: AppUpdate, ownVersionCode: Int): UpdateCheck =
        if (update.versionCode > ownVersionCode) UpdateCheck.Available(update) else UpdateCheck.UpToDate

    /** The 200 body, strictly: every field present and of its JSON type (numbers are not strings); null otherwise. */
    fun parse(text: String?): AppUpdate? {
        val root = text?.let { runCatching { HermesJson.parseToJsonElement(it) }.getOrNull() } as? JsonObject ?: return null
        val version = root["version"].text()?.takeIf { VERSION.matches(it) } ?: return null
        val code = root["version_code"].whole()?.takeIf { it in 1..Int.MAX_VALUE }?.toInt() ?: return null
        val sha = root["sha256"].text()?.takeIf { SHA256.matches(it) }?.lowercase() ?: return null
        val size = root["size"].whole()?.takeIf { it in 1..MAX_SIZE } ?: return null
        return AppUpdate(version, code, sha, size)
    }

    /** One notification per version (Q132): only for a versionCode above the last one notified. */
    fun shouldNotify(update: AppUpdate, lastNotified: Int?): Boolean = lastNotified == null || update.versionCode > lastNotified

    /** The version the app was updated to, when the install recorded before the restart ([installing]) is this app now. */
    fun updatedTo(installing: Int?, ownVersionCode: Int, ownVersionName: String): String? =
        ownVersionName.takeIf { installing != null && ownVersionCode >= installing }

    /** "35.4 MB" (1 MB = 10^6 bytes, as a file manager counts), in the phone's number format. */
    fun megabytes(bytes: Long, locale: Locale): String = String.format(locale, "%.1f MB", bytes / 1_000_000.0)

    private fun JsonElement?.prim(): JsonPrimitive? = (this as? JsonPrimitive)?.takeUnless { it is JsonNull }
    private fun JsonElement?.text(): String? = prim()?.takeIf { it.isString }?.content
    private fun JsonElement?.whole(): Long? = prim()?.takeUnless { it.isString }?.takeUnless { '.' in it.content || 'e' in it.content.lowercase() }?.longOrNull
}
