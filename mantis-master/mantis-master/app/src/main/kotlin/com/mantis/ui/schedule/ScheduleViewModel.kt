package com.mantis.ui.schedule

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mantis.scheduler.ProactiveScheduler
import com.mantis.scheduler.ScheduledTask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.TimeZone

class ScheduleViewModel(app: Application) : AndroidViewModel(app) {

    private val _tasks = MutableStateFlow<List<ScheduledTask>>(emptyList())
    val tasks: StateFlow<List<ScheduledTask>> = _tasks.asStateFlow()

    init {
        loadTasks()
    }

    fun loadTasks() {
        viewModelScope.launch(Dispatchers.IO) {
            _tasks.value = ScheduledTask.loadAll(getApplication())
        }
    }

    fun addTask(name: String, task: String, hour: Int, minute: Int, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val scheduled = ScheduledTask.create(
                name = name,
                task = task,
                time = "%02d:%02d".format(hour, minute),
                timezone = TimeZone.getDefault().id,
                enabled = enabled,
            )
            scheduled.save(getApplication())
            if (enabled) ProactiveScheduler.schedule(getApplication(), scheduled)
            loadTasks()
        }
    }

    fun updateTask(
        existing: ScheduledTask,
        name: String,
        task: String,
        hour: Int,
        minute: Int,
        enabled: Boolean,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            // Cancel the old schedule before replacing it.
            ProactiveScheduler.cancel(getApplication(), existing.id)
            val updated = existing.copy(
                name = name,
                task = task,
                time = "%02d:%02d".format(hour, minute),
                // Refresh to device's current timezone so edits made after travelling
                // fire at the correct local time in the new location.
                timezone = TimeZone.getDefault().id,
                enabled = enabled,
            )
            updated.save(getApplication())
            if (enabled) ProactiveScheduler.schedule(getApplication(), updated)
            loadTasks()
        }
    }

    fun deleteTask(task: ScheduledTask) {
        viewModelScope.launch(Dispatchers.IO) {
            ProactiveScheduler.cancel(getApplication(), task.id)
            task.delete(getApplication())
            loadTasks()
        }
    }
}
