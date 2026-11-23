/*
 * Adapted from DroidRun Portal (github.com/droidrun/droidrun-portal)
 * Original work Copyright (c) DroidRun contributors
 * Licensed under MIT License
 * Modifications: Removed ADB/ContentProvider integration. Pushes frames to
 *   AgentBridge.updateScreenshot() via MantisApp reference. Runs as a
 *   foreground service (required by Android 14+ for MediaProjection use).
 *   Scales capture to 720p to match the LLM API image budget.
 */
package com.mantis.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import com.mantis.MantisApp

/**
 * Foreground service that captures the screen via MediaProjection and pushes
 * frames to [AgentBridge][com.mantis.bridge.AgentBridge].
 *
 * Android 14+ requires MediaProjection to be used from a running foreground
 * service with type `mediaProjection`. This service satisfies that requirement.
 *
 * Frames are captured via [ImageReader] + [VirtualDisplay] on a dedicated
 * background thread. Each frame is converted to a [Bitmap] and pushed to
 * AgentBridge via the MantisApp reference (null-safe — AgentBridge may not
 * be running yet).
 *
 * Capture resolution: the display is scaled so the shorter side equals
 * [TARGET_SHORT_SIDE_PX] (720), preserving the device's aspect ratio.
 *
 * If the [MediaProjection] is revoked by the system, [SecureConfig.isMediaProjectionValid]
 * is set to false and the service stops itself — prompting the user to re-grant.
 *
 * Lifecycle: started by [start] (from onboarding or MainActivity on resume),
 * registered on [MantisApp.screenCapture] in [onCreate], cleared in [onDestroy].
 */
class MantisScreenCapture : Service() {

