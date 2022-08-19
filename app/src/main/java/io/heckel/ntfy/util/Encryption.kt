package io.heckel.ntfy.util

import android.util.Base64
import io.heckel.ntfy.db.Notification
import io.heckel.ntfy.db.Subscription
import io.heckel.ntfy.msg.NotificationParser
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

fun ByteArray.toHex(): String = joinToString(separator = "") { eachByte -> "%02x".format(eachByte) }

object Encryption {
    private const val TAG = "NtfyEncryption"
    private const val keyDerivIter = 50000
    private const val keyLenBits = 256
    private const val gcmTagLenBits = 128
    private const val encodingJwe = "jwe"
    private val parser = NotificationParser()

    fun maybeDecrypt(subscription: Subscription, notification: Notification): Notification {
        if (notification.encoding != encodingJwe) {
            return notification
        } else if (subscription.encryptionKey == null) {
            Log.w(TAG, "Notification is encrypted, but key is missing: $notification; leaving encrypted message intact")
            return notification
        }
        return try {
            val plaintext = decrypt(notification.message, subscription.encryptionKey)
            val decryptedNotification = parser.parse(plaintext) ?: throw Exception("Cannot parse decrypted message: $plaintext")
            if (decryptedNotification.id != notification.id || decryptedNotification.timestamp != notification.timestamp) {
                throw Exception("Message ID or timestamp mismatch in decrypted message: $plaintext")
            }
            decryptedNotification
        } catch (e: Exception) {
            Log.w(TAG, "Unable to decrypt message, falling back to original", e)
            notification
        }
    }

    fun deriveKey(password: String, topicUrl: String): ByteArray {
        val salt = MessageDigest.getInstance("SHA-256").digest(topicUrl.toByteArray())
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(password.toCharArray(), salt, keyDerivIter, keyLenBits)
        return factory.generateSecret(spec).encoded
    }

    fun decrypt(input: String, key: ByteArray): String {
        val parts = input.split(".")
        if (parts.size != 5 || parts[1] != "") {
            throw Exception("Unexpected format")
        }
        val encodedHeader = parts[0]
        val iv = Base64.decode(parts[2], Base64.URL_SAFE)
        val ciphertext = Base64.decode(parts[3], Base64.URL_SAFE)
        val tag = Base64.decode(parts[4], Base64.URL_SAFE)
        val ciphertextWithTag = ciphertext + tag
        val secretKeySpec = SecretKeySpec(key, "AES")
        val gcmParameterSpec = GCMParameterSpec(gcmTagLenBits, iv)
        val cipher: Cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, gcmParameterSpec)
        cipher.updateAAD(encodedHeader.toByteArray())
        return String(cipher.doFinal(ciphertextWithTag))
    }
}
