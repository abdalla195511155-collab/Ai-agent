package com.mantis.onboarding

import android.app.Application
import android.os.PowerManager
import android.provider.Settings
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mantis.MantisApp
import com.mantis.storage.ProviderAuthType
import com.mantis.storage.ProviderProtocol
import com.mantis.storage.ProviderConfig
import com.mantis.permissions.ManufacturerGuide
import com.mantis.permissions.ManufacturerGuides
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.SecureRandom
import javax.crypto.spec.PBEKeySpec
import javax.crypto.SecretKeyFactory

enum class OnboardingStep {
    NAME, LLM_SELECT, CUSTOM_API_SETTINGS, API_KEY, ACCESSIBILITY, SCREEN_CAPTURE, BATTERY_OVERLAY, SET_LOCK, DONE
}

data class OnboardingState(
    val step: OnboardingStep = OnboardingStep.NAME,
    val name: String = "",
    val llmChoice: String = "",         // "claude" | "openai" | "gemini" | "custom_api"
    val apiKey: String = "",
    val apiKeyVisible: Boolean = false,
    val customApiKey: String = "",
    val customModelId: String = "",
    val customBaseUrl: String = "https://integrate.api.nvidia.com/v1",
    val customConnectionStatus: String = "", // "" | "testing" | "success" | "error"
    val customConnectionResult: ConnectionTestResult? = null,
    val isAccessibilityGranted: Boolean = false,
    val isMediaProjectionGranted: Boolean = false,
    val isOverlayGranted: Boolean = false,
    val isBatteryGranted: Boolean = false,
    val pin: String = "",
    val pinConfirm: String = "",
    val pinError: String = "",
    val lockEnabled: Boolean = true,
    val manufacturerGuide: ManufacturerGuide = ManufacturerGuides.forCurrentDevice(),
)

class OnboardingViewModel(app: Application) : AndroidViewModel(app) {

    private val secureConfig get() = getApplication<MantisApp>().secureConfig

    private val _state = MutableStateFlow(OnboardingState())
    val state: StateFlow<OnboardingState> = _state.asStateFlow()

    // ── Step 1: Name ──────────────────────────────────────────────────────────

    fun onNameChange(value: String) = _state.update { it.copy(name = value) }

    fun submitName() {
        val name = _state.value.name.trim()
        if (name.isBlank()) return
        secureConfig.name = name
        _state.update { it.copy(name = name, step = OnboardingStep.LLM_SELECT) }
    }

    // ── Step 2: LLM select ────────────────────────────────────────────────────

    fun onLlmChoiceChange(choice: String) = _state.update { it.copy(llmChoice = choice) }

    fun submitLlmChoice() {
        val choice = _state.value.llmChoice
        if (choice.isBlank()) return
        secureConfig.llmChoice = choice
        // Custom API goes to its settings screen; other providers go to API key screen
        val next = when (choice) {
            "custom_api" -> OnboardingStep.CUSTOM_API_SETTINGS
            else -> OnboardingStep.API_KEY
        }
        _state.update { it.copy(step = next) }
    }

    // ── Step 3: API key ───────────────────────────────────────────────────────

    fun onApiKeyChange(value: String) = _state.update { it.copy(apiKey = value) }

    fun toggleApiKeyVisible() = _state.update { it.copy(apiKeyVisible = !it.apiKeyVisible) }

    fun submitApiKey() {
        val key = _state.value.apiKey.trim()
        if (key.isBlank()) return
        secureConfig.setApiKey(_state.value.llmChoice, key)
        _state.update { it.copy(step = OnboardingStep.ACCESSIBILITY) }
    }

    // ── Step 3b: Custom API settings ────────────────────────────────────────────

    fun onCustomApiKeyChange(value: String) =
        _state.update { it.copy(customApiKey = value, customConnectionStatus = "", customConnectionResult = null) }

    fun onCustomModelIdChange(value: String) =
        _state.update { it.copy(customModelId = value, customConnectionStatus = "", customConnectionResult = null) }

    fun onCustomBaseUrlChange(value: String) =
        _state.update { it.copy(customBaseUrl = value, customConnectionStatus = "", customConnectionResult = null) }

