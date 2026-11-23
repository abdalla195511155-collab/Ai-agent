package com.mantis.llm

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.mantis.bridge.AgentAction
import com.mantis.storage.SecureConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.url
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
 * [LLMConnector] implementation that calls the Google Gemini API.
 *
 * [analyze] uses `gemini-2.0-flash` with vision (screenshot as inlineData base64 part).
 * [complete] uses the same model for cheap text-only calls (skill selection,
 * memory summarization).
 *
 * API key is read from [SecureConfig] (Android Keystore) on every call.
 * It is never logged, never stored in a field, never sent anywhere except
 * the `?key=` query parameter of the Gemini API request.
 *
 * Retry: up to [RETRY_COUNT] attempts with exponential backoff ([RETRY_DELAYS_MS]).
 * 429 and 5xx responses are retried; other 4xx are thrown immediately.
 *
 * JSON extraction: Gemini may prepend preamble text or wrap the response in
 * markdown code fences. The raw text is cleaned by extracting the substring
 * between the first `{` and last `}` before parsing.
 */
class GeminiConnector(private val secureConfig: SecureConfig) : LLMConnector {

    companion object {
        private const val TAG = "GeminiConnector"

        private const val MODEL       = "gemini-2.0-flash"
        private const val BASE_URL    = "https://generativelanguage.googleapis.com/v1beta/models"
        private const val MAX_TOKENS  = 1024
        private const val JPEG_QUALITY = 70

        private const val RETRY_COUNT = 3
        private val RETRY_DELAYS_MS = listOf(2_000L, 4_000L)
    }

    private val parser = ActionParser()

    private val http = HttpClient(Android) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        // Do NOT install Logging — request URL contains the API key.
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
            return withRetry { callGemini(apiKey, body) }
        } catch (e: LLMException) {
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            throw LLMException("Unexpected error in GeminiConnector: ${e.javaClass.simpleName} — ${e.message}", e)
        }
    }

    override suspend fun complete(systemPrompt: String, userMessage: String): String {
        val apiKey = secureConfig.getApiKey()
            ?: throw LLMException("No API key configured")

        val body = buildJsonObject {
            putJsonObject("systemInstruction") {
                putJsonArray("parts") {
                    add(buildJsonObject { put("text", systemPrompt) })
                }
            }
            putJsonArray("contents") {
                add(buildJsonObject {
                    put("role", "user")
                    putJsonArray("parts") {
                        add(buildJsonObject { put("text", userMessage) })
                    }
                })
            }
            putJsonObject("generationConfig") {
                put("maxOutputTokens", 4096)
            }
        }

        val response = http.post {
            url("$BASE_URL/$MODEL:generateContent?key=$apiKey")
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }

        if (!response.status.isSuccess()) {
            throw LLMException("Gemini API HTTP ${response.status.value} (complete)")
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
        // System prompt via systemInstruction (Gemini-native field).
        putJsonObject("systemInstruction") {
            putJsonArray("parts") {
                add(buildJsonObject { put("text", systemPrompt) })
            }
        }
        putJsonArray("contents") {
            // Previous conversation turns — text only, no screenshots in history.
            // Gemini uses role "model" for assistant turns (not "assistant").
            for (msg in history) {
                val geminiRole = if (msg.role == "assistant") "model" else "user"
                add(buildJsonObject {
                    put("role", geminiRole)
                    putJsonArray("parts") {
                        add(buildJsonObject { put("text", msg.content) })
                    }
                })
            }
            // Current turn: screenshot + task description.
            add(buildJsonObject {
                put("role", "user")
                putJsonArray("parts") {
                    add(buildJsonObject {
                        putJsonObject("inlineData") {
                            put("mimeType", "image/jpeg")
                            put("data", screenshotB64)
                        }
                    })
                    add(buildJsonObject { put("text", "Task: $userTask") })
                }
            })
        }
        putJsonObject("generationConfig") {
            put("maxOutputTokens", MAX_TOKENS)
        }
    }

    // ── HTTP call ─────────────────────────────────────────────────────────────

    private suspend fun callGemini(
        apiKey: String,
        body: kotlinx.serialization.json.JsonObject,
    ): AgentAction {
        val response = http.post {
            url("$BASE_URL/$MODEL:generateContent?key=$apiKey")
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }

        if (!response.status.isSuccess()) {
            val code = response.status.value
            val errorBody = response.bodyAsText()
            Log.e(TAG, "Gemini API $code — body: $errorBody")
            val retryable = code == 429 || code >= 500
            val msg = "Gemini API HTTP $code"
            throw if (retryable) RetryableException(msg) else LLMException(msg)
        }

        val rawText = extractTextFromResponse(response.bodyAsText())
        Log.d(TAG, "Raw Gemini text (first 500 chars): ${rawText.take(500)}")

        // Gemini may prepend preamble text or wrap the JSON in markdown code fences.
        // Extract the JSON object by finding the first '{' and last '}'.
        val start = rawText.indexOf('{')
        val end   = rawText.lastIndexOf('}')
        if (start == -1 || end == -1 || end < start) {
            throw LLMException("Gemini response contains no JSON object: ${rawText.take(200)}")
        }
        val cleanedJson = rawText.substring(start, end + 1)

        return parser.parse(cleanedJson)
    }

    // ── Response parsing ──────────────────────────────────────────────────────

    /**
     * Extracts the text content from `candidates[0].content.parts[0].text`
     * of the Gemini response.
     */
    private fun extractTextFromResponse(responseJson: String): String {
        return try {
            val root = json.parseToJsonElement(responseJson).jsonObject
            root["candidates"]?.jsonArray
                ?.firstOrNull()
                ?.jsonObject?.get("content")
                ?.jsonObject?.get("parts")
                ?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("text")
                ?.jsonPrimitive?.content
                ?: throw LLMException("Gemini response missing candidates[0].content.parts[0].text")
        } catch (e: LLMException) {
            throw e
        } catch (e: Exception) {
            throw LLMException("Failed to parse Gemini API response envelope", e)
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
        throw LLMException("Gemini API failed after $RETRY_COUNT attempts", lastException)
    }

    private class RetryableException(message: String) : Exception(message)
}
