package com.mantis.ui.settings

import android.app.Application
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mantis.MantisApp
import com.mantis.lock.PinManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val _secureConfig = (application as MantisApp).secureConfig
    private val memoryManager = (application as MantisApp).memoryManager

    /** Returns the SecureConfig instance for provider management. */
    val secureConfig get() = _secureConfig

    // ── Profile ───────────────────────────────────────────────────────────────

    private val _name = MutableStateFlow(secureConfig.name)
    val name: StateFlow<String> = _name

    fun saveName(value: String) {
        secureConfig.name = value
        _name.value = value
    }

    // ── AI Provider ───────────────────────────────────────────────────────────

    val llmChoice: String get() = secureConfig.llmChoice.ifBlank { "claude" }
    val hasApiKey: Boolean get() = secureConfig.getProviderIds().isNotEmpty()

    fun saveApiKey(key: String) {
        if (key.isNotBlank()) secureConfig.setApiKey(key)
    }

    // ── Agent ─────────────────────────────────────────────────────────────────

    private val _maxSteps = MutableStateFlow(secureConfig.maxStepsSetting)
    val maxSteps: StateFlow<Int> = _maxSteps

    fun saveMaxSteps(steps: Int) {
        secureConfig.maxStepsSetting = steps
        _maxSteps.value = steps
    }

    // ── Security ──────────────────────────────────────────────────────────────

    private val _isLockEnabled = MutableStateFlow(secureConfig.isLockEnabled)
    val isLockEnabled: StateFlow<Boolean> = _isLockEnabled

    private val _isBiometricEnabled = MutableStateFlow(secureConfig.isBiometricEnabled)
    val isBiometricEnabled: StateFlow<Boolean> = _isBiometricEnabled

    val hasPinSet: Boolean get() = secureConfig.pinHash.isNotBlank()

    fun setLockEnabled(enabled: Boolean) {
        secureConfig.isLockEnabled = enabled
        _isLockEnabled.value = enabled
        // If disabling lock, also disable biometric.
        if (!enabled) setBiometricEnabled(false)
    }

    fun setBiometricEnabled(enabled: Boolean) {
        secureConfig.isBiometricEnabled = enabled
        _isBiometricEnabled.value = enabled
    }

    /** Returns true if [pin] matches the stored hash. */
    fun verifyPin(pin: String): Boolean = PinManager(secureConfig).verify(pin)

    /** Hashes [newPin] with a fresh salt and persists it. */
    fun changePin(newPin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        secureConfig.pinSalt = Base64.encodeToString(salt, Base64.NO_WRAP)
        secureConfig.pinHash = hashPin(newPin, salt)
    }

    // ── Memory ────────────────────────────────────────────────────────────────

    private val _memoryContent = MutableStateFlow(memoryManager.load())
    val memoryContent: StateFlow<String> = _memoryContent

    private val _memoryWordCount = MutableStateFlow(memoryManager.wordCount())
    val memoryWordCount: StateFlow<Int> = _memoryWordCount

    fun saveMemory(content: String) {
        viewModelScope.launch(Dispatchers.IO) {
            memoryManager.save(content)
            _memoryContent.value = content
            _memoryWordCount.value = memoryManager.wordCount()
        }
    }

    fun clearMemory() {
        viewModelScope.launch(Dispatchers.IO) {
            memoryManager.clear()
            _memoryContent.value = ""
            _memoryWordCount.value = 0
        }
    }

    private fun hashPin(pin: String, salt: ByteArray): String {
        // Parameters must match OnboardingViewModel exactly.
        val spec = PBEKeySpec(pin.toCharArray(), salt, 310_000, 256)
        val hash = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(spec).encoded
        return Base64.encodeToString(hash, Base64.NO_WRAP)
    }
}
