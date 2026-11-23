package com.mantis.memory

import android.content.Context
import android.util.Log
import com.mantis.llm.LLMConnector
import com.mantis.storage.SecureConfig
import java.io.File

/**
 * Manages the on-device user memory file at /files/memory/user_context.md.
 *
 * The memory file is a plain Markdown document that accumulates facts about
 * the user across sessions. It is injected into the AgentLoop system prompt
 * so MANTIS already knows the user's name, habits, and preferences without
 * being told each time.
 *
 * Size is capped at 4,000 words. Step 2 (summarization) will auto-shrink
 * the file when it exceeds that limit.
 *
 * Hold a single instance on [com.mantis.MantisApp] — do not create multiple instances.
 */
class MemoryManager(private val context: Context) {

    companion object {
        private const val TAG = "MemoryManager"
        private const val MEMORY_DIR = "memory"
        private const val MEMORY_FILE = "user_context.md"
        const val MAX_WORDS = 4000
    }

    private val memoryFile: File
        get() = File(context.filesDir, "$MEMORY_DIR/$MEMORY_FILE")

    init {
        // Create the directory and skeleton file eagerly on first install
        // so the memory folder is visible immediately in Device Explorer.
        if (!memoryFile.exists()) {
            try {
                ensureDir()
                memoryFile.writeText(initialStructure())
                Log.d(TAG, "init: created initial memory file")
            } catch (e: Exception) {
                Log.e(TAG, "init: failed to create initial memory file", e)
            }
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns the full content of the memory file, or an empty string if it
     * does not exist yet. Never throws.
     */
    fun load(): String {
        return try {
            if (memoryFile.exists()) memoryFile.readText() else ""
        } catch (e: Exception) {
            Log.e(TAG, "load: failed to read memory file", e)
            ""
        }
    }

    /**
     * Overwrites the memory file with [content].
     * Called by the Settings screen when the user edits memory inline,
     * and by the summarizer after condensing an oversized file.
     */
    fun save(content: String) {
        try {
            ensureDir()
            memoryFile.writeText(content)
            Log.d(TAG, "save: ${wordCount()} words")
        } catch (e: Exception) {
            Log.e(TAG, "save: failed to write memory file", e)
        }
    }

    /**
     * Appends [text] followed by a blank line to the memory file, then checks
     * whether the file has grown past [MAX_WORDS] and auto-summarizes if so.
     *
     * [connector] is used only when summarization is needed — passing it here
     * (rather than in the constructor) keeps the class testable without a real LLM.
     */
    suspend fun append(connector: LLMConnector, text: String) {
        try {
            ensureDir()
            if (!memoryFile.exists()) {
                memoryFile.writeText(initialStructure())
            }
            memoryFile.appendText("\n$text\n")
            Log.d(TAG, "append: now ${wordCount()} words")
            checkAndSummarize(connector)
        } catch (e: Exception) {
            Log.e(TAG, "append: failed to append to memory file", e)
        }
    }

    /**
     * If the memory file exceeds [MAX_WORDS], calls Haiku to condense it to ≤ 2,000 words
     * while preserving all section headings and important facts.
     * Replaces the file in-place with the summary.
     */
    private suspend fun checkAndSummarize(connector: LLMConnector) {
        val count = wordCount()
        if (count <= MAX_WORDS) return

        Log.d(TAG, "checkAndSummarize: $count words exceeds $MAX_WORDS — summarizing")
        try {
            val current = load()
            val summary = connector.complete(
                systemPrompt = "You are a memory compressor for a personal AI agent. " +
                    "Summarize the user memory file, keeping all important preferences, " +
                    "habits, and learned patterns. Return ONLY the updated markdown. " +
                    "Max 2,000 words. Preserve the section headings exactly.",
                userMessage = current,
            )
            save(summary)
            Log.d(TAG, "checkAndSummarize: compressed to ${wordCount()} words")
        } catch (e: Exception) {
            Log.e(TAG, "checkAndSummarize: summarization failed — keeping original", e)
        }
    }

    /**
     * Deletes the memory file entirely.
     * Called from Settings → Memory → Clear.
     */
    fun clear() {
        try {
            if (memoryFile.exists()) {
                memoryFile.delete()
                Log.d(TAG, "clear: memory file deleted")
            }
        } catch (e: Exception) {
            Log.e(TAG, "clear: failed to delete memory file", e)
        }
    }

    /**
     * Returns the number of whitespace-separated tokens in the memory file.
     * Returns 0 if the file does not exist.
     */
    fun wordCount(): Int {
        val content = load()
        if (content.isBlank()) return 0
        return content.trim().split(Regex("\\s+")).size
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun ensureDir() {
        File(context.filesDir, MEMORY_DIR).mkdirs()
    }

    /**
     * Returns the skeleton Markdown structure written on first use.
     * The user's name is read from [SecureConfig] at creation time.
     */
    private fun initialStructure(): String {
        val name = try {
            SecureConfig(context).name.ifBlank { "the user" }
        } catch (e: Exception) {
            "the user"
        }
        return """
            |# About $name
            |
            |## Personal
            |
            |## Habits & Preferences
            |
            |## Frequent Tasks
            |
            |## Learned Patterns
            |
            |## Last Updated
            |
        """.trimMargin()
    }
}
