package com.monithome.network

import android.util.Base64
import java.util.*
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object CryptoUtils {
    private const val ALGORITHM = "AES/GCM/NoPadding"
    private const val NONCE_SIZE = 16
    private const val TAG_SIZE = 16

    fun encrypt(data: String, keyHex: String): String? {
        if (keyHex.isEmpty()) return data
        return try {
            val keyBytes = hexToBytes(keyHex)
            val secretKey = SecretKeySpec(keyBytes, "AES")
            val cipher = Cipher.getInstance(ALGORITHM)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            
            val nonce = cipher.iv // В GCM iv и есть nonce
            val ciphertextWithTag = cipher.doFinal(data.toByteArray(Charsets.UTF_8))
            
            // Собираем: nonce + ciphertext (который уже включает tag в Java)
            // Внимание: Python Crypto.Cipher.GCM выдает tag отдельно. 
            // Java Cipher AES/GCM при doFinal добавляет tag в конец ciphertext.
            // Нам нужно соответствовать формату Python: nonce(16) + tag(16) + ciphertext
            
            val tagSizeInBytes = 16
            val ciphertextOnlySize = ciphertextWithTag.size - tagSizeInBytes
            val tag = ciphertextWithTag.copyOfRange(ciphertextOnlySize, ciphertextWithTag.size)
            val ciphertextOnly = ciphertextWithTag.copyOfRange(0, ciphertextOnlySize)
            
            val combined = ByteArray(nonce.size + tag.size + ciphertextOnly.size)
            System.arraycopy(nonce, 0, combined, 0, nonce.size)
            System.arraycopy(tag, 0, combined, nonce.size, tag.size)
            System.arraycopy(ciphertextOnly, 0, combined, nonce.size + tag.size, ciphertextOnly.size)
            
            Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun decrypt(encryptedBase64: String, keyHex: String): String? {
        if (keyHex.isEmpty()) return encryptedBase64
        return try {
            val keyBytes = hexToBytes(keyHex)
            val combined = Base64.decode(encryptedBase64, Base64.DEFAULT)
            
            val nonce = combined.copyOfRange(0, 16)
            val tag = combined.copyOfRange(16, 32)
            val ciphertextOnly = combined.copyOfRange(32, combined.size)
            
            // В Java GCM tag должен быть в конце ciphertext
            val ciphertextWithTag = ByteArray(ciphertextOnly.size + tag.size)
            System.arraycopy(ciphertextOnly, 0, ciphertextWithTag, 0, ciphertextOnly.size)
            System.arraycopy(tag, 0, ciphertextWithTag, ciphertextOnly.size, tag.size)
            
            val secretKey = SecretKeySpec(keyBytes, "AES")
            val cipher = Cipher.getInstance(ALGORITHM)
            val spec = GCMParameterSpec(TAG_SIZE * 8, nonce)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
            
            val decrypted = cipher.doFinal(ciphertextWithTag)
            String(decrypted, Charsets.UTF_8)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun hexToBytes(hex: String): ByteArray {
        val bytes = ByteArray(hex.length / 2)
        for (i in bytes.indices) {
            bytes[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return bytes
    }
}
