/*
 * Adapted from DroidRun Portal (github.com/droidrun/droidrun-portal)
 * Original work Copyright (c) DroidRun contributors
 * Licensed under MIT License
 * Modifications: Removed ADB/ContentProvider integration. Uses
 *   AccessibilityService.dispatchGesture() for coordinate-based actions and
 *   performGlobalAction() for system actions. Zero ADB. Scroll implemented as
 *   a gesture swipe for consistent cross-app behaviour.
 */
package com.mantis.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import com.mantis.bridge.ScrollDirection
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Executes UI actions on behalf of the MANTIS agent using Android's AccessibilityService APIs.
 *
 * All coordinate-based actions (tap, swipe, longPress, scroll) use
 * [AccessibilityService.dispatchGesture] — zero ADB. System actions (back, home) use
 * [AccessibilityService.performGlobalAction].
 *
 * Registered on [MantisApp][com.mantis.MantisApp] by
 * [MantisAccessibilityService.onServiceConnected] and cleared on service destroy.
 * [AgentBridge][com.mantis.bridge.AgentBridge] reads it from MantisApp at call time,
 * so the start order of AgentBridge and the accessibility service does not matter.
 *
 * Gesture-based actions are [suspend] because [dispatchGesture] delivers results
 * asynchronously via callback.
 */
class MantisActionExecutor(private val service: AccessibilityService) {

    companion object {
        private const val TAG = "MantisExecutor"

        /** Duration of a synthetic tap gesture in milliseconds. */
        private const val TAP_DURATION_MS = 50L

        /**
         * Pixels to travel during a scroll gesture. Covers roughly 40% of a 1080p screen.
         * Applied symmetrically from the centre point, so actual swipe length is half this.
         */
        private const val SCROLL_DISTANCE_PX = 500

        /** Duration of the scroll swipe gesture in milliseconds. */
        private const val SCROLL_DURATION_MS = 300L
    }

    // ── Public action API ─────────────────────────────────────────────────────

