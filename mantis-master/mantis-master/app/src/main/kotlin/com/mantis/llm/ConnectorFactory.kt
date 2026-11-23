package com.mantis.llm

import android.util.Log
import com.mantis.storage.ProviderConfig
import com.mantis.storage.ProviderProtocol
import com.mantis.storage.SecureConfig

/**
 * Creates the connector matching the provider selected in encrypted settings.
 */
object ConnectorFactory {
    private const val TAG = "ConnectorFactory"

    fun create(secureConfig: SecureConfig): LLMConnector =
        when (val providerId = secureConfig.llmChoice.ifBlank { "claude" }) {
            "claude" -> ClaudeConnector(secureConfig)
            "openai" -> OpenAIConnector(secureConfig)
            "gemini" -> GeminiConnector(secureConfig)
            else -> {
                val config = secureConfig.getProviderConfig(providerId)
                if (config == null) {
                    Log.w(TAG, "No configuration found for provider '$providerId'; using Claude fallback.")
                    ClaudeConnector(secureConfig)
                } else {
                    when (config.protocol) {
                        ProviderProtocol.OPENAI_COMPATIBLE ->
                            CustomOpenAIConnector(secureConfig, config)
                        ProviderProtocol.NATIVE -> {
                            Log.w(TAG, "Provider '$providerId' uses an unsupported native protocol; using Claude fallback.")
                            ClaudeConnector(secureConfig)
                        }
                    }
                }
            }
        }
}
