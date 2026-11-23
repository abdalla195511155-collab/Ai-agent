package com.mantis.ui.home

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import com.mantis.MantisApp
import com.mantis.agent.AgentService
import com.mantis.agent.AgentState
import com.mantis.permissions.HealthStatus
import kotlinx.coroutines.flow.StateFlow

/**
 * ViewModel for [HomeScreen].
 *
 * Reads live state from [MantisApp] and forwards user actions to [AgentService].
 * Uses AndroidViewModel so it can access the Application for service intents.
 */
class HomeViewModel(app: Application) : AndroidViewModel(app) {

    private val mantisApp = app as MantisApp

    /** Live agent lifecycle state — sourced from MantisApp (single source of truth). */
    val agentState: StateFlow<AgentState> = mantisApp.agentState

    /** User's display name from SecureConfig. */
    val userName: String get() = mantisApp.secureConfig.name.ifBlank { "there" }

    /** Live permission health — sourced from PermissionWatcher via MantisApp. */
    val permissionHealth: StateFlow<HealthStatus> = mantisApp.permissionHealth

    /** Submit a plain-language task to the agent. No-ops if task is blank. */
    fun submitTask(task: String) {
        if (task.isBlank()) return
        AgentService.startTask(getApplication(), task)
    }

    /** TAKE OVER — pauses the agent and moves MANTIS to the background. */
    fun takeOver() {
        getApplication<Application>().startService(
            Intent(getApplication(), AgentService::class.java).apply {
                action = AgentService.ACTION_TAKE_OVER
            }
        )
    }
}
