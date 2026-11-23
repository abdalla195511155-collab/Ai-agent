package com.mantis.permissions

/**
 * Severity of a missing permission.
 *
 *  CRITICAL — agent cannot run at all (Accessibility, MediaProjection)
 *  DEGRADED — agent runs with reduced capability; amber warning shown
 */
enum class PermissionSeverity { CRITICAL, DEGRADED }

/**
 * A single permission that is not in the desired state.
 */
data class PermissionIssue(
    val id: String,
    val label: String,
    val severity: PermissionSeverity,
    val description: String,
)

/**
 * Overall health of the permission set required by MANTIS.
 */
sealed class HealthStatus {
    /** All permissions granted. */
    object Healthy : HealthStatus()

    /** No CRITICAL issues, but one or more DEGRADED permissions are missing. */
    data class Degraded(val issues: List<PermissionIssue>) : HealthStatus()

    /** At least one CRITICAL permission is missing. Agent cannot run. */
    data class Broken(val issues: List<PermissionIssue>) : HealthStatus()
}