    companion object {
        private const val TAG = "MantisCapture"
        private const val NOTIF_ID = 2001
        private const val CHANNEL_ID = "mantis_screen_capture"

        /** The shorter display side is scaled to this pixel count (720p). */
        private const val TARGET_SHORT_SIDE_PX = 720

        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_DATA = "data"

        /**
         * Starts the capture foreground service with the given MediaProjection grant.
         *
         * Call from the UI thread after [android.media.projection.MediaProjectionManager]
         * has returned RESULT_OK. Safe to call if the service is already running —
         * [MantisApp.screenCapture] is checked before starting.
         */
        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, MantisScreenCapture::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_DATA, data)
            }
            context.startForegroundService(intent)
        }
    }

    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null

    /**
     * Set to true at the very start of [releaseCapture] so that any in-flight
     * [onFrameAvailable] callback on the HandlerThread exits immediately without
     * touching the Image, preventing "Image is already closed" crashes.
     */
    @Volatile private var isReleasing = false

    private var captureWidth = 0
    private var captureHeight = 0

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        (application as MantisApp).screenCapture = this
        Log.d(TAG, "MantisScreenCapture created and registered on MantisApp")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            intent?.getParcelableExtra(EXTRA_DATA)
        }

        if (resultCode == 0 || data == null) {
            Log.e(TAG, "onStartCommand: invalid token — stopping")
            (application as MantisApp).secureConfig.isMediaProjectionValid = false
            stopSelf()
            return START_NOT_STICKY
        }

        startCaptureForeground()
        startCapture(resultCode, data)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseCapture()
        val app = application as MantisApp
        if (app.screenCapture === this) app.screenCapture = null
        Log.d(TAG, "MantisScreenCapture destroyed and cleared from MantisApp")
        super.onDestroy()
    }

    // ── Foreground notification ───────────────────────────────────────────────

    private fun startCaptureForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Screen Capture",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "MANTIS is capturing the screen to carry out your task." }
        )

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("MANTIS")
            .setContentText("Screen capture active")
            .setSmallIcon(com.mantis.R.drawable.ic_notification)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    // ── Capture setup ─────────────────────────────────────────────────────────

    private fun startCapture(resultCode: Int, data: Intent) {
        val dm = resources.displayMetrics
        val scale = TARGET_SHORT_SIDE_PX.toFloat() / minOf(dm.widthPixels, dm.heightPixels)
        captureWidth = (dm.widthPixels * scale).toInt()
        captureHeight = (dm.heightPixels * scale).toInt()

        val thread = HandlerThread("MantisCapture").also { it.start() }
        captureThread = thread
        val handler = Handler(thread.looper)

        val reader = ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        reader.setOnImageAvailableListener(::onFrameAvailable, handler)

        val manager = getSystemService(MediaProjectionManager::class.java)
        val projection = manager.getMediaProjection(resultCode, data)
        mediaProjection = projection

        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.w(TAG, "MediaProjection revoked — marking invalid and stopping")
                val app = application as MantisApp
                app.secureConfig.isMediaProjectionValid = false
                // Clear the in-memory token so tryStartCaptureService() doesn't
                // attempt to reuse the revoked grant and crash on getMediaProjection().
                app.mediaProjectionIntent = null
                app.mediaProjectionResultCode = 0
                stopSelf()
            }
        }, handler)

        projection.createVirtualDisplay(
            "MantisCapture",
            captureWidth,
            captureHeight,
            dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            handler,
        )

        Log.d(TAG, "Capture started: ${captureWidth}×${captureHeight} dpi=${dm.densityDpi}")
    }

    private fun releaseCapture() {
        // Signal in-flight callbacks to exit immediately before we tear anything down.
        isReleasing = true

        // Null first to prevent re-entrant onStop callback if stop() triggers it again.
        val mp = mediaProjection
        mediaProjection = null
        mp?.stop()

        // Quit the HandlerThread BEFORE closing the ImageReader.
        // Closing the reader invalidates all acquired Images; if an onFrameAvailable
        // callback is still reading plane data on the HandlerThread at that moment,
        // it will throw "Image is already closed".  Quitting the thread first stops
        // new callbacks from starting; isReleasing guards any callback already mid-flight.
        captureThread?.quitSafely()
        captureThread = null

        imageReader?.close()
        imageReader = null
    }

    // ── Frame processing ──────────────────────────────────────────────────────

    /**
     * Called on the capture [HandlerThread] for each available frame.
     *
     * Acquires only the latest image (older frames are dropped) to prevent
     * buffer backpressure if the consumer is slow. The bitmap is pushed to
     * AgentBridge — ownership transfers on that call, do not recycle here.
     */
    private fun onFrameAvailable(reader: ImageReader) {
        // Fast exit if releaseCapture() has started — the ImageReader may already
        // be closing and accessing any acquired Image's planes would throw.
        if (isReleasing) return

        val image = reader.acquireLatestImage() ?: return
        try {
            // Check again after acquiring — releaseCapture() may have run between
            // the acquireLatestImage() call and now.
            if (isReleasing) return
            val bitmap = imageToBitmap(image, captureWidth, captureHeight)
            (application as MantisApp).agentBridge?.updateScreenshot(bitmap)
        } catch (e: Exception) {
            Log.e(TAG, "Frame processing error", e)
        } finally {
            image.close()
        }
    }

    /**
     * Converts an [Image] in RGBA_8888 format to a [Bitmap].
     *
     * Row padding is common when the GPU requires row stride alignment — the
     * padded bitmap is created and then cropped to the exact [width]×[height].
     * The padded intermediate bitmap is recycled immediately.
     */
    private fun imageToBitmap(image: Image, width: Int, height: Int): Bitmap {
        val plane = image.planes[0]
        val rowPadding = plane.rowStride - plane.pixelStride * width
        val paddedWidth = width + rowPadding / plane.pixelStride

        val raw = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
        raw.copyPixelsFromBuffer(plane.buffer)

        return if (rowPadding > 0) {
            val cropped = Bitmap.createBitmap(raw, 0, 0, width, height)
            raw.recycle()
            cropped
        } else {
            raw
        }
    }
}
