package com.mantis.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.mantis.MainActivity
import com.mantis.MantisApp
import com.mantis.R
import com.mantis.llm.ConnectorFactory
import com.mantis.skills.SkillLoader
import com.mantis.skills.SkillSelector
import com.mantis.ui.history.TaskHistoryRecord
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant

/**
 * Foreground service that owns the AgentLoop coroutine and WakeLock.
 *
 * Start a task:
 *   context.startForegroundService(Intent(context, AgentService::class.java).apply {
 *       action = ACTION_START_TASK
 *       putExtra(EXTRA_TASK, "Turn on dark mode")
 *   })
 *
 * Pause (TAKE OVER):
 *   context.startService(Intent(context, AgentService::class.java).apply {
 *       action = ACTION_TAKE_OVER
 *   })
 *
 * Observe state:
 *   (applicationContext as MantisApp).agentService?.agentState
 */
class AgentService : Service() {

    companion object {
        private const val TAG = "AgentService"

        const val ACTION_START_TASK = "com.mantis.action.START_TASK"
        const val ACTION_TAKE_OVER  = "com.mantis.action.TAKE_OVER"
        const val ACTION_RESUME     = "com.mantis.action.RESUME"
        const val ACTION_STOP       = "com.mantis.action.STOP"
        const val EXTRA_TASK        = "task"

        private const val NOTIFICATION_ID         = 1001
        private const val CONFIRM_NOTIFICATION_ID = 1002
        private const val RESUME_NOTIFICATION_ID  = 1003
        private const val CHANNEL_ID              = "mantis_agent"
        private const val CONFIRM_CHANNEL_ID      = "mantis_confirm"
        private const val RESUME_CHANNEL_ID       = "mantis_resume"
        private const val WAKE_LOCK_TAG            = "mantis:agent_loop"

        /** Broadcast action sent by Allow / Cancel buttons on the confirmation notification. */
        const val ACTION_CONFIRM  = "com.mantis.action.CONFIRM_IRREVERSIBLE"
        const val EXTRA_CONFIRMED = "confirmed"

        /** Convenience launcher — starts the service from any Context. */
        fun startTask(context: Context, task: String) {
            val intent = Intent(context, AgentService::class.java).apply {
                action = ACTION_START_TASK
                putExtra(EXTRA_TASK, task)
            }
            context.startForegroundService(intent)
        }
    }

