package com.novelforge.android.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.novelforge.android.ai.AiConfig
import com.novelforge.android.data.ProjectRepository
import com.novelforge.android.data.SettingsStore
import com.novelforge.android.domain.Project
import com.novelforge.android.domain.ProjectStatus
import com.novelforge.android.generator.GenProgress
import com.novelforge.android.generator.NovelGenerator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = SettingsStore(app)

    val config: StateFlow<AiConfig> =
        settings.configFlow.stateIn(viewModelScope, SharingStarted.Eagerly, AiConfig())

    val projects: StateFlow<List<Project>> = ProjectRepository.projects

    private val _progress = MutableStateFlow<GenProgress?>(null)
    val progress: StateFlow<GenProgress?> = _progress

    private val _generatingId = MutableStateFlow<String?>(null)
    val generatingId: StateFlow<String?> = _generatingId

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    fun saveSettings(config: AiConfig) {
        viewModelScope.launch { settings.save(config) }
    }

    fun clearError() { _error.value = null }

    /** Legt ein Projekt an und startet die Generierung. Gibt die Projekt-ID zurück. */
    fun createAndGenerate(project: Project): String {
        ProjectRepository.upsert(project)
        viewModelScope.launch {
            _error.value = null
            _generatingId.value = project.id
            try {
                val cfg = settings.configFlow.first()
                NovelGenerator(cfg).generate(project) { p ->
                    _progress.value = p
                    ProjectRepository.touch()
                }
            } catch (e: Exception) {
                project.status = ProjectStatus.FAILED
                _error.value = e.message ?: "Unbekannter Fehler"
            } finally {
                ProjectRepository.touch()
                _generatingId.value = null
                _progress.value = null
            }
        }
        return project.id
    }
}
