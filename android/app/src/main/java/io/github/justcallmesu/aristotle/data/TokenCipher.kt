package io.github.justcallmesu.aristotle.data

import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts the device token at rest. [aad] binds a ciphertext to its PC entry. */
interface TokenCipher {
    fun encrypt(plain: ByteArray, aad: ByteArray): ByteArray
    fun decrypt(blob: ByteArray, aad: ByteArray): ByteArray
}

/**
 * AES-256-GCM; blob = 1-byte IV length, IV, ciphertext+tag. The cipher picks the IV (an Android
 * Keystore key refuses caller-chosen IVs). [key] is called per operation so the Keystore lookup
 * stays lazy.
 */
class AesGcmTokenCipher(private val key: () -> SecretKey) : TokenCipher {

    override fun encrypt(plain: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(aad)
        val sealed = cipher.doFinal(plain)
        val iv = cipher.iv
        return ByteBuffer.allocate(1 + iv.size + sealed.size).put(iv.size.toByte()).put(iv).put(sealed).array()
    }

    override fun decrypt(blob: ByteArray, aad: ByteArray): ByteArray {
        require(blob.isNotEmpty()) { "empty ciphertext" }
        val ivLength = blob[0].toInt() and 0xFF
        require(ivLength in 12..16 && blob.size > 1 + ivLength) { "malformed ciphertext" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, blob, 1, ivLength))
        cipher.updateAAD(aad)
        return cipher.doFinal(blob, 1 + ivLength, blob.size - 1 - ivLength)
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
    }
}
