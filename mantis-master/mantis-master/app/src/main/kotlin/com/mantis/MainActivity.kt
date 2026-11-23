package com.mantis

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.mantis.agent.AgentService
import com.mantis.bridge.AgentBridge
import com.mantis.capture.MantisScreenCapture
import com.mantis.lock.LockScreen
import com.mantis.onboarding.OnboardingFlow
import com.mantis.permissions.PermissionRepairScreen
import com.mantis.storage.SecureConfig
import com.mantis.ui.history.TaskHistoryRecord
import com.mantis.ui.history.TaskHistoryScreen
import com.mantis.ui.home.HomeScreen
import com.mantis.ui.schedule.ScheduleScreen
import com.mantis.ui.settings.SettingsScreen
import com.mantis.ui.skills.SkillStoreScreen
import com.mantis.ui.theme.MantisTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    /** Task waiting to run after the user grants MediaProjection permission. */
    private var pendingTask: String? = null

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val app = applicationContext as MantisApp
            app.mediaProjectionResultCode = result.resultCode
            app.mediaProjectionIntent = result.data
            app.secureConfig.isMediaProjectionValid = true
            Log.d(TAG, "MediaProjection granted — starting capture")
            MantisScreenCapture.start(this, result.resultCode, result.data!!)
            pendingTask?.let { task ->
                AgentService.startTask(this, task)
                pendingTask = null
            }
        } else {
            (applicationContext as MantisApp).secureConfig.isMediaProjectionValid = false
            pendingTask = null
            Log.w(TAG, "MediaProjection denied by user")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startService(Intent(this, AgentBridge::class.java))
        enableEdgeToEdge()
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = true
        setContent {
            MantisTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    MantisNavHost()
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        val config = (applicationContext as MantisApp).secureConfig
        if (config.isOnboarded) {
            config.lastActiveTimestamp = System.currentTimeMillis()
        }
    }

    /**
     * Called by [HomeScreen] when the user taps Send.
     *
     * Ensures [MantisScreenCapture] is running before the agent starts — screen
     * capture is only needed when the agent is actually working, not on every resume.
     * If the MediaProjection token is missing, requests the permission dialog and
     * stores [task] until the grant result arrives.
     */
    fun ensureCaptureAndSubmit(task: String) {
        val app = applicationContext as MantisApp
        if (app.screenCapture != null) {
            // Capture already running — go straight to the agent.
            AgentService.startTask(this, task)
            return
        }
        val intent = app.mediaProjectionIntent
        if (intent != null) {
            // Token present but service died — restart it then start the task.
            MantisScreenCapture.start(this, app.mediaProjectionResultCode, intent)
            AgentService.startTask(this, task)
            return
        }
        // No token — ask the user for screen-capture permission, then start.
        Log.d(TAG, "MediaProjection token missing — requesting grant before task: $task")
        pendingTask = task
        val manager = getSystemService(MediaProjectionManager::class.java)
        mediaProjectionLauncher.launch(manager.createScreenCaptureIntent())
    }
}

@Composable
private fun MantisNavHost() {
    val context = LocalContext.current
    val activity = context as MainActivity
    val app = remember { context.applicationContext as MantisApp }
    val lifecycleOwner = LocalLifecycleOwner.current
    val density = LocalDensity.current

    var drawerOpen by remember { mutableStateOf(false) }

    // Drawer width in pixels — used for pixel-accurate offset animations.
    val drawerWidthPx = with(density) { 280.dp.toPx() }

    // Content slides right when drawer opens; drawer slides in from the left edge.
    val contentOffsetPx by animateFloatAsState(
        targetValue = if (drawerOpen) drawerWidthPx else 0f,
        animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing),
        label = "contentOffset",
    )
    val drawerOffsetPx by animateFloatAsState(
        targetValue = if (drawerOpen) 0f else -drawerWidthPx,
        animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing),
        label = "drawerOffset",
    )

    val startDestination = remember {
        when {
            !app.secureConfig.isOnboarded -> "onboarding"
            shouldLockOnColdStart(app.secureConfig) -> "lock"
            else -> "home"
        }
    }

    val navController = rememberNavController()

    // Resume-from-background lock check.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val route = navController.currentDestination?.route
                if (route in setOf("home", "history", "schedule", "settings", "skill_store") &&
                    shouldLockOnResume(app.secureConfig)
                ) {
                    navController.navigate("lock") {
                        popUpTo("home") { inclusive = true }
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(modifier = Modifier.fillMaxSize()) {

        // ── 1. Main content — full size, pushed right by the animation ─────────
        Box(
            modifier = Modifier
                .fillMaxSize()
                .offset { IntOffset(x = contentOffsetPx.roundToInt(), y = 0) },
        ) {
            NavHost(
                navController = navController,
                startDestination = startDestination,
            ) {
                composable("onboarding") {
                    OnboardingFlow(navController = navController)
                }
                composable("lock") {
                    LockScreen(navController = navController)
                }
                composable("home") {
                    HomeScreen(
                        navController = navController,
                        onMenuClick = { drawerOpen = true },
                        onSubmitTask = { task -> activity.ensureCaptureAndSubmit(task) },
                    )
                }
                composable("history") {
                    TaskHistoryScreen(onMenuClick = { drawerOpen = true })
                }
                composable("schedule") {
                    ScheduleScreen(onMenuClick = { drawerOpen = true })
                }
                composable("settings") {
                    SettingsScreen(onMenuClick = { drawerOpen = true })
                }
                composable("skill_store") {
                    SkillStoreScreen(onMenuClick = { drawerOpen = true })
                }
                composable("permission_repair") {
                    PermissionRepairScreen(navController = navController)
                }
            }
        }

        // ── 2. Scrim — fades in over the pushed content, tap to close ──────────
        AnimatedVisibility(
            visible = drawerOpen,
            enter = fadeIn(tween(320)),
            exit  = fadeOut(tween(280)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = { drawerOpen = false },
                    ),
            )
        }

        // ── 3. Drawer panel — slides in from the left edge ────────────────────
        Surface(
            modifier = Modifier
                .width(280.dp)
                .fillMaxHeight()
                .offset { IntOffset(x = drawerOffsetPx.roundToInt(), y = 0) },
            color = Color.White,
            shadowElevation = 24.dp,
        ) {
            DrawerPanel(
                navController = navController,
                onClose = { drawerOpen = false },
            )
        }
    }
}

