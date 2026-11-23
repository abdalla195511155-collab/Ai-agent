package com.mantis.ui.schedule

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mantis.scheduler.ScheduledTask
import com.mantis.ui.MantisTopBar
import java.time.ZoneId
import java.time.ZonedDateTime

private val CardBg          = Color(0xFFF3F3F3)
private val BorderIdle      = Color(0xFFF3F3F3)
private val BorderFocused   = Color(0xFF31AA50)
private val MantisGreen     = Color(0xFF3DBB5E)
private val TextDark        = Color(0xFF1A1A1A)
private val TextSub         = Color(0xFF888888)
private val SwitchThumbOff  = Color(0xFF888888)
private val SwitchTrackOff  = Color(0xFFD0D0D0)

@Composable
fun ScheduleScreen(onMenuClick: () -> Unit = {}, vm: ScheduleViewModel = viewModel()) {
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    var showAddDialog by rememberSaveable { mutableStateOf(false) }
    var editingTask by remember { mutableStateOf<ScheduledTask?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            MantisTopBar(title = "Schedule", onMenuClick = onMenuClick)

            if (tasks.isEmpty()) {
                EmptyScheduleHint(modifier = Modifier.weight(1f))
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(tasks, key = { it.id }) { task ->
                        TaskRow(
                            task = task,
                            onToggleEnabled = { enabled ->
                                vm.updateTask(task, task.name, task.task,
                                    task.time.hour, task.time.minute, enabled)
                            },
                            onClick = { editingTask = task },
                        )
                    }
                }
            }
        }

        // ── FAB ───────────────────────────────────────────────────────────────
        Surface(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(24.dp)
                .size(56.dp)
                .clickable { showAddDialog = true },
            shape = CircleShape,
            color = CardBg,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = "Add scheduled task",
                    tint = TextDark,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }

    if (showAddDialog) {
        ScheduleDialog(
            existingTask = null,
            onSave = { name, task, hour, minute, enabled ->
                vm.addTask(name, task, hour, minute, enabled)
                showAddDialog = false
            },
            onDelete = null,
            onDismiss = { showAddDialog = false },
        )
    }

    editingTask?.let { task ->
        ScheduleDialog(
            existingTask = task,
            onSave = { name, taskDesc, hour, minute, enabled ->
                vm.updateTask(task, name, taskDesc, hour, minute, enabled)
                editingTask = null
            },
            onDelete = {
                vm.deleteTask(task)
                editingTask = null
            },
            onDismiss = { editingTask = null },
        )
    }
}

// ── Task row ──────────────────────────────────────────────────────────────────

@Composable
private fun TaskRow(
    task: ScheduledTask,
    onToggleEnabled: (Boolean) -> Unit,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = CardBg,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = TextDark,
                )
                Spacer(Modifier.height(2.dp))
                val (mainTime, originNote) = localTimeDisplay(task)
                Text(
                    text = mainTime,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSub,
                )
                if (originNote != null) {
                    Text(
                        text = originNote,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSub.copy(alpha = 0.7f),
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Switch(
                checked = task.enabled,
                onCheckedChange = onToggleEnabled,
                colors = SwitchDefaults.colors(
                    checkedThumbColor     = Color.White,
                    checkedTrackColor     = MantisGreen,
                    uncheckedThumbColor   = SwitchThumbOff,
                    uncheckedTrackColor   = SwitchTrackOff,
                    uncheckedBorderColor  = SwitchTrackOff,
                ),
            )
        }
    }
}

