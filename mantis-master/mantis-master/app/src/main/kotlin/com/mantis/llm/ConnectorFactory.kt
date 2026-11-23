package com.mantis.llm

import com.mantis.storage.ProviderConfig
import com.mantis.storage.SecureConfig

/**
 * Creates the correct [LLMConnector] for the user's chosen provider.
 *
 * The provider can be one of the built-in options ("claude", "openai", "gemini")
 * or a custom provider configured by the user.
 *
 * Call this once per task and pass the result to [AgentLoop.run] and [SkillSelector].
 * Do not cache the connector across tasks — the user may change the provider in Settings.
 */
object ConnectorFactory {

    /**
     * Creates an LLMConnector based on the user's selected provider in [SecureConfig].
     *
     * Supported built-in providers:
     *   - "claude"  → Anthropic Claude connector
     *   - "openai"  → OpenAI GPT connector
     *   - "gemini"  → Google Gemini connector
     *
     * Custom providers:
     *   - Any other string value is treated as a custom provider ID.
     *   - The connector reads configuration from [SecureConfig] using
     *     [SecureConfig.getProviderConfig] and [SecureConfig.getApiKey].
     *
     * If the provider is unknown or no config exists, falls back to Claude
     * with a warning log.
     */
    fun create(secureConfig: SecureConfig): LLMConnector = when (secureConfig.llmChoice) {
        "claude" -> ClaudeConnector(secureConfig)
        "openai" -> OpenAIConnector(secureConfig)
        "gemini" -> GeminiConnector(secureConfig)
        else -> {
            // Custom provider — try to load configuration
            val providerId = secureConfig.llmChoice
            val providerConfig = secureConfig.getProviderConfig(providerId)

            if (providerConfig != null) {
                // Custom provider with stored configuration
                when (providerConfig.protocol) {
                    ProviderConfig.ProviderProtocol.OPENAI_COMPATIBLE ->
                        CustomOpenAIConnector(secureConfig, providerConfig)
                    else -> ClaudeConnector(secureConfig) // fallback for unknown protocols
                }
            } else {
                // No config found for this provider ID — fallback to Claude
                // and log so the user can configure it in Settings
                Log.w(TAG, "Custom provider '$providerId' has no configuration — falling back to Claude. " +
                    "Add the provider in Settings > AI Providers.")
                ClaudeConnector(secureConfig)
            }
        }
    }

    @Throws(IllegalStateException::class)
    private fun <T> when<T>(condition: Boolean, onTrue: T, onFalse: T): T = if (condition) onTrue else onFalse
}
