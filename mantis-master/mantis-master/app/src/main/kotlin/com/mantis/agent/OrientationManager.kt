package com.mantis.agent

import android.content.Context
import android.content.res.Configuration

/**
 * Reads the current device orientation for inclusion in the AgentLoop system prompt.
 * Polled fresh on each loop iteration — no lifecycle listener needed.
 */
object OrientationManager {

    /**
     * Returns `"portrait"` or `"landscape"` based on the current device configuration.
     * Falls back to `"portrait"` for any unexpected orientation value (e.g. square screens).
     */
    fun currentOrientation(context: Context): String =
        when (context.resources.configuration.orientation) {
            Configuration.ORIENTATION_LANDSCAPE -> "landscape"
            else -> "portrait"
        }
}
