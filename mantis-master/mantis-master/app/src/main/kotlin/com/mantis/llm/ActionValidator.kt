package com.mantis.llm

import com.mantis.bridge.AgentAction

/**
 * Validates a parsed [AgentAction] before it is dispatched to [AgentBridge.executeAction].
 *
 * Checks performed:
 *  - **Coordinate bounds** — Tap, Swipe, and LongPress x/y values must lie within
 *    the display rectangle (0..displayWidth–1, 0..displayHeight–1). Out-of-bounds
 *    coordinates would be silently dropped by the system; catching them here surfaces
 *    a clear error instead.
 *  - **Required text** — TypeText must have a non-empty [AgentAction.TypeText.text].
 *  - **Swipe destination** — both swipe endpoints must be in bounds.
 *  - **Terminal actions** — Complete and Stuck are always valid; they carry no coordinates.
 *  - **Other actions** — Wait, PressBack, PressHome, Scroll carry no coordinates and
 *    pass validation unconditionally.
 *
 * Throws [ValidationException] on the first violation found.
 *
 * @param displayWidth   Logical display width in pixels (from DisplayMetrics.widthPixels).
 * @param displayHeight  Logical display height in pixels (from DisplayMetrics.heightPixels).
 */
class ActionValidator(
    private val displayWidth: Int,
    private val displayHeight: Int,
) {

    /**
     * Validates [action] and throws [ValidationException] if it is invalid.
     *
     * Call this after [ActionParser.parse] and before [AgentBridge.executeAction].
     */
    fun validate(action: AgentAction) {
        when (action) {
            is AgentAction.Tap -> {
                requireInBounds("Tap x", action.x, displayWidth)
                requireInBounds("Tap y", action.y, displayHeight)
            }
            is AgentAction.LongPress -> {
                requireInBounds("LongPress x", action.x, displayWidth)
                requireInBounds("LongPress y", action.y, displayHeight)
            }
            is AgentAction.Swipe -> {
                requireInBounds("Swipe fromX", action.fromX, displayWidth)
                requireInBounds("Swipe fromY", action.fromY, displayHeight)
                requireInBounds("Swipe toX", action.toX, displayWidth)
                requireInBounds("Swipe toY", action.toY, displayHeight)
            }
            is AgentAction.TypeText -> {
                if (action.text.isEmpty()) {
                    throw ValidationException("TypeText 'text' must not be empty", action)
                }
            }
            is AgentAction.Wait,
            is AgentAction.PressBack,
            is AgentAction.PressHome,
            is AgentAction.Scroll,
            is AgentAction.Complete,
            is AgentAction.Stuck -> {
                // No coordinate or content constraints — always valid.
            }
        }
    }

    private fun requireInBounds(label: String, value: Int, bound: Int) {
        if (value < 0 || value >= bound) {
            throw ValidationException(
                "$label=$value is out of display bounds [0, $bound)",
                action = null,
            )
        }
    }
}

/**
 * Thrown by [ActionValidator] when an [AgentAction] fails semantic validation.
 *
 * [action] is the offending action, included for diagnostic logging.
 * It may be null when the violation is detected before the full action is available
 * (e.g. coordinate check inside a helper).
 */
class ValidationException(
    message: String,
    val action: AgentAction?,
) : Exception(message)
