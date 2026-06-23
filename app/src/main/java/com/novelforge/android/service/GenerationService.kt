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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground-Service: führt die Buchproduktion (Einzelbuch oder Auto-Modus) im
 * Hintergrund aus, mit dauerhafter Benachrichtigung – das Handy bleibt nutzbar,
 * und Android beendet den Prozess nicht.
 */
class GenerationService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_GENERATE -> intent.getStringExtra(EXTRA_ID)?.let { startGenerate(it) }
            ACTION_AUTO -> startAuto()
            ACTION_STOP -> stopAll()
        }
        return START_NOT_STICKY
    }

    private fun startGenerate(projectId: String) {
        if (job?.isActive == true) return
        ensureForeground("Buch wird erstellt …")
        job = scope.launch {
            try {
                val cfg = SettingsStore(applicationContext).configFlow.first()
                val project = ProjectRepository.get(projectId) ?: return@launch
                GenerationController.setActive(project.id)
                GenerationController.setError(null)
                NovelGenerator(cfg).generate(project) { p ->
                    GenerationController.setProgress(p)
                    ProjectRepository.touch()
                    updateNotification("${project.title}: ${p.phase}")
                }
                GenerationController.incCompleted()
            } catch (e: Exception) {
                ProjectRepository.get(projectId)?.status = ProjectStatus.FAILED
                GenerationController.setError(e.message ?: "Unbekannter Fehler")
            } finally {
                GenerationController.setActive(null)
                GenerationController.setProgress(null)
                ProjectRepository.touch()
                stopSelfSafely()
            }
        }
    }

    private fun startAuto() {
        if (job?.isActive == true) return
        ensureForeground("Auto-Modus: Bücher werden produziert …")
        GenerationController.setAuto(true)
        job = scope.launch {
            try {
                val cfg = SettingsStore(applicationContext).configFlow.first()
                val ai = AiClient(cfg)
                val gen = NovelGenerator(cfg)
                var round = 0
                while (isActive && GenerationController.auto.value) {
                    round++
                    val genre = Genres.all[(round - 1) % Genres.all.size]
                    val ideaText = try {
                        ai.chat(
                            "Du bist ein Bestseller-Lektor und Titel-Experte.",
                            PromptFactory.bookIdea(genre, "Deutsch"),
                            temperature = 0.95, maxTokens = 500
                        )
                    } catch (e: Exception) { "" }
                    val title = parseField(ideaText, "TITEL").ifBlank { "$genre-Roman $round" }
                    val premise = parseField(ideaText, "PRÄMISSE")

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
                    GenerationController.setActive(project.id)
                    GenerationController.setError(null)
                    try {
                        gen.generate(project) { p ->
                            GenerationController.setProgress(p)
                            ProjectRepository.touch()
                            updateNotification("Auto · ${project.title}: ${p.phase}")
                        }
                        GenerationController.incCompleted()
                    } catch (e: Exception) {
                        // Ein gescheitertes Buch stoppt die Dauerproduktion nicht.
                        project.status = ProjectStatus.FAILED
                        GenerationController.setError(e.message ?: "Fehler")
                    }
                    ProjectRepository.touch()
                }
            } finally {
                GenerationController.setAuto(false)
                GenerationController.setActive(null)
                GenerationController.setProgress(null)
                stopSelfSafely()
            }
        }
    }

    private fun stopAll() {
        GenerationController.setAuto(false)
        job?.cancel()
        job = null
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
