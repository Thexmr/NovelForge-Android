package com.novelforge.android.export

import android.content.Context
import com.novelforge.android.domain.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Cover-Erzeugung über Pollinations.ai (kostenloser, keyloser Flux-Endpunkt) – Port des
 * macOS `CoverArtService`. Liefert eine JPEG-Datei im App-Speicher, direkt KDP-tauglich.
 * Kein API-Key, keine laufenden Kosten.
 */
object CoverArtService {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    /** Zielort der Cover-Datei (stabil je Projekt). */
    fun coverFile(context: Context, project: Project): File =
        File(File(context.filesDir, "covers").apply { mkdirs() }, "${project.id}.jpg")

    /** Baut den Bildprompt aus Cover-Vorgabe/Prämisse + Titel/Genre. */
    fun buildPrompt(project: Project): String {
        val base = project.profile.coverPrompt.ifBlank { project.profile.premise }
            .ifBlank { "${project.genre}: ${project.title}" }
        return listOf(
            "Professionelles Buchcover, hochwertig, dramatische Beleuchtung",
            base,
            "Genre ${project.genre}${if (project.subgenre.isNotBlank()) " / ${project.subgenre}" else ""}",
            "vertikales Hochformat, kein Text, keine Buchstaben, keine Wasserzeichen",
        ).joinToString(", ").take(600)
    }

    /**
     * Lädt ein Cover (1024×1536, Flux) und speichert es als JPEG. Wirft bei Netz-/Serverfehler,
     * ist aber nie destruktiv. `seed` macht das Ergebnis reproduzierbar bzw. variierbar.
     */
    suspend fun generate(context: Context, project: Project, seed: Int = 42): File = withContext(Dispatchers.IO) {
        val prompt = URLEncoder.encode(buildPrompt(project), "UTF-8")
        val url = "https://image.pollinations.ai/prompt/$prompt" +
            "?width=1024&height=1536&model=flux&nologo=true&enhance=true&seed=$seed"
        val req = Request.Builder().url(url).get().build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Cover-Server HTTP ${resp.code}")
            val bytes = resp.body?.bytes() ?: throw IOException("Leere Cover-Antwort")
            if (bytes.size < 1024) throw IOException("Cover zu klein (${bytes.size} Bytes) – vermutlich Fehlerseite")
            coverFile(context, project).apply { writeBytes(bytes) }
        }
    }
}
