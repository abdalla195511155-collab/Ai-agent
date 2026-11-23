package com.mantis.scheduler

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.mantis.MainActivity
import com.mantis.MantisApp
import com.mantis.agent.AgentLoop
import com.mantis.agent.AgentState
import com.mantis.llm.ConnectorFactory
import com.mantis.permissions.HealthStatus
import com.mantis.skills.SkillLoader
import com.mantis.skills.SkillSelector
import com.mantis.ui.history.TaskHistoryRecord
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant

/**
 * WorkManager worker that executes a [ScheduledTask] silently in the background.
 *
 * No foreground service — WorkManager provides its own partial WakeLock for the
 * duration of `doWork()`. No UI updates; results are delivered via notifications
 * on the [CHANNEL_ID] channel.
 *
 * Pre-run checks (fast-fail before touching the agent):
 *  1. Permissions — if BROKEN, post a repair notification and return failure.
 *  2. Agent busy — if Running/Paused, retry later.
 *  3. Services available — if AgentBridge is null (app was killed), post notification and fail.
 */
class ScheduledTaskWorker(
    private val appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "ScheduledTaskWorker"
        const val KEY_TASK_ID = "task_id"

        private const val CHANNEL_ID = "mantis_scheduled"
        private const val CHANNEL_NAME = "MANTIS Scheduled Tasks"
    }

    override suspend fun doWork(): Result {
        val taskId = inputData.getString(KEY_TASK_ID)
        if (taskId.isNullOrBlank()) {
            Log.e(TAG, "No task ID in inputData — failing permanently")
            return Result.failure()
        }

        val app = appContext as MantisApp
        ensureNotificationChannel()

        // Load task from disk.
        val task = ScheduledTask.loadAll(appContext).firstOrNull { it.id == taskId }
        if (task == null) {
            Log.w(TAG, "Task $taskId not found on disk — may have been deleted")
            return Result.failure()
        }

        if (!task.enabled) {
            Log.d(TAG, "Task '${task.name}' is disabled — skipping")
            return Result.success()
        }

        // ── Pre-check 1: Permissions ──────────────────────────────────────────

        val health = app.permissionHealthMonitor.checkHealth()
        if (health is HealthStatus.Broken) {
            Log.w(TAG, "Task '${task.name}' skipped — permissions BROKEN")
            postNotification(
                notifId = taskId.hashCode(),
                title = "MANTIS couldn't run '${task.name}'",
                text = "A required permission is missing — open MANTIS to fix it.",
            )
            return Result.failure()
        }

        // ── Pre-check 2: Agent already busy ──────────────────────────────────

        val currentState = app.agentState.value
        if (currentState is AgentState.Running || currentState is AgentState.Paused) {
            Log.d(TAG, "Agent busy (${currentState::class.simpleName}) — retrying later")
            return Result.retry()
        }

        // ── Pre-check 3: Core services available ──────────────────────────────

        val bridge = app.agentBridge
        if (bridge == null) {
            Log.w(TAG, "AgentBridge not available — app may have been killed")
            postNotification(
                notifId = taskId.hashCode(),
                title = "MANTIS couldn't run '${task.name}'",
                text = "MANTIS wasn't active — open the app and leave it in the background.",
            )
            return Result.failure()
        }

        // ── Run AgentLoop silently ────────────────────────────────────────────

        Log.d(TAG, "Running scheduled task '${task.name}': ${task.task}")

        val connector = ConnectorFactory.create(app.secureConfig)
        val skills = SkillLoader.loadAll(appContext)
        val skill = runCatching {
            SkillSelector(connector).select(task.task, skills)
        }.getOrNull()

        val memory = app.memoryManager.load()

        val terminalState = AgentLoop.run(
            task = task.task,
            connector = connector,
            bridge = bridge,
            secureConfig = app.secureConfig,
            context = appContext,
            skill = skill,
            memory = memory,
            onStateUpdate = { state ->
                Log.d(TAG, "Scheduled task state: ${state::class.simpleName}")
            },
        )

        // ── Update last_run timestamp ─────────────────────────────────────────

        val completedAt = Instant.now().toString()
        task.copy(lastRun = completedAt).save(appContext)

        // ── Write history record (same format as AgentService) ────────────────

        if (terminalState is AgentState.Complete) {
            app.memoryManager.append(
                connector,
                "## Task completed: ${task.task}\n" +
                "Result: ${terminalState.summary}\n" +
                "Updated: $completedAt",
            )
        }

        val record = when (terminalState) {
            is AgentState.Complete -> TaskHistoryRecord(
                task = task.task,
                timestamp = completedAt,
                outcome = "complete",
                steps = terminalState.steps,
                summary = terminalState.summary,
            )
            is AgentState.Stuck -> TaskHistoryRecord(
                task = task.task,
                timestamp = completedAt,
                outcome = "stuck",
                steps = terminalState.step,
                summary = terminalState.reason,
            )
            is AgentState.Error -> TaskHistoryRecord(
                task = task.task,
                timestamp = completedAt,
                outcome = "error",
                steps = 0,
                summary = terminalState.message,
            )
            else -> null
        }
        record?.let { writeHistory(it) }

        // ── Post result notification ──────────────────────────────────────────

        when (terminalState) {
            is AgentState.Complete -> {
                if (task.notifyOnComplete) {
                    postNotification(
                        notifId = taskId.hashCode(),
                        title = "Done: ${task.name}",
                        text = terminalState.summary,
                    )
                }
                Log.i(TAG, "Task '${task.name}' complete in ${terminalState.steps} steps")
            }
            is AgentState.Stuck -> {
                postNotification(
                    notifId = taskId.hashCode(),
                    title = "'${task.name}' needs help",
                    text = terminalState.reason,
                )
                Log.w(TAG, "Task '${task.name}' stuck: ${terminalState.reason}")
            }
            is AgentState.Error -> {
                postNotification(
                    notifId = taskId.hashCode(),
                    title = "'${task.name}' failed",
                    text = terminalState.message,
                )
                Log.e(TAG, "Task '${task.name}' error: ${terminalState.message}")
            }
            else -> Unit
        }

        return Result.success()
    }

    // ── History ───────────────────────────────────────────────────────────────

    private fun writeHistory(record: TaskHistoryRecord) {
        try {
            val dir = File(appContext.filesDir, "history").also { it.mkdirs() }
            val filename = "${record.timestamp.replace(':', '-')}.json"
            File(dir, filename).writeText(Json.encodeToString(TaskHistoryRecord.serializer(), record))
        } catch (e: Exception) {
            Log.e(TAG, "writeHistory: failed to write record", e)
        }
    }

    // ── Notification helpers ──────────────────────────────────────────────────

    private fun ensureNotificationChannel() {
        val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Results and alerts from MANTIS scheduled tasks"
            enableVibration(true)
        }
        manager.createNotificationChannel(channel)
    }

    private fun postNotification(notifId: Int, title: String, text: String) {
        val openAppPi = PendingIntent.getActivity(
            appContext, notifId,
            Intent(appContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(com.mantis.R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openAppPi)
            .setAutoCancel(true)
            .build()

        (appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(notifId, notification)
    }
}
