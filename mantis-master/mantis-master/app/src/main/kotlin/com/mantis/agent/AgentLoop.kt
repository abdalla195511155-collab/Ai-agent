package com.mantis.agent

import android.content.Context
import android.graphics.Rect
import android.util.Log
import com.mantis.bridge.AgentAction
import com.mantis.bridge.AgentBridge
import com.mantis.bridge.UiSnapshot
import com.mantis.llm.ActionValidator
import com.mantis.skills.Skill
import com.mantis.llm.IrreversibleDetector
import com.mantis.llm.LLMConnector
import com.mantis.llm.LLMException
import com.mantis.llm.Message
import com.mantis.llm.ValidationException
import com.mantis.storage.SecureConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Core agent execution engine for MANTIS.
 *
 * Stateless — [AgentService] owns the coroutine scope and exposes [AgentState].
 * Each call to [run] executes a full task from first step to terminal state.
 *
 * Loop contract (12 steps per iteration):
 *  1. Capture screenshot (wait up to 2 s if null)
 *  2. Capture latest UiSnapshot; detect whether UI changed since last step
 *  3. Build system prompt (includes no-change warning when UI is unchanged)
 *  4. Call LLM → AgentAction
 *  5. Validate action
 *  6. Check irreversibility (Phase 9 adds confirmation gate)
 *  7. Execute action via AgentBridge
 *  8. Append assistant turn to history; prune to 6 messages
 *  9. If Complete → return AgentState.Complete
 * 10. If Stuck → return AgentState.Stuck
 * 11. If step >= maxSteps → return AgentState.Error
 * 12. Delay 0.8 s for UI to settle, then repeat
 */
object AgentLoop {

    private const val TAG = "AgentLoop"
    private const val SCREENSHOT_WAIT_MS = 2_000L
    private const val STEP_DELAY_MS = 800L   // 0.8 s — tight enough for snappy execution
    private const val MAX_UI_ELEMENTS = 50

