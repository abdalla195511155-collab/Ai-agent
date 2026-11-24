package com.mantis.llm

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.mantis.bridge.AgentAction
import com.mantis.storage.ProviderAuthType
import com.mantis.storage.ProviderConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpTimeout
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
 * Connector for providers implementing the OpenAI Chat Completions API.
 * API keys are retrieved from encrypted storage and are never logged.
 */
class CustomOpenAIConnector(
    private val secureConfig: com.mantis.storage.SecureConfig,
    private val providerConfig: ProviderConfig,
) : LLMConnector {

    companion object {
        private const val TAG = "CustomOpenAIConnector"
        private const val RETRY_COUNT = 3
        private const val JPEG_QUALITY = 70
        private val RETRY_DELAYS_MS = listOf(1_000L, 2_000L, 4_000L)
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val parser = ActionParser()
    private val baseUrl = providerConfig.baseUrl.trimEnd('/')
    private val http = HttpClient(Android) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        install(HttpTimeout) {
            requestTimeoutMillis = providerConfig.timeoutMs.toLong().coerceAtLeast(1_000L)
            connectTimeoutMillis = providerConfig.timeoutMs.toLong().coerceAtLeast(1_000L)
            socketTimeoutMillis = providerConfig.timeoutMs.toLong().coerceAtLeast(1_000L)
        }
        // Never install HTTP logging: request headers contain credentials.
    }

    private fun authHeaders(): Pair<String, String> {
        val key = secureConfig.getApiKey(providerConfig.id)?.takeIf { it.isNotBlank() }
            ?: throw LLMException("No API key configured for provider '${providerConfig.name}'.")
        return when (providerConfig.authType) {
            ProviderAuthType.BEARER_TOKEN -> "Authorization" to "Bearer $key"
            ProviderAuthType.CUSTOM_API_KEY_HEADER -> {
                val name = providerConfig.customHeaderName?.takeIf { it.isNotBlank() }
                    ?: throw LLMException("Custom API key header name is missing.")
                name to (providerConfig.customHeaderValue?.takeIf { it.isNotBlank() } ?: key)
            }
        }
    }

    override suspend fun analyze(
        screenshot: Bitmap,
        systemPrompt: String,
        conversationHistory: List<Message>,
        userTask: String,
    ): AgentAction {
        val screenshotB64 = encodeScreenshot(screenshot)
        val body = buildJsonObject {
            put("model", providerConfig.modelId)
            put("max_tokens", 1_000)
            put("temperature", 0.0)
            putJsonArray("messages") {
                if (systemPrompt.isNotBlank()) {
                    add(buildJsonObject {
                        put("role", "system")
                        put("content", systemPrompt)
                    })
                }
                for (message in Message.prune(conversationHistory)) {
                    add(buildJsonObject {
                        put("role", message.role)
                        put("content", message.content)
                    })
                }
                add(buildJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", "Task: $userTask")
                        })
                        add(buildJsonObject {
                            put("type", "image_url")
                            putJsonObject("image_url") {
                                put("url", "data:image/jpeg;base64,$screenshotB64")
                            }
                        })
                    }
                })
            }
        }
        return withRetry {
            val rawText = postChatCompletion(body)
            val start = rawText.indexOf('{')
            val end = rawText.lastIndexOf('}')
            if (start < 0 || end < start) {
                throw LLMException("Provider response did not contain a JSON action.")
            }
            parser.parse(rawText.substring(start, end + 1))
        }
    }

    override suspend fun complete(systemPrompt: String, userMessage: String): String {
        val body = buildJsonObject {
            put("model", providerConfig.modelId)
            put("max_tokens", 2_000)
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
        return withRetry { postChatCompletion(body) }
    }

    private suspend fun postChatCompletion(body: kotlinx.serialization.json.JsonObject): String {
        val (headerName, headerValue) = authHeaders()
        val response = http.post("$baseUrl/chat/completions") {
            contentType(ContentType.Application.Json)
            header(headerName, headerValue)
            setBody(body.toString())
        }
        if (!response.status.isSuccess()) {
            val code = response.status.value
            // Do not include the response body: providers may echo request data.
            if (code == 429 || code >= 500) {
                throw RetryableException("Provider HTTP $code")
            }
            throw LLMException("Provider request failed with HTTP $code.")
        }
        val root = try {
            json.parseToJsonElement(response.bodyAsText()).jsonObject
        } catch (e: Exception) {
            throw LLMException("Could not parse provider response.", e)
        }
        val content = root["choices"]?.jsonArray
            ?.firstOrNull()
            ?.jsonObject
            ?.get("message")
            ?.jsonObject
            ?.get("content")
            ?.jsonPrimitive
            ?.content
        return content?.takeIf { it.isNotBlank() }
            ?: throw LLMException("Provider response is missing choices[0].message.content.")
    }

    private fun encodeScreenshot(bitmap: Bitmap): String {
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
        return Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
    }

    private suspend fun <T> withRetry(block: suspend () -> T): T {
        var lastError: Exception? = null
        repeat(RETRY_COUNT) { attempt ->
            try {
                return block()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: LLMException) {
                throw e
            } catch (e: RetryableException) {
                lastError = e
                if (attempt < RETRY_COUNT - 1) {
                    val wait = RETRY_DELAYS_MS.getOrElse(attempt) { RETRY_DELAYS_MS.last() }
                    Log.w(TAG, "Transient provider failure; retry ${attempt + 1}/$RETRY_COUNT after ${wait}ms.")
                    delay(wait)
                }
            } catch (e: Exception) {
                lastError = e
                if (attempt < RETRY_COUNT - 1) {
                    val wait = RETRY_DELAYS_MS.getOrElse(attempt) { RETRY_DELAYS_MS.last() }
                    Log.w(TAG, "Provider network failure; retry ${attempt + 1}/$RETRY_COUNT after ${wait}ms.")
                    delay(wait)
                }
            }
        }
        throw LLMException("Custom provider request failed after $RETRY_COUNT attempts.", lastError)
    }

    private class RetryableException(message: String) : Exception(message)
}
