package com.mantis.bridge

import android.graphics.Rect

// ── UI Tree ──────────────────────────────────────────────────────────────────

/**
 * A single element from the Android accessibility tree.
 *
 * [contentDescription] is an intentional addition to the spec's base field list —
 * it is a standard Android accessibility attribute essential for identifying
 * unlabelled elements (icon buttons, image views).
 */
data class UiElement(
    val index: Int,
    val text: String,
    val contentDescription: String,
    val bounds: Rect,
    val clickable: Boolean,
    val scrollable: Boolean,
    val className: String,
)

/**
 * A snapshot of the foreground app's full accessibility tree at a point in time.
 */
data class UiSnapshot(
    val elements: List<UiElement>,
    val packageName: String,
    val timestamp: Long,
)

// ── Agent Actions ─────────────────────────────────────────────────────────────

enum class ScrollDirection { UP, DOWN, LEFT, RIGHT }

/**
 * All actions the agent can perform. Every subclass carries:
 *  - [description]: human-readable status text displayed in LiveViewPanel (Phase 6)
 *  - [thought]: Claude's reasoning, also displayed in the UI
 *
 * Both fields come directly from Claude's JSON response and flow through
 * AgentBridge to the UI without modification.
 *
 * Dispatch routing (see AgentBridge.executeAction):
 *  - Tap / Swipe / LongPress / PressBack / PressHome / Scroll → MantisActionExecutor (Phase 2 Step 3)
 *  - TypeText → private dispatchTypeText() → MantisKeyboardIME (Phase 2 Step 4)
 *  - Wait → delay(durationMs) inside executeAction — no executor needed
 *  - Complete / Stuck → terminal states consumed by AgentLoop (Phase 4), not executed
 *
 * [isIrreversible] and [irreversibleDescription] are set from Claude's JSON response.
 * IrreversibleDetector (Phase 3 Step 4) combines this with local keyword matching.
 * Subtypes that cannot be irreversible inherit the sealed-class default (false / null).
 */
sealed class AgentAction {
    abstract val description: String
    abstract val thought: String
    open val isIrreversible: Boolean = false
    open val irreversibleDescription: String? = null

    data class Tap(
        val x: Int,
        val y: Int,
        override val description: String,
        override val thought: String,
        override val isIrreversible: Boolean = false,
        override val irreversibleDescription: String? = null,
    ) : AgentAction()

    data class Swipe(
        val fromX: Int,
        val fromY: Int,
        val toX: Int,
        val toY: Int,
        val durationMs: Long,
        override val description: String,
        override val thought: String,
        override val isIrreversible: Boolean = false,
        override val irreversibleDescription: String? = null,
    ) : AgentAction()

    data class LongPress(
        val x: Int,
        val y: Int,
        override val description: String,
        override val thought: String,
        override val isIrreversible: Boolean = false,
        override val irreversibleDescription: String? = null,
    ) : AgentAction()

    data class TypeText(
        val text: String,
        override val description: String,
        override val thought: String,
        override val isIrreversible: Boolean = false,
        override val irreversibleDescription: String? = null,
    ) : AgentAction()

    data class Wait(
        val durationMs: Long,
        override val description: String,
        override val thought: String,
    ) : AgentAction()

    data class PressBack(
        override val description: String,
        override val thought: String,
    ) : AgentAction()

    data class PressHome(
        override val description: String,
        override val thought: String,
    ) : AgentAction()

    data class Scroll(
        val direction: ScrollDirection,
        val elementIndex: Int?,
        override val description: String,
        override val thought: String,
    ) : AgentAction()

    /** Agent has determined the task is complete. Not dispatched to any executor. */
    data class Complete(
        override val description: String,
        override val thought: String,
    ) : AgentAction()

    /**
     * Agent cannot proceed and needs human help.
     * [needsHelpReason] is displayed in the UI so the user knows what went wrong.
     * Not dispatched to any executor.
     */
    data class Stuck(
        val needsHelpReason: String,
        override val description: String,
        override val thought: String,
    ) : AgentAction()
}

// ── Overlay ───────────────────────────────────────────────────────────────────

/** A single numbered bounding box drawn by MantisOverlayService. */
data class OverlayElement(val index: Int, val bounds: Rect)

/** A full frame of overlay elements to draw over the screen. */
data class OverlayFrame(val elements: List<OverlayElement>)
