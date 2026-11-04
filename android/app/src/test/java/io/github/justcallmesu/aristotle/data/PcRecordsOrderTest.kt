package io.github.justcallmesu.aristotle.data

import io.github.justcallmesu.aristotle.background.AlertSweep
import io.github.justcallmesu.aristotle.data.chat.ChatDrafts
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** TS1b: one commit per credential change, unpair's order, and the unpair notification sweep. */
class PcRecordsOrderTest {
    /** One line per write (an [edit] is one line, as one commit is), shared by every store. */
    private class RecordingStore(private val name: String, private val log: MutableList<String>) : KeyValueStore {
        val map = mutableMapOf<String, String>()
        override fun get(key: String) = map[key]
        override fun put(key: String, value: String) {
            map[key] = value
            log += "$name put $key"
        }
        override fun remove(key: String) {
            map.remove(key)
            log += "$name remove $key"
        }
        override fun keys(): Set<String> = map.keys.toSet()
        override fun edit(changes: Map<String, String?>, durable: Boolean) {
            changes.forEach { (key, value) -> if (value == null) map.remove(key) else map[key] = value }
            log += "$name edit${if (durable) " durable" else ""} ${changes.keys.sorted().joinToString(",")}"
        }
    }

    private val lan = ServerEndpoint("192.168.1.23", 8760, Network.Lan)
    private fun pc(id: String) = PcCredentials(
        pcId = id,
        deviceId = id,
        addresses = listOf(lan),
        fingerprint = "7E54 D87B 19C0 A4F2 5B3E 0D96 C1A8 72FE 3E09 B6D4 8A15 F0C3 62D9 4E7A BCA7 EAA7",
        token = DeviceToken("token-$id"),
        lastGood = lan,
    )

    private val log = mutableListOf<String>()
    private val key = softwareKey()
    private val credentialStore = RecordingStore("credentials", log)
    private val credentials = CredentialStore(credentialStore, CredentialCodec(AesGcmTokenCipher { key }), io = Dispatchers.Unconfined)
    private val settingsStore = RecordingStore("settings", log)
    private val pinsStore = RecordingStore("pins", log)
    private val draftsStore = RecordingStore("drafts", log)
    private val selection = ProfileSelection(settingsStore)
    private val pins = SessionPins(pinsStore)
    private val drafts = ChatDrafts(draftsStore) { Instant.EPOCH }
    private val records = PcRecords(credentials, selection, pins, drafts, io = Dispatchers.Unconfined)

    private fun seed(id: String) {
        runBlocking { credentials.save(pc(id)) }
        selection.follow(id)
        selection.select("work")
        pins.set(id, null, "s1", pinned = true)
        drafts.put("s1", "half a thought")
        log.clear()
    }

    @Test
    fun aSaveIsOneCommit() {
        runBlocking { credentials.save(pc("dev_a")) }
        assertEquals(listOf("credentials edit active_pc,pc.dev_a"), log)
    }

    @Test
    fun aReplaceWritesTheNewEntryAndDropsTheOldOneInOneCommit() {
        seed("dev_old")
        assertTrue(runBlocking { records.replace("dev_old", pc("dev_new")) })
        assertEquals("credentials edit active_pc,pc.dev_new,pc.dev_old", log.first())
        assertEquals(setOf("pc.dev_new", "active_pc"), credentialStore.keys())
        // Only then the old PC's settings, on disk.
        assertTrue(log.drop(1).all { it.startsWith("settings edit durable") || it.startsWith("pins edit durable") })
    }

    @Test
    fun aCancelledReplaceWritesNothing() {
        seed("dev_old")
        assertFalse(runBlocking { records.replace("dev_old", pc("dev_new")) { false } })
        assertTrue(log.isEmpty())
        assertEquals("dev_old", (credentials.state.value as CredentialState.Paired).credentials.pcId)
    }

    @Test
    fun unpairClearsSettingsAndDraftsOnDiskBeforeTheCredentialsInOneCommit() {
        seed("dev_a")
        runBlocking { records.unpair("dev_a") }
        val credentialWrites = log.filter { it.startsWith("credentials") }
        assertEquals(listOf("credentials edit active_pc,pc.dev_a"), credentialWrites)
        val last = log.indexOf(credentialWrites.single())
        assertEquals(log.size - 1, last)
        val before = log.take(last)
        assertTrue(before.any { it.startsWith("drafts edit durable") })
        assertTrue(before.any { it.startsWith("settings edit durable profile.dev_a") })
        assertTrue(before.any { it.startsWith("pins edit durable") })
        assertTrue(draftsStore.keys().isEmpty())
        assertTrue(credentialStore.keys().isEmpty())
        assertNull(settingsStore.get("profile.dev_a"))
    }

    @Test
    fun theUnpairSweepTakesSessionAlertsOnly() {
        val alertId = 2
        val active = listOf("turn:s1" to alertId, "needs:s2" to alertId, null to 1, "needs:s2" to alertId, "other" to 7)
        assertEquals(listOf("turn:s1", "needs:s2"), AlertSweep.tags(active, alertId))
    }

    @Test
    fun theRouterForgetsEverythingOnReset() {
        val router = AlertRouter()
        val away = ScreenFocus(appVisible = false, openSessionId = null)
        router.onAlert(SessionAlert.NeedsYou("s1", "T", "p1", PromptKind.Approval, null), away)
        assertEquals(listOf(NotifyAction.Cancel(NotificationText.needsTag("s1"))), router.reset())
        assertTrue(router.reset().isEmpty())
        // The same prompt after a re-pair is new again, not a repeat.
        val again = router.onAlert(SessionAlert.NeedsYou("s1", "T", "p1", PromptKind.Approval, null), away)
        assertEquals(1, again.size)
    }
}
