package com.mantis.lock

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mantis.MantisApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class LockMode { BIOMETRIC, PIN }

data class LockState(
    val mode: LockMode = LockMode.BIOMETRIC,
    val pin: String = "",
    val error: String = "",
    val biometricFailures: Int = 0,
    val isUnlocked: Boolean = false,
)

class LockViewModel(app: Application) : AndroidViewModel(app) {

    private val secureConfig get() = getApplication<MantisApp>().secureConfig
    private val pinManager by lazy { PinManager(secureConfig) }

    private val _state = MutableStateFlow(
        LockState(
            mode = if (getApplication<MantisApp>().secureConfig.isBiometricEnabled)
                LockMode.BIOMETRIC else LockMode.PIN
        )
    )
    val state: StateFlow<LockState> = _state.asStateFlow()

    // ── Biometric callbacks ───────────────────────────────────────────────────

    fun onBiometricSuccess() {
        secureConfig.lastActiveTimestamp = System.currentTimeMillis()
        _state.update { it.copy(isUnlocked = true) }
    }

    fun onBiometricFailed() {
        val failures = _state.value.biometricFailures + 1
        _state.update { it.copy(biometricFailures = failures) }
        if (failures >= 3) switchToPinMode()
    }

    fun switchToPinMode() = _state.update { it.copy(mode = LockMode.PIN) }

    // ── PIN entry ─────────────────────────────────────────────────────────────

    fun onPinChange(value: String) {
        if (value.length <= 6 && value.all { it.isDigit() })
            _state.update { it.copy(pin = value, error = "") }
    }

    fun submitPin() {
        val pin = _state.value.pin
        // PBKDF2 is CPU-intensive — run off main thread
        viewModelScope.launch {
            val correct = withContext(Dispatchers.Default) { pinManager.verify(pin) }
            if (correct) {
                secureConfig.lastActiveTimestamp = System.currentTimeMillis()
                _state.update { it.copy(isUnlocked = true) }
            } else {
                _state.update { it.copy(error = "Incorrect PIN", pin = "") }
            }
        }
    }
}
