/*
 * Adapted from DroidRun Portal (github.com/droidrun/droidrun-portal)
 * Original work Copyright (c) DroidRun contributors
 * Licensed under MIT License
 * Modifications: Removed ContentProvider, ADB, and SocketServer integration.
 *   Pushes UiSnapshot to AgentBridge via MantisApp application reference.
 *   Added contentDescription field. Filters out zero-size nodes.
 *   Self-package exclusion to avoid capturing MANTIS's own UI.
 */
package com.mantis.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.mantis.MantisApp
import com.mantis.bridge.UiElement
import com.mantis.bridge.UiSnapshot


/**
 * Full UI tree reader for MANTIS.
 *
 * On every meaningful UI change, traverses the active window's accessibility tree
 * and pushes a [UiSnapshot] to [AgentBridge][com.mantis.bridge.AgentBridge] via
 * the [MantisApp] application reference.
 *
 * Responds to [AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED] (new screen/dialog)
 * and [AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED] (content updated).
 * All other event types are ignored.
 *
 * AgentBridge is accessed null-safely — if it is not yet started, the snapshot
 * is silently dropped. This is normal during early startup.
 *
 * Replaces the Phase 1 stub (com.mantis.MantisAccessibilityService).
 */
class MantisAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "MantisA11y"
        private const val PACKAGE_SELF = "com.mantis"

        /**
         * Cap on elements per snapshot. Prevents runaway memory use on
         * pathologically complex UIs (e.g. some browser pages).
         */
        private const val MAX_ELEMENTS = 500

        private val RELEVANT_EVENTS = setOf(
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
        )
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        (applicationContext as MantisApp).actionExecutor = MantisActionExecutor(this)
        Log.d(TAG, "MantisAccessibilityService connected, MantisActionExecutor registered")
    }

    override fun onDestroy() {
        (applicationContext as MantisApp).actionExecutor = null
        Log.d(TAG, "MantisAccessibilityService destroyed, MantisActionExecutor cleared")
        super.onDestroy()
    }

    override fun onInterrupt() {
        Log.w(TAG, "MantisAccessibilityService interrupted")
    }

    @Suppress("DEPRECATION") // recycle() deprecated API 33+; required for minSdk 26 compat
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType !in RELEVANT_EVENTS) return

        val root = rootInActiveWindow ?: return
        try {
            val packageName = root.packageName?.toString() ?: return

            // Never snapshot MANTIS's own UI — avoids feeding agent its own overlay.
            if (packageName == PACKAGE_SELF) return

            val elements = mutableListOf<UiElement>()
            traverseNode(root, elements)

            val snapshot = UiSnapshot(
                elements = elements,
                packageName = packageName,
                timestamp = System.currentTimeMillis(),
            )

            (applicationContext as MantisApp).agentBridge?.updateSnapshot(snapshot)
            Log.d(TAG, "Snapshot pushed: ${elements.size} elements in $packageName")
        } finally {
            root.recycle()
        }
    }

    /**
     * Recursively traverses [node] and appends visible elements to [elements].
     *
     * Nodes with zero-width or zero-height bounds are skipped — they are
     * invisible or layout-only containers with no meaningful agent interaction.
     *
     * Callers are responsible for recycling [node] after this call returns.
     * Each child obtained during traversal is recycled here before the next
     * sibling is fetched.
     */
    @Suppress("DEPRECATION") // recycle() deprecated API 33+; required for minSdk 26 compat
    private fun traverseNode(node: AccessibilityNodeInfo, elements: MutableList<UiElement>) {
        if (elements.size >= MAX_ELEMENTS) return

        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        if (bounds.width() > 0 && bounds.height() > 0) {
            elements.add(
                UiElement(
                    index = elements.size,
                    text = node.text?.toString() ?: "",
                    contentDescription = node.contentDescription?.toString() ?: "",
                    bounds = bounds,
                    clickable = node.isClickable,
                    scrollable = node.isScrollable,
                    className = node.className?.toString() ?: "",
                )
            )
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            traverseNode(child, elements)
            child.recycle()
        }
    }
}
