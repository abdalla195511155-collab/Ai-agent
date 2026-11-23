package com.mantis

import android.app.Application
import android.content.Intent
import com.mantis.accessibility.MantisActionExecutor
import com.mantis.agent.AgentService
import com.mantis.agent.AgentState
import com.mantis.bridge.AgentBridge
import kotlinx.coroutines.flow.MutableStateFlow
import com.mantis.capture.MantisScreenCapture
import com.mantis.ime.MantisKeyboardIME
import com.mantis.overlay.MantisOverlayService
import com.mantis.permissions.HealthStatus
import com.mantis.permissions.PermissionHealthMonitor
import com.mantis.permissions.PermissionWatcher
import com.mantis.registry.BlockedAppsRegistry
import com.mantis.memory.MemoryManager
import com.mantis.storage.SecureConfig

/**
 * MANTIS Application class.
 *
 * Fully on-device AI agent app. No backend. No accounts. No telemetry.
 * MIT License — github.com/sajjad-vahdatzadeh/mantis
 */
class MantisApp : Application() {

    lateinit var secureConfig: SecureConfig
        private set

    lateinit var permissionHealthMonitor: PermissionHealthMonitor
        private set

    lateinit var memoryManager: MemoryManager
        private set

    lateinit var blockedAppsRegistry: BlockedAppsRegistry
        private set

    /**
     * Set by [AgentBridge.onCreate], cleared by [AgentBridge.onDestroy].
     * @Volatile because system services (MantisAccessibilityService,
     * MantisScreenCapture) may read this from non-main threads.
     * Always access null-safely: (applicationContext as MantisApp).agentBridge?.x()
     */
    @Volatile
    var agentBridge: AgentBridge? = null

    /**
     * Set by [MantisAccessibilityService.onServiceConnected], cleared on destroy.
     * @Volatile because AgentBridge reads this from whatever thread calls executeAction.
     * Always access null-safely: (applicationContext as MantisApp).actionExecutor?.tap(...)
     */
    @Volatile
    var actionExecutor: MantisActionExecutor? = null

    /**
     * Set by [MantisKeyboardIME.onCreate], cleared by [MantisKeyboardIME.onDestroy].
     * @Volatile because AgentBridge reads this from whatever thread calls executeAction.
     * Always access null-safely: (applicationContext as MantisApp).keyboardIme?.typeText(...)
     */
    @Volatile
    var keyboardIme: MantisKeyboardIME? = null

    /**
     * MediaProjection grant token — stored in memory only; lost on process kill.
     * Set by [com.mantis.onboarding.OnboardingViewModel.onMediaProjectionGranted].
     * If null on startup but [SecureConfig.isMediaProjectionValid] is true, the token
     * has been lost and the user must re-grant screen capture.
     */
    @Volatile var mediaProjectionResultCode: Int = 0
    @Volatile var mediaProjectionIntent: Intent? = null

    /**
     * The running [MantisScreenCapture] foreground service instance, or null if not active.
     * Set by [MantisScreenCapture.onCreate], cleared by [MantisScreenCapture.onDestroy].
     */
    @Volatile
    var screenCapture: MantisScreenCapture? = null

    /**
     * The running [AgentService] foreground service instance, or null if not active.
     * Set by [AgentService.onCreate], cleared by [AgentService.onDestroy].
     */
    @Volatile
    var agentService: AgentService? = null

    /**
     * Single source of truth for agent lifecycle state.
     * Updated by [AgentService] on every loop step and terminal state.
     * Phase 6 HomeScreen observes this directly.
     */
    val agentState: MutableStateFlow<AgentState> = MutableStateFlow(AgentState.Idle)

    /**
     * Live permission health — updated every 2 seconds by [PermissionWatcher].
     * Phase 6 HomeScreen observes this to show the amber warning banner or repair screen.
     */
    val permissionHealth: MutableStateFlow<HealthStatus> = MutableStateFlow(HealthStatus.Healthy)

    /**
     * The running [MantisOverlayService] instance, or null if not active.
     * Set by [MantisOverlayService.onCreate], cleared by [MantisOverlayService.onDestroy].
     * @Volatile because AgentBridge reads this from the coroutine dispatcher.
     */
    @Volatile
    var overlayService: MantisOverlayService? = null

    override fun onCreate() {
        super.onCreate()
        secureConfig = SecureConfig(this)
        permissionHealthMonitor = PermissionHealthMonitor(this, secureConfig)
        blockedAppsRegistry = BlockedAppsRegistry(this)
        memoryManager = MemoryManager(this)
        PermissionWatcher.start(this)
    }
}