// ── Add / Edit dialog ─────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduleDialog(
    existingTask: ScheduledTask?,
    onSave: (name: String, task: String, hour: Int, minute: Int, enabled: Boolean) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val initialHour   = existingTask?.time?.hour ?: 8
    val initialMinute = existingTask?.time?.minute ?: 0

    var name            by rememberSaveable { mutableStateOf(existingTask?.name ?: "") }
    var taskDesc        by rememberSaveable { mutableStateOf(existingTask?.task ?: "") }
    var enabled         by rememberSaveable { mutableStateOf(existingTask?.enabled ?: false) }
    var selectedHour    by rememberSaveable { mutableIntStateOf(initialHour) }
    var selectedMinute  by rememberSaveable { mutableIntStateOf(initialMinute) }
    var showTimePicker  by rememberSaveable { mutableStateOf(false) }
    var showDeleteConfirm by rememberSaveable { mutableStateOf(false) }

    var nameFocused by remember { mutableStateOf(false) }
    var taskFocused by remember { mutableStateOf(false) }

    val isValid = name.isNotBlank() && taskDesc.isNotBlank()

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = Color.White,
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // ── Title + optional delete ───────────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (existingTask == null) "Add a Scheduled Task" else "Edit Scheduled Task",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = TextDark,
                        modifier = Modifier.weight(1f),
                    )
                    if (onDelete != null) {
                        IconButton(onClick = { showDeleteConfirm = true }) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = "Delete task",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }

                // ── Name field (pill shape, animated border) ──────────────────
                val nameBorder by animateColorAsState(
                    targetValue = if (nameFocused) BorderFocused else BorderIdle,
                    animationSpec = tween(200),
                    label = "nameBorder",
                )
                Surface(
                    shape = RoundedCornerShape(50.dp),
                    color = Color.White,
                    border = BorderStroke(2.dp, nameBorder),
                ) {
                    BasicTextField(
                        value = name,
                        onValueChange = { name = it },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = TextDark),
                        cursorBrush = SolidColor(BorderFocused),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 14.dp)
                            .onFocusChanged { nameFocused = it.isFocused },
                        decorationBox = { innerTextField ->
                            if (name.isEmpty()) {
                                Text("Name", color = TextSub,
                                    style = MaterialTheme.typography.bodyLarge)
                            }
                            innerTextField()
                        },
                    )
                }

                // ── Task description (rounded rect, animated border) ──────────
                val taskBorder by animateColorAsState(
                    targetValue = if (taskFocused) BorderFocused else BorderIdle,
                    animationSpec = tween(200),
                    label = "taskBorder",
                )
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color.White,
                    border = BorderStroke(2.dp, taskBorder),
                ) {
                    BasicTextField(
                        value = taskDesc,
                        onValueChange = { taskDesc = it },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = TextDark),
                        cursorBrush = SolidColor(BorderFocused),
                        minLines = 4,
                        maxLines = 6,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp)
                            .onFocusChanged { taskFocused = it.isFocused },
                        decorationBox = { innerTextField ->
                            if (taskDesc.isEmpty()) {
                                Text("What Should Mantis do?", color = TextSub,
                                    style = MaterialTheme.typography.bodyLarge)
                            }
                            innerTextField()
                        },
                    )
                }

                // ── Run at ────────────────────────────────────────────────────
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showTimePicker = true }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "Run at",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = TextDark,
                    )
                    Text(
                        text = "%02d:%02d".format(selectedHour, selectedMinute),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MantisGreen,
                    )
                }

                // ── Enabled toggle ────────────────────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "Enabled?",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = TextDark,
                    )
                    Switch(
                        checked = enabled,
                        onCheckedChange = { enabled = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor    = Color.White,
                            checkedTrackColor    = MantisGreen,
                            uncheckedThumbColor  = SwitchThumbOff,
                            uncheckedTrackColor  = SwitchTrackOff,
                            uncheckedBorderColor = SwitchTrackOff,
                        ),
                    )
                }

                // ── Action buttons ────────────────────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(
                            "Cancel",
                            color = Color(0xFFE53935),
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    TextButton(
                        onClick = {
                            if (isValid) onSave(
                                name.trim(), taskDesc.trim(),
                                selectedHour, selectedMinute, enabled,
                            )
                        },
                        enabled = isValid,
                    ) {
                        Text(
                            "Save",
                            color = if (isValid) TextDark else TextSub,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }

    // ── Time picker dialog ────────────────────────────────────────────────────
    if (showTimePicker) {
        TimePickerDialog(
            initialHour = selectedHour,
            initialMinute = selectedMinute,
            onConfirm = { h, m ->
                selectedHour = h
                selectedMinute = m
                showTimePicker = false
            },
            onDismiss = { showTimePicker = false },
        )
    }

    // ── Delete confirmation ───────────────────────────────────────────────────
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete task?") },
            text = { Text("\"$name\" will be permanently deleted and its schedule cancelled.") },
            confirmButton = {
                TextButton(onClick = { onDelete?.invoke() }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            },
        )
    }
}

// ── Time picker dialog ────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerDialog(
    initialHour: Int,
    initialMinute: Int,
    onConfirm: (hour: Int, minute: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberTimePickerState(
        initialHour = initialHour,
        initialMinute = initialMinute,
        is24Hour = true,
    )
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = Color.White,
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("Pick a time", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold, color = TextDark)
                TimePicker(state = state)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel", color = Color(0xFFE53935))
                    }
                    Spacer(Modifier.width(4.dp))
                    TextButton(onClick = { onConfirm(state.hour, state.minute) }) {
                        Text("OK", color = TextDark, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

// ── Empty state ───────────────────────────────────────────────────────────────

@Composable
private fun EmptyScheduleHint(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "No scheduled tasks yet",
                style = MaterialTheme.typography.bodyLarge,
                color = TextSub,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Tap + to add one",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSub,
            )
        }
    }
}

// ── Timezone display helpers ──────────────────────────────────────────────────

private fun localTimeDisplay(task: ScheduledTask): Pair<String, String?> {
    val deviceZone = ZoneId.systemDefault()
    val taskZone = runCatching { ZoneId.of(task.timezone) }.getOrElse { deviceZone }

    if (taskZone == deviceZone) {
        return Pair("Daily at ${task.time}", null)
    }

    val (h, m) = task.time.split(":").map { it.toIntOrNull() ?: 0 }
    val taskInstant = ZonedDateTime.now(taskZone)
        .withHour(h).withMinute(m).withSecond(0).withNano(0)
    val localTime = taskInstant.withZoneSameInstant(deviceZone)
    val localFormatted = "%02d:%02d".format(localTime.hour, localTime.minute)
    val locationName = task.timezone.substringAfterLast("/").replace("_", " ")

    return Pair("Daily at $localFormatted", "${task.time} $locationName time")
}

// ── Extension helpers ─────────────────────────────────────────────────────────

private val String.hour: Int get() = split(":").getOrNull(0)?.toIntOrNull() ?: 8
private val String.minute: Int get() = split(":").getOrNull(1)?.toIntOrNull() ?: 0