// ── Drawer panel ──────────────────────────────────────────────────────────────

@Composable
private fun DrawerPanel(navController: NavController, onClose: () -> Unit) {
    val context = LocalContext.current

    val recentTasks by produceState<List<TaskHistoryRecord>>(initialValue = emptyList()) {
        value = withContext(Dispatchers.IO) {
            val dir = File(context.filesDir, "history")
            if (!dir.exists()) return@withContext emptyList()
            val json = Json { ignoreUnknownKeys = true }
            dir.listFiles()
                ?.filter { it.name.endsWith(".json") && !it.name.endsWith("_state.json") }
                ?.sortedByDescending { it.name }
                ?.take(3)
                ?.mapNotNull { f ->
                    runCatching { json.decodeFromString<TaskHistoryRecord>(f.readText()) }.getOrNull()
                }
                ?: emptyList()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 20.dp),
    ) {

        // ── New Task button ───────────────────────────────────────────────────
        Button(
            onClick = {
                onClose()
                navController.navigate("home") {
                    popUpTo("home") { inclusive = true }
                    launchSingleTop = true
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(50.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF3DBB5E),
                contentColor = Color.White,
            ),
        ) {
            Text("New Task", fontWeight = FontWeight.SemiBold)
        }

        Spacer(Modifier.height(28.dp))

        // ── Previous Tasks ────────────────────────────────────────────────────
        Text(
            text = "Previous Tasks",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF1A1A1A),
        )
        Spacer(Modifier.height(10.dp))
        if (recentTasks.isEmpty()) {
            Text(
                text = "No tasks yet",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF999999),
            )
        } else {
            recentTasks.forEach { record ->
                Text(
                    text = record.task,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF666666),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onClose()
                            navController.navigate("history") {
                                popUpTo("home") { saveState = true }
                                launchSingleTop = true
                            }
                        }
                        .padding(vertical = 9.dp),
                )
            }
        }

        Spacer(Modifier.weight(1f))

        // ── Bottom navigation ─────────────────────────────────────────────────
        HorizontalDivider(color = Color(0xFFEEEEEE))
        Spacer(Modifier.height(12.dp))

        DrawerNavItem(
            iconRes = R.drawable.ic_heart,
            label = "Donate to Project",
            iconTint = Color(0xFFF75555),
            onClick = {
                onClose()
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://buymeacoffee.com/mantis.ai"))
                context.startActivity(intent)
            },
        )
        DrawerNavItem(
            iconRes = R.drawable.ic_schedule,
            label = "Schedule a Task",
            onClick = {
                onClose()
                navController.navigate("schedule") {
                    popUpTo("home") { saveState = true }
                    launchSingleTop = true
                }
            },
        )
        DrawerNavItem(
            iconRes = R.drawable.ic_history,
            label = "History",
            onClick = {
                onClose()
                navController.navigate("history") {
                    popUpTo("home") { saveState = true }
                    launchSingleTop = true
                }
            },
        )
        DrawerNavItem(
            iconRes = R.drawable.ic_skills,
            label = "Skill Store",
            onClick = {
                onClose()
                navController.navigate("skill_store") {
                    popUpTo("home") { saveState = true }
                    launchSingleTop = true
                }
            },
        )
        DrawerNavItem(
            iconRes = R.drawable.ic_settings,
            label = "Settings",
            onClick = {
                onClose()
                navController.navigate("settings") {
                    popUpTo("home") { saveState = true }
                    launchSingleTop = true
                }
            },
        )
    }
}

@Composable
private fun DrawerNavItem(
    iconRes: Int,
    label: String,
    iconTint: Color = Color(0xFF1A1A1A),
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = label,
            tint = iconTint,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF1A1A1A),
        )
    }
}

// ── Lock helpers ──────────────────────────────────────────────────────────────

private fun shouldLockOnColdStart(config: SecureConfig): Boolean {
    return config.isLockEnabled && config.pinHash.isNotEmpty()
}

private fun shouldLockOnResume(config: SecureConfig): Boolean {
    if (!config.isLockEnabled) return false
    val lastActive = config.lastActiveTimestamp
    if (lastActive == 0L) return false
    return System.currentTimeMillis() - lastActive > LOCK_TIMEOUT_MS
}

private const val LOCK_TIMEOUT_MS = 5 * 60 * 1000L
