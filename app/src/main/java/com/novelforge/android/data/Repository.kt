package com.novelforge.android.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.novelforge.android.ai.AiConfig
import com.novelforge.android.domain.Project
import com.novelforge.android.domain.ProjectStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File
import java.util.ConcurrentModificationException

private val Context.dataStore by preferencesDataStore(name = "novelforge_settings")

/** Persistiert die KI-Anbieter-Konfiguration (Key prompt-frei in DataStore). */
class SettingsStore(private val context: Context) {
    private val keyBase = stringPreferencesKey("base_url")
    private val keyApi = stringPreferencesKey("api_key")
    private val keyModel = stringPreferencesKey("model")
    private val keyWritingModel = stringPreferencesKey("writing_model")
    private val keyVisionModel = stringPreferencesKey("vision_model")
    private val keyOnboarded = booleanPreferencesKey("onboarded")

    val configFlow: Flow<AiConfig> = context.dataStore.data.map { p ->
        AiConfig(
            baseUrl = p[keyBase] ?: AiConfig().baseUrl,
            apiKey = p[keyApi] ?: "",
            model = p[keyModel] ?: AiConfig().model,
            writingModel = p[keyWritingModel] ?: "",
            visionModel = p[keyVisionModel] ?: "",
        )
    }

    /** true, sobald der Einrichtungs-Assistent einmal abgeschlossen wurde. */
    val onboardedFlow: Flow<Boolean> = context.dataStore.data.map { p -> p[keyOnboarded] ?: false }

    suspend fun save(config: AiConfig) {
        context.dataStore.edit { p ->
            p[keyBase] = config.baseUrl
            p[keyApi] = config.apiKey
            p[keyModel] = config.model
            p[keyWritingModel] = config.writingModel
            p[keyVisionModel] = config.visionModel
        }
    }

    suspend fun setOnboarded(done: Boolean) {
        context.dataStore.edit { p -> p[keyOnboarded] = done }
    }
}

/**
 * Prozessweite Projektablage. `live` hält die kanonischen (im Generator in-place mutierten)
 * Instanzen; der StateFlow emittiert bei jeder Änderung FRISCHE Tiefkopien – nur so erkennt
 * Compose neue Kapitel/Status live (MutableStateFlow unterdrückt sonst gleich-wirkende Werte,
 * weil Project eine data class ist). Spiegelung als JSON-Schnappschuss überlebt App-Neustarts.
 */
object ProjectRepository {
    private val live = mutableListOf<Project>()
    private val _projects = MutableStateFlow<List<Project>>(emptyList())
    val projects: StateFlow<List<Project>> = _projects.asStateFlow()

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fileLock = Any()
    private var file: File? = null
    @Volatile private var lastPersist = 0L
    @Volatile private var retryScheduled = false

    /** Einmalig beim App-Start: lädt den Bestand im Hintergrund (kein ANR). */
    fun init(context: Context) {
        if (file != null) return
        val f = File(context.applicationContext.filesDir, "projects.json")
        file = f
        ioScope.launch {
            val text = runCatching { if (f.exists()) f.readText() else "" }.getOrDefault("")
            if (text.isBlank()) return@launch
            val loaded = runCatching { ProjectJson.decodeList(text) }.getOrElse {
                // Korrupte Datei NICHT stillschweigend mit Leer überschreiben → sichern.
                runCatching { synchronized(fileLock) { f.copyTo(File(f.parentFile, "projects.json.bak"), overwrite = true) } }
                return@launch
            }
            if (loaded.isEmpty()) return@launch
            synchronized(live) {
                if (live.isNotEmpty()) return@launch
                // Kaltstart: keine Generierung kann laufen → hängende GENERATING-Bücher = FEHLGESCHLAGEN.
                loaded.forEach { if (it.status == ProjectStatus.GENERATING) it.status = ProjectStatus.FAILED }
                live.addAll(loaded)
            }
            emit(force = true)
        }
    }

    fun upsert(project: Project) {
        synchronized(live) {
            val idx = live.indexOfFirst { it.id == project.id }
            if (idx >= 0) live[idx] = project else live.add(0, project)
        }
        emit(force = true)
    }

