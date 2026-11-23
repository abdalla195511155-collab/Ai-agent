package com.mantis.llm

import com.mantis.bridge.AgentAction

/**
 * Detects whether an [AgentAction] is irreversible before it is executed.
 *
 * Two independent signals are combined with OR — if either fires, the action is
 * considered irreversible and Phase 9 will present a confirmation notification
 * to the user before the action is dispatched.
 *
 * **Signal 1 — LLM hint:** Claude sets `is_irreversible = true` in its JSON
 * response when it judges the action cannot be undone. This is carried on the
 * [AgentAction] as [AgentAction.isIrreversible].
 *
 * **Signal 2 — Local keyword match:** A case-insensitive scan of the action's
 * [AgentAction.description], [AgentAction.irreversibleDescription], and (for
 * [AgentAction.TypeText]) the typed text, plus the caller-supplied [context]
 * string, against [IRREVERSIBLE_KEYWORDS].
 *
 * This class holds no state and is safe to use as a singleton.
 */
class IrreversibleDetector {

    companion object {
        /**
         * Keywords that indicate an action is likely irreversible.
         * Matched case-insensitively as whole substrings.
         */
        val IRREVERSIBLE_KEYWORDS = listOf(
            "send",
            "delete",
            "purchase",
            "pay",
            "transfer",
            "submit",
            "confirm",
            "uninstall",
            "clear all",
            "factory reset",
        )
    }

    /**
     * Returns `true` if [action] is irreversible.
     *
     * @param action   The action about to be executed.
     * @param context  Additional textual context (e.g. the current task description
     *                 or the LLM's `thought` field). Included in the keyword scan.
     */
    fun isIrreversible(action: AgentAction, context: String = ""): Boolean {
        // Signal 1: LLM explicitly flagged this action.
        if (action.isIrreversible) return true

        // Signal 2: local keyword scan across all text surfaces.
        val surfaces = buildList {
            add(action.description)
            action.irreversibleDescription?.let { add(it) }
            if (action is AgentAction.TypeText) add(action.text)
            if (context.isNotBlank()) add(context)
        }
        return containsKeyword(surfaces)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun containsKeyword(surfaces: List<String>): Boolean {
        val combined = surfaces.joinToString(" ").lowercase()
        return IRREVERSIBLE_KEYWORDS.any { keyword -> combined.contains(keyword) }
    }
}
