package com.novelforge.android.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.novelforge.android.ai.AiConfig
import com.novelforge.android.domain.Project
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "novelforge_settings")

/** Persistiert die KI-Anbieter-Konfiguration (Key prompt-frei in DataStore). */
class SettingsStore(private val context: Context) {
    private val keyBase = stringPreferencesKey("base_url")
    private val keyApi = stringPreferencesKey("api_key")
    private val keyModel = stringPreferencesKey("model")

    val configFlow: Flow<AiConfig> = context.dataStore.data.map { p ->
        AiConfig(
            baseUrl = p[keyBase] ?: AiConfig().baseUrl,
            apiKey = p[keyApi] ?: "",
            model = p[keyModel] ?: AiConfig().model,
        )
    }

    suspend fun save(config: AiConfig) {
        context.dataStore.edit { p ->
            p[keyBase] = config.baseUrl
            p[keyApi] = config.apiKey
            p[keyModel] = config.model
        }
    }
}

/** In-Memory-Projektablage (Room-Persistenz folgt). Prozessweit geteilt. */
object ProjectRepository {
    private val _projects = MutableStateFlow<List<Project>>(emptyList())
    val projects: StateFlow<List<Project>> = _projects.asStateFlow()

    fun upsert(project: Project) {
        val current = _projects.value.toMutableList()
        val idx = current.indexOfFirst { it.id == project.id }
        if (idx >= 0) current[idx] = project else current.add(0, project)
        _projects.value = current
    }

    fun get(id: String): Project? = _projects.value.firstOrNull { it.id == id }

    fun touch() {
        // Erzwingt eine Flow-Emission, wenn ein Projekt in-place mutiert wurde.
        _projects.value = _projects.value.toList()
    }
}