    // ── Coroutine scope ───────────────────────────────────────────────────────

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.IO)
    private var loopJob: Job? = null

    // ── Irreversible action confirmation ──────────────────────────────────────

    /**
     * Holds the pending deferred while the agent is waiting for the user to
     * Allow or Cancel an irreversible action. Resolved by [confirmReceiver].
     * Null when no confirmation is in progress.
     */
    @Volatile private var pendingConfirmation: CompletableDeferred<Boolean>? = null

    /**
     * Dynamically-registered receiver (lifecycle-bound to this service).
     * Receives the user's Allow/Cancel tap from the confirmation notification.
     */
    private val confirmReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val confirmed = intent.getBooleanExtra(EXTRA_CONFIRMED, false)
            Log.d(TAG, "confirmReceiver: confirmed=$confirmed")
            pendingConfirmation?.complete(confirmed)
            pendingConfirmation = null
            getSystemService(NotificationManager::class.java).cancel(CONFIRM_NOTIFICATION_ID)
        }
    }

    // ── Paused task (resume banner) ───────────────────────────────────────────

    /**
     * Task string saved when the user presses TAKE OVER.
     * Used by [ACTION_RESUME] to restart the same task from scratch.
     * Cleared when a new task starts or the user taps Dismiss.
     */
    private var pausedTask: String? = null
    private var pausedStep: Int = 0

    // ── State ─────────────────────────────────────────────────────────────────

    /** Delegates to the app-level StateFlow so any screen can observe without binding. */
    val agentState: StateFlow<AgentState> get() = (application as MantisApp).agentState

    // ── WakeLock ──────────────────────────────────────────────────────────────

    private var wakeLock: PowerManager.WakeLock? = null

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        (application as MantisApp).agentService = this
        createNotificationChannel()
        createConfirmChannel()
        createResumeChannel()
        // Register confirmation receiver dynamically — only valid for this service's lifetime.
        val filter = IntentFilter(ACTION_CONFIRM)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(confirmReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(confirmReceiver, filter)
        }
        Log.d(TAG, "AgentService created")
    }

    override fun onDestroy() {
        unregisterReceiver(confirmReceiver)
        // Resolve any in-flight confirmation as cancelled so the loop doesn't hang.
        pendingConfirmation?.complete(false)
        pendingConfirmation = null
        (application as MantisApp).agentService = null
        releaseWakeLock()
        serviceScope.cancel()
        Log.d(TAG, "AgentService destroyed")
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_TASK -> {
                val task = intent.getStringExtra(EXTRA_TASK)
                if (task.isNullOrBlank()) {
                    Log.e(TAG, "ACTION_START_TASK received with no task — ignoring")
                } else {
                    startAgentTask(task)
                }
            }
            ACTION_TAKE_OVER -> handleTakeOver()
            ACTION_RESUME -> {
                val task = pausedTask
                if (task.isNullOrBlank()) {
                    Log.w(TAG, "ACTION_RESUME: no paused task to resume — ignoring")
                } else {
                    Log.i(TAG, "ACTION_RESUME: restarting task from scratch: $task")
                    cancelResumeBanner()
                    startAgentTask(task)
                }
            }
            ACTION_STOP -> {
                // Dismiss the resume banner and return to Idle without restarting.
                cancelResumeBanner()
                pausedTask = null
                pausedStep = 0
                (application as MantisApp).agentState.value = AgentState.Idle
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    // ── Task control ──────────────────────────────────────────────────────────

    private fun startAgentTask(task: String) {
        // Cancel any currently running loop and clear any resume banner before starting.
        loopJob?.cancel()
        loopJob = null
        cancelResumeBanner()
        pausedTask = null
        pausedStep = 0

        val app = application as MantisApp
        val bridge = app.agentBridge

        // Show foreground notification immediately to satisfy Android's 5-second rule.
        val initialState = AgentState.Running(task, 1, app.secureConfig.maxStepsSetting)
        startForegroundCompat(buildNotification(initialState))
        (application as MantisApp).agentState.value = initialState

        if (bridge == null) {
            Log.e(TAG, "startAgentTask: AgentBridge not available")
            val errorState = AgentState.Error(task, "AgentBridge not connected — is Accessibility Service enabled?")
            (application as MantisApp).agentState.value = errorState
            updateNotification(errorState)
            releaseWakeLock()
            return
        }

        // Move MANTIS to the background so the agent starts from the home screen,
        // not from inside the MANTIS UI (which would confuse the agent and cause a
        // mirror loop in LiveViewPanel).
        startActivity(
            Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        )

        val connector = ConnectorFactory.create(app.secureConfig)
        acquireWakeLock()

        loopJob = serviceScope.launch {
            // Select the best skill for this task (cheap text call, ~0.5s).
            // Falls back to null (freeform) if selection fails or no skill matches.
            val skills = SkillLoader.loadAll(applicationContext)
            val skill = SkillSelector(connector).select(task, skills)
            if (skill != null) Log.d(TAG, "Using skill: ${skill.name}")
            else Log.d(TAG, "No skill matched — running freeform")

            // Load memory once before the loop starts — injected into every system prompt.
            val memory = app.memoryManager.load()
            Log.d(TAG, "Memory loaded: ${memory.length} chars")

            AgentLoop.run(
                task = task,
                connector = connector,
                bridge = bridge,
                secureConfig = app.secureConfig,
                context = applicationContext,
                skill = skill,
                memory = memory,
                onIrreversibleAction = { description ->
                    // Suspend the loop: post a heads-up notification and wait up to 60 seconds.
                    pendingConfirmation = CompletableDeferred()
                    postConfirmNotification(description)
                    val result = withTimeoutOrNull(60_000L) { pendingConfirmation!!.await() } ?: false
                    // Cancel the notification and clear state regardless of how we resolved.
                    getSystemService(NotificationManager::class.java).cancel(CONFIRM_NOTIFICATION_ID)
                    pendingConfirmation = null
                    if (!result) Log.i(TAG, "Irreversible action timed out or cancelled: $description")
                    result
                },
                onStateUpdate = { state ->
                    (application as MantisApp).agentState.value = state
                    updateNotification(state)
                    // Persist history record, release WakeLock, and return to MANTIS on terminal states.
                    when (state) {
                        is AgentState.Complete -> {
                            val completedAt = Instant.now().toString()
                            writeHistory(TaskHistoryRecord(
                                task = state.task,
                                timestamp = completedAt,
                                outcome = "complete",
                                steps = state.steps,
                                summary = state.summary,
                            ))
                            serviceScope.launch {
                                app.memoryManager.append(
                                    connector,
                                    "## Task completed: ${state.task}\n" +
                                    "Result: ${state.summary}\n" +
                                    "Updated: $completedAt",
                                )
                            }
                            releaseWakeLock()
                            bringToForeground()
                        }
                        is AgentState.Stuck -> {
                            writeHistory(TaskHistoryRecord(
                                task = state.task,
                                timestamp = Instant.now().toString(),
                                outcome = "stuck",
                                steps = state.step,
                                summary = state.reason,
                            ))
                            releaseWakeLock()
                            bringToForeground()
                        }
                        is AgentState.Error -> {
                            writeHistory(TaskHistoryRecord(
                                task = state.task,
                                timestamp = Instant.now().toString(),
                                outcome = "error",
                                steps = 0,
                                summary = state.message,
                            ))
                            releaseWakeLock()
                            bringToForeground()
                        }
                        else -> Unit
                    }
                },
            )
        }
    }

    private fun handleTakeOver() {
        val current = (application as MantisApp).agentState.value
        if (current is AgentState.Paused) return // already paused

        val pausedState = when (current) {
            is AgentState.Running -> AgentState.Paused(current.task, current.step)
            else -> AgentState.Paused("", 0)
        }

        // Save task details so ACTION_RESUME can restart the same task.
        pausedTask = pausedState.task.ifBlank { null }
        pausedStep = pausedState.step

        loopJob?.cancel()
        loopJob = null
        releaseWakeLock()

        (application as MantisApp).agentState.value = pausedState
        updateNotification(pausedState)

        // Post the persistent resume banner if there's a real task to resume.
        if (!pausedTask.isNullOrBlank()) {
            postResumeBanner(pausedTask!!, pausedStep)
        }

        Log.i(TAG, "TAKE OVER — agent paused at step $pausedStep, moving to background")

        // Move MANTIS to background so the user has full control of the device.
        startActivity(
            Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        )
    }

    // ── Foreground return ─────────────────────────────────────────────────────

    /**
     * Brings MANTIS back to the foreground after the agent loop finishes.
     * Called on Complete / Stuck / Error so the user immediately sees the result
     * instead of being left on whatever app the agent last touched.
     * Not called on Paused — the user pressed TAKE OVER intentionally.
     */
    private fun bringToForeground() {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            }
        )
    }

    // ── WakeLock ──────────────────────────────────────────────────────────────

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            .also { it.acquire(10 * 60 * 1000L) } // 10-minute safety timeout
        Log.d(TAG, "WakeLock acquired")
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) { it.release(); Log.d(TAG, "WakeLock released") } }
        wakeLock = null
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "MANTIS Agent",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "MANTIS agent progress while a task is running"
            setSound(null, null)
            enableVibration(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun createConfirmChannel() {
        val channel = NotificationChannel(
            CONFIRM_CHANNEL_ID,
            "MANTIS Confirmation",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Requires your approval before MANTIS performs an irreversible action"
            enableVibration(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun createResumeChannel() {
        // Separate DEFAULT-importance channel for the resume banner so that action buttons
        // (Resume / Dismiss) are always visible — IMPORTANCE_LOW collapses them on OneUI.
        // lockscreenVisibility = PUBLIC ensures the actions survive Android's screen-sharing
        // notification redaction, which MANTIS triggers via its own MediaProjection session.
        val channel = NotificationChannel(
            RESUME_CHANNEL_ID,
            "MANTIS Resume",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Lets you resume or dismiss a paused MANTIS task"
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /**
     * Posts a persistent notification on [CHANNEL_ID] (silent) giving the user
     * the option to resume the paused task or dismiss it entirely.
     * Cancelled by [cancelResumeBanner] when a new task starts or the user taps Dismiss.
     */
    private fun postResumeBanner(task: String, step: Int) {
        val resumePi = PendingIntent.getService(
            this, 20,
            Intent(this, AgentService::class.java).apply { action = ACTION_RESUME },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val dismissPi = PendingIntent.getService(
            this, 21,
            Intent(this, AgentService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, RESUME_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Task paused: $task")
            .setContentText("Step $step — tap Resume to continue, or Dismiss to cancel.")
            .setOngoing(true)
            // VISIBILITY_PUBLIC overrides Android's screen-sharing notification redaction.
            // Without this, MediaProjection (used by MANTIS for screen capture) causes Android
            // to strip action buttons from notifications shown while sharing is active.
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(Notification.Action.Builder(null, "Resume", resumePi).build())
            .addAction(Notification.Action.Builder(null, "Dismiss", dismissPi).build())
            .build()
        getSystemService(NotificationManager::class.java).notify(RESUME_NOTIFICATION_ID, notification)
        Log.d(TAG, "Resume banner posted for: $task (step $step)")
    }

    private fun cancelResumeBanner() {
        getSystemService(NotificationManager::class.java).cancel(RESUME_NOTIFICATION_ID)
    }

    /**
     * Posts a heads-up notification asking the user to Allow or Cancel an irreversible action.
     * The user's tap resolves [pendingConfirmation] via [confirmReceiver].
     */
    private fun postConfirmNotification(description: String) {
        val allowPi = PendingIntent.getBroadcast(
            this, 10,
            Intent(ACTION_CONFIRM).apply {
                putExtra(EXTRA_CONFIRMED, true)
                `package` = packageName
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val cancelPi = PendingIntent.getBroadcast(
            this, 11,
            Intent(ACTION_CONFIRM).apply {
                putExtra(EXTRA_CONFIRMED, false)
                `package` = packageName
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CONFIRM_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("MANTIS wants to: $description")
            .setContentText("This action can't be undone. Allow it?")
            .setAutoCancel(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(Notification.Action.Builder(null, "Allow", allowPi).build())
            .addAction(Notification.Action.Builder(null, "Cancel", cancelPi).build())
            .build()
        getSystemService(NotificationManager::class.java).notify(CONFIRM_NOTIFICATION_ID, notification)
    }

    private fun buildNotification(state: AgentState): Notification {
        val contentText = when (state) {
            is AgentState.Running -> "Step ${state.step}/${state.maxSteps}: ${state.task}"
            is AgentState.Paused  -> "Paused — tap to return"
            is AgentState.Complete -> "Done: ${state.summary}"
            is AgentState.Stuck   -> "Needs help: ${state.reason}"
            is AgentState.Error   -> "Error: ${state.message}"
            is AgentState.Idle    -> "Ready"
        }

        // "TAKE OVER" action button — only shown while the loop is running.
        val takeOverPi = PendingIntent.getService(
            this, 0,
            Intent(this, AgentService::class.java).apply { action = ACTION_TAKE_OVER },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // Tapping a Paused notification re-opens MANTIS.
        val openAppPi = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("MANTIS")
            .setContentText(contentText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)

        if (state is AgentState.Running) {
            builder.addAction(
                Notification.Action.Builder(null, "TAKE OVER", takeOverPi).build()
            )
        }

        if (state is AgentState.Paused) {
            builder.setContentIntent(openAppPi)
        }

        return builder.build()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(state: AgentState) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(state))
    }

    // ── History ───────────────────────────────────────────────────────────────

    private fun writeHistory(record: TaskHistoryRecord) {
        try {
            val dir = File(filesDir, "history").also { it.mkdirs() }
            // Sanitise the ISO timestamp for use as a filename — replace colons
            // (invalid on some filesystems) with dashes.
            val filename = "${record.timestamp.replace(':', '-')}.json"
            File(dir, filename).writeText(Json.encodeToString(TaskHistoryRecord.serializer(), record))
        } catch (e: Exception) {
            Log.e(TAG, "writeHistory: failed to write record", e)
        }
    }
}
