package io.github.justcallmesu.aristotle.data

/**
 * The debug log's redaction (Q80b, TL2), applied to every line at [AppLog]'s door before it reaches the file or logcat.
 * Pure; each rule has its own test.
 *
 * It removes: bearer values; the value after a token-like key (`token`, `token_sealed`, `authorization`, `pin`,
 * `pairing_code`); everything after a `password` / `secret` / `value` key or a content key (`text`, `content`,
 * `message`, `prompt`, `reasoning`, `output`, `body`); URL query strings and fragments; long opaque strings (device tokens);
 * pairing codes (8 characters of the code alphabet, grouped or not); kotlinx.serialization's quoted "JSON input".
 * Full certificate fingerprints keep their first 8 hex digits.
 *
 * This is the safety net, not the rule: callers pass event names, kinds, counts and ids, never message, prompt,
 * reasoning or tool-output text (content cannot be recognised by shape). `AppLogSourceGuardTest` holds them to it.
 */
object LogRedactor {
    const val REDACTED = "[redacted]"

    /** Longer lines are cut: a log line is an event, never a payload. */
    const val MAX_LINE = 1000

    fun redact(line: String): String {
        var out = line.replace('\n', ' ').replace('\r', ' ')
        out = jsonInput(out)
        out = URL.replace(out) { it.groupValues[1] + if (it.groupValues[2].isNotEmpty()) "?$REDACTED" else "" }
        out = BEARER.replace(out) { "Bearer $REDACTED" }
        out = REST_OF_LINE.replace(out) { "${it.groupValues[1]} $REDACTED" }
        out = VALUE_KEY.replace(out) { it.groupValues[1] + REDACTED }
        out = FINGERPRINT_SHORT.replace(out) { keep8(it.value) }
        out = FINGERPRINT_GROUPED.replace(out) { keep8(it.value) }
        out = FINGERPRINT_COLONS.replace(out) { keep8(it.value) }
        out = OPAQUE.replace(out) { match -> if (isOpaque(match.value)) REDACTED else match.value }
        out = PAIRING_CODE.replace(out) { match -> if (match.value.any { it.isLetter() }) REDACTED else match.value }
        return if (out.length > MAX_LINE) out.take(MAX_LINE) + "…" else out
    }

    /** The first 8 hex digits of a fingerprint, then an ellipsis: enough to tell two PCs apart, never the pin. */
    private fun keep8(fingerprint: String): String = fingerprint.filter { it.isHexDigit() }.take(8) + "…"

    private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    /** kotlinx.serialization quotes its input after this marker; whatever follows may be a message. */
    private fun jsonInput(line: String): String {
        val at = line.indexOf(JSON_INPUT, ignoreCase = true)
        return if (at < 0) line else line.substring(0, at + JSON_INPUT.length) + " " + REDACTED
    }

    /** A device token is 43 base64url characters; ids we log are shorter or UUIDs. Letters and digits both. */
    private fun isOpaque(value: String): Boolean =
        !UUID.matches(value) && value.any { it.isDigit() } && value.any { it.isLetter() }

    private const val JSON_INPUT = "JSON input:"

    // Scheme, host and path stay; the query and fragment go.
    private val URL = Regex("""((?:https?|wss?)://[^\s?#"'<>]*)([?#][^\s"'<>]*)?""", RegexOption.IGNORE_CASE)

    private val BEARER = Regex("""(?<![A-Za-z0-9])bearer\s+[^\s,;"']+""", RegexOption.IGNORE_CASE)

    private val REST_OF_LINE = Regex(
        """(["']?(?<![A-Za-z0-9])(?:password|passphrase|passwd|secret|value|text|content|message|prompt|reasoning|output|body)(?![A-Za-z0-9])["']?\s*[:=]).*""",
        RegexOption.IGNORE_CASE,
    )

    private val VALUE_KEY = Regex(
        """((?<![A-Za-z0-9])(?:token_sealed|tokenSealed|token|authorization|pairing_code|pairingCode|pin)(?![A-Za-z0-9])["']?\s*[:=]\s*)("(?:[^"\\]|\\.)*"?|'(?:[^'\\]|\\.)*'?|[^\s,;&}\]]+)""",
        RegexOption.IGNORE_CASE,
    )

    // "7E54 D87B … BCA7 EAA7" (Fingerprint.abbreviated).
    private val FINGERPRINT_SHORT = Regex("""(?<![0-9A-Fa-f])[0-9A-Fa-f]{4} [0-9A-Fa-f]{4} … [0-9A-Fa-f]{4} [0-9A-Fa-f]{4}(?![0-9A-Fa-f])""")

    // 32+ hex digits, contiguous or in groups of 4 split by spaces or colons (Fingerprint.format).
    private val FINGERPRINT_GROUPED = Regex("""(?<![0-9A-Fa-f])[0-9A-Fa-f]{4}(?:[ :]?[0-9A-Fa-f]{4}){7,}(?![0-9A-Fa-f])""")

    // "7E:54:D8:…" (openssl style).
    private val FINGERPRINT_COLONS = Regex("""(?<![0-9A-Fa-f:])[0-9A-Fa-f]{2}(?::[0-9A-Fa-f]{2}){15,}(?![0-9A-Fa-f])""")

    private val OPAQUE = Regex("""(?<![A-Za-z0-9_=-])[A-Za-z0-9_-]{32,}=*(?![A-Za-z0-9_=-])""")

    private val UUID = Regex("""[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}""")

    // PairingCode's alphabet (no I, L, O, U), uppercase as the server issues it; "…" follows a kept fingerprint.
    private val PAIRING_CODE = Regex("""(?<![A-Za-z0-9])[0-9A-HJKMNP-TV-Z]{4}[ -]?[0-9A-HJKMNP-TV-Z]{4}(?![A-Za-z0-9…])""")
}
