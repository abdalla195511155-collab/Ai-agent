package com.mantis.ui.skills

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class SkillEntry(
    val id: String,
    val name: String,
    val description: String,
    val author: String,
    val version: String,
    val file: String,
)

class SkillStoreViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "SkillStoreViewModel"
        private const val REGISTRY_URL =
            "https://raw.githubusercontent.com/sajjad-vahdatzadeh/mantis-skills/main/registry.json"
        private const val BASE_URL =
            "https://raw.githubusercontent.com/sajjad-vahdatzadeh/mantis-skills/main"
    }

    private val httpClient = HttpClient(Android) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    private val _entries = MutableStateFlow<List<SkillEntry>>(emptyList())
    val entries: StateFlow<List<SkillEntry>> = _entries

    private val _installedIds = MutableStateFlow(loadInstalledIds())
    val installedIds: StateFlow<Set<String>> = _installedIds

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    /** Non-null when a snackbar error should be shown. Cleared after display. */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val installedDir: File
        get() = File(getApplication<Application>().filesDir, "skills/installed").also { it.mkdirs() }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Fetches the registry fresh from GitHub. */
    fun loadRegistry() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                val response = httpClient.get(REGISTRY_URL)
                if (response.status.isSuccess()) {
                    _entries.value = Json { ignoreUnknownKeys = true }
                        .decodeFromString<List<SkillEntry>>(response.bodyAsText())
                    Log.d(TAG, "Loaded ${_entries.value.size} skill(s) from registry")
                } else {
                    _error.value = "Registry unavailable (${response.status.value})"
                    Log.w(TAG, "Registry fetch failed: ${response.status}")
                }
            } catch (e: Exception) {
                _error.value = "Network error — check your connection"
                Log.e(TAG, "Registry fetch error", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    /** Downloads a skill's .md file and saves it to /files/skills/installed/. */
    fun install(entry: SkillEntry) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val fileUrl = "$BASE_URL/${entry.file}"
                val response = httpClient.get(fileUrl)
                if (response.status.isSuccess()) {
                    File(installedDir, "${entry.id}.md").writeText(response.bodyAsText())
                    _installedIds.value = loadInstalledIds()
                    Log.d(TAG, "Installed skill '${entry.name}'")
                } else {
                    _error.value = "Failed to install '${entry.name}' (${response.status.value})"
                }
            } catch (e: Exception) {
                _error.value = "Install failed — check your connection"
                Log.e(TAG, "Install error for '${entry.name}'", e)
            }
        }
    }

    /** Deletes a skill file from /files/skills/installed/. */
    fun uninstall(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            File(installedDir, "$id.md").delete()
            _installedIds.value = loadInstalledIds()
            Log.d(TAG, "Uninstalled skill id='$id'")
        }
    }

    /** Clears the current error so the snackbar is dismissed. */
    fun clearError() {
        _error.value = null
    }

    override fun onCleared() {
        super.onCleared()
        httpClient.close()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun loadInstalledIds(): Set<String> {
        val dir = File(getApplication<Application>().filesDir, "skills/installed")
        return dir.listFiles { f -> f.extension == "md" }
            ?.map { it.nameWithoutExtension }
            ?.toSet()
            ?: emptySet()
    }
}
