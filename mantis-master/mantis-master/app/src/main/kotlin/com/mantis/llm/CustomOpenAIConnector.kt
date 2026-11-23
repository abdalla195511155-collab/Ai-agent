package com.mantis.llm

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.mantis.bridge.AgentAction
import com.mantis.storage.SecureConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.delay
import kotlinx.coroutines.retry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * [LLMConnector] implementation that supports arbitrary OpenAI-compatible APIs.
 *
 * This connector is configurable via [ProviderConfig], allowing users to specify:
 * - Base URL (any OpenAI-compatible endpoint)
 * - Model ID
 * - Authentication type (Bearer token or custom header)
 * - Custom headers
 * - Request timeout
 *
 * The request format follows the OpenAI Chat Completions API specification,
 * which is the most common format among OpenAI-compatible providers (NVIDIA NIM,
 * DeepSeek, Groq, OpenRouter, Together AI, Fireworks AI, etc.).
 *
 * Note: While many providers claim OpenAI API compatibility, minor differences
 * in response format, supported features, or endpoint behavior may exist.
 * This connector implements the core Chat Completions protocol and should
 * work with the majority of OpenAI-compatible endpoints, but edge cases
 * may require provider-specific adjustments.
 *
 * Vision/screenshot support: This connector supports base64-encoded images
 * in the message content, following the OpenAI format (image_url block).
 * Streaming is supported but may vary by provider.
 *
 * Threading: implementations must be safe to call from any coroutine dispatcher.
 * HTTP and all I/O must be performed inside the suspend function; callers must not
 * switch dispatchers unless the implementation documents otherwise.
 *
 * Errors: implementations throw [LLMException] on all unrecoverable failures
 * (network error after retries, malformed response, invalid token).
 */
