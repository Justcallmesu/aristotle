package io.github.justcallmesu.aristotle.data

import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** In-memory [KeyValueStore] for tests. */
class MemoryStore : KeyValueStore {
    val map = mutableMapOf<String, String>()
    override fun get(key: String) = map[key]
    override fun put(key: String, value: String) {
        map[key] = value
    }
    override fun remove(key: String) {
        map.remove(key)
    }
}

fun softwareKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
