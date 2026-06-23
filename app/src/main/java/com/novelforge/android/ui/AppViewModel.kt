package com.novelforge.android.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.novelforge.android.ai.AiConfig
import com.novelforge.android.data.ProjectRepository
import com.novelforge.android.data.SettingsStore
import com.novelforge.android.domain.Project
import com.novelforge.android.domain.SeriesContext
import com.novelforge.android.generator.GenProgress
import com.novelforge.android.generator.GenerationController
import com.novelforge.android.service.GenerationService
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = SettingsStore(app)

    val config: StateFlow<AiConfig> =
        settings.configFlow.stateIn(viewModelScope, SharingStarted.Eagerly, AiConfig())

    val projects: StateFlow<List<Project>> = ProjectRepository.projects

    // Generierungs-Zustand kommt aus dem prozessweiten Controller (Hintergrund-Service).
    val progress: StateFlow<GenProgress?> = GenerationController.progress
    val generatingId: StateFlow<String?> = GenerationController.activeId
    val error: StateFlow<String?> = GenerationController.error
    val autoRunning: StateFlow<Boolean> = GenerationController.auto
    val completed: StateFlow<Int> = GenerationController.completed

    fun saveSettings(config: AiConfig) {
        viewModelScope.launch { settings.save(config) }
    }

    fun clearError() { GenerationController.clearError() }

    /** Legt ein Projekt an und startet die Generierung im Hintergrund-Foreground-Service. */
    fun createAndGenerate(project: Project): String {
        ProjectRepository.upsert(project)
        GenerationService.generate(getApplication<Application>(), project.id)
        return project.id
    }

    /**
     * Erzeugt den nächsten Band einer Reihe aus einem abgeschlossenen Buch und startet die
     * Generierung. Trägt Figuren, Welt und Schlusszustand des Vorbands weiter (Kontinuität).
     */
    fun createSequel(fromId: String): String? {
        val prev = projects.value.firstOrNull { it.id == fromId } ?: return null
        val reihe = prev.seriesName.ifBlank { prev.title }
        val nextNumber = (if (prev.seriesNumber > 0) prev.seriesNumber else 1) + 1
        val sequel = Project(
            title = "$reihe – Band $nextNumber",
            authorName = prev.authorName,
            language = prev.language,
            genre = prev.genre,
            subgenre = prev.subgenre,
            styleProfile = prev.styleProfile,
            tropes = prev.tropes,
            spiceLevel = prev.spiceLevel,
            seriesName = reihe,
            seriesNumber = nextNumber,
            sequelContext = SeriesContext.build(prev, nextNumber),
            targetPageCount = prev.targetPageCount,
            chapterTarget = prev.chapterTarget,
            createdAt = System.currentTimeMillis(),
        )
        sequel.characters.addAll(prev.characters.map { it.copy() })
        ProjectRepository.upsert(sequel)
        GenerationService.generate(getApplication<Application>(), sequel.id)
        return sequel.id
    }

    /** Startet die Dauerproduktion (Auto-Modus) im Hintergrund. */
    fun startAuto() { GenerationService.startAuto(getApplication<Application>()) }

    /** Stoppt die laufende Generierung bzw. den Auto-Modus. */
    fun stopGeneration() { GenerationService.stop(getApplication<Application>()) }
}
