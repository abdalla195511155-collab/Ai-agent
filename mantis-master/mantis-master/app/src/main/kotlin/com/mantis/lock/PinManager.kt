package com.mantis.lock

import android.util.Base64
import com.mantis.storage.SecureConfig
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

// Stateless PIN verifier. Hashing parameters must match OnboardingViewModel exactly.
class PinManager(private val secureConfig: SecureConfig) {

    fun verify(pin: String): Boolean {
        val storedHash = secureConfig.pinHash
        val saltB64 = secureConfig.pinSalt
        if (storedHash.isBlank() || saltB64.isBlank()) return false
        val salt = Base64.decode(saltB64, Base64.NO_WRAP)
        return hashPin(pin, salt) == storedHash
    }

    // PBKDF2+SHA-256, 310,000 iterations (OWASP 2024 recommendation)
    private fun hashPin(pin: String, salt: ByteArray): String {
        val spec = PBEKeySpec(pin.toCharArray(), salt, 310_000, 256)
        val hash = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(spec).encoded
        return Base64.encodeToString(hash, Base64.NO_WRAP)
    }
}
