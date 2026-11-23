package com.mantis.ui.history

import kotlinx.serialization.Serializable

/** Persisted to `/files/history/[ISO_timestamp].json` when a task ends. */
@Serializable
data class TaskHistoryRecord(
    val task: String,
    val timestamp: String,         // ISO 8601, e.g. "2026-03-27T14:32:00Z"
    val outcome: String,           // "complete" | "stuck" | "error"
    val steps: Int,
    val summary: String,           // human-readable outcome sentence
)