    fun testCustomConnection() {
        val st = _state.value
        if (st.customApiKey.isBlank() || st.customModelId.isBlank() || st.customBaseUrl.isBlank()) return

        _state.update { it.copy(customConnectionStatus = "testing", customConnectionResult = null) }

        viewModelScope.launch {
            try {
                val client = io.ktor.client.HttpClient(io.ktor.client.engine.android.Android)
                try {
                    val baseUrl = st.customBaseUrl.trim()
                    val url = if (baseUrl.endsWith("/")) "${baseUrl}models" else "${baseUrl}/models"

                    val response = client.get(url) {
                        header("Authorization", "Bearer ${st.customApiKey.trim()}")
                        header("Content-Type", "application/json")
                    }

                    val ok = response.status.isSuccess()
                    _state.update {
                        it.copy(
                            customConnectionStatus = if (ok) "success" else "error",
                            customConnectionResult = ConnectionTestResult(
                                success = ok,
                                message = if (ok) "Connection successful" else "Connection failed (HTTP ${response.status.value})",
                            ),
                        )
                    }
                } finally {
                    client.close()
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        customConnectionStatus = "error",
                        customConnectionResult = ConnectionTestResult(
                            success = false,
                            message = "Error: ${e.message ?: "unknown error"}",
                        ),
                    )
                }
            }
        }
    }

    fun saveCustomApiSettings(onSave: () -> Unit) {
        val st = _state.value
        if (st.customApiKey.isBlank() || st.customModelId.isBlank() || st.customBaseUrl.isBlank()) return

        val config = ProviderConfig(
            id = "custom_api",
            name = "Custom API",
            baseUrl = st.customBaseUrl.trim(),
            modelId = st.customModelId.trim(),
            protocol = ProviderProtocol.OPENAI_COMPATIBLE,
            authType = ProviderAuthType.BEARER_TOKEN,
            timeoutMs = 30_000,
        )

        secureConfig.setProviderConfig("custom_api", config)
        secureConfig.setApiKey("custom_api", st.customApiKey.trim())

        // المضي قدماً في عملية التسجيل
        _state.update { it.copy(step = OnboardingStep.ACCESSIBILITY) }
        onSave()
    }

    // ── Step 4: Accessibility ─────────────────────────────────────────────────

    fun startPollingAccessibility() {
        viewModelScope.launch {
            while (_state.value.step == OnboardingStep.ACCESSIBILITY) {
                val granted = isAccessibilityEnabled()
                _state.update { it.copy(isAccessibilityGranted = granted) }
                if (granted) {
                    delay(800) // brief pause so user sees the checkmark
                    _state.update { it.copy(step = OnboardingStep.SCREEN_CAPTURE) }
                    return@launch
                }
                delay(1000)
            }
        }
    }

    // ── Step 5: Screen capture ────────────────────────────────────────────────

    fun onMediaProjectionGranted(resultCode: Int, data: android.content.Intent) {
        val app = getApplication<MantisApp>()
        app.mediaProjectionResultCode = resultCode
        app.mediaProjectionIntent = data
        secureConfig.isMediaProjectionValid = true
        // Start capture service immediately — token is only valid while the app is alive.
        com.mantis.capture.MantisScreenCapture.start(app, resultCode, data)
        _state.update { it.copy(isMediaProjectionGranted = true, step = OnboardingStep.BATTERY_OVERLAY) }
    }

    // ── Step 8: Battery + Overlay ─────────────────────────────────────────────

    fun startPollingBatteryOverlay() {
        viewModelScope.launch {
            while (_state.value.step == OnboardingStep.BATTERY_OVERLAY) {
                val overlay = Settings.canDrawOverlays(getApplication())
                val battery = isBatteryOptimizationExempt()
                _state.update { it.copy(isOverlayGranted = overlay, isBatteryGranted = battery) }
                delay(1000)
            }
        }
    }

    fun advanceToPinSetup() = _state.update { it.copy(step = OnboardingStep.SET_LOCK) }

    // ── Step 9: PIN setup ─────────────────────────────────────────────────────

    fun onPinChange(value: String) {
        if (value.length <= 6 && value.all { it.isDigit() })
            _state.update { it.copy(pin = value, pinError = "") }
    }

    fun onPinConfirmChange(value: String) {
        if (value.length <= 6 && value.all { it.isDigit() })
            _state.update { it.copy(pinConfirm = value, pinError = "") }
    }

    fun onLockEnabledChange(enabled: Boolean) = _state.update { it.copy(lockEnabled = enabled) }

    fun submitPin() {
        val st = _state.value
        if (!st.lockEnabled) {
            finishOnboarding(lockEnabled = false)
            return
        }
        if (st.pin.length < 6) {
            _state.update { it.copy(pinError = "PIN must be 6 digits") }
            return
        }
        if (st.pin != st.pinConfirm) {
            _state.update { it.copy(pinError = "PINs do not match", pinConfirm = "") }
            return
        }
        // PBKDF2 is CPU-intensive — run off main thread
        viewModelScope.launch {
            val salt = generateSalt()
            val hash = withContext(Dispatchers.Default) { hashPin(st.pin, salt) }
            secureConfig.pinHash = hash
            secureConfig.pinSalt = Base64.encodeToString(salt, Base64.NO_WRAP)
            secureConfig.isBiometricEnabled = true
            finishOnboarding(lockEnabled = true)
        }
    }

    // ── Completion ────────────────────────────────────────────────────────────

    private fun finishOnboarding(lockEnabled: Boolean) {
        secureConfig.isLockEnabled = lockEnabled
        secureConfig.lastActiveTimestamp = System.currentTimeMillis()
        secureConfig.isOnboarded = true
        _state.update { it.copy(step = OnboardingStep.DONE) }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun isAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            getApplication<Application>().contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        return enabled.contains(getApplication<Application>().packageName, ignoreCase = true)
    }

    private fun isBatteryOptimizationExempt(): Boolean {
        val pm = getApplication<Application>()
            .getSystemService(android.content.Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(getApplication<Application>().packageName)
    }

    private fun generateSalt(): ByteArray =
        ByteArray(16).also { SecureRandom().nextBytes(it) }

    // PBKDF2+SHA-256, 310,000 iterations (OWASP 2024 recommendation)
    private fun hashPin(pin: String, salt: ByteArray): String {
        val spec = PBEKeySpec(pin.toCharArray(), salt, 310_000, 256)
        val hash = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(spec).encoded
        return Base64.encodeToString(hash, Base64.NO_WRAP)
    }
}
