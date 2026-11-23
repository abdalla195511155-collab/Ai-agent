package com.mantis.permissions

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.mantis.MantisApp

@Composable
fun PermissionRepairScreen(navController: NavController) {
    val context = LocalContext.current
    val monitor = remember { (context.applicationContext as MantisApp).permissionHealthMonitor }

    var health by remember { mutableStateOf<HealthStatus>(monitor.checkHealth()) }

    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1000)
            health = monitor.checkHealth()
        }
    }

    val issues = when (val h = health) {
        is HealthStatus.Broken -> h.issues
        is HealthStatus.Degraded -> h.issues
        HealthStatus.Healthy -> emptyList()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(top = 56.dp, bottom = 32.dp),
    ) {
        Text("Permission issues", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            text = when (health) {
                is HealthStatus.Broken -> "Fix the critical items below before MANTIS can run tasks."
                is HealthStatus.Degraded -> "These optional permissions improve the experience."
                HealthStatus.Healthy -> "All permissions are granted."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
        Spacer(Modifier.height(24.dp))

        if (issues.isEmpty()) {
            Text(
                text = "All good — nothing to fix.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            issues.forEachIndexed { index, issue ->
                RepairRow(issue = issue, context = context)
                if (index < issues.lastIndex) {
                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                    Spacer(Modifier.height(16.dp))
                }
            }
        }

        Spacer(Modifier.height(32.dp))
        OutlinedButton(
            onClick = { navController.popBackStack() },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Back")
        }
    }
}

@Composable
private fun RepairRow(issue: PermissionIssue, context: Context) {
    val severityColor = when (issue.severity) {
        PermissionSeverity.CRITICAL -> MaterialTheme.colorScheme.error
        PermissionSeverity.DEGRADED -> MaterialTheme.colorScheme.tertiary
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = issue.label, style = MaterialTheme.typography.titleSmall)
            Text(
                text = issue.severity.name,
                style = MaterialTheme.typography.labelSmall,
                color = severityColor,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = issue.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
        Spacer(Modifier.width(12.dp))
        OutlinedButton(onClick = { openRepairSettings(issue, context) }) {
            Text("Fix")
        }
    }
}

private fun openRepairSettings(issue: PermissionIssue, context: Context) {
    val intent = when (issue.id) {
        "accessibility" -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        "keyboard" -> Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
        "media_projection" -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        "overlay" -> Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        )
        "battery" -> {
            ManufacturerGuides.forCurrentDevice().tryLaunchBatteryDeepLink(context)
            return
        }
        "notifications" -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        }
        else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
    }
    context.startActivity(intent)
}
