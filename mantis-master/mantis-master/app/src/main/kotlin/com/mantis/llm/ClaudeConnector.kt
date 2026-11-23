package com.mantis.llm

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.mantis.bridge.AgentAction
import com.mantis.storage.SecureConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.ByteArrayOutputStream

/**
 * [LLMConnector] implementation that calls the Anthropic Claude API.
 *
 * Sends the current screenshot (JPEG, 720p, 70% quality) as a base64 image block
 * alongside the conversation history and user task. Claude is instructed via the
 * system prompt to respond with a JSON object matching the MANTIS action schema.
 *
 * API key is read from [SecureConfig] (Android Keystore) on every call.
 * It is never logged, never stored in a field, never sent anywhere except
 * the `x-api-key` header of the Anthropic API request.
 *
 * Retry: up to [RETRY_COUNT] attempts with exponential backoff ([RETRY_DELAYS_MS]).
 * Only 429 and 5xx responses are retried; 4xx are thrown immediately.
 *
 * The inline JSON → [AgentAction] parsing in [parseActionJson] will be
 * extracted to [ActionParser] in Phase 3 Step 3.
 */
class ClaudeConnector(private val secureConfig: SecureConfig) : LLMConnector {

    companion object {
        private const val TAG = "ClaudeConnector"

        /** Model used for the main agent loop (sends screenshot + full context). */
        const val MODEL_AGENT = "claude-sonnet-4-6"

        /** Model used for lightweight routing / classification calls (no screenshot). */
        const val MODEL_CHEAP = "claude-haiku-4-5-20251001"

        private const val ENDPOINT = "https://api.anthropic.com/v1/messages"
        private const val ANTHROPIC_VERSION = "2023-06-01"
        private const val MAX_TOKENS = 1024
        private const val JPEG_QUALITY = 70

        private const val RETRY_COUNT = 3

        /** Delay before attempt 2, then attempt 3. Last value reused if more retries added. */
        private val RETRY_DELAYS_MS = listOf(2_000L, 4_000L)
    }

    private val parser = ActionParser()

