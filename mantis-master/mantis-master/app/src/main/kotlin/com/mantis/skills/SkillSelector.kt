package com.mantis.skills

import android.util.Log
import com.mantis.llm.LLMConnector

/**
 * Classifies a user task against the loaded skill list using the active [LLMConnector].
 *
 * Sends the task and skill trigger keywords to the connector's cheap text completion
 * endpoint and asks for the single best-matching skill name, or "none" if no skill fits.
 *
 * If the call fails for any reason the selector silently returns null so the
 * agent falls back to freeform execution — skill selection is never a hard dependency.
 *
 * Usage:
 * ```kotlin
 * val connector = ConnectorFactory.create(secureConfig)
 * val skill = SkillSelector(connector).select(task, SkillLoader.loadAll(context))
 * // skill?.steps is injected into AgentLoop system prompt when non-null
 * ```
 */
class SkillSelector(private val connector: LLMConnector) {

    companion object {
        private const val TAG = "SkillSelector"
    }

    /**
     * Returns the best-matching [Skill] for [task], or null if none fits.
     *
     * Never throws — any failure returns null (freeform fallback).
     */
    suspend fun select(task: String, skills: List<Skill>): Skill? {
        if (skills.isEmpty()) return null

        val skillList = skills.joinToString("\n") { "- ${it.name}: ${it.trigger}" }

        return try {
            val answer = connector.complete(
                systemPrompt = "You are a task classifier for a mobile AI agent. " +
                    "Given a user task and a list of skills, identify the best match. " +
                    "Return ONLY the exact skill name if one clearly matches. " +
                    "Return ONLY the word \"none\" if no skill fits or the task is ambiguous. " +
                    "No explanation. No punctuation. Just the skill name or \"none\".",
                userMessage = "User task: \"$task\"\n\nAvailable skills (name: trigger keywords):\n$skillList",
            ).trim()

            if (answer.equals("none", ignoreCase = true)) {
                Log.d(TAG, "No skill match for: \"$task\"")
                return null
            }

            val matched = skills.firstOrNull { it.name.equals(answer, ignoreCase = true) }
            if (matched != null) {
                Log.d(TAG, "Selected skill \"${matched.name}\" for: \"$task\"")
            } else {
                Log.w(TAG, "LLM returned unknown skill name \"$answer\" — freeform fallback")
            }
            matched
        } catch (e: Exception) {
            Log.w(TAG, "Skill selection error — freeform fallback", e)
            null
        }
    }
}
