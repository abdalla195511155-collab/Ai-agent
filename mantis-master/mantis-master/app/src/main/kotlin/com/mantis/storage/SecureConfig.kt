package com.mantis.storage

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Encrypted persistent storage for app settings, provider configurations and API keys.
 */
class SecureConfig(context: Context) {
    private val appContext = context.applicationContext
    private val masterKey = MasterKey.Builder(appContext)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()
    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        appContext,
        "mantis_secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    var name: String
        get() = prefs.getString("name", "") ?: ""
        set(value) = prefs.edit().putString("name", value).apply()

    var llmChoice: String
        get() = prefs.getString("llm_choice", "").orEmpty()
        set(value) = prefs.edit().putString("llm_choice", value).apply()

    var maxStepsSetting: Int
        get() = prefs.getInt("max_steps", 30)
        set(value) = prefs.edit().putInt("max_steps", value.coerceIn(1, 200)).apply()

    var isLockEnabled: Boolean
        get() = prefs.getBoolean("lock_enabled", false)
        set(value) = prefs.edit().putBoolean("lock_enabled", value).apply()

    var isBiometricEnabled: Boolean
        get() = prefs.getBoolean("biometric_enabled", false)
        set(value) = prefs.edit().putBoolean("biometric_enabled", value).apply()

    var pinHash: String
        get() = prefs.getString("pin_hash", "").orEmpty()
        set(value) = prefs.edit().putString("pin_hash", value).apply()

    var pinSalt: String
        get() = prefs.getString("pin_salt", "").orEmpty()
        set(value) = prefs.edit().putString("pin_salt", value).apply()

    var isMediaProjectionValid: Boolean
        get() = prefs.getBoolean("media_projection_valid", false)
        set(value) = prefs.edit().putBoolean("media_projection_valid", value).apply()

    var isOnboarded: Boolean
        get() = prefs.getBoolean("is_onboarded", false)
        set(value) = prefs.edit().putBoolean("is_onboarded", value).apply()

    var lastActiveTimestamp: Long
        get() = prefs.getLong("last_active_timestamp", 0L)
        set(value) = prefs.edit().putLong("last_active_timestamp", value).apply()

    private fun apiKeyPref(providerId: String) =
        "api_key_${providerId.lowercase().replace(" ", "_")}"

    fun setApiKey(providerId: String, apiKey: String) {
        prefs.edit().putString(apiKeyPref(providerId), apiKey).apply()
    }

    fun getApiKey(providerId: String): String? = prefs.getString(apiKeyPref(providerId), null)

    fun hasApiKey(providerId: String): Boolean = !getApiKey(providerId).isNullOrBlank()

    // Compatibility overloads for the built-in single-provider connectors.
    fun setApiKey(apiKey: String) = setApiKey(llmChoice.ifBlank { "default" }, apiKey)
    fun getApiKey(): String? = getApiKey(llmChoice.ifBlank { "default" })
    fun hasApiKey(): Boolean = hasApiKey(llmChoice.ifBlank { "default" })

    fun setProviderConfig(providerId: String, config: ProviderConfig) {
        prefs.edit().putString(providerPref(providerId), json.encodeToString(config)).apply()
    }

    fun getProviderConfig(providerId: String): ProviderConfig? =
        prefs.getString(providerPref(providerId), null)?.let {
            runCatching { json.decodeFromString<ProviderConfig>(it) }
                .onFailure { error -> Log.e(TAG, "Invalid provider config: $providerId", error) }
                .getOrNull()
        }

    fun getProviderIds(): List<String> = prefs.all.keys
        .filter { it.startsWith(PROVIDER_PREFIX) }
        .map { it.removePrefix(PROVIDER_PREFIX) }
        .sorted()

    fun hasProviderConfig(providerId: String): Boolean = prefs.contains(providerPref(providerId))

    fun setActiveProvider(providerId: String) {
        getProviderIds().forEach { id ->
            getProviderConfig(id)?.let { setProviderConfig(id, it.copy(isActive = id == providerId)) }
        }
        getProviderConfig(providerId)?.let { setProviderConfig(providerId, it.copy(isActive = true)) }
        llmChoice = providerId
    }

    fun deleteProvider(providerId: String) {
        prefs.edit().remove(providerPref(providerId)).remove(apiKeyPref(providerId)).apply()
        if (llmChoice == providerId) llmChoice = ""
    }

    fun getLegacyApiKey(): String? = prefs.getString(LEGACY_API_KEY_KEY, null)
    fun setLegacyApiKey(key: String?) {
        prefs.edit().putString(LEGACY_API_KEY_KEY, key).apply()
    }

    fun migrateLegacyConfigs() {
        val legacy = getLegacyApiKey()
        if (!legacy.isNullOrBlank() && getProviderIds().isEmpty()) {
            setApiKey("openai", legacy)
            setProviderConfig(
                "openai",
                ProviderConfig(
                    id = "openai",
                    name = "OpenAI",
                    baseUrl = "https://api.openai.com/v1",
                    modelId = "gpt-4o",
                    protocol = ProviderProtocol.OPENAI_COMPATIBLE,
                    authType = ProviderAuthType.BEARER_TOKEN
                )
            )
            llmChoice = "openai"
            setLegacyApiKey(null)
        }
    }

    private fun providerPref(providerId: String) =
        PROVIDER_PREFIX + providerId.lowercase().replace(" ", "_")

    companion object {
        private const val TAG = "SecureConfig"
        private const val PROVIDER_PREFIX = "providers/"
        private const val LEGACY_API_KEY_KEY = "legacy_api_key"
    }
}
