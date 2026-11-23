package com.mantis.permissions

import android.content.Context
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.mantis.storage.SecureConfig

/**
 * Checks the live state of every permission MANTIS requires and returns
 * an overall [HealthStatus].
 *
 * Call [checkHealth] from a ViewModel or LaunchedEffect; it is synchronous
 * and safe to call on the main thread (all checks are non-blocking reads).
 *
 * Permission severity:
 *  CRITICAL  — Accessibility Service, MediaProjection
 *              → HealthStatus.Broken; agent cannot run
 *  DEGRADED  — Overlay, Battery optimization, Notifications
 *              → HealthStatus.Degraded; agent runs with amber warning
 */
class PermissionHealthMonitor(
    private val context: Context,
    private val secureConfig: SecureConfig,
) {

    fun checkHealth(): HealthStatus {
        val issues = mutableListOf<PermissionIssue>()

        // ── CRITICAL ──────────────────────────────────────────────────────────

        if (!isAccessibilityEnabled()) {
            issues += PermissionIssue(
                id = "accessibility",
                label = "Accessibility Service",
                severity = PermissionSeverity.CRITICAL,
                description = "MANTIS needs Accessibility Service to read and interact with other apps.",
            )
        }

        if (!secureConfig.isMediaProjectionValid) {
            issues += PermissionIssue(
                id = "media_projection",
                label = "Screen Capture",
                severity = PermissionSeverity.CRITICAL,
                description = "MANTIS needs Screen Capture permission to see what's on your screen.",
            )
        }

        // ── DEGRADED ──────────────────────────────────────────────────────────

        if (!Settings.canDrawOverlays(context)) {
            issues += PermissionIssue(
                id = "overlay",
                label = "Display over other apps",
                severity = PermissionSeverity.DEGRADED,
                description = "Without this, MANTIS cannot show its live-view overlay while running tasks.",
            )
        }

        if (!isBatteryOptimizationExempt()) {
            issues += PermissionIssue(
                id = "battery",
                label = "Battery optimization",
                severity = PermissionSeverity.DEGRADED,
                description = "Android may kill MANTIS mid-task without this exemption.",
            )
        }

        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            issues += PermissionIssue(
                id = "notifications",
                label = "Notifications",
                severity = PermissionSeverity.DEGRADED,
                description = "MANTIS uses notifications to report task results and request confirmation.",
            )
        }

        // ── Result ────────────────────────────────────────────────────────────

        return when {
            issues.any { it.severity == PermissionSeverity.CRITICAL } -> HealthStatus.Broken(issues)
            issues.isNotEmpty() -> HealthStatus.Degraded(issues)
            else -> HealthStatus.Healthy
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun isAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        return enabled.contains(context.packageName, ignoreCase = true)
    }

    private fun isBatteryOptimizationExempt(): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }
}
