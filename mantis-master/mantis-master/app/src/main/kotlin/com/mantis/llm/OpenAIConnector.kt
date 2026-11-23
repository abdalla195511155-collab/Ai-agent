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
 * [LLMConnector] implementation that calls the OpenAI Chat Completions API.
 *
 * [analyze] uses `gpt-4o` with vision (screenshot as base64 image_url block).
 * [complete] uses `gpt-4o-mini` for cheap text-only calls (skill selection,
 * memory summarization).
 *
 * API key is read from [SecureConfig] (Android Keystore) on every call.
 * It is never logged, never stored in a field, never sent anywhere except
 * the `Authorization: Bearer {key}` header.
 *
 * Retry: up to [RETRY_COUNT] attempts with exponential backoff ([RETRY_DELAYS_MS]).
 * 429 and 5xx responses are retried; other 4xx are thrown immediately.
 *
 * JSON extraction: GPT-4o may prepend preamble text or wrap the response in
 * markdown code fences. The raw text is cleaned by extracting the substring
 * between the first `{` and last `}` before parsing.
 */
class OpenAIConnector(private val secureConfig: SecureConfig) : LLMConnector {

    companion object {
        private const val TAG = "OpenAIConnector"

        private const val MODEL_AGENT  = "gpt-4o"
        private const val MODEL_CHEAP  = "gpt-4o-mini"
        private const val ENDPOINT     = "https://api.openai.com/v1/chat/completions"
        private const val MAX_TOKENS   = 1024
        private const val JPEG_QUALITY = 70

        private const val RETRY_COUNT = 3
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
            val body = buildAnalyzeBody(systemPrompt, conversationHistory, userTask, screenshotB64)
            return withRetry { callOpenAI(apiKey, body, MODEL_AGENT) }
        } catch (e: LLMException) {
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            throw LLMException("Unexpected error in OpenAIConnector: ${e.javaClass.simpleName} — ${e.message}", e)
        }
    }

    override suspend fun complete(systemPrompt: String, userMessage: String): String {
        val apiKey = secureConfig.getApiKey()
            ?: throw LLMException("No API key configured")

        val body = buildJsonObject {
            put("model", MODEL_CHEAP)
            put("max_tokens", 4096)
            putJsonArray("messages") {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", systemPrompt)
                })
                add(buildJsonObject {
                    put("role", "user")
                    put("content", userMessage)
                })
            }
        }

        val response = http.post(ENDPOINT) {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $apiKey")
            setBody(body.toString())
        }

        if (!response.status.isSuccess()) {
            throw LLMException("OpenAI API HTTP ${response.status.value} (complete)")
        }

        return extractTextFromResponse(response.bodyAsText())
    }

    // ── Screenshot encoding ───────────────────────────────────────────────────

    private fun encodeScreenshot(bitmap: Bitmap): String {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    // ── Request building ──────────────────────────────────────────────────────

    private fun buildAnalyzeBody(
        systemPrompt: String,
        history: List<Message>,
        userTask: String,
        screenshotB64: String,
    ) = buildJsonObject {
        put("model", MODEL_AGENT)
        put("max_tokens", MAX_TOKENS)
        putJsonArray("messages") {
            // System prompt as the first message.
            add(buildJsonObject {
                put("role", "system")
                put("content", systemPrompt)
            })
            // Previous conversation turns — text only, no screenshots in history.
            for (msg in history) {
                add(buildJsonObject {
                    put("role", msg.role)
                    put("content", msg.content)
                })
            }
            // Current turn: screenshot + task description as a vision message.
            add(buildJsonObject {
                put("role", "user")
                putJsonArray("content") {
                    add(buildJsonObject {
                        put("type", "image_url")
                        putJsonObject("image_url") {
                            put("url", "data:image/jpeg;base64,$screenshotB64")
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

    private suspend fun callOpenAI(
        apiKey: String,
        body: kotlinx.serialization.json.JsonObject,
        model: String,
    ): AgentAction {
        val response = http.post(ENDPOINT) {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $apiKey")
            setBody(body.toString())
        }

        if (!response.status.isSuccess()) {
            val code = response.status.value
            val errorBody = response.bodyAsText()
            Log.e(TAG, "OpenAI API $code — body: $errorBody")
            val retryable = code == 429 || code >= 500
            val msg = "OpenAI API HTTP $code"
            throw if (retryable) RetryableException(msg) else LLMException(msg)
        }

        val rawText = extractTextFromResponse(response.bodyAsText())
        Log.d(TAG, "Raw OpenAI text (first 500 chars): ${rawText.take(500)}")

        // GPT-4o may prepend preamble text or wrap the JSON in markdown code fences.
        // Extract the JSON object by finding the first '{' and last '}'.
        val start = rawText.indexOf('{')
        val end   = rawText.lastIndexOf('}')
        if (start == -1 || end == -1 || end < start) {
            throw LLMException("OpenAI response contains no JSON object: ${rawText.take(200)}")
        }
        val cleanedJson = rawText.substring(start, end + 1)

        return parser.parse(cleanedJson)
    }

    // ── Response parsing ──────────────────────────────────────────────────────

    /**
     * Extracts the text content from `choices[0].message.content` of the OpenAI response.
     */
    private fun extractTextFromResponse(responseJson: String): String {
        return try {
            val root = json.parseToJsonElement(responseJson).jsonObject
            root["choices"]?.jsonArray
                ?.firstOrNull()
                ?.jsonObject?.get("message")
                ?.jsonObject?.get("content")
                ?.jsonPrimitive?.content
                ?: throw LLMException("OpenAI response missing choices[0].message.content")
        } catch (e: LLMException) {
            throw e
        } catch (e: Exception) {
            throw LLMException("Failed to parse OpenAI API response envelope", e)
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
                throw e
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                lastException = e
                val delayMs = RETRY_DELAYS_MS.getOrElse(attempt) { RETRY_DELAYS_MS.last() }
                Log.w(TAG, "Attempt ${attempt + 1}/$RETRY_COUNT network error: ${e.javaClass.simpleName} — retrying in ${delayMs}ms")
                delay(delayMs)
            }
        }
        throw LLMException("OpenAI API failed after $RETRY_COUNT attempts", lastException)
    }

    private class RetryableException(message: String) : Exception(message)
}
