/*
 * Adapted from DroidRun Portal (github.com/droidrun/droidrun-portal)
 * Original work Copyright (c) DroidRun contributors
 * Licensed under MIT License
 * Modifications: Removed ADB/ContentProvider integration. Receives OverlayFrame
 *   from AgentBridge StateFlow. Draws numbered bounding boxes (Set-of-Marks style)
 *   over detected UI elements using SYSTEM_ALERT_WINDOW overlay view.
 */
package com.mantis.overlay

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.IBinder
import android.util.Log
import android.view.View
import android.view.WindowManager
import com.mantis.MantisApp
import com.mantis.bridge.OverlayFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Overlay service that draws numbered bounding boxes over detected UI elements
 * (Set-of-Marks style) using [WindowManager] with [PixelFormat.TRANSLUCENT].
 *
 * Lifecycle:
 *  - Started by [start] companion function; stopped by [stop].
 *  - Registers itself on [MantisApp.overlayService] in [onCreate],
 *    clears in [onDestroy].
 *
 * Rendering:
 *  - Observes [AgentBridge.overlay] StateFlow on [Dispatchers.Main].
 *  - On each new [OverlayFrame], [OverlayView.setFrame] is called to trigger
 *    a [View.invalidate] + redraw cycle.
 *  - When [clearOverlay] is called (frame is null or list is empty), the
 *    overlay view is hidden without removing the window — avoids the overhead
 *    of add/remove on every frame transition.
 *
 * Requires [android.permission.SYSTEM_ALERT_WINDOW] — already declared in manifest.
 */
class MantisOverlayService : Service() {

    companion object {
        private const val TAG = "MantisOverlay"

        fun start(context: Context) {
            context.startService(Intent(context, MantisOverlayService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MantisOverlayService::class.java))
        }
    }

    private var windowManager: WindowManager? = null
    private var overlayView: OverlayView? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        (application as MantisApp).overlayService = this
        attachOverlayView()
        observeAgentBridge()
        Log.d(TAG, "MantisOverlayService created and registered on MantisApp")
    }

    override fun onDestroy() {
        serviceScope.cancel()
        detachOverlayView()
        val app = application as MantisApp
        if (app.overlayService === this) app.overlayService = null
        Log.d(TAG, "MantisOverlayService destroyed and cleared from MantisApp")
        super.onDestroy()
    }

    // ── Window setup ──────────────────────────────────────────────────────────

    private fun attachOverlayView() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )

        val view = OverlayView(this)
        overlayView = view
        wm.addView(view, params)
    }

    private fun detachOverlayView() {
        val view = overlayView ?: return
        try {
            windowManager?.removeView(view)
        } catch (e: Exception) {
            Log.w(TAG, "detachOverlayView: view already removed", e)
        }
        overlayView = null
        windowManager = null
    }

    // ── AgentBridge observation ───────────────────────────────────────────────

    private fun observeAgentBridge() {
        serviceScope.launch {
            val bridge = (application as MantisApp).agentBridge
            if (bridge == null) {
                Log.w(TAG, "observeAgentBridge: AgentBridge not running — overlay will stay blank")
                return@launch
            }
            bridge.overlay.collect { frame ->
                if (frame == null || frame.elements.isEmpty()) {
                    overlayView?.clearFrame()
                } else {
                    overlayView?.setFrame(frame)
                }
            }
        }
    }
}

// ── Overlay drawing view ───────────────────────────────────────────────────────

/**
 * Full-screen transparent [View] that paints numbered bounding boxes over
 * each [OverlayFrame.elements] entry.
 *
 * Colours are chosen for contrast against both light and dark app backgrounds:
 *  - Box stroke: semi-transparent cyan (#CC00BCD4)
 *  - Fill:       very light cyan (#1A00BCD4) — barely visible, just tints the area
 *  - Label pill: opaque dark (#DD000000) with white text
 */
private class OverlayView(context: Context) : View(context) {

    private var frame: OverlayFrame? = null

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CC00BCD4")   // semi-transparent cyan
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1A00BCD4")   // light cyan fill
        style = Paint.Style.FILL
    }

    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#DD000000")   // near-opaque dark pill
        style = Paint.Style.FILL
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 28f
        isFakeBoldText = true
    }

    fun setFrame(newFrame: OverlayFrame) {
        frame = newFrame
        visibility = VISIBLE
        invalidate()
    }

    fun clearFrame() {
        frame = null
        visibility = INVISIBLE
    }

    override fun onDraw(canvas: Canvas) {
        val elements = frame?.elements ?: return
        for (el in elements) {
            val b = el.bounds
            // Fill
            canvas.drawRect(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(), fillPaint)
            // Stroke
            canvas.drawRect(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(), strokePaint)
            // Label pill (top-left corner of element)
            val label = el.index.toString()
            val textWidth = textPaint.measureText(label)
            val pillPad = 6f
            val pillLeft = b.left.toFloat()
            val pillTop = b.top.toFloat() - 36f
            val pillRight = pillLeft + textWidth + pillPad * 2
            val pillBottom = b.top.toFloat()
            canvas.drawRoundRect(pillLeft, pillTop, pillRight, pillBottom, 6f, 6f, pillPaint)
            canvas.drawText(label, pillLeft + pillPad, pillBottom - 6f, textPaint)
        }
    }
}
