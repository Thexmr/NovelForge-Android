package com.novelforge.android.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.novelforge.android.ai.AiConfig
import com.novelforge.android.domain.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File

private val Context.dataStore by preferencesDataStore(name = "novelforge_settings")

/** Persistiert die KI-Anbieter-Konfiguration (Key prompt-frei in DataStore). */
class SettingsStore(private val context: Context) {
    private val keyBase = stringPreferencesKey("base_url")
    private val keyApi = stringPreferencesKey("api_key")
    private val keyModel = stringPreferencesKey("model")
    private val keyWritingModel = stringPreferencesKey("writing_model")

    val configFlow: Flow<AiConfig> = context.dataStore.data.map { p ->
        AiConfig(
            baseUrl = p[keyBase] ?: AiConfig().baseUrl,
            apiKey = p[keyApi] ?: "",
            model = p[keyModel] ?: AiConfig().model,
            writingModel = p[keyWritingModel] ?: "",
        )
    }

    suspend fun save(config: AiConfig) {
        context.dataStore.edit { p ->
            p[keyBase] = config.baseUrl
            p[keyApi] = config.apiKey
            p[keyModel] = config.model
            p[keyWritingModel] = config.writingModel
        }
    }
}

/**
 * Prozessweite Projektablage. Hält die Projekte im Speicher (reaktiver StateFlow) und
 * spiegelt sie als JSON-Schnappschuss in den App-internen Speicher, damit erzeugte
 * Bücher App-Neustarts überleben (auch Auto-Modus-Bücher).
 */
object ProjectRepository {
    private val _projects = MutableStateFlow<List<Project>>(emptyList())
    val projects: StateFlow<List<Project>> = _projects.asStateFlow()

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fileLock = Any()
    private var file: File? = null
    @Volatile private var lastPersist = 0L

    /** Einmalig beim App-Start aufrufen: lädt den gespeicherten Bestand (im Hintergrund, kein ANR). */
    fun init(context: Context) {
        if (file != null) return
        val f = File(context.applicationContext.filesDir, "projects.json")
        file = f
        ioScope.launch {
            runCatching {
                if (f.exists()) {
                    val loaded = ProjectJson.decodeList(f.readText())
                    // Nicht überschreiben, falls in der Zwischenzeit schon etwas angelegt wurde.
                    if (loaded.isNotEmpty() && _projects.value.isEmpty()) _projects.value = loaded
                }
            }
        }
    }

    fun delete(id: String) {
        _projects.value = _projects.value.filterNot { it.id == id }
        persist(force = true)
    }

    fun upsert(project: Project) {
        val current = _projects.value.toMutableList()
        val idx = current.indexOfFirst { it.id == project.id }
        if (idx >= 0) current[idx] = project else current.add(0, project)
        _projects.value = current
        persist(force = true)
    }

    fun get(id: String): Project? = _projects.value.firstOrNull { it.id == id }

    fun touch() {
        // Erzwingt eine Flow-Emission, wenn ein Projekt in-place mutiert wurde.
        _projects.value = _projects.value.toList()
        persist(force = false)
    }

    /** Schreibt den Bestand als JSON. `force=false` drosselt auf höchstens alle ~2,5 s. */
    private fun persist(force: Boolean) {
        val f = file ?: return
        val now = System.currentTimeMillis()
        if (!force && now - lastPersist < 2500) return
        lastPersist = now
        val snapshot = _projects.value
        ioScope.launch {
            runCatching {
                val json = ProjectJson.encodeList(snapshot)
                synchronized(fileLock) { f.writeText(json) }
            }
        }
    }
}
