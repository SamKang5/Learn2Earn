package com.example.learn2earn2.account

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object GuestApproval {
    const val KEY_HASH = "guestApprovalHash"

    fun hash(pin: String): String {
        val salt = ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes)
        val derived = derive(pin, salt, ITERATIONS)
        return listOf(
            FORMAT,
            ITERATIONS.toString(),
            Base64.encodeToString(salt, Base64.NO_WRAP),
            Base64.encodeToString(derived, Base64.NO_WRAP)
        ).joinToString("$")
    }

    fun verify(pin: String, stored: String): Boolean {
        val parts = stored.split('$')
        if (parts.size == 4 && parts[0] == FORMAT) {
            val iterations = parts[1].toIntOrNull()?.takeIf { it in 10_000..500_000 }
                ?: return false
            val salt = runCatching { Base64.decode(parts[2], Base64.NO_WRAP) }.getOrNull()
                ?: return false
            val expected = runCatching { Base64.decode(parts[3], Base64.NO_WRAP) }.getOrNull()
                ?: return false
            return MessageDigest.isEqual(expected, derive(pin, salt, iterations))
        }
        // One-way compatibility for guest PINs created by an older build.
        val legacy = MessageDigest.getInstance("SHA-256")
            .digest(pin.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return MessageDigest.isEqual(
            legacy.toByteArray(Charsets.US_ASCII),
            stored.toByteArray(Charsets.US_ASCII)
        )
    }

    private fun derive(pin: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec)
                .encoded
        } finally {
            spec.clearPassword()
        }
    }

    private const val FORMAT = "pbkdf2-sha256"
    private const val ITERATIONS = 120_000
    private const val SALT_BYTES = 16
    private const val KEY_BITS = 256
}
