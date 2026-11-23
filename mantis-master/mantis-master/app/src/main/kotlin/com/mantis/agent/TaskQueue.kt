package com.mantis.agent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory FIFO queue for pending agent tasks.
 * Phase 7 (ProactiveScheduler) feeds into this queue.
 * Phase 6 UI observes [pending] to display the queue.
 */
class TaskQueue {

    private val _pending = MutableStateFlow<List<String>>(emptyList())

    /** Observable list of tasks waiting to run. Front of list = next to execute. */
    val pending: StateFlow<List<String>> = _pending.asStateFlow()

    /** Add a task to the back of the queue. */
    fun enqueue(task: String) {
        _pending.value = _pending.value + task
    }

    /** Remove and return the front task, or null if the queue is empty. */
    fun dequeue(): String? {
        val current = _pending.value
        if (current.isEmpty()) return null
        _pending.value = current.drop(1)
        return current.first()
    }

    /** Discard all pending tasks. */
    fun clear() {
        _pending.value = emptyList()
    }
}
