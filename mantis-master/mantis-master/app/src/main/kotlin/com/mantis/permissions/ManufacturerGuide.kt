package com.mantis.permissions

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * Manufacturer-specific instructions for granting permissions that require
 * navigating outside the standard Android settings path.
 *
 * [batteryDeepLinkPackage] — the package name of the OEM battery management app.
 * When non-null, [tryLaunchBatteryDeepLink] attempts to open it directly;
 * falls back to the standard Android battery optimization screen on failure.
 *
 * Used by onboarding screens and PermissionRepairScreen.
 */
data class ManufacturerGuide(
    val manufacturer: String,
    val accessibilitySteps: List<String>,
    val batterySteps: List<String>,
    val overlaySteps: List<String>,
    val batteryDeepLinkPackage: String? = null,
) {
    /**
     * Tries to open the OEM battery management screen directly.
     * Falls back to [Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS] on any failure.
     */
    fun tryLaunchBatteryDeepLink(context: Context) {
        val launched = batteryDeepLinkPackage?.let { pkg ->
            try {
                val intent = context.packageManager.getLaunchIntentForPackage(pkg)
                if (intent != null) {
                    context.startActivity(intent)
                    true
                } else {
                    false
                }
            } catch (e: Exception) {
                Log.w("ManufacturerGuide", "Deep-link launch failed for $pkg", e)
                false
            }
        } ?: false

        if (!launched) {
            try {
                context.startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:${context.packageName}"),
                    )
                )
            } catch (e: Exception) {
                Log.w("ManufacturerGuide", "Fallback battery intent failed", e)
                context.startActivity(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS))
            }
        }
    }
}

object ManufacturerGuides {

    fun forCurrentDevice(): ManufacturerGuide =
        when (Build.MANUFACTURER.lowercase()) {
            "samsung" -> samsung
            "xiaomi", "redmi", "poco" -> xiaomi
            "huawei", "honor" -> huawei
            "oneplus" -> oneplus
            "oppo" -> oppo
            "vivo" -> vivo
            else -> stockAndroid
        }

    // ── Samsung (OneUI) ───────────────────────────────────────────────────────

    private val samsung = ManufacturerGuide(
        manufacturer = "Samsung",
        batteryDeepLinkPackage = "com.samsung.android.lool",
        accessibilitySteps = listOf(
            "Open Settings",
            "Tap Accessibility",
            "Tap Installed apps",
            "Tap MANTIS",
            "Enable MANTIS Accessibility Service",
            "Tap Allow on the confirmation dialog",
        ),
        batterySteps = listOf(
            "Open Device Care (tap the shortcut that opens now)",
            "Tap Battery",
            "Tap Background usage limits",
            "Tap Never sleeping apps",
            "Tap the + button and select MANTIS",
        ),
        overlaySteps = listOf(
            "Open Settings",
            "Tap Apps",
            "Tap MANTIS",
            "Tap Permissions",
            "Tap Appear on top",
            "Enable the toggle",
        ),
    )

    // ── Xiaomi / MIUI ─────────────────────────────────────────────────────────

    private val xiaomi = ManufacturerGuide(
        manufacturer = "Xiaomi",
        batteryDeepLinkPackage = "com.miui.powerkeeper",
        accessibilitySteps = listOf(
            "Open Settings",
            "Tap Additional settings",
            "Tap Accessibility",
            "Tap MANTIS",
            "Enable MANTIS Accessibility Service",
        ),
        batterySteps = listOf(
            "Open Power Keeper (tap the shortcut that opens now)",
            "Tap App battery saver",
            "Tap MANTIS",
            "Select No restrictions",
        ),
        overlaySteps = listOf(
            "Open Settings",
            "Tap Apps",
            "Tap Manage apps",
            "Tap MANTIS",
            "Tap Other permissions",
            "Enable Display pop-up windows while running in the background",
        ),
    )

    // ── Huawei / EMUI ─────────────────────────────────────────────────────────

