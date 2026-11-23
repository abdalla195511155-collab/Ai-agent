package com.mantis.llm

import com.mantis.bridge.AgentAction
import com.mantis.bridge.ScrollDirection
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Parses the JSON action string returned by Claude into an [AgentAction].
 *
 * Throws [ParseException] on any malformed input — invalid JSON, missing required fields,
 * or an unrecognised action type. The raw JSON snippet is attached to the exception
 * so the caller can log it for debugging without risk of logging sensitive data
 * (the action JSON never contains the API key or screenshot data).
 *
 * This class holds no state; a single instance can be reused across calls.
 */
class ActionParser {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Parses [actionJson] into an [AgentAction].
     *
     * @param actionJson  The text content block from the Claude API response — a JSON object
     *                    conforming to the MANTIS action schema.
     * @throws ParseException  If the string is not valid JSON, a required field is absent,
     *                         or the `action` value is not a known type.
     */
    fun parse(actionJson: String): AgentAction {
        val j = try {
            json.parseToJsonElement(actionJson).jsonObject
        } catch (e: Exception) {
            throw ParseException(
                message = "Action JSON is not valid JSON",
                rawJson = actionJson.take(300),
                cause = e,
            )
        }

        fun str(key: String) = j[key]?.jsonPrimitive?.content?.takeIf { it != "null" }
        fun int(key: String) = str(key)?.toIntOrNull()
        fun long(key: String) = str(key)?.toLongOrNull()
        fun bool(key: String) = str(key)?.toBooleanStrictOrNull() ?: false

        fun require(key: String, context: String) =
            int(key) ?: throw ParseException("$context missing '$key'", actionJson.take(300))

        val action = str("action")
            ?: throw ParseException("Missing 'action' field", actionJson.take(300))
        val description = str("description") ?: ""
        val thought = str("thought") ?: ""
        val isIrreversible = bool("is_irreversible")
        val irreversibleDesc = str("irreversible_description")

        return when (action) {
            "tap" -> AgentAction.Tap(
                x = require("x", "Tap"),
                y = require("y", "Tap"),
                description = description,
                thought = thought,
                isIrreversible = isIrreversible,
                irreversibleDescription = irreversibleDesc,
            )
            "swipe" -> AgentAction.Swipe(
                fromX = require("x", "Swipe"),
                fromY = require("y", "Swipe"),
                toX = require("swipe_to_x", "Swipe"),
                toY = require("swipe_to_y", "Swipe"),
                durationMs = long("duration_ms") ?: 300L,
                description = description,
                thought = thought,
                isIrreversible = isIrreversible,
                irreversibleDescription = irreversibleDesc,
            )
            "long_press" -> AgentAction.LongPress(
                x = require("x", "LongPress"),
                y = require("y", "LongPress"),
                description = description,
                thought = thought,
                isIrreversible = isIrreversible,
                irreversibleDescription = irreversibleDesc,
            )
            "type" -> AgentAction.TypeText(
                text = str("text")
                    ?: throw ParseException("TypeText missing 'text'", actionJson.take(300)),
                description = description,
                thought = thought,
                isIrreversible = isIrreversible,
                irreversibleDescription = irreversibleDesc,
            )
            "wait" -> AgentAction.Wait(
                durationMs = long("duration_ms") ?: 1_000L,
                description = description,
                thought = thought,
            )
            "press_back" -> AgentAction.PressBack(
                description = description,
                thought = thought,
            )
            "press_home" -> AgentAction.PressHome(
                description = description,
                thought = thought,
            )
            "scroll" -> AgentAction.Scroll(
                direction = when (str("direction")?.lowercase()) {
                    "up" -> ScrollDirection.UP
                    "left" -> ScrollDirection.LEFT
                    "right" -> ScrollDirection.RIGHT
                    else -> ScrollDirection.DOWN
                },
                elementIndex = int("element_index"),
                description = description,
                thought = thought,
            )
            "complete" -> AgentAction.Complete(
                description = description,
                thought = thought,
            )
            "stuck" -> AgentAction.Stuck(
                needsHelpReason = str("needs_help_reason") ?: "Agent is stuck",
                description = description,
                thought = thought,
            )
            else -> throw ParseException("Unknown action type: '$action'", actionJson.take(300))
        }
    }
}

/**
 * Thrown by [ActionParser] when the action JSON cannot be parsed into an [AgentAction].
 *
 * Extends [LLMException] so callers of [LLMConnector.analyze] receive a consistent
 * exception type regardless of whether the failure was a network error or a parse error.
 *
 * [rawJson] is a truncated snippet of the input — safe to log for debugging.
 */
class ParseException(
    message: String,
    val rawJson: String,
    cause: Throwable? = null,
) : LLMException(message, cause)
