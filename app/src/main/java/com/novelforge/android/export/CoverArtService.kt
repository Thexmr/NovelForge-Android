package com.novelforge.android.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.novelforge.android.domain.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Cover-Erzeugung: atmosphärisches Motiv (Pollinations/Flux, kostenlos, KEIN Text im Bild)
 * + scharfes Titel-/Autor-Typo-Overlay → FERTIGES KDP-Cover (1600×2560, 1.6:1). Kein API-Key.
 */
object CoverArtService {

    private const val W = 1600
    private const val H = 2560

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    fun coverFile(context: Context, project: Project): File =
        File(File(context.filesDir, "covers").apply { mkdirs() }, "${project.id}.jpg")

    /** Motiv-Prompt: hochwertig, mit Negativraum oben/unten für Typografie, OHNE Text im Bild. */
    fun buildPrompt(project: Project): String {
        val base = project.profile.coverPrompt.ifBlank { project.profile.premise }
            .ifBlank { "${project.genre}: ${project.title}" }
        return listOf(
            "Professionelles Buchcover-Motiv, cineastisch, starker Fokuspunkt, hochwertige Lichtstimmung",
            base,
            "Genre ${project.genre}${if (project.subgenre.isNotBlank()) " / ${project.subgenre}" else ""}",
            "vertikales Hochformat 2:3, ruhige kontrastarme Negativflächen oben und unten für später eingesetzte Typografie",
            "ABSOLUT KEIN Text, keine Buchstaben, keine Zahlen, keine Wasserzeichen, keine Logos, keine Rahmen",
        ).joinToString(", ").take(640)
    }

    /**
     * Lädt das Motiv (1024×1638, Flux) und komponiert ein fertiges Cover mit Titel + Autor.
     * Wirft bei Netz-/Serverfehler; nie destruktiv.
     */
    suspend fun generate(context: Context, project: Project, seed: Int = 42): File = withContext(Dispatchers.IO) {
        val prompt = URLEncoder.encode(buildPrompt(project), "UTF-8")
        val url = "https://image.pollinations.ai/prompt/$prompt" +
            "?width=1024&height=1638&model=flux&nologo=true&enhance=true&seed=$seed"
        val req = Request.Builder().url(url).get().build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Cover-Server HTTP ${resp.code}")
            val bytes = resp.body?.bytes() ?: throw IOException("Leere Cover-Antwort")
            if (bytes.size < 1024) throw IOException("Cover zu klein (${bytes.size} Bytes) – vermutlich Fehlerseite")
            val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: throw IOException("Cover-Motiv nicht dekodierbar")
            val composed = composeCover(
                src,
                title = project.profile.kdpTitle.ifBlank { project.title },
                author = project.authorName.ifBlank { "Autor" },
            )
            val file = coverFile(context, project)
            FileOutputStream(file).use { composed.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            src.recycle(); composed.recycle()
            file
        }
    }

    /** Komponiert Motiv + Verläufe (in der Textzone nahezu deckend, überdeckt KI-Text) + Typografie. */
    private fun composeCover(srcBg: Bitmap, title: String, author: String): Bitmap {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)

        // 1) Motiv formatfüllend (center-crop).
        val scale = maxOf(W.toFloat() / srcBg.width, H.toFloat() / srcBg.height)
        val dw = srcBg.width * scale; val dh = srcBg.height * scale
        val left = (W - dw) / 2f; val top = (H - dh) / 2f
        c.drawBitmap(srcBg, null, RectF(left, top, left + dw, top + dh), Paint(Paint.FILTER_BITMAP_FLAG))

        val dark = Color.rgb(8, 8, 13)
        fun argb(a: Int) = Color.argb(a, 8, 8, 13)

        // 2a) Oben: deckend am oberen Rand → transparent.
        val topH = H * 0.34f
        c.drawRect(0f, 0f, W.toFloat(), topH, Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, topH,
                intArrayOf(argb(255), argb(250), argb(0)), floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP)
        })
        // 2b) Unten: deckend am unteren Rand → transparent nach oben.
        val botTop = H * 0.62f
        c.drawRect(0f, botTop, W.toFloat(), H.toFloat(), Paint().apply {
            shader = LinearGradient(0f, H.toFloat(), 0f, botTop,
                intArrayOf(argb(255), argb(252), argb(0)), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        })

        // 3) Titel (Serif, fett), umgebrochen, oberes Drittel.
        val up = title.uppercase()
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(247, 243, 234)
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            letterSpacing = 0.04f
            setShadowLayer(6f, 0f, 2f, Color.argb(150, 0, 0, 0))
            textSize = when {
                up.length <= 12 -> 168f; up.length <= 20 -> 138f
                up.length <= 30 -> 112f; up.length <= 44 -> 92f; else -> 76f
            }
        }
        val lines = wrap(up, titlePaint, W * 0.84f)
        val lineH = titlePaint.textSize * 1.08f
        var y = H * 0.14f + titlePaint.textSize
        for (ln in lines) { c.drawText(ln, W / 2f, y, titlePaint); y += lineH }

        // 4) Gold-Akzentlinie unter dem Titel.
        val ruleY = H * 0.14f + lines.size * lineH + 26f
        c.drawRect(W / 2f - 90f, ruleY, W / 2f + 90f, ruleY + 5f, Paint().apply { color = Color.rgb(201, 162, 75) })

        // 5) Autor (Sans, gesperrt), unten.
        val authorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(239, 233, 220)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            textSize = 58f
            letterSpacing = 0.14f
            setShadowLayer(5f, 0f, 2f, Color.argb(160, 0, 0, 0))
        }
        c.drawText(author.uppercase(), W / 2f, H * 0.905f, authorPaint)
        return bmp
    }

    private fun wrap(text: String, paint: Paint, maxWidth: Float): List<String> {
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        val lines = mutableListOf<String>(); var cur = ""
        for (w in words) {
            val cand = if (cur.isEmpty()) w else "$cur $w"
            if (paint.measureText(cand) <= maxWidth) cur = cand
            else { if (cur.isNotEmpty()) lines.add(cur); cur = w }
        }
        if (cur.isNotEmpty()) lines.add(cur)
        return if (lines.size > 4) lines.take(4) else lines
    }
}