    fun get(id: String): Project? = synchronized(live) { live.firstOrNull { it.id == id } }

    fun delete(id: String) {
        synchronized(live) { live.removeAll { it.id == id } }
        emit(force = true)
    }

    fun touch() = emit(force = false)

    private fun emit(force: Boolean) {
        var degraded = false
        val snap = synchronized(live) {
            live.map { p ->
                val (proj, wasDegraded) = snapshot(p)
                if (wasDegraded) degraded = true
                proj
            }
        }
        if (degraded) {
            // Mindestens eine Liste ließ sich wegen anhaltender Generator-Kontention NICHT
            // sauber kopieren. FRÜHER wurde dafür eine LEERE Liste eingesetzt und dieser
            // verstümmelte Schnappschuss emittiert UND auf Platte geschrieben → echter
            // Datenverlust (Buch mit 0 Kapiteln in UI und projects.json). Jetzt: diesen
            // emit verwerfen und kurz darauf erneut versuchen, wenn die Struktur-Änderung
            // durch ist. Der bisherige _projects.value bleibt unverändert stehen.
            scheduleRetryEmit()
            return
        }
        _projects.value = snap
        persist(snap, force)
    }

    /** Kurz warten und erneut emittieren, sobald die kollidierende Struktur-Änderung durch ist. */
    private fun scheduleRetryEmit() {
        if (retryScheduled) return
        retryScheduled = true
        ioScope.launch {
            delay(50)
            retryScheduled = false
            emit(force = true)
        }
    }

    /**
     * Tiefkopie (entkoppelt von der live-mutierten Instanz), damit der Flow wirklich emittiert.
     * Liefert zusätzlich zurück, ob eine der Listen NICHT sauber kopiert werden konnte (degraded).
     */
    private fun snapshot(p: Project): Pair<Project, Boolean> {
        val chapters = safeCopy(p.chapters) { it.copy() }
        val characters = safeCopy(p.characters) { it.copy() }
        val degraded = chapters == null || characters == null
        return p.copy(
            profile = p.profile.copy(),
            chapters = chapters ?: mutableListOf(),
            characters = characters ?: mutableListOf(),
        ) to degraded
    }

    /**
     * Kopiert eine Liste auch dann ohne Absturz, wenn der Generator-Thread sie gerade strukturell
     * ändert (clear()/addAll()). Zuerst ein schneller Struktur-Snapshot (ArrayList) – das verkleinert
     * das Kollisionsfenster gegenüber map{} mit Lambda pro Element – dann kopieren. Bei anhaltender
     * Kollision `null` (NICHT leere Liste!), damit der Aufrufer den verstümmelten Schnappschuss
     * verwerfen statt persistieren kann. Eine echt leere Quelle liefert normal eine leere Liste.
     */
    private fun <T> safeCopy(src: List<T>, copy: (T) -> T): MutableList<T>? {
        repeat(6) {
            try {
                val stable = ArrayList(src)
                return stable.map(copy).toMutableList()
            }
            catch (e: ConcurrentModificationException) { /* erneut versuchen */ }
            catch (e: IndexOutOfBoundsException) { /* gleiche Ursache, erneut versuchen */ }
            catch (e: NullPointerException) { /* Element wurde während der Kopie entfernt */ }
        }
        return null
    }

    private fun persist(snapshot: List<Project>, force: Boolean) {
        val f = file ?: return
        val now = System.currentTimeMillis()
        if (!force && now - lastPersist < 2500) return
        lastPersist = now
        ioScope.launch {
            runCatching {
                val json = ProjectJson.encodeList(snapshot)
                synchronized(fileLock) {
                    // Atomar: erst in .tmp, dann umbenennen → nie eine halb geschriebene Datei.
                    val tmp = File(f.parentFile, "projects.json.tmp")
                    tmp.writeText(json)
                    if (!tmp.renameTo(f)) { f.writeText(json); tmp.delete() }
                }
            }
        }
    }
}
