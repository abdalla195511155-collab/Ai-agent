package com.mantis.bridge

import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.os.Binder
import android.os.IBinder
import android.util.Log
import com.mantis.MantisApp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Local IPC backbone for MANTIS.
 *
 * Acts as the central hub for all Phase 2 components:
 *  - MantisAccessibilityService pushes UiSnapshot via [updateSnapshot]
 *  - MantisScreenCapture pushes Bitmap via [updateScreenshot]
 *  - MantisOverlayService pushes OverlayFrame via [updateOverlay]
 *  - Phase 4 AgentService binds to this service and calls [executeAction]
 *  - Phase 6 LiveViewPanel observes [snapshot], [screenshot], [overlay]
 *
 * All components access this service via:
 *   (applicationContext as MantisApp).agentBridge?.updateX(...)
 * Null-safe access is mandatory — system services may start before AgentBridge.
 *
 * Bitmap ownership: once [updateScreenshot] is called, ownership transfers here.
 * Callers must NOT recycle the bitmap after passing it. Step 5 design spec
 * specifies the safe recycling strategy.
 */
class AgentBridge : Service() {

    companion object {
        private const val TAG = "AgentBridge"
    }

    // ── IBinder ───────────────────────────────────────────────────────────────

    inner class LocalBinder : Binder() {
        fun getService(): AgentBridge = this@AgentBridge
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent): IBinder = binder

    // ── StateFlows ────────────────────────────────────────────────────────────

    private val _snapshot = MutableStateFlow<UiSnapshot?>(null)
    val snapshot: StateFlow<UiSnapshot?> = _snapshot.asStateFlow()

    private val _screenshot = MutableStateFlow<Bitmap?>(null)
    val screenshot: StateFlow<Bitmap?> = _screenshot.asStateFlow()

