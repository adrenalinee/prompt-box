package malibu.llm.prompt.security

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class ApiKeyCipher private constructor(
    private val keyBytes: ByteArray,
) {
    private val secureRandom = SecureRandom()

    fun encrypt(plainText: String): String {
        val iv = ByteArray(IV_LENGTH_BYTES)
        secureRandom.nextBytes(iv)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, KEY_ALGORITHM), GCMParameterSpec(TAG_LENGTH_BITS, iv))
        val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))

        return listOf(
            FORMAT_VERSION,
            Base64.getEncoder().encodeToString(iv),
            Base64.getEncoder().encodeToString(cipherText),
        ).joinToString(DELIMITER)
    }

    fun decrypt(value: String): String {
        if (!value.startsWith("$FORMAT_VERSION$DELIMITER")) {
            return value
        }
        val parts = value.split(DELIMITER)
        if (parts.size != 3 || parts[0] != FORMAT_VERSION) {
            throw IllegalArgumentException("Invalid api key ciphertext format")
        }

        val iv = Base64.getDecoder().decode(parts[1])
        val cipherText = Base64.getDecoder().decode(parts[2])

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, KEY_ALGORITHM), GCMParameterSpec(TAG_LENGTH_BITS, iv))
        val plainBytes = cipher.doFinal(cipherText)
        return String(plainBytes, Charsets.UTF_8)
    }

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_ALGORITHM = "AES"
        private const val IV_LENGTH_BYTES = 12
        private const val TAG_LENGTH_BITS = 128
        private const val FORMAT_VERSION = "v1"
        private const val DELIMITER = ":"

        fun fromBase64Key(base64Key: String): ApiKeyCipher {
            val keyBytes = Base64.getDecoder().decode(base64Key)
            if (keyBytes.size != 32) {
                throw IllegalArgumentException("API key encryption key must be 32 bytes (base64-encoded)")
            }
            return ApiKeyCipher(keyBytes)
        }
    }
}
