package com.mantis.ui.history

import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mantis.ui.MantisTopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val TAG = "TaskHistoryScreen"

private val lenientJson = Json { ignoreUnknownKeys = true }

private val timestampFormatter = DateTimeFormatter
    .ofPattern("MMM d, yyyy · h:mm a")
    .withZone(ZoneId.systemDefault())

private val CardBg    = Color(0xFFF3F3F3)
private val TextDark  = Color(0xFF1A1A1A)
private val TextSub   = Color(0xFF888888)
private val BadgeDone = Color(0xFF3DBB5E)
private val BadgeError = Color(0xFFE53935)
private val BadgeStuck = Color(0xFFFF9800)

@Composable
fun TaskHistoryScreen(onMenuClick: () -> Unit = {}) {
    val context = LocalContext.current

    val records by produceState<List<TaskHistoryRecord>>(initialValue = emptyList()) {
        value = withContext(Dispatchers.IO) { loadHistory(context.filesDir) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        MantisTopBar(title = "History", onMenuClick = onMenuClick)

        if (records.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "No tasks yet",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = TextDark,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Complete a task to see it here",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSub,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(records, key = { it.timestamp }) { record ->
                    HistoryCard(record = record)
                }
            }
        }
    }
}

// ── History card ──────────────────────────────────────────────────────────────

@Composable
private fun HistoryCard(record: TaskHistoryRecord) {
    var expanded by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        shape = RoundedCornerShape(16.dp),
        color = CardBg,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = record.task,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = TextDark,
                    maxLines = if (expanded) Int.MAX_VALUE else 1,
                    overflow = if (expanded) TextOverflow.Clip else TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 12.dp),
                )
                OutcomeBadge(outcome = record.outcome)
            }

            Spacer(Modifier.height(4.dp))

            Text(
                text = buildString {
                    append(formatTimestamp(record.timestamp))
                    append(" • ")
                    append("${record.steps} step${if (record.steps == 1) "" else "s"}")
                },
                style = MaterialTheme.typography.bodySmall,
                color = TextSub,
            )

            if (expanded && record.summary.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color.White,
                ) {
                    Text(
                        text = record.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSub,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

// ── Outcome badge ─────────────────────────────────────────────────────────────

@Composable
private fun OutcomeBadge(outcome: String) {
    val (label, color) = when (outcome) {
        "complete" -> "Done"  to BadgeDone
        "stuck"    -> "Stuck" to BadgeStuck
        "error"    -> "Error" to BadgeError
        else       -> outcome.replaceFirstChar { it.uppercase() } to Color(0xFF9E9E9E)
    }
    Surface(
        shape = RoundedCornerShape(50.dp),
        color = color,
    ) {
        Text(
            text = label,
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

// ── File I/O ──────────────────────────────────────────────────────────────────

private fun loadHistory(filesDir: File): List<TaskHistoryRecord> {
    val dir = File(filesDir, "history")
    if (!dir.exists()) return emptyList()

    return dir.listFiles { f -> f.name.endsWith(".json") && !f.name.endsWith("_state.json") }
        ?.mapNotNull { file ->
            try {
                lenientJson.decodeFromString<TaskHistoryRecord>(file.readText())
            } catch (e: Exception) {
                Log.w(TAG, "Skipping malformed history file: ${file.name}", e)
                null
            }
        }
        ?.sortedByDescending { runCatching { Instant.parse(it.timestamp) }.getOrNull() }
        ?: emptyList()
}

private fun formatTimestamp(iso: String): String = try {
    timestampFormatter.format(Instant.parse(iso))
} catch (_: Exception) {
    iso
}
