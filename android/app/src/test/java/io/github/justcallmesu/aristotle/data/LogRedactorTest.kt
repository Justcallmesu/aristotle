package io.github.justcallmesu.aristotle.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** TL2 (Q80b): each redaction rule of the debug log's door. */
class LogRedactorTest {
    private fun r(line: String) = LogRedactor.redact(line)
    private val token = "Qm9vX2ZhbGxfdG9rZW5fdmFsdWVfMTIzNDU2Nzg5MGFi" // 43 base64url characters, like a device token

    @Test
    fun bearerValuesGo() {
        assertEquals("auth Bearer [redacted] ok", r("auth Bearer abc.def-123 ok"))
        assertEquals("Authorization: [redacted] [redacted]", r("Authorization: Bearer abc"))
        assertEquals("x bearer=y", r("x bearer=y")) // only the "Bearer <value>" shape
    }

    @Test
    fun tokenKeysLoseTheirValues() {
        assertEquals("token=[redacted] next=1", r("token=abc next=1"))
        assertEquals("""{"token":[redacted],"id":"s1"}""", r("""{"token":"abc def","id":"s1"}"""))
        assertEquals("""{"token_sealed": [redacted]}""", r("""{"token_sealed": "c2VhbGVk"}"""))
        assertEquals("tokenSealed=[redacted]", r("tokenSealed=xyz"))
        assertEquals("pin: [redacted] ok", r("pin: AB12 ok"))
        assertEquals("pairing_code=[redacted]", r("pairing_code=K7Q2M9XD"))
        // A word that only contains the key is not a key.
        assertEquals("tokens=3 tokenizer=x", r("tokens=3 tokenizer=x"))
    }

    @Test
    fun anEscapedQuoteDoesNotEndTheValue() {
        assertEquals("""token=[redacted] id=1""", r("""token="a\"b c" id=1"""))
    }

    @Test
    fun everythingAfterASecretKeyGoes() {
        assertEquals("sudo password= [redacted]", r("sudo password=hunter2 and more words"))
        assertEquals("""{"secret": [redacted]""", r("""{"secret": "s3", "id": 1}"""))
        assertEquals("client_secret= [redacted]", r("client_secret=abc rest"))
        assertEquals("answer value: [redacted]", r("answer value: whatever the user typed"))
        assertEquals("PASSWORD= [redacted]", r("PASSWORD=x"))
    }

    @Test
    fun contentKeysLoseEverythingAfterThem() {
        assertEquals("""frame {"type":"x","text": [redacted]""", r("""frame {"type":"x","text":"hello there","id":1}"""))
        assertEquals("prompt= [redacted]", r("prompt=write me a poem"))
        assertEquals("reasoning: [redacted]", r("reasoning: because"))
        assertEquals("message= [redacted]", r("message=hi"))
        assertEquals("output= [redacted]", r("output=ls -la"))
        assertEquals("context=3", r("context=3"))
    }

    @Test
    fun urlQueryStringsAndFragmentsGo() {
        assertEquals("GET https://192.168.1.5:8443/v1/pc-status?[redacted] 200", r("GET https://192.168.1.5:8443/v1/pc-status?token=abc&x=1 200"))
        assertEquals("wss://h:1/v1/relay?[redacted]", r("wss://h:1/v1/relay#frag"))
        assertEquals("https://h:1/v1/me", r("https://h:1/v1/me"))
    }

    @Test
    fun fullFingerprintsKeepEightHexDigits() {
        val grouped = "7E54 D87B 0C2A 9F11 22AB 33CD 44EF 5501 6612 7723 8834 9945 AA56 BB67 CC78 BCA7"
        assertEquals("pin 7E54D87B… ok", r("pin is $grouped ok".replace("pin is", "pin")))
        assertEquals("fp 7e54d87b…", r("fp " + "7e54d87b0c2a9f1122ab33cd44ef550166127723883499 45aa56bb67cc78bca7".replace(" ", "")))
        assertEquals("fp 7E54D87B…", r("fp " + (1..32).joinToString(":") { listOf("7E", "54", "D8", "7B")[(it - 1) % 4] }))
        assertEquals("card 7E54D87B…", r("card 7E54 D87B … BCA7 EAA7"))
        // Short hex (an id, a port) stays.
        assertEquals("id=7e54d87b port=8443", r("id=7e54d87b port=8443"))
    }

    @Test
    fun opaqueTokensGoButIdsStay() {
        assertEquals("dial [redacted] done", r("dial $token done"))
        assertEquals("session 550e8400-e29b-41d4-a716-446655440000", r("session 550e8400-e29b-41d4-a716-446655440000"))
        assertEquals("session 20261104_101213_ab12cd", r("session 20261104_101213_ab12cd"))
        assertEquals("aVeryLongIdentifierWithoutAnyDigitsAtAll", r("aVeryLongIdentifierWithoutAnyDigitsAtAll"))
    }

    @Test
    fun pairingCodesGo() {
        assertEquals("code [redacted] typed", r("code K7Q2M9XD typed"))
        assertEquals("code [redacted]", r("code K7Q2 M9XD"))
        assertEquals("code [redacted]", r("code K7Q2-M9XD"))
        // Not codes: lowercase words, numbers, words with letters outside the alphabet.
        assertEquals("verified=1 bytes=12345678 OFFLINE1 state=Online", r("verified=1 bytes=12345678 OFFLINE1 state=Online"))
    }

    @Test
    fun serializationInputIsCut() {
        val line = "SerializationException: Unexpected JSON token at offset 9. JSON input: {\"text\":\"my secret plan\"}"
        assertEquals("SerializationException: Unexpected JSON token at offset 9. JSON input: [redacted]", r(line))
    }

    @Test
    fun linesAreOneLineAndBounded() {
        assertFalse(r("a\nb\rc").contains('\n'))
        val long = r("x ".repeat(2000))
        assertTrue(long.length <= LogRedactor.MAX_LINE + 1)
    }

    @Test
    fun plainEventsPassUnchanged() {
        listOf(
            "link state=Online network=Lan",
            "link state=Offline cause=PcUnreachable",
            "lan scan: probed=254 open=1 verified=1 rejected=0",
            "background link refused error=java.lang.IllegalStateException",
            "app start version=1.0.0 code=12 sdk=34",
        ).forEach { assertEquals(it, r(it)) }
    }
}
