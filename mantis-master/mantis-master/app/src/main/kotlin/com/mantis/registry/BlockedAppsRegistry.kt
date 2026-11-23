package com.mantis.registry

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persists and queries apps that block accessibility inspection.
 *
 * Some apps (banking, payment, enterprise) detect accessibility services
 * and either blank their UI tree or show a security warning screen. MANTIS
 * detects this during task execution and marks the app blocked so the agent
 * can fail fast instead of spinning on an empty snapshot.
 *
 * Detection heuristic (called from AgentBridge before acting on a snapshot):
 *  - Snapshot element list is empty, OR
 *  - Any element's text or contentDescription contains a known block phrase
 *    (case-insensitive): "accessibility", "screen reader", "security policy",
 *    "cannot be used with"
 *
 * Storage: [Context.getFilesDir]/blocked_apps.json
 * Schema: `{ "blocked": [ { "package": "...", "name": "...", "blockedAt": 12345 } ] }`
 *
 * Thread safety: reads and writes are synchronised on this instance. All
 * mutations flush immediately to disk so the registry survives process kill.
 *
 * Instantiate once in [com.mantis.MantisApp] and share via the application singleton.
 */
class BlockedAppsRegistry(context: Context) {

    companion object {
        private const val TAG = "BlockedAppsRegistry"
        private const val FILE_NAME = "blocked_apps.json"

        /** Phrases that indicate an app has blocked accessibility access. */
        private val BLOCK_PHRASES = listOf(
            "accessibility",
            "screen reader",
            "security policy",
            "cannot be used with",
        )
    }

    private val file: File = File(context.applicationContext.filesDir, FILE_NAME)

    // In-memory cache: packageName → entry. Guarded by `this`.
    private val cache = mutableMapOf<String, BlockedAppEntry>()

    init {
        loadFromDisk()
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns true if [packageName] has previously been flagged as blocked.
     */
    @Synchronized
    fun isBlocked(packageName: String): Boolean = cache.containsKey(packageName)

    /**
     * Marks [packageName] as blocked and persists the entry immediately.
     * Safe to call multiple times for the same package — subsequent calls are no-ops.
     */
    @Synchronized
    fun markBlocked(packageName: String, appName: String) {
        if (cache.containsKey(packageName)) return
        val entry = BlockedAppEntry(
            packageName = packageName,
            appName = appName,
            blockedAt = System.currentTimeMillis(),
        )
        cache[packageName] = entry
        saveToDisk()
        Log.i(TAG, "Marked blocked: $packageName ($appName)")
    }

    /**
     * Checks whether [snapshot] indicates that the foreground app is blocking
     * accessibility inspection.
     *
     * Returns true if:
     *  - The element list is empty (UI tree was withheld), OR
     *  - Any element contains a known [BLOCK_PHRASES] string
     *
     * Callers should call [markBlocked] if this returns true.
     */
    fun looksBlocked(snapshot: com.mantis.bridge.UiSnapshot): Boolean {
        if (snapshot.elements.isEmpty()) return true
        for (element in snapshot.elements) {
            val combined = (element.text + " " + element.contentDescription).lowercase()
            if (BLOCK_PHRASES.any { combined.contains(it) }) return true
        }
        return false
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    private fun loadFromDisk() {
        if (!file.exists()) return
        try {
            val json = JSONObject(file.readText())
            val array = json.optJSONArray("blocked") ?: return
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val entry = BlockedAppEntry(
                    packageName = obj.getString("package"),
                    appName = obj.optString("name", ""),
                    blockedAt = obj.optLong("blockedAt", 0L),
                )
                cache[entry.packageName] = entry
            }
            Log.d(TAG, "Loaded ${cache.size} blocked app(s) from disk")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load blocked_apps.json — starting fresh", e)
        }
    }

    private fun saveToDisk() {
        try {
            val array = JSONArray()
            for (entry in cache.values) {
                array.put(JSONObject().apply {
                    put("package", entry.packageName)
                    put("name", entry.appName)
                    put("blockedAt", entry.blockedAt)
                })
            }
            val json = JSONObject().apply { put("blocked", array) }
            file.writeText(json.toString(2))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save blocked_apps.json", e)
        }
    }
}

/** Immutable record of a blocked app. */
private data class BlockedAppEntry(
    val packageName: String,
    val appName: String,
    val blockedAt: Long,
)
