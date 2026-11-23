package com.mantis.scheduler

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * A single scheduled task — stored as /files/scheduled/{id}.json.
 * schedule = "daily_at" only for v1 (weekly/interval in v2).
 */
@Serializable
data class ScheduledTask(
    val id: String,
    val name: String,
    val task: String,
    val schedule: String = "daily_at",
    val time: String,           // "HH:mm" in 24-hour format
    val timezone: String,       // e.g. "Asia/Dubai"
    val enabled: Boolean = true,
    val lastRun: String? = null,
    val notifyOnComplete: Boolean = true,
) {
    fun save(context: Context) {
        scheduledDir(context).mkdirs()
        scheduledFile(context).writeText(json.encodeToString(this))
    }

    fun delete(context: Context) {
        scheduledFile(context).delete()
    }

    private fun scheduledFile(context: Context): File =
        File(scheduledDir(context), "$id.json")

    companion object {
        private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

        fun create(
            name: String,
            task: String,
            time: String,
            timezone: String,
            enabled: Boolean = true,
            notifyOnComplete: Boolean = true,
        ): ScheduledTask = ScheduledTask(
            id = UUID.randomUUID().toString(),
            name = name,
            task = task,
            time = time,
            timezone = timezone,
            enabled = enabled,
            notifyOnComplete = notifyOnComplete,
        )

        fun loadAll(context: Context): List<ScheduledTask> {
            val dir = scheduledDir(context)
            if (!dir.exists()) return emptyList()
            return dir.listFiles { f -> f.extension == "json" }
                ?.mapNotNull { file ->
                    runCatching { json.decodeFromString<ScheduledTask>(file.readText()) }.getOrNull()
                }
                ?.sortedBy { it.name }
                ?: emptyList()
        }

        private fun scheduledDir(context: Context): File =
            File(context.filesDir, "scheduled")
    }
}