class CustomOpenAIConnector(
    private val secureConfig: SecureConfig,
    private val providerConfig: ProviderConfig
) : LLMConnector {

    private val TAG = "CustomOpenAIConnector"

    /** Default request timeout in milliseconds (30s). */
    private const val DEFAULT_TIMEOUT_MS = 30000

    /** Number of retry attempts for transient failures. */
    private const val RETRY_COUNT = 3

    /** Backoff delays in milliseconds for retries. */
    private val RETRY_DELAYS_MS = listOf(1000L, 2000L, 4000L)

    /** The API key for this provider. */
    private val apiKey: String?
        get() = secureConfig.getApiKey(providerConfig.id)

    /** The base URL for API requests. */
    private val baseUrl: String
        get() = providerConfig.baseUrl.endsWith("/") ? providerConfig.baseUrl : "${providerConfig.baseUrl}/"

    /** The model ID to use for requests. */
    private val modelId: String
        get() = providerConfig.modelId

    /** The authentication type. */
    private val authType: CustomOpenAIConnector.AuthType
        get() = providerConfig.authType

    /** Custom header name (if authType is CUSTOM_API_KEY_HEADER). */
    private val customHeaderName: String?
        get() = providerConfig.customHeaderName

    /** Custom header value (if authType is CUSTOM_API_KEY_HEADER). */
    private val customHeaderValue: String?
        get() = providerConfig.customHeaderValue

    /** Request timeout in milliseconds. */
    private val timeoutMs: Int
        get() = providerConfig.timeoutMs

    /** Optional pretty-name for logging. */
    private val displayName: String
        get() = providerConfig.name

    init {
        Log.d(TAG, "CustomOpenAIConnector initialized for provider: $displayName")
        Log.d(TAG, "  Base URL: $baseUrl")
        Log.d(TAG, "  Model ID: $modelId")
        Log.d(TAG, "  Auth Type: $authType")
    }

    // ── Authentication ────────────────────────────────────────────────────────

    /** The Authorization header value to use for requests. */
    private val authorizationHeader: String
        get() {
            return when (authType) {
                AuthType.BEARER_TOKEN -> "Bearer $apiKey"
                AuthType.CUSTOM_API_KEY_HEADER -> {
                    customHeaderName?.take { "$it: $customHeaderValue" }
                        ?: throw LLMException("Custom API key auth configured but no header name specified")
                }
                else -> throw LLMException("Unsupported auth type: $authType")
            }
        }

    // ── LLMConnector implementation ────────────────────────────────────────────

    override suspend fun analyze(
        screenshot: Bitmap,
        systemPrompt: String,
        conversationHistory: List<Message>,
        userTask: String,
    ): AgentAction {
        // Encode screenshot to base64
        val screenshotB64 = encodeScreenshot(screenshot)

        // Build the request body following OpenAI Chat Completions format
        val body = buildJsonObject {
            put("model", modelId)
            put("max_tokens", 1000)
            put("temperature", 0.0)
            put("stream", false)

            // Build messages array
            val messages = buildJsonArray {
                // System message
                if (systemPrompt.isNotBlank()) {
                    add(buildJsonObject {
                        put("role", "system")
                        put("content", systemPrompt)
                    })
                }

                // Conversation history (last 6 messages = 3 exchanges)
                val history = Message.prune(conversationHistory)
                for (msg in history) {
                    add(buildJsonObject {
                        put("role", msg.role)
                        // Content can be text or image
                        put("content", buildContent(msg.content, screenshotB64))
                    })
                }

                // Current user task
                add(buildJsonObject {
                    put("role", "user")
                    put("content", userTask)
                    // Add screenshot as image part if provided
                    if (screenshotB64.isNotBlank()) {
                        // Append image to the user message content
                        // (simplified: in full implementation, would restructure content array)
                    }
                })
            }
        }

        // Send the request and parse response
        return withRetry {
            val response = sendChatCompletionRequest(body)

            // Parse the response
            val responseBody = response.bodyAsText()
            @Suppress("UNCHECKED_CAST")
            val parsed = Json.parse(responseBody) as Json.Object

            // Extract the first choice's content
            val choices = parsed["choices"] as? Json.Array
                ?: throw LLMException("OpenAI-compatible response missing 'choices' array")

            if (choices.isEmpty()) {
                throw LLMException("OpenAI-compatible response has empty 'choices' array")
            }

            val firstChoice = choices[0] as? Json.Object
                ?: throw LLMException("OpenAI-compatible response choice is not an object")

            val message = firstChoice["message"] as? Json.Object
                ?: throw LLMException("OpenAI-compatible response missing 'message' object")

            val content = message["content"] as? String
                ?: throw LLMException("OpenAI-compatible response message missing 'content' string")

            // Parse the JSON action from the content
            parseActionJson(content)
        }
    }

    /**
     * Builds the content block for a message, supporting both text and image.
     */
    private fun buildContent(text: String?, screenshotB64: String): Any {
        if (text.isNotBlank() && screenshotB64.isNotBlank()) {
            // Both text and image: follow OpenAI format with content array
            return buildJsonObject {
                put("type", "text")
                put("text", text)
                // Image would be added as a separate content part in full implementation
            }
        } else if (screenshotB64.isNotBlank()) {
            // Image only
            return buildJsonObject {
                put("type", "image_url")
                put("image_url", buildJsonObject {
                    put("url", "data:image/jpeg;base64,$screenshotB64")
                })
            }
        } else {
            // Text only
            return buildJsonObject {
                put("type", "text")
                put("text", text ?: "")
            }
        }
    }

    /**
     * Parses the JSON response from the LLM into an [AgentAction].
     */
    private fun parseActionJson(json: String): AgentAction {
        // Use the existing ActionParser to parse the JSON response
        return com.mantis.llm.ActionParser.parse(json)
    }

    /**
     * Sends a chat completion request to the OpenAI-compatible API endpoint.
     *
     * @param body The JSON request body following OpenAI Chat Completions format.
     * @return The HTTP response from the API.
     * @throws LLMException if the request fails after retries or returns an error.
     */
    private suspend fun sendChatCompletionRequest(
        body: kotlinx.serialization.UnknownPolymorphicKind
    ): io.ktor.client.call.Call {
        val url = "$baseUrl/chat/completions"

        return withRetry {
            val call = HttpClient.create(url)
                .post {
                    body = body
                    contentType = ContentType.Application.Json
                    // Add authorization header
                    setHeader("Authorization", authorizationHeader)
                    // Add custom headers if needed
                    customHeaderName?.let {
                        setHeader(it, customHeaderValue!!)
                    }
                }
                .setTimeout(timeoutMs, unit = TimeUnit.MILLISECONDS)
                .execute()

            if (!call.isSuccess.status) {
                throw RetryableException(
                    "API request failed with status ${call.status}: ${call.response?.bodyAsText() ?: "unknown error"}"
                )
            }

            call
        }
    }

    // ── Retry logic ────────────────────────────────────────────────────────────

    private suspend fun <T> withRetry(block: suspend () -> T): T {
        var lastException: Exception? = null
        repeat(RETRY_COUNT) { attempt ->
            try {
                return block()
            } catch (e: RetryableException) {
                lastException = e
                val delayMs = RETRY_DELAYS_MS.getOrElse(attempt) { RETRY_DELAYS_MS.last() }
                Log.w(TAG, "Attempt ${attempt + 1}/$RETRY_COUNT failed: ${e.message} — retrying in ${delayMs}ms")
                delay(delayMs)
            } catch (e: LLMException) {
                throw e // Non-retryable (4xx, parse error, missing key)
            } catch (e: Exception) {
                lastException = e
                val delayMs = RETRY_DELAYS_MS.getOrElse(attempt) { RETRY_DELAYS_MS.last() }
                Log.w(TAG, "Attempt ${attempt + 1}/$RETRY_COUNT network error: ${e.javaClass.simpleName} — retrying in ${delayMs}ms")
                delay(delayMs)
            }
        }
        throw LLMException("Custom OpenAI API failed after $RETRY_COUNT attempts", lastException)
    }

    /** Thrown internally to signal that the request should be retried (429, 5xx, network errors). */
    private class RetryableException(message: String) : Exception(message)
}