    private val huawei = ManufacturerGuide(
        manufacturer = "Huawei",
        batteryDeepLinkPackage = "com.huawei.systemmanager",
        accessibilitySteps = listOf(
            "Open Settings",
            "Tap Accessibility features",
            "Tap Accessibility",
            "Tap MANTIS",
            "Enable MANTIS Accessibility Service",
        ),
        batterySteps = listOf(
            "Open Phone Manager (tap the shortcut that opens now)",
            "Tap App launch",
            "Find MANTIS and switch it to Manage manually",
            "Enable Auto-launch, Secondary launch, and Run in background",
        ),
        overlaySteps = listOf(
            "Open Settings",
            "Tap Apps",
            "Tap Apps",
            "Tap MANTIS",
            "Tap Permissions",
            "Enable Display over other apps",
        ),
    )

    // ── OnePlus / OxygenOS ────────────────────────────────────────────────────

    private val oneplus = ManufacturerGuide(
        manufacturer = "OnePlus",
        batteryDeepLinkPackage = "com.oneplus.opti",
        accessibilitySteps = listOf(
            "Open Settings",
            "Tap Accessibility",
            "Tap MANTIS",
            "Enable MANTIS Accessibility Service",
        ),
        batterySteps = listOf(
            "Open Battery (tap the shortcut that opens now)",
            "Tap Battery optimization",
            "Tap All apps",
            "Tap MANTIS",
            "Select Don't optimize",
        ),
        overlaySteps = listOf(
            "Open Settings",
            "Tap Apps",
            "Tap MANTIS",
            "Tap Display over other apps",
            "Enable the toggle",
        ),
    )

    // ── Oppo / ColorOS ────────────────────────────────────────────────────────

    private val oppo = ManufacturerGuide(
        manufacturer = "Oppo",
        batteryDeepLinkPackage = "com.coloros.opti",
        accessibilitySteps = listOf(
            "Open Settings",
            "Tap Additional Settings",
            "Tap Accessibility",
            "Tap MANTIS",
            "Enable MANTIS Accessibility Service",
        ),
        batterySteps = listOf(
            "Open Battery (tap the shortcut that opens now)",
            "Tap Battery optimization",
            "Tap All apps",
            "Tap MANTIS",
            "Select Don't optimize",
        ),
        overlaySteps = listOf(
            "Open Settings",
            "Tap Apps",
            "Tap App management",
            "Tap MANTIS",
            "Tap Display over other apps",
            "Enable the toggle",
        ),
    )

    // ── Vivo / FuntouchOS ─────────────────────────────────────────────────────

    private val vivo = ManufacturerGuide(
        manufacturer = "Vivo",
        batteryDeepLinkPackage = "com.vivo.abe",
        accessibilitySteps = listOf(
            "Open Settings",
            "Tap Accessibility features",
            "Tap Accessibility",
            "Tap MANTIS",
            "Enable MANTIS Accessibility Service",
        ),
        batterySteps = listOf(
            "Open App Battery Manager (tap the shortcut that opens now)",
            "Find and tap MANTIS",
            "Select No restriction",
        ),
        overlaySteps = listOf(
            "Open Settings",
            "Tap Apps",
            "Tap MANTIS",
            "Tap Display over other apps",
            "Enable the toggle",
        ),
    )

    // ── Stock Android ─────────────────────────────────────────────────────────

    private val stockAndroid = ManufacturerGuide(
        manufacturer = "Android",
        accessibilitySteps = listOf(
            "Open Settings",
            "Tap Accessibility",
            "Scroll down to \"Downloaded apps\"",
            "Tap MANTIS",
            "Tap \"Use MANTIS\" and confirm",
        ),
        batterySteps = listOf(
            "Open Settings",
            "Tap Apps",
            "Tap MANTIS",
            "Tap Battery",
            "Select Unrestricted",
        ),
        overlaySteps = listOf(
            "Open Settings",
            "Tap Apps",
            "Tap MANTIS",
            "Tap Display over other apps",
            "Enable the toggle",
        ),
    )
}
