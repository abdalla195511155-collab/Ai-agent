package com.mantis.permissions

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.mantis.MainActivity
import com.mantis.MantisApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Continuously monitors all MANTIS permissions and notifies the user immediately
 * when any permission is revoked or degrades.
 *
 * Started once from [MantisApp.onCreate] via [start]. Runs for the full process lifetime
 * using an application-scoped [CoroutineScope] — it is intentionally never stopped.
 *
 * Polling interval: [POLL_INTERVAL_MS] (2 seconds).
 *
 * Notification behaviour:
 *  - BROKEN (critical permission missing) → high-priority alert with "⛔ MANTIS cannot run"
 *  - DEGRADED (optional permission missing) → normal-priority warning
 *  - HEALTHY (all permissions present) → any existing notification is cancelled
 *
 * Only fires a new notification when health status changes, preventing repeated
 * notifications for the same condition.
 */
object PermissionWatcher {

    private const val TAG = "PermissionWatcher"
    const val CHANNEL_ID = "mantis_permissions"
    private const val NOTIF_ID = 900
    private const val POLL_INTERVAL_MS = 2_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * Starts the background permission polling loop. Safe to call multiple times —
     * the coroutine scope is shared so only one loop runs.
     */
    fun start(app: MantisApp) {
        createChannel(app)
        scope.launch {
            var prevHealth: HealthStatus? = null
            while (true) {
                val health = app.permissionHealthMonitor.checkHealth()
                if (health != prevHealth) {
                    Log.d(TAG, "Health changed: ${prevHealth?.javaClass?.simpleName} → ${health.javaClass.simpleName}")
                    app.permissionHealth.value = health
                    when (health) {
                        is HealthStatus.Broken -> notifyBroken(app, health.issues)
                        is HealthStatus.Degraded -> notifyDegraded(app, health.issues)
                        HealthStatus.Healthy -> cancelNotification(app)
                    }
                    prevHealth = health
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    // ── Notification channel ──────────────────────────────────────────────────

    private fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Permission Alerts",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Notifies immediately when a required MANTIS permission is revoked."
            enableVibration(true)
        }
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    // ── Notification helpers ──────────────────────────────────────────────────

    private fun notifyBroken(context: Context, issues: List<PermissionIssue>) {
        val labels = issues
            .filter { it.severity == PermissionSeverity.CRITICAL }
            .joinToString(", ") { it.label }
        notify(
            context,
            title = "MANTIS cannot run",
            text = "Missing: $labels — tap to fix",
        )
        Log.w(TAG, "BROKEN — missing critical permissions: $labels")
    }

    private fun notifyDegraded(context: Context, issues: List<PermissionIssue>) {
        val labels = issues.joinToString(", ") { it.label }
        notify(
            context,
            title = "MANTIS permission warning",
            text = "$labels — tap to fix",
        )
        Log.w(TAG, "DEGRADED — missing optional permissions: $labels")
    }

    private fun notify(context: Context, title: String, text: String) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("navigate_to_repair", true)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(NOTIF_ID, notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS not granted — log and continue. The UI StateFlow
            // still reflects the broken state even without the notification.
            Log.w(TAG, "Cannot post notification — POST_NOTIFICATIONS not granted")
        }
    }

    private fun cancelNotification(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIF_ID)
        Log.d(TAG, "All permissions healthy — notification cleared")
    }
}