    /**
     * Runs the agent loop until a terminal state is reached or the task is interrupted.
     *
     * Must be called from a cancellable coroutine — cancellation propagates cleanly.
     * [onStateUpdate] is invoked on every step transition and on terminal states.
     *
     * @return The terminal [AgentState] (Complete, Stuck, or Error).
     */
    suspend fun run(
        task: String,
        connector: LLMConnector,
        bridge: AgentBridge,
        secureConfig: SecureConfig,
        context: Context,
        onStateUpdate: (AgentState) -> Unit,
        skill: Skill? = null,
        memory: String = "",
        /**
         * Called when the agent detects an irreversible action before execution.
         * Return true to allow execution, false to skip the action and continue the loop.
         * Default is true (allow) — safe for ScheduledTaskWorker which has no UI to confirm.
         * AgentService overrides this with a user-facing notification + 60-second timeout.
         */
        onIrreversibleAction: suspend (description: String) -> Boolean = { true },
    ): AgentState {
        val maxSteps = secureConfig.maxStepsSetting
        val dm = context.resources.displayMetrics
        val irreversibleDetector = IrreversibleDetector()
        val history = mutableListOf<Message>()
        var step = 0

        // Tracks the snapshot from the previous iteration to detect UI stagnation.
        var prevSnapshot: UiSnapshot? = null
        var noChangeCount = 0

        // Tracks the last action's signature (type + coordinates) to detect
        // same-button-tap loops where element count never changes.
        var lastActionSignature = ""
        var sameActionCount = 0

        while (true) {
            step++
            onStateUpdate(AgentState.Running(task, step, maxSteps))
            Log.d(TAG, "Step $step/$maxSteps — $task")

            // 1. Capture screenshot — wait up to 2 s if capture hasn't produced a frame yet.
            val screenshot = withTimeoutOrNull(SCREENSHOT_WAIT_MS) {
                bridge.screenshot.first { it != null }
            } ?: run {
                Log.e(TAG, "Step $step: screenshot unavailable after ${SCREENSHOT_WAIT_MS}ms")
                val state = AgentState.Error(
                    task,
                    "Screenshot unavailable — is MantisScreenCapture running?",
                )
                onStateUpdate(state)
                return state
            }

            // Screenshot is captured at a fixed lower resolution (e.g. 720×1560) while the
            // physical display may be higher (e.g. 1440×3088). Claude receives the screenshot
            // and must output coordinates in screenshot space so its visual reasoning stays
            // accurate. We then scale those coordinates up to physical display space before
            // dispatching gestures.
            val shotW = screenshot.width   // e.g. 720
            val shotH = screenshot.height  // e.g. 1560
            val scaleX = dm.widthPixels.toFloat() / shotW
            val scaleY = dm.heightPixels.toFloat() / shotH
            val validator = ActionValidator(shotW, shotH)

            // 2. Capture latest UI snapshot; compare to previous to detect stagnation.
            val snapshot: UiSnapshot? = bridge.snapshot.value
            val uiChanged = uiChanged(prevSnapshot, snapshot)
            if (!uiChanged && prevSnapshot != null) {
                noChangeCount++
                Log.w(TAG, "Step $step: UI unchanged for $noChangeCount consecutive step(s)")
            } else {
                noChangeCount = 0
            }
            prevSnapshot = snapshot

            // 3. Build system prompt — inject no-change warning when the UI is stagnating.
            //    Element bounds are scaled to screenshot space so they align with the image.
            val orientation = OrientationManager.currentOrientation(context)
            val systemPrompt = buildSystemPrompt(
                widthPx = shotW,
                heightPx = shotH,
                orientation = orientation,
                maxSteps = maxSteps,
                snapshot = snapshot,
                noChangeCount = noChangeCount,
                sameActionCount = sameActionCount,
                skill = skill,
                scaleX = scaleX,
                scaleY = scaleY,
                memory = memory,
            )

            // 4. Call LLM.
            val action = try {
                connector.analyze(screenshot, systemPrompt, Message.prune(history), task)
            } catch (e: LLMException) {
                Log.e(TAG, "Step $step: LLM failure — ${e.message}")
                val state = AgentState.Error(task, "LLM error: ${e.message}")
                onStateUpdate(state)
                return state
            }

            Log.d(TAG, "Step $step: ${action::class.simpleName} — ${action.description}")

            // 5. Validate action before executing (coordinates in screenshot space).
            try {
                validator.validate(action)
            } catch (e: ValidationException) {
                Log.e(TAG, "Step $step: validation failure — ${e.message}")
                val state = AgentState.Error(task, "Invalid action: ${e.message}")
                onStateUpdate(state)
                return state
            }

            // 6. Irreversibility check — pause and await user confirmation before executing.
            //    onIrreversibleAction suspends until the user taps Allow/Cancel or 60 s elapses.
            if (irreversibleDetector.isIrreversible(action, context = action.thought)) {
                Log.w(TAG, "Step $step: irreversible action detected — ${action.description}")
                val allowed = onIrreversibleAction(action.description)
                if (!allowed) {
                    Log.i(TAG, "Step $step: irreversible action cancelled by user — skipping")
                    delay(STEP_DELAY_MS)
                    continue
                }
                Log.i(TAG, "Step $step: irreversible action allowed by user — proceeding")
            }

            // 7. Scale coordinates from screenshot space → physical display space, then execute.
            val scaledAction = scaleActionCoords(action, scaleX, scaleY)
            bridge.executeAction(scaledAction)

            // Track same-action repetition (catches stuck same-button-tap loops where
            // element count doesn't change because the button is already in the target state
            // or the coordinates are slightly off).
            val actionSignature = actionSignature(action)
            if (actionSignature == lastActionSignature) {
                sameActionCount++
            } else {
                sameActionCount = 0
                lastActionSignature = actionSignature
            }

            // Wait briefly, then sample the post-action snapshot for the history observation.
            delay(STEP_DELAY_MS)
            val postSnapshot = bridge.snapshot.value

            // 8. Append alternating user-observation + assistant-action pair to history.
            //    The user observation tells Claude what actually happened after the action,
            //    preventing "I haven't done X yet" confusion in subsequent steps.
            val postApp = postSnapshot?.packageName?.substringAfterLast('.') ?: "unknown"
            val postCount = postSnapshot?.elements?.size ?: 0
            val observation = "Step $step executed: ${action.description}. " +
                "Screen now: $postApp ($postCount elements). " +
                "Evaluate whether the task \"$task\" is now complete."
            history.add(Message(role = "user",      content = observation))
            history.add(Message(role = "assistant", content = action.description))
            val pruned = Message.prune(history)
            history.clear()
            history.addAll(pruned)

            // 9. Complete — task finished successfully.
            if (action is AgentAction.Complete) {
                Log.i(TAG, "Task complete in $step steps: ${action.description}")
                val state = AgentState.Complete(task, step, action.description)
                onStateUpdate(state)
                return state
            }

            // 10. Stuck — agent cannot proceed, needs human help.
            if (action is AgentAction.Stuck) {
                Log.w(TAG, "Agent stuck at step $step: ${action.needsHelpReason}")
                val state = AgentState.Stuck(task, step, action.needsHelpReason)
                onStateUpdate(state)
                return state
            }

            // 11. Max steps guard — prevent runaway agent.
            if (step >= maxSteps) {
                Log.w(TAG, "Max steps ($maxSteps) reached")
                val state = AgentState.Error(task, "Max steps ($maxSteps) reached")
                onStateUpdate(state)
                return state
            }
            // (UI settle delay moved to step 8 above, right before the post-action snapshot.)
        }
    }