    /**
     * Taps at screen coordinates ([x], [y]).
     */
    suspend fun tap(x: Int, y: Int) {
        Log.d(TAG, "tap($x, $y)")
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, TAP_DURATION_MS))
            .build()
        dispatchGestureAsync(gesture)
    }

    /**
     * Swipes from ([fromX], [fromY]) to ([toX], [toY]) over [durationMs] milliseconds.
     */
    suspend fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Long) {
        Log.d(TAG, "swipe($fromX,$fromY → $toX,$toY, ${durationMs}ms)")
        val path = Path().apply {
            moveTo(fromX.toFloat(), fromY.toFloat())
            lineTo(toX.toFloat(), toY.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs.coerceAtLeast(1L)))
            .build()
        dispatchGestureAsync(gesture)
    }

    /**
     * Long-presses at screen coordinates ([x], [y]).
     * Duration is [ViewConfiguration.getLongPressTimeout] + 100 ms safety margin.
     */
    suspend fun longPress(x: Int, y: Int) {
        Log.d(TAG, "longPress($x, $y)")
        val duration = ViewConfiguration.getLongPressTimeout().toLong() + 100L
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, duration))
            .build()
        dispatchGestureAsync(gesture)
    }

    /**
     * Presses the system Back button via [AccessibilityService.performGlobalAction].
     */
    fun pressBack() {
        Log.d(TAG, "pressBack()")
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
    }

    /**
     * Presses the system Home button via [AccessibilityService.performGlobalAction].
     */
    fun pressHome() {
        Log.d(TAG, "pressHome()")
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
    }

    /**
     * Scrolls in [direction] within the element at [elementIndex] (if not null),
     * or the centre of the screen when [elementIndex] is null.
     *
     * Implemented as a swipe gesture for consistent behaviour across apps that
     * intercept scroll events in non-standard ways.
     *
     * Scroll direction convention (matches natural finger movement):
     *  - DOWN: content moves down (finger swipes up), revealing content below
     *  - UP: content moves up (finger swipes down), revealing content above
     *  - RIGHT / LEFT: analogous for horizontal lists
     */
    suspend fun scroll(direction: ScrollDirection, elementIndex: Int?) {
        Log.d(TAG, "scroll($direction, elementIndex=$elementIndex)")

        val bounds = if (elementIndex != null) findBoundsForIndex(elementIndex) else null
        val cx: Int
        val cy: Int
        if (bounds != null) {
            cx = bounds.centerX()
            cy = bounds.centerY()
        } else {
            val dm = service.resources.displayMetrics
            cx = dm.widthPixels / 2
            cy = dm.heightPixels / 2
        }

        val half = SCROLL_DISTANCE_PX / 2
        val (fromX, fromY, toX, toY) = when (direction) {
            ScrollDirection.DOWN  -> listOf(cx, cy + half, cx, cy - half)
            ScrollDirection.UP    -> listOf(cx, cy - half, cx, cy + half)
            ScrollDirection.RIGHT -> listOf(cx + half, cy, cx - half, cy)
            ScrollDirection.LEFT  -> listOf(cx - half, cy, cx + half, cy)
        }
        swipe(fromX, fromY, toX, toY, SCROLL_DURATION_MS)
    }

    /**
     * Sets [text] on the currently focused input field via
     * [AccessibilityNodeInfo.ACTION_SET_TEXT].
     *
     * This works regardless of which IME is currently active — the agent does not
     * need MANTIS Keyboard to be the selected input method to type text.
     *
     * Returns true if the action was dispatched successfully, false if no focused
     * input node was found or the action was rejected by the target app.
     */
    @Suppress("DEPRECATION") // node.recycle() required for minSdk 26
    fun typeText(text: String): Boolean {
        val root = service.rootInActiveWindow ?: run {
            Log.w(TAG, "typeText: no active window root")
            return false
        }
        val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: run {
            root.recycle()
            Log.w(TAG, "typeText: no focused input node")
            return false
        }
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val success = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        node.recycle()
        root.recycle()
        Log.d(TAG, "typeText via ACTION_SET_TEXT: success=$success, chars=${text.length}")
        return success
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    /**
     * Dispatches [gesture] and suspends until the system reports completion or
     * cancellation. Returns true if completed, false if cancelled or rejected.
     */
    private suspend fun dispatchGestureAsync(gesture: GestureDescription): Boolean =
        suspendCancellableCoroutine { cont ->
            val callback = object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) {
                    cont.resume(true)
                }
                override fun onCancelled(gestureDescription: GestureDescription) {
                    Log.w(TAG, "Gesture cancelled")
                    cont.resume(false)
                }
            }
            if (!service.dispatchGesture(gesture, callback, null)) {
                Log.w(TAG, "dispatchGesture returned false — service may lack canPerformGestures")
                cont.resume(false)
            }
        }

    /**
     * Traverses the current accessibility tree and returns the screen bounds of
     * the element whose traversal index equals [index], or null if not found.
     *
     * Uses the same traversal order as [MantisAccessibilityService.traverseNode] so
     * the index here matches the [com.mantis.bridge.UiElement.index] in the snapshot.
     */
    @Suppress("DEPRECATION") // recycle() deprecated API 33+; required for minSdk 26 compat
    private fun findBoundsForIndex(index: Int): Rect? {
        val root = service.rootInActiveWindow ?: return null
        var count = 0
        var result: Rect? = null

        fun search(node: AccessibilityNodeInfo) {
            if (result != null) return
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (bounds.width() > 0 && bounds.height() > 0) {
                if (count == index) {
                    result = Rect(bounds)
                    return
                }
                count++
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                search(child)
                child.recycle()
            }
        }

        try {
            search(root)
        } finally {
            root.recycle()
        }
        return result
    }
}