    private val _overlay = MutableStateFlow<OverlayFrame?>(null)
    val overlay: StateFlow<OverlayFrame?> = _overlay.asStateFlow()

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        (application as MantisApp).agentBridge = this
        Log.d(TAG, "AgentBridge created and registered on MantisApp")
    }

    override fun onDestroy() {
        (application as MantisApp).agentBridge = null
        Log.d(TAG, "AgentBridge destroyed and unregistered from MantisApp")
        super.onDestroy()
    }

    // ── Push Functions ────────────────────────────────────────────────────────

    /**
     * Called by MantisAccessibilityService (Phase 2 Step 2) whenever the
     * foreground app's UI tree changes.
     */
    fun updateSnapshot(snapshot: UiSnapshot) {
        _snapshot.value = snapshot
    }

    /**
     * Called by MantisScreenCapture (Phase 2 Step 5) with each new frame.
     *
     * Ownership of [bitmap] transfers to AgentBridge on this call.
     * The caller must NOT recycle the bitmap after calling this function.
     */
    fun updateScreenshot(bitmap: Bitmap) {
        _screenshot.value = bitmap
    }

    /**
     * Called by MantisOverlayService (Phase 2 Step 6) with updated element bounds.
     */
    fun updateOverlay(frame: OverlayFrame) {
        _overlay.value = frame
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    /**
     * Types [text] into the currently focused field.
     *
     * Strategy (in order):
     *  1. [AccessibilityNodeInfo.ACTION_SET_TEXT] via MantisActionExecutor — works regardless
     *     of which IME is active. Preferred because the user may have switched away from
     *     MANTIS Keyboard (e.g. to type their task in the HomeScreen).
     *  2. MantisKeyboardIME.typeText() fallback — used for apps where ACTION_SET_TEXT is
     *     rejected (some WebViews, custom input views). Requires MANTIS Keyboard to be
     *     the active IME; polls up to 2 s for the IME to connect after a Tap.
     */
    private suspend fun dispatchTypeText(text: String) {
        // Try ACTION_SET_TEXT first — does not require MANTIS to be the active IME.
        val exec = (application as MantisApp).actionExecutor
        if (exec != null && exec.typeText(text)) return

        // Fallback: IME-based injection (requires MANTIS Keyboard to be active).
        Log.d(TAG, "dispatchTypeText: ACTION_SET_TEXT failed — trying IME fallback")
        val ime = withTimeoutOrNull(2_000L) {
            var result = (application as MantisApp).keyboardIme
            while (result == null) {
                delay(100)
                result = (application as MantisApp).keyboardIme
            }
            result
        }
        if (ime != null) {
            ime.typeText(text)
        } else {
            Log.w(TAG, "dispatchTypeText: both ACTION_SET_TEXT and IME fallback failed")
        }
    }

    // ── Dispatch Function ─────────────────────────────────────────────────────

    /**
     * Single public entry point for all agent actions.
     *
     * Declared suspend because [AgentAction.Wait] uses [delay].
     * All callers must invoke from a coroutine scope.
     *
     * Routing:
     *  - Tap / Swipe / LongPress / PressBack / PressHome / Scroll
     *      → MantisActionExecutor (via MantisApp.actionExecutor)
     *  - TypeText
     *      → private dispatchTypeText() → MantisKeyboardIME (wired in Phase 2 Step 4)
     *  - Wait
     *      → delay(durationMs) directly — no executor needed
     *
     * If MantisActionExecutor is null when an action arrives, a warning is logged
     * and the action is dropped. This can happen if the accessibility service is
     * not running; check PermissionHealthMonitor for CRITICAL state.
     */
    suspend fun executeAction(action: AgentAction) {
        when (action) {
            is AgentAction.Wait -> {
                Log.d(TAG, "executeAction: Wait — ${action.description}")
                delay(action.durationMs)
            }
            is AgentAction.TypeText -> {
                Log.d(TAG, "executeAction: TypeText — ${action.description}")
                dispatchTypeText(action.text) // suspend — waits up to 2 s for IME to connect
            }
            is AgentAction.Tap -> {
                Log.d(TAG, "executeAction: Tap — ${action.description}")
                val exec = (application as MantisApp).actionExecutor
                if (exec != null) exec.tap(action.x, action.y)
                else Log.w(TAG, "executeAction: Tap dropped — MantisActionExecutor not connected")
            }
            is AgentAction.Swipe -> {
                Log.d(TAG, "executeAction: Swipe — ${action.description}")
                val exec = (application as MantisApp).actionExecutor
                if (exec != null) exec.swipe(action.fromX, action.fromY, action.toX, action.toY, action.durationMs)
                else Log.w(TAG, "executeAction: Swipe dropped — MantisActionExecutor not connected")
            }
            is AgentAction.LongPress -> {
                Log.d(TAG, "executeAction: LongPress — ${action.description}")
                val exec = (application as MantisApp).actionExecutor
                if (exec != null) exec.longPress(action.x, action.y)
                else Log.w(TAG, "executeAction: LongPress dropped — MantisActionExecutor not connected")
            }
            is AgentAction.PressBack -> {
                Log.d(TAG, "executeAction: PressBack — ${action.description}")
                val exec = (application as MantisApp).actionExecutor
                if (exec != null) exec.pressBack()
                else Log.w(TAG, "executeAction: PressBack dropped — MantisActionExecutor not connected")
            }
            is AgentAction.PressHome -> {
                Log.d(TAG, "executeAction: PressHome — ${action.description}")
                val exec = (application as MantisApp).actionExecutor
                if (exec != null) exec.pressHome()
                else Log.w(TAG, "executeAction: PressHome dropped — MantisActionExecutor not connected")
            }
            is AgentAction.Scroll -> {
                Log.d(TAG, "executeAction: Scroll — ${action.description}")
                val exec = (application as MantisApp).actionExecutor
                if (exec != null) exec.scroll(action.direction, action.elementIndex)
                else Log.w(TAG, "executeAction: Scroll dropped — MantisActionExecutor not connected")
            }
            // Terminal states — consumed by AgentLoop (Phase 4), not dispatched here.
            is AgentAction.Complete -> Log.d(TAG, "executeAction: Complete — ${action.description}")
            is AgentAction.Stuck -> Log.d(TAG, "executeAction: Stuck — ${action.needsHelpReason}")
        }
    }
}
