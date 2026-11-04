package io.github.justcallmesu.aristotle.data

import io.github.justcallmesu.aristotle.data.chat.ChatDrafts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** TS1 Unpair: everything this phone keeps about the PC goes; nothing of another PC or the app's own settings. */
class PcRecordsTest {
    private val lan = ServerEndpoint("192.168.1.23", 8760, Network.Lan)
    private val tailnet = ServerEndpoint("100.101.102.103", 8760, Network.Tailscale)
    private val pc = PcCredentials(
        pcId = "dev_0123456789ab",
        deviceId = "dev_0123456789ab",
        addresses = listOf(lan, tailnet),
        fingerprint = "7E54 D87B 19C0 A4F2 5B3E 0D96 C1A8 72FE 3E09 B6D4 8A15 F0C3 62D9 4E7A BCA7 EAA7",
        token = DeviceToken("very-secret-token-value"),
        lastGood = tailnet,
    )
    private val key = softwareKey()
    private val credentialStore = MemoryStore()
    private val credentials = CredentialStore(credentialStore, CredentialCodec(AesGcmTokenCipher { key }), io = Dispatchers.Unconfined)

    // Settings and the profile selection share one preferences file in the app (AppContainer SETTINGS_FILE).
    private val settingsStore = MemoryStore()
    private val settings = AppSettings(settingsStore)
    private val selection = ProfileSelection(settingsStore)
    private val pinsStore = MemoryStore()
    private val pins = SessionPins(pinsStore)
    private val draftsStore = MemoryStore()
    private val drafts = ChatDrafts(draftsStore)
    private val records = PcRecords(credentials, selection, pins, drafts)

    @Test
    fun unpairForgetsTokenPinAddressesDraftsAndThePcsSettings() {
        runBlocking { credentials.save(pc) }
        selection.follow(pc.pcId)
        selection.select("work")
        pins.set(pc.pcId, null, "s1", pinned = true)
        pins.set(pc.pcId, "work", "s2", pinned = true)
        pins.set("dev_other", null, "s9", pinned = true)
        drafts.put("s1", "half a thought")
        drafts.put("s2", "another")
        settings.setTheme(ThemeChoice.Dark)
        settings.setKeepConnected(false)

        runBlocking { records.unpair(pc.pcId) }

        // Credential store: the entry (sealed token, pin, addresses) and the active pointer are gone.
        assertEquals(CredentialState.Unpaired, credentials.state.value)
        assertTrue(credentialStore.map.isEmpty())
        val reread = CredentialStore(credentialStore, CredentialCodec(AesGcmTokenCipher { key }), io = Dispatchers.Unconfined)
        runBlocking { reread.load() }
        assertEquals(CredentialState.Unpaired, reread.state.value)
        // Per-PC settings: profile and every profile's pins; another PC's pins stay.
        assertNull(settingsStore.get("profile.${pc.pcId}"))
        assertNull(selection.selected.value)
        assertEquals(setOf("pins.dev_other.\u0000"), pinsStore.keys())
        // Drafts and their timestamps.
        assertTrue(draftsStore.keys().isEmpty())
        // The app's own settings are not the PC's.
        assertEquals(ThemeChoice.Dark, AppSettings(settingsStore).theme.value)
        assertEquals(false, AppSettings(settingsStore).keepConnected.value)
    }

    @Test
    fun replacingWithTheSameIdKeepsTheEntry() {
        runBlocking { credentials.save(pc) }
        pins.set(pc.pcId, null, "s1", pinned = true)
        runBlocking { records.replace(pc.pcId, pc.copy(token = DeviceToken("fresh"))) }
        assertEquals("fresh", (credentials.state.value as CredentialState.Paired).credentials.token.value)
        assertEquals(setOf("s1"), pins.of(pc.pcId, null))
    }

    // D48: the version read from the old PC must not show for the next one before its first /v1/me.
    @Test
    fun unpairAndRepairClearTheServerVersions() {
        runBlocking { credentials.save(pc) }
        ServerVersions.record(ServerInfo("1.0.0", 1, 1))
        runBlocking { records.unpair(pc.pcId) }
        assertNull(ServerVersions.current.value)

        runBlocking { credentials.save(pc) }
        ServerVersions.record(ServerInfo("1.0.0", 1, 1))
        runBlocking { records.replace(pc.pcId, pc.copy(pcId = "dev_ba9876543210", deviceId = "dev_ba9876543210")) }
        assertNull(ServerVersions.current.value)
    }

    @Test
    fun aCancelledRepairKeepsTheServerVersions() {
        runBlocking { credentials.save(pc) }
        ServerVersions.record(ServerInfo("1.0.0", 1, 1))
        runBlocking { records.replace(pc.pcId, pc.copy(token = DeviceToken("fresh")), wanted = { false }) }
        assertEquals("1.0.0", ServerVersions.current.value?.version)
        ServerVersions.clear()
    }
}
