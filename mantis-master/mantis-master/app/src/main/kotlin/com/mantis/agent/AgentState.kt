package com.mantis.agent

/**
 * Represents every possible state in the MANTIS agent lifecycle.
 * Observed by AgentService (Phase 4), HomeScreen (Phase 6), and Notifications (Phase 9).
 */
sealed class AgentState {

    /** No task running. Initial state and post-completion resting state. */
    object Idle : AgentState()

    /** Agent is actively executing a task. */
    data class Running(
        val task: String,
        val step: Int,
        val maxSteps: Int,
    ) : AgentState()

    /** User pressed TAKE OVER. Loop is paused; app moved to background. */
    data class Paused(
        val task: String,
        val step: Int,
    ) : AgentState()

    /** Agent signalled task_complete = true. Loop finished successfully. */
    data class Complete(
        val task: String,
        val steps: Int,
        val summary: String,
    ) : AgentState()

    /** Agent signalled needs_help = true (action == "stuck"). Cannot proceed alone. */
    data class Stuck(
        val task: String,
        val step: Int,
        val reason: String,
    ) : AgentState()

    /** Unrecoverable failure — LLM error, max steps reached, or validation failure. */
    data class Error(
        val task: String,
        val message: String,
    ) : AgentState()
}
