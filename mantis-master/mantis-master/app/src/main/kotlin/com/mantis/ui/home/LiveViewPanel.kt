package com.mantis.ui.home

import android.graphics.Bitmap
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mantis.MantisApp
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Displays the live screenshot feed from [AgentBridge.screenshot] with numbered
 * bounding-box overlays from [AgentBridge.overlay] drawn on top.
 *
 * Overlay element bounds arrive in physical display pixel space.
 * This composable scales them to the panel's layout pixel space so they align
 * with the screenshot image regardless of the panel's rendered size.
 */
@Composable
fun LiveViewPanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = remember { context.applicationContext as MantisApp }
    val dm = context.resources.displayMetrics

    // Stable fallback flows used when AgentBridge hasn't started yet.
    val fallbackScreenshot = remember { MutableStateFlow<Bitmap?>(null) }
    val fallbackOverlay = remember { MutableStateFlow(null) }

    val screenshot by (app.agentBridge?.screenshot ?: fallbackScreenshot)
        .collectAsStateWithLifecycle(initialValue = null)
    val overlay by (app.agentBridge?.overlay ?: fallbackOverlay)
        .collectAsStateWithLifecycle(initialValue = null)

    if (screenshot == null) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color(0xFF0D0D0D)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Waiting for screen capture\u2026",
                color = Color(0xFF666666),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
        }
        return
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val panelW = constraints.maxWidth.toFloat()
        val panelH = constraints.maxHeight.toFloat()
        val displayW = dm.widthPixels.toFloat()
        val displayH = dm.heightPixels.toFloat()

        // Scale factors: physical display pixels → composable layout pixels.
        val scaleX = panelW / displayW
        val scaleY = panelH / displayH

        // Screenshot — stretched to fill the panel exactly so coordinates align.
        Image(
            bitmap = screenshot!!.asImageBitmap(),
            contentDescription = "Live screen",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds,
        )

        // Numbered bounding boxes from the accessibility overlay.
        overlay?.let { frame ->
            if (frame.elements.isEmpty()) return@let

            val boxColor = Color(0xFF00E5FF)
            val labelPaint = remember {
                Paint().apply {
                    color = android.graphics.Color.parseColor("#00E5FF")
                    textSize = 28f
                    isAntiAlias = true
                }
            }

            Canvas(modifier = Modifier.fillMaxSize()) {
                frame.elements.forEach { el ->
                    val left = el.bounds.left * scaleX
                    val top = el.bounds.top * scaleY
                    val w = el.bounds.width() * scaleX
                    val h = el.bounds.height() * scaleY

                    drawRect(
                        color = boxColor,
                        topLeft = Offset(left, top),
                        size = Size(w, h),
                        style = Stroke(width = 2f),
                    )

                    drawContext.canvas.nativeCanvas.drawText(
                        el.index.toString(),
                        left + 4f,
                        top + 28f,
                        labelPaint,
                    )
                }
            }
        }
    }
}