    // ── UI change detection ───────────────────────────────────────────────────

    /**
     * Returns true if the UI appears to have changed between [prev] and [current].
     * Uses package name + element count as a lightweight signal.
     * A tap that moves focus within the same app (same package, same count) is still
     * considered changed if the package changed — element count alone is not enough.
     */
    private fun uiChanged(prev: UiSnapshot?, current: UiSnapshot?): Boolean {
        if (prev == null || current == null) return true
        return prev.packageName != current.packageName ||
               prev.elements.size != current.elements.size
    }

    // ── System Prompt ─────────────────────────────────────────────────────────

    private fun buildSystemPrompt(
        widthPx: Int,
        heightPx: Int,
        orientation: String,
        maxSteps: Int,
        snapshot: UiSnapshot?,
        noChangeCount: Int,
        sameActionCount: Int = 0,
        skill: Skill? = null,
        scaleX: Float = 1f,
        scaleY: Float = 1f,
        memory: String = "",
    ): String {
        val sameActionWarning = if (sameActionCount >= 2) """

🔁 SAME ACTION REPEATED $sameActionCount times with no change. STOP. This action is NOT working.
Possible reasons:
- The button you are tapping is ALREADY in the target state (e.g. AM is already selected — stop tapping it).
- Your tap coordinates are slightly off — try a nearby coordinate.
- The control works differently — try a long press instead.
- The screen may require scrolling to reach the real control.
Decision rule: if the desired state is already achieved (e.g. AM is already selected), do NOT keep tapping. Look at the overall task state and decide if you should tap OK/Confirm or move on.
""" else ""

        val noChangeWarning = if (noChangeCount >= 1) """

⚠️ UI UNCHANGED for $noChangeCount step(s) in a row. Your previous action had no effect.

Diagnosis by action type:
• SWIPE that didn't open the app drawer: You must use a FAST FLING, not a slow drag.
  - duration_ms MUST be 100–150. A 500ms swipe is a slow drag that springs back.
  - Start Y must be ≥75% of screen height (e.g. y=${(heightPx * 0.85).toInt()} for a ${heightPx}px screen).
  - End Y should be ≤20% of screen height (e.g. y=${(heightPx * 0.10).toInt()}).
  - Correct example: swipe from (${widthPx / 2}, ${(heightPx * 0.85).toInt()}) → (${widthPx / 2}, ${(heightPx * 0.10).toInt()}), duration_ms=120
• SWIPE that didn't scroll a list: Use duration_ms 200–400. Start from inside the list.
• TAP on a text field: Do NOT tap again. Use "type" action immediately.
• ANY repeated failure after 3+ steps: press_home, then open the target app via the app drawer or directly by tapping its icon.

Do NOT repeat the exact same action again.
""" else ""

        val skillGuidance = if (skill != null) """

Skill guidance — follow these steps for this task:
${skill.steps}
${if (skill.notes.isNotBlank()) "\nNotes:\n${skill.notes}" else ""}
""" else ""

        val memoryBlock = if (memory.isNotBlank()) """
User memory — things you already know about this person:
$memory

---
""" else ""

        return """
${memoryBlock}You are MANTIS, an Android AI agent. Control the device to complete the user's task.
$sameActionWarning
Device: ${widthPx}×${heightPx} px, $orientation
Max steps: $maxSteps

KEYBOARD RULE — read carefully:
MANTIS has no visible on-screen keyboard. Do NOT tap a field and then wait or retry because no keyboard appeared.
The correct sequence for typing text is always:
  1. tap — tap the input field to focus it
  2. type — immediately use the "type" action to enter the text
No keyboard will ever appear visually. That is normal. "type" works directly.

SWIPE RULES — critical for gesture success:
• App drawer (swipe up from home screen): FAST FLING required.
  - duration_ms: 100–150 (NOT 300, NOT 500 — those spring back).
  - Start at y ≥ 75% of screen height. End at y ≤ 20% of screen height.
  - Example for ${heightPx}px screen: from (${widthPx/2}, ${(heightPx*0.85).toInt()}) → (${widthPx/2}, ${(heightPx*0.10).toInt()}), duration_ms=120
• Notification shade (swipe down from top): Start at y=0 or y=10, end at y ≥ 60% of screen height, duration_ms=150.
• Scroll inside a list or page: duration_ms=250–400. Start/end inside the scrollable area.
• Slow swipes (>300ms) for app-drawer open WILL spring back and fail. Always use ≤150ms for drawer.
$skillGuidance$noChangeWarning
UI elements currently on screen:
${buildUiElementLines(snapshot, scaleX, scaleY)}

Respond with ONLY a JSON object — no markdown, no explanation:
{
  "thought": "...",
  "action": "tap|swipe|type|press_back|press_home|long_press|wait|scroll|complete|stuck",
  "x": 340,
  "y": 890,
  "text": null,
  "swipe_to_x": null,
  "swipe_to_y": null,
  "duration_ms": null,
  "description": "Tapping the search icon",
  "task_complete": false,
  "needs_help": false,
  "needs_help_reason": null,
  "is_irreversible": false,
  "irreversible_description": null
}
        """.trimIndent()
    }

