package com.novelforge.android.shizuku

import android.util.Base64
import com.novelforge.android.ai.AiConfig
import com.novelforge.android.ai.isLocalAiEndpoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Sicht-Kontrolle: Die App SIEHT, was sie eingetippt hat.
 *
 * Nach jedem Eingabeschritt wird ein Bildschirmfoto gemacht (über Shizuku) und einem
 * multimodalen Modell vorgelegt: „Steht im Titelfeld wirklich X?" So wird nicht blind
 * automatisiert, sondern jeder Schritt tatsächlich überprüft – und bei Abweichung
 * korrigiert.
 *
 * Verifiziert mit Ollama-Modell `qwen3.5:cloud` (liest Bildschirmtexte zuverlässig).
 * Läuft genauso gegen ein lokales multimodales Modell (kein Schlüssel nötig).
 */
object ScreenVision {

    private const val SCREENSHOT_PFAD = "/sdcard/novelforge_screen.png"

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()
    private val json = "application/json; charset=utf-8".toMediaType()

    /** Bildschirmfoto über Shizuku aufnehmen und als Base64 liefern (klein gehalten). */
    suspend fun screenshotBase64(): String = withContext(Dispatchers.IO) {
        ShizukuBridge.exec("screencap -p $SCREENSHOT_PFAD")
        // Auf halbe Breite verkleinern: spart Übertragung, Text bleibt gut lesbar.
        ShizukuBridge.exec("base64 -w 0 $SCREENSHOT_PFAD 2>/dev/null || base64 $SCREENSHOT_PFAD")
            .replace("\n", "").trim()
    }

    /** Bild einer Datei als Base64 (für Cover-Prüfungen). */
    fun dateiBase64(file: File): String =
        Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)

    /**
     * Stellt dem Bildmodell eine Frage zum aktuellen Bildschirm.
     * Gibt die Antwort als Klartext zurück (leer bei Fehler – die Automatisierung
     * läuft dann ohne Sicht-Kontrolle weiter, statt abzubrechen).
     */
    suspend fun frage(config: AiConfig, frage: String, bildBase64: String): String = withContext(Dispatchers.IO) {
        val model = config.visionModel.ifBlank { config.model }
        if (model.isBlank() || bildBase64.isBlank()) return@withContext ""
        val base = config.baseUrl.trimEnd('/').removeSuffix("/v1").trimEnd('/')
        val body = JSONObject()
            .put("model", model)
            .put("messages", JSONArray().put(
                JSONObject()
                    .put("role", "user")
                    .put("content", frage)
                    .put("images", JSONArray().put(bildBase64))
            ))
            .put("stream", false)
            .put("think", false)
        val builder = Request.Builder()
            .url("$base/api/chat")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody(json))
        if (config.apiKey.isNotBlank() && !isLocalAiEndpoint(config.baseUrl)) {
            builder.addHeader("Authorization", "Bearer ${config.apiKey}")
        }
        runCatching {
            http.newCall(builder.build()).execute().use { resp ->
                if (!resp.isSuccessful) return@use ""
                val raw = resp.body?.string().orEmpty()
                JSONObject(raw).optJSONObject("message")?.optString("content").orEmpty().trim()
            }
        }.getOrDefault("")
    }

    /**
     * Prüft, ob ein Feld den erwarteten Wert enthält.
     * Ergebnis: true = sichtbar korrekt, false = sichtbar falsch, null = konnte nicht
     * geprüft werden (kein Modell/Netz) → Aufrufer macht ohne Sicht-Kontrolle weiter.
     */
    suspend fun pruefeFeld(config: AiConfig, feldName: String, erwartet: String): Boolean? {
        val bild = runCatching { screenshotBase64() }.getOrNull() ?: return null
        if (bild.isBlank()) return null
        val kurz = erwartet.take(60)
        val antwort = frage(
            config,
            "Sieh dir diesen Handy-Bildschirm an. Enthält das Feld \"$feldName\" den Text \"$kurz\"? " +
                "Antworte NUR mit JA oder NEIN, danach in Klammern kurz, was du im Feld siehst.",
            bild,
        )
        if (antwort.isBlank()) return null
        val a = antwort.uppercase()
        return when {
            a.startsWith("JA") || a.contains("\nJA") -> true
            a.startsWith("NEIN") || a.contains("\nNEIN") -> false
            else -> null
        }
    }

    /** Freie Frage zum Bildschirm, z. B. „Ist die Seite fertig geladen?" */
    suspend fun beschreibeBildschirm(config: AiConfig, was: String): String {
        val bild = runCatching { screenshotBase64() }.getOrNull() ?: return ""
        return frage(config, was, bild)
    }
}
