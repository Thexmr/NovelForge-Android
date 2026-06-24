package com.novelforge.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.novelforge.android.ai.AiClient
import com.novelforge.android.ai.AiConfig
import com.novelforge.android.ai.PromptFactory
import com.novelforge.android.data.ProjectRepository
import com.novelforge.android.data.SettingsStore
import com.novelforge.android.domain.Genres
import com.novelforge.android.domain.Project
import com.novelforge.android.domain.ProjectStatus
import com.novelforge.android.generator.GenerationController
import com.novelforge.android.generator.NovelGenerator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Foreground-Service: führt die Buchproduktion im Hintergrund aus (Handy bleibt nutzbar).
 * EIN serieller Worker arbeitet eine Warteschlange ab (Einzel-/Fortsetzungs-Bücher zuerst),
 * danach – solange Auto-Modus aktiv ist – fortlaufend neue Bücher. Robust gegen Abbruch,
 * Dauerfehler (Pacing + Abbruch nach mehreren Fehlern) und Stop/Start-Races.
 */
class GenerationService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    private val lock = Any()
    private val queue = ArrayDeque<String>()
    private var workerRunning = false
    private var autoRound = 0
    private val recentIdeas = ArrayDeque<String>()
    @Volatile private var lastStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        ensureForeground("Buchproduktion läuft …") // erfüllt den startForegroundService-Vertrag
        when (intent?.action) {
            ACTION_GENERATE -> intent.getStringExtra(EXTRA_ID)?.let { enqueue(it) }
            ACTION_AUTO -> { GenerationController.setAuto(true); ensureWorker() }
            ACTION_STOP -> stopAll()
        }
        return START_NOT_STICKY
    }

    private fun enqueue(projectId: String) {
        synchronized(lock) { queue.addLast(projectId) }
        ensureWorker()
    }

    private fun ensureWorker() {
        synchronized(lock) {
            if (workerRunning) return
            workerRunning = true
        }
        job = scope.launch { worker() }
    }

    private suspend fun worker() {
        try {
            val cfg = try {
                SettingsStore(applicationContext).configFlow.first()
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                AiConfig() // Fallback: Bücher schlagen sauber fehl statt zu stranden
            }
            var failures = 0
            while (true) {
                val pid = synchronized(lock) { if (queue.isNotEmpty()) queue.removeFirst() else null }
                if (pid != null) {
                    try { generateOne(cfg, pid) }
                    catch (c: CancellationException) { throw c }
                    catch (e: Exception) { GenerationController.setError(e.message ?: "Fehler") }
                    failures = 0
                    continue
                }
                if (GenerationController.auto.value) {
                    val ok = try { generateAuto(cfg) }
                    catch (c: CancellationException) { throw c }
                    catch (e: Exception) { GenerationController.setError(e.message ?: "Fehler"); false }
                    failures = if (ok) 0 else failures + 1
                    // Circuit-Breaker: bei Dauerfehlern (z. B. falscher API-Key) Auto-Modus stoppen.
                    if (failures >= 3) {
                        GenerationController.setError("Auto-Modus gestoppt – bitte API-Key/Modell prüfen.")
                        GenerationController.setAuto(false)
                    }
                    continue
                }
                // Nichts mehr zu tun → unter Lock abmelden und Schleife verlassen.
                val done = synchronized(lock) {
                    if (queue.isEmpty() && !GenerationController.auto.value) { workerRunning = false; true } else false
                }
                if (done) break
            }
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            GenerationController.setError(e.message ?: "Fehler")
            synchronized(lock) { workerRunning = false }
        } finally {
            GenerationController.setProgress(null)
            // Nur stoppen, wenn KEIN neuer Worker übernommen hat und nichts mehr ansteht.
            val stop = synchronized(lock) { !workerRunning && queue.isEmpty() && !GenerationController.auto.value }
            if (stop) {
                GenerationController.setActive(null)
                stopSelfSafely()
            }
        }
    }

    /** @return true bei erfolgreichem Buch, false bei Fehler. */
    private suspend fun generateOne(cfg: AiConfig, projectId: String): Boolean {
        val project = ProjectRepository.get(projectId) ?: return false
        GenerationController.setActive(project.id)
        GenerationController.setError(null)
        updateNotification(project.title)
        return try {
            NovelGenerator(cfg).generate(project) { p ->
                GenerationController.setProgress(p)
                ProjectRepository.touch()
                updateNotification("${project.title}: ${p.phase}")
            }
            GenerationController.incCompleted()
            ProjectRepository.upsert(project) // Endstatus COMPLETED hart sichern
            true
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            project.status = ProjectStatus.FAILED
            GenerationController.setError(e.message ?: "Unbekannter Fehler")
            ProjectRepository.upsert(project) // Endstatus FAILED hart sichern
            false
        } finally {
            GenerationController.setProgress(null)
        }
    }

    private suspend fun generateAuto(cfg: AiConfig): Boolean {
        val ai = AiClient(cfg)
        val genre = Genres.all[autoRound % Genres.all.size]
        autoRound++
        val avoid = synchronized(lock) { recentIdeas.toList() }
        val ideaText = try {
            ai.chat(
                "Du bist ein Bestseller-Lektor und Titel-Experte.",
                PromptFactory.bookIdea(genre, "Deutsch", avoid),
                temperature = 0.95, maxTokens = 500
            )
        } catch (c: CancellationException) { throw c } catch (e: Exception) { "" }
        val title = parseField(ideaText, "TITEL").ifBlank { "$genre-Roman $autoRound" }
        val premise = parseField(ideaText, "PRÄMISSE")
        synchronized(lock) {
            recentIdeas.addLast(title)
            while (recentIdeas.size > 12) recentIdeas.removeFirst()
        }
        val project = Project(
            title = title, authorName = "NovelForge", genre = genre,
            targetPageCount = 200, chapterTarget = 16, createdAt = System.currentTimeMillis(),
        )
        project.profile.premise = premise
        ProjectRepository.upsert(project)
        val ok = generateOne(cfg, project.id)
        delay(1500) // Pacing: verhindert Heißlaufen bei schnellen Dauerfehlern
        return ok
    }

    private fun stopAll() {
        GenerationController.setAuto(false)
        synchronized(lock) { queue.clear() }
        val j = job
        scope.launch {
            runCatching { j?.cancelAndJoin() }
            GenerationController.setProgress(null)
            // Falls während des Abbruchs etwas Neues angefordert wurde: weiterarbeiten statt verlieren.
            val restart = synchronized(lock) {
                workerRunning = false
                queue.isNotEmpty() || GenerationController.auto.value
            }
            if (restart) {
                ensureForeground("Buchproduktion läuft …")
                ensureWorker()
            } else {
                GenerationController.setActive(null)
                stopSelfSafely()
            }
        }
    }

    private fun parseField(text: String, label: String): String {
        if (text.isBlank()) return ""
        val regex = Regex("(?im)^\\**\\s*$label\\s*:?\\**\\s*(.+)$")
        return regex.find(text)?.groupValues?.getOrNull(1)?.trim().orEmpty()
    }

    // Android 14+: Foreground-Service-Zeitlimit (dataSync, 6 h/Tag) → sauber stoppen.
    override fun onTimeout(startId: Int) {
        GenerationController.setAuto(false)
        GenerationController.setError("Hintergrundzeit abgelaufen – bitte erneut starten.")
        stopAll()
    }

    // ---- Foreground / Notification -------------------------------------------

    private fun ensureForeground(text: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL, "Buchproduktion", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notif = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("NovelForge")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    private fun updateNotification(text: String) {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mgr.notify(NOTIF_ID, buildNotification(text))
    }

    private fun stopSelfSafely() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        // stopSelf(startId): no-op, falls inzwischen ein neueres Kommando eintraf.
        stopSelf(lastStartId)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "novelforge_generation"
        private const val NOTIF_ID = 1001
        const val ACTION_GENERATE = "com.novelforge.android.action.GENERATE"
        const val ACTION_AUTO = "com.novelforge.android.action.AUTO"
        const val ACTION_STOP = "com.novelforge.android.action.STOP"
        const val EXTRA_ID = "project_id"

        fun generate(context: Context, projectId: String) {
            val i = Intent(context, GenerationService::class.java)
                .setAction(ACTION_GENERATE).putExtra(EXTRA_ID, projectId)
            ContextCompat.startForegroundService(context, i)
        }

        fun startAuto(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, GenerationService::class.java).setAction(ACTION_AUTO)
            )
        }

        fun stop(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, GenerationService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