    private fun buildUiElementLines(snapshot: UiSnapshot?, scaleX: Float = 1f, scaleY: Float = 1f): String {
        if (snapshot == null) return "(no UI snapshot available)"
        val elements = snapshot.elements
        // When over the cap, prefer clickable elements — they are more useful to the agent.
        val trimmed = if (elements.size <= MAX_UI_ELEMENTS) {
            elements
        } else {
            val clickable = elements.filter { it.clickable }
            val nonClickable = elements.filter { !it.clickable }
            (clickable + nonClickable).take(MAX_UI_ELEMENTS)
        }
        return trimmed.joinToString("\n") { el ->
            val label = when {
                el.text.isNotBlank() -> "\"${el.text}\""
                el.contentDescription.isNotBlank() -> "\"${el.contentDescription}\""
                else -> "(no label)"
            }
            // Scale bounds from physical display space to screenshot space so Claude's
            // visual position in the image aligns with the coordinates in the element list.
            val scaledBounds = if (scaleX != 1f || scaleY != 1f) {
                Rect(
                    (el.bounds.left / scaleX).toInt(),
                    (el.bounds.top / scaleY).toInt(),
                    (el.bounds.right / scaleX).toInt(),
                    (el.bounds.bottom / scaleY).toInt(),
                )
            } else {
                el.bounds
            }
            "${el.index}. [${el.className.substringAfterLast('.')}] $label " +
                "bounds=$scaledBounds clickable=${el.clickable}"
        }
    }

    /**
     * Returns a short string identifying the action type and approximate target coordinates.
     * Used to detect when the agent repeats the exact same gesture multiple times.
     * Coordinates are bucketed to ±30px so minor jitter doesn't reset the counter.
     */
    private fun actionSignature(action: AgentAction): String = when (action) {
        is AgentAction.Tap       -> "tap_${action.x / 30}_${action.y / 30}"
        is AgentAction.LongPress -> "lp_${action.x / 30}_${action.y / 30}"
        is AgentAction.Swipe     -> "swipe_${action.fromX / 30}_${action.fromY / 30}_${action.toX / 30}_${action.toY / 30}"
        else                     -> action::class.simpleName ?: "unknown"
    }

    /**
     * Scales tap/swipe/longpress coordinates from screenshot space to physical display space.
     * Other actions (PressBack, PressHome, TypeText, Scroll, Wait, Complete, Stuck) carry
     * no screen coordinates and are returned unchanged.
     */
    private fun scaleActionCoords(action: AgentAction, scaleX: Float, scaleY: Float): AgentAction {
        if (scaleX == 1f && scaleY == 1f) return action
        return when (action) {
            is AgentAction.Tap -> action.copy(
                x = (action.x * scaleX).toInt(),
                y = (action.y * scaleY).toInt(),
            )
            is AgentAction.Swipe -> action.copy(
                fromX = (action.fromX * scaleX).toInt(),
                fromY = (action.fromY * scaleY).toInt(),
                toX = (action.toX * scaleX).toInt(),
                toY = (action.toY * scaleY).toInt(),
            )
            is AgentAction.LongPress -> action.copy(
                x = (action.x * scaleX).toInt(),
                y = (action.y * scaleY).toInt(),
            )
            else -> action
        }
    }
}
