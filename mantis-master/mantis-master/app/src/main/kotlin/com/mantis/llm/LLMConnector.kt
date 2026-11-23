package com.mantis.llm

import android.graphics.Bitmap
import com.mantis.bridge.AgentAction

/**
 * A single turn in the conversation history sent to the LLM.
 *
 * [role] is either "user" or "assistant".
 * [content] is plain text — screenshots are passed separately via the [LLMConnector.analyze] call.
 *
 * Conversation history is capped at [MAX_HISTORY_MESSAGES] entries (last 6 = 3 exchanges).
 * Older messages are pruned from the front to stay within the cap.
 */
data class Message(
    val role: String,
    val content: String,
) {
    companion object {
        /** Maximum number of messages kept in history (3 user + 3 assistant = 3 exchanges). */
        const val MAX_HISTORY_MESSAGES = 6

        /**
         * Trims [history] to at most [MAX_HISTORY_MESSAGES] by dropping the oldest entries.
         * Call this before passing history into [LLMConnector.analyze].
         */
        fun prune(history: List<Message>): List<Message> =
            if (history.size > MAX_HISTORY_MESSAGES) history.takeLast(MAX_HISTORY_MESSAGES)
            else history
    }
}

/**
 * Provider-agnostic interface for sending a screenshot + task context to an LLM
 * and receiving the next [AgentAction] to execute.
 *
 * Implementations:
 *  - [com.mantis.llm.ClaudeConnector]  — Anthropic Claude via api.anthropic.com
 *  - [com.mantis.llm.OpenAIConnector]  — OpenAI GPT-4o via api.openai.com
 *  - [com.mantis.llm.GeminiConnector]  — Google Gemini via generativelanguage.googleapis.com
 *
 * Threading: implementations must be safe to call from any coroutine dispatcher.
 * HTTP and all I/O must be performed inside the suspend function; callers must not
 * switch dispatchers unless the implementation documents otherwise.
 *
 * Errors: implementations throw [LLMException] on all unrecoverable failures
 * (network error after retries, malformed response, invalid token).
 */
interface LLMConnector {

    /**
     * Analyzes the current screen state and returns the next action for the agent to take.
     *
     * @param screenshot  Latest frame from MantisScreenCapture (720p JPEG). The implementation
     *                    is responsible for encoding this to base64 before sending to the API.
     * @param systemPrompt  The full system prompt describing the agent's role and JSON schema.
     * @param conversationHistory  Previous turns in this task session. Pass the output of
     *                             [Message.prune] to keep within the 6-message cap.
     * @param userTask  The plain-language task description typed by the user.
     * @return The next [AgentAction] parsed from the LLM's JSON response.
     * @throws LLMException if the LLM call fails after all retries or returns an unparseable response.
     */
    suspend fun analyze(
        screenshot: Bitmap,
        systemPrompt: String,
        conversationHistory: List<Message>,
        userTask: String,
    ): AgentAction

    /**
     * Sends a plain-text prompt and returns the raw text response.
     * Used for lightweight tasks that don't need a screenshot (memory summarization,
     * skill selection). Implementations should use a cheaper / faster model tier.
     *
     * @throws LLMException on failure after retries.
     */
    suspend fun complete(systemPrompt: String, userMessage: String): String
}

/**
 * Thrown by [LLMConnector] implementations when the LLM call cannot be completed.
 *
 * [message] describes the failure (e.g. "HTTP 429 after 3 retries", "malformed JSON response").
 * [cause] carries the underlying exception when available.
 */
open class LLMException(message: String, cause: Throwable? = null) : Exception(message, cause)