    private val http = HttpClient(Android) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        // Do NOT install Logging — request headers contain the API key.
    }

    private val json = Json { ignoreUnknownKeys = true }

    // ── LLMConnector ──────────────────────────────────────────────────────────

    override suspend fun analyze(
        screenshot: Bitmap,
        systemPrompt: String,
        conversationHistory: List<Message>,
        userTask: String,
    ): AgentAction {
        try {
            val apiKey = secureConfig.getApiKey()
                ?: throw LLMException("No API key configured — user must complete onboarding")

            val screenshotB64 = encodeScreenshot(screenshot)
            val body = buildRequestBody(
                model = MODEL_AGENT,
                systemPrompt = systemPrompt,
                history = conversationHistory,
                userTask = userTask,
                screenshotB64 = screenshotB64,
            )

            return withRetry { callClaude(apiKey, body) }
        } catch (e: LLMException) {
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // always re-throw so coroutine cancellation propagates cleanly
        } catch (e: Exception) {
            throw LLMException("Unexpected error in ClaudeConnector: ${e.javaClass.simpleName} — ${e.message}", e)
        }
    }

    // ── Text-only completion (no screenshot) ─────────────────────────────────

    /**
     * Sends a plain-text prompt to Haiku and returns the raw text response.
     * Used for lightweight tasks that don't need a screenshot (memory summarization,
     * skill selection). Implements [LLMConnector.complete].
     */
    override suspend fun complete(systemPrompt: String, userMessage: String): String {
        val apiKey = secureConfig.getApiKey()
            ?: throw LLMException("No API key configured")

        val body = buildJsonObject {
            put("model", MODEL_CHEAP)
            put("max_tokens", 4096)
            put("system", systemPrompt)
            putJsonArray("messages") {
                add(buildJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", userMessage)
                        })
                    }
                })
            }
        }

        val response = http.post(ENDPOINT) {
            contentType(ContentType.Application.Json)
            header("x-api-key", apiKey)
            header("anthropic-version", ANTHROPIC_VERSION)
            setBody(body.toString())
        }

        if (!response.status.isSuccess()) {
            throw LLMException("Claude API HTTP ${response.status.value} (complete)")
        }

        val root = json.parseToJsonElement(response.bodyAsText()).jsonObject
        return root["content"]?.jsonArray
            ?.firstOrNull { it.jsonObject["type"]?.jsonPrimitive?.content == "text" }
            ?.jsonObject?.get("text")?.jsonPrimitive?.content
            ?: throw LLMException("No text content in Claude complete() response")
    }

    // ── Screenshot encoding ───────────────────────────────────────────────────

    private fun encodeScreenshot(bitmap: Bitmap): String {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    // ── Request building ──────────────────────────────────────────────────────

    private fun buildRequestBody(
        model: String,
        systemPrompt: String,
        history: List<Message>,
        userTask: String,
        screenshotB64: String,
    ) = buildJsonObject {
        put("model", model)
        put("max_tokens", MAX_TOKENS)
        put("system", systemPrompt)
        putJsonArray("messages") {
            // Previous conversation turns — text only, no screenshots in history.
            for (msg in history) {
                add(buildJsonObject {
                    put("role", msg.role)
                    putJsonArray("content") {
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", msg.content)
                        })
                    }
                })
            }
            // Current turn: screenshot + task description.
            add(buildJsonObject {
                put("role", "user")
                putJsonArray("content") {
                    add(buildJsonObject {
                        put("type", "image")
                        putJsonObject("source") {
                            put("type", "base64")
                            put("media_type", "image/jpeg")
                            put("data", screenshotB64)
                        }
                    })
                    add(buildJsonObject {
                        put("type", "text")
                        put("text", "Task: $userTask")
                    })
                }
            })
        }
    }

    // ── HTTP call ─────────────────────────────────────────────────────────────

    private suspend fun callClaude(
        apiKey: String,
        body: kotlinx.serialization.json.JsonObject,
    ): AgentAction {
        val response = http.post(ENDPOINT) {
            contentType(ContentType.Application.Json)
            header("x-api-key", apiKey)
            header("anthropic-version", ANTHROPIC_VERSION)
            setBody(body.toString())
        }

        if (!response.status.isSuccess()) {
            val code = response.status.value
            val errorBody = response.bodyAsText()
            Log.e(TAG, "Claude API $code — body: $errorBody")
            val retryable = code == 429 || code >= 500
            val msg = "Claude API HTTP $code"
            throw if (retryable) RetryableException(msg) else LLMException(msg)
        }

        return parseClaudeResponse(response.bodyAsText())
    }

    // ── Response parsing ──────────────────────────────────────────────────────

    /**
     * Extracts the text content block from the Claude API response envelope and
     * delegates to [parseActionJson] to convert the embedded JSON into an [AgentAction].
     *
     * Claude API response shape:
     * `{ "content": [ { "type": "text", "text": "<action JSON>" } ] }`
     */
    private fun parseClaudeResponse(responseJson: String): AgentAction {
        try {
            val root = json.parseToJsonElement(responseJson).jsonObject
            val contentArray = root["content"]?.jsonArray
                ?: throw LLMException("Claude response missing 'content' array")

            val actionJson = contentArray
                .firstOrNull { it.jsonObject["type"]?.jsonPrimitive?.content == "text" }
                ?.jsonObject?.get("text")?.jsonPrimitive?.content
                ?: throw LLMException("Claude response contains no text content block")

            // Log raw response so parse failures can be diagnosed in Logcat.
            Log.d(TAG, "Raw Claude text block (first 500 chars): ${actionJson.take(500)}")

            // Claude occasionally adds preamble text or markdown code fences before the JSON.
            // Extract the JSON object by finding the first '{' and last '}'.
            val start = actionJson.indexOf('{')
            val end = actionJson.lastIndexOf('}')
            if (start == -1 || end == -1 || end < start) {
                throw LLMException("Claude response contains no JSON object: ${actionJson.take(200)}")
            }
            val cleanedJson = actionJson.substring(start, end + 1)

            return parser.parse(cleanedJson)
        } catch (e: LLMException) {
            throw e
        } catch (e: Exception) {
            throw LLMException("Failed to parse Claude API response envelope", e)
        }
    }

    // ── Retry ─────────────────────────────────────────────────────────────────

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
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e // always propagate cancellation
            } catch (e: Exception) {
                // Network errors (IOException, SocketException, SSL, etc.) are retryable.
                lastException = e
                val delayMs = RETRY_DELAYS_MS.getOrElse(attempt) { RETRY_DELAYS_MS.last() }
                Log.w(TAG, "Attempt ${attempt + 1}/$RETRY_COUNT network error: ${e.javaClass.simpleName} — retrying in ${delayMs}ms")
                delay(delayMs)
            }
        }
        throw LLMException("Claude API failed after $RETRY_COUNT attempts", lastException)
    }

    /** Thrown internally to signal that the request should be retried (429, 5xx). */
    private class RetryableException(message: String) : Exception(message)
}
