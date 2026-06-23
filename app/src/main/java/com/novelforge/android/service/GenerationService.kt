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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Foreground-Service: führt die Buchproduktion im Hintergrund aus (das Handy bleibt nutzbar).
 * EIN serieller Worker arbeitet eine Warteschlange ab: angeforderte Einzel-/Fortsetzungs-Bücher
 * zuerst, danach – solange Auto-Modus aktiv ist – fortlaufend neue Bücher. Dadurch geht keine
 * Anforderung verloren, auch nicht während eine andere Generierung schon läuft.
 */
class GenerationService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    private val lock = Any()
    private val queue = ArrayDeque<String>()       // wartende Einzel-/Fortsetzungs-Bücher
    private var workerRunning = false
    private var autoRound = 0
    private val recentIdeas = ArrayDeque<String>() // Story-Memory: zuletzt erzeugte Titel (Auto-Modus)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Erfüllt den startForegroundService→startForeground-Vertrag bei JEDEM Kommando.
        ensureForeground("Buchproduktion läuft …")
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

    /** Startet genau EINEN seriellen Worker, falls keiner läuft. */
    private fun ensureWorker() {
        synchronized(lock) {
            if (workerRunning) return
            workerRunning = true
        }
        job = scope.launch { worker() }
    }

    private suspend fun worker() {
        try {
            val cfg = SettingsStore(applicationContext).configFlow.first()
            while (true) {
                val pid = synchronized(lock) { if (queue.isNotEmpty()) queue.removeFirst() else null }
                if (pid != null) { generateOne(cfg, pid); continue }
                if (GenerationController.auto.value) { generateAuto(cfg); continue }
                // Nichts mehr zu tun – atomar abmelden (schließt die Producer/Consumer-Lücke).
                synchronized(lock) {
                    if (queue.isEmpty() && !GenerationController.auto.value) {
                        workerRunning = false
                        return@worker
                    }
                }
            }
        } finally {
            synchronized(lock) { workerRunning = false }
            GenerationController.setActive(null)
            GenerationController.setProgress(null)
            ProjectRepository.touch()
            stopSelfSafely()
        }
    }

    private suspend fun generateOne(cfg: AiConfig, projectId: String) {
        val project = ProjectRepository.get(projectId) ?: return
        GenerationController.setActive(project.id)
        GenerationController.setError(null)
        updateNotification(project.title)
        try {
            NovelGenerator(cfg).generate(project) { p ->
                GenerationController.setProgress(p)
                ProjectRepository.touch()
                updateNotification("${project.title}: ${p.phase}")
            }
            GenerationController.incCompleted()
        } catch (e: Exception) {
            project.status = ProjectStatus.FAILED
            GenerationController.setError(e.message ?: "Unbekannter Fehler")
        } finally {
            GenerationController.setProgress(null)
            ProjectRepository.touch()
        }
    }

    private suspend fun generateAuto(cfg: AiConfig) {
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
        } catch (e: Exception) { "" }
        val title = parseField(ideaText, "TITEL").ifBlank { "$genre-Roman $autoRound" }
        val premise = parseField(ideaText, "PRÄMISSE")
        synchronized(lock) {
            recentIdeas.addLast(title)
            while (recentIdeas.size > 12) recentIdeas.removeFirst()
        }
        val project = Project(
            title = title,
            authorName = "NovelForge",
            genre = genre,
            targetPageCount = 200,
            chapterTarget = 16,
            createdAt = System.currentTimeMillis(),
        )
        project.profile.premise = premise
        ProjectRepository.upsert(project)
        generateOne(cfg, project.id)
    }

    private fun stopAll() {
        GenerationController.setAuto(false)
        synchronized(lock) { queue.clear(); workerRunning = false }
        job?.cancel()
        job = null
        GenerationController.setActive(null)
        GenerationController.setProgress(null)
        stopSelfSafely()
    }

    private fun parseField(text: String, label: String): String {
        if (text.isBlank()) return ""
        val regex = Regex("(?im)^\\**\\s*$label\\s*:?\\**\\s*(.+)$")
        return regex.find(text)?.groupValues?.getOrNull(1)?.trim().orEmpty()
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
        stopSelf()
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
