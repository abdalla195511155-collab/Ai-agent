package com.mantis.scheduler

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * Manages WorkManager scheduling for all [ScheduledTask]s.
 *
 * Each task maps to a unique PeriodicWorkRequest tagged with the task's id.
 * `rescheduleAll` is called on boot (Phase 10 BootReceiver) to restore
 * schedules lost when the process is killed.
 */
object ProactiveScheduler {

    /**
     * Schedules (or re-schedules) a single task.
     * Uses the task's [ScheduledTask.time] and [ScheduledTask.timezone] to
     * compute the initial delay so the first run aligns to the target wall-clock time.
     * Subsequent runs repeat every 24 hours from that point.
     *
     * Calling this on an already-scheduled task replaces it (CANCEL_AND_REENQUEUE).
     */
    fun schedule(context: Context, task: ScheduledTask) {
        val request = buildWorkRequest(task, policy = ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE)
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(task.id, ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE, request)
    }

    /**
     * Cancels the WorkManager job for a task.
     * Call this when the user disables or deletes a scheduled task.
     */
    fun cancel(context: Context, taskId: String) {
        WorkManager.getInstance(context).cancelUniqueWork(taskId)
    }

    /**
     * Re-enqueues all enabled scheduled tasks.
     * Called on boot to restore schedules (WorkManager periodic work survives
     * process death but not full device reboot without re-enqueueing).
     *
     * Uses KEEP so existing in-flight schedules are not reset.
     */
    fun rescheduleAll(context: Context) {
        ScheduledTask.loadAll(context)
            .filter { it.enabled }
            .forEach { task ->
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    task.id,
                    ExistingPeriodicWorkPolicy.KEEP,
                    buildWorkRequest(task),
                )
            }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun buildWorkRequest(
        task: ScheduledTask,
        policy: ExistingPeriodicWorkPolicy = ExistingPeriodicWorkPolicy.KEEP,
    ) = PeriodicWorkRequestBuilder<ScheduledTaskWorker>(24, TimeUnit.HOURS)
        .setInitialDelay(computeInitialDelayMs(task), TimeUnit.MILLISECONDS)
        .setInputData(workDataOf(ScheduledTaskWorker.KEY_TASK_ID to task.id))
        .addTag(task.id)
        .setConstraints(
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED) // LLM calls need internet
                .build()
        )
        .build()

    /**
     * Returns milliseconds until the next wall-clock occurrence of [task.time]
     * in [task.timezone]. If the target time has already passed today, returns
     * the delay to tomorrow's occurrence.
     */
    internal fun computeInitialDelayMs(task: ScheduledTask): Long {
        val (hour, minute) = task.time.split(":").map { it.toInt() }
        val zone = ZoneId.of(task.timezone)
        val now = ZonedDateTime.now(zone)
        val target = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
        val adjusted = if (!target.isAfter(now)) target.plusDays(1) else target
        return Duration.between(now, adjusted).toMillis().coerceAtLeast(0L)
    }
}
