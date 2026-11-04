package io.github.justcallmesu.aristotle.data

import java.util.Locale
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** One reading of the PC (`GET /v1/pc-status`, protocol §16). Every part is null when the PC could not read it. */
data class PcStatus(
    val battery: PcBattery? = null,
    val cpuPercent: Double? = null,
    val memory: PcMemory? = null,
    val disk: PcDisk? = null,
    val hermesRunning: Boolean? = null,
    val tailscaleUp: Boolean? = null,
    val uptimeSeconds: Long? = null,
) {
    /** False for an all-null reading: there is nothing to draw, so the card stays out. */
    val hasData: Boolean
        get() = battery != null || cpuPercent != null || memory != null || disk != null ||
            hermesRunning != null || tailscaleUp != null || uptimeSeconds != null
}

data class PcBattery(val percent: Int, val charging: Boolean)
data class PcMemory(val usedBytes: Long, val totalBytes: Long)
data class PcDisk(val freeBytes: Long, val totalBytes: Long)

/** The last reading and when the phone got it ([fetchedAtMillis]); [None] until the first one. */
data class PcStatusView(val status: PcStatus? = null, val fetchedAtMillis: Long? = null) {
    val shown: PcStatus? get() = status?.takeIf { it.hasData }

    companion object {
        val None = PcStatusView()
    }
}

/** The PC-status call; [HttpLinkDialer] in the app, a fake in tests. Null when the call failed. */
fun interface PcStatusSource {
    suspend fun pcStatus(pc: PcCredentials, endpoint: ServerEndpoint): PcStatus?
}

/** Reads the §16 body. Tolerant: a missing or wrongly typed field is null; null only when [text] is not a JSON object. */
object PcStatusParser {
    fun parse(text: String): PcStatus? {
        val root = runCatching { HermesJson.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
        val battery = (root["battery"] as? JsonObject)?.let { b ->
            percent(b["percent"])?.let { PcBattery(it, b["charging"].bool() == true) }
        }
        val memory = (root["memory"] as? JsonObject)?.let { m ->
            val used = m["used_bytes"].long()
            val total = m["total_bytes"].long()
            if (used != null && total != null && total > 0 && used >= 0) PcMemory(used.coerceAtMost(total), total) else null
        }
        val disk = (root["disk"] as? JsonObject)?.let { d ->
            val free = d["free_bytes"].long()
            val total = d["total_bytes"].long()
            if (free != null && total != null && total > 0 && free >= 0) PcDisk(free.coerceAtMost(total), total) else null
        }
        return PcStatus(
            battery = battery,
            cpuPercent = root["cpu_percent"].double()?.takeIf { it >= 0 && it.isFinite() }?.coerceAtMost(100.0),
            memory = memory,
            disk = disk,
            hermesRunning = root["hermes_running"].bool(),
            tailscaleUp = root["tailscale_up"].bool(),
            uptimeSeconds = root["uptime_seconds"].long()?.takeIf { it >= 0 },
        )
    }

    private fun percent(e: JsonElement?): Int? = e.double()?.takeIf { it.isFinite() }?.toInt()?.takeIf { it in 0..100 }

    private fun JsonElement?.prim(): JsonPrimitive? = (this as? JsonPrimitive)?.takeUnless { it is JsonNull }
    private fun JsonElement?.bool(): Boolean? = prim()?.takeUnless { it.isString }?.booleanOrNull
    private fun JsonElement?.long(): Long? = prim()?.takeUnless { it.isString }?.let { it.longOrNull ?: it.doubleOrNull?.takeIf(Double::isFinite)?.toLong() }
    private fun JsonElement?.double(): Double? = prim()?.takeUnless { it.isString }?.doubleOrNull
}

/** Units for the card, as pure functions. Numbers follow [locale] (a comma in Indonesian). */
object PcStatusFormat {
    private const val GIB = 1024.0 * 1024.0 * 1024.0
    private const val GB = 1_000_000_000.0

    /** Memory in GB with one decimal; 1 GB = 2^30 bytes, as the PC's own tools count RAM: 9771728896 -> "9.1". */
    fun memoryGb(bytes: Long, locale: Locale): String = String.format(locale, "%.1f", bytes / GIB)

    /** Disk size as the drive's label counts it (1 GB = 10^9 bytes): "120 GB", "1.5 TB"; one decimal under 10. */
    fun diskSize(bytes: Long, locale: Locale): String {
        val gb = bytes / GB
        return when {
            gb >= 1000 -> String.format(locale, "%.1f TB", gb / 1000)
            gb >= 10 -> String.format(locale, "%d GB", Math.round(gb))
            else -> String.format(locale, "%.1f GB", gb)
        }
    }

    /** Meter fills, 0..1, null when the reading is missing or has no usable total. */
    fun batteryFraction(battery: PcBattery?): Float? = battery?.let { (it.percent / 100f).coerceIn(0f, 1f) }

    fun cpuFraction(cpuPercent: Double?): Float? = cpuPercent?.takeIf { it.isFinite() }?.let { (it / 100).toFloat().coerceIn(0f, 1f) }

    fun ramFraction(memory: PcMemory?): Float? =
        memory?.takeIf { it.totalBytes > 0 }?.let { (it.usedBytes.toDouble() / it.totalBytes).toFloat().coerceIn(0f, 1f) }

    /** Disk used, not free: the bar fills as the drive does. */
    fun diskUsedFraction(disk: PcDisk?): Float? =
        disk?.takeIf { it.totalBytes > 0 }?.let { (1.0 - it.freeBytes.toDouble() / it.totalBytes).toFloat().coerceIn(0f, 1f) }

    fun percent(value: Double): Int = Math.round(value).toInt().coerceIn(0, 100)

    /** The two biggest non-zero units of [seconds], as days+hours, hours+minutes or minutes. */
    fun uptime(seconds: Long): Uptime {
        val total = seconds.coerceAtLeast(0)
        return Uptime(days = total / 86_400, hours = total % 86_400 / 3_600, minutes = total % 3_600 / 60)
    }
}

/** Uptime split for display: [days] and [hours] when it is a day or more, else [hours] and [minutes], else [minutes]. */
data class Uptime(val days: Long, val hours: Long, val minutes: Long) {
    val kind: Kind get() = when {
        days > 0 -> Kind.DaysHours
        hours > 0 -> Kind.HoursMinutes
        else -> Kind.Minutes
    }

    enum class Kind { DaysHours, HoursMinutes, Minutes }
}
