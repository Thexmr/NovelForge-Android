package com.novelforge.android.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class AiConfig(
    val baseUrl: String = "https://ollama.com",
    val apiKey: String = "",
    val model: String = "kimi-k2.6",
    // Optionales, stärkeres Modell nur fürs Schreiben der Kapitel (zweistufig).
    // Leer = es wird durchgehend `model` verwendet.
    val writingModel: String = "",
    // Multimodales Modell für die SICHT-KONTROLLE: die App macht ein Bildschirmfoto und
    // lässt prüfen, ob wirklich das Richtige im Feld steht. Leer = `model` wird versucht.
    // Verifiziert mit "qwen3.5:cloud"; jedes bildfähige (auch lokale) Modell geht.
    val visionModel: String = "",
) {
    /** Lokaler Endpunkt (LAN/localhost/Emulator-Host) – braucht keinen API-Key. */
    val isLocal: Boolean get() = isLocalAiEndpoint(baseUrl)
    /** Einsatzbereit: Key hinterlegt ODER lokaler Server (kein Key nötig). */
    val usable: Boolean get() = apiKey.isNotBlank() || isLocal
}

/**
 * Erkennt lokale KI-Server (eigener Mac/PC im LAN, localhost, Android-Emulator-Host
 * 10.0.2.2, private RFC-1918-Bereiche). Solche Server laufen ohne API-Key und meist
 * über Klartext-HTTP.
 */
fun isLocalAiEndpoint(url: String): Boolean =
    Regex("localhost|127\\.0\\.0\\.1|::1|10\\.0\\.2\\.2|://(10\\.|192\\.168\\.|172\\.(1[6-9]|2\\d|3[01])\\.)")
        .containsMatchIn(url.lowercase())

class AiException(message: String) : Exception(message)

/**
 * Chat-Client für Ollama Cloud (native /api/chat, mit think:false – sonst
 * verbrauchen Reasoning-Modelle das Token-Budget für Denkschritte und liefern
 * leeren Text) sowie OpenAI-kompatible Anbieter (/v1/chat/completions).
 * Inklusive Retry mit Backoff bei Rate-Limit/Netzwerkfehlern.
 */
class AiClient(private val config: AiConfig) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val json = "application/json; charset=utf-8".toMediaType()

    // Ollama-natives Protokoll (/api/chat) nur, wenn NICHT der OpenAI-kompatible
    // /v1-Pfad genutzt wird. So läuft llama.cpp/LM Studio (/v1) und Ollamas eigener
    // /v1-Kompatibilitätsendpunkt über den OpenAI-Zweig; lokales Ollama (:11434) nativ.
    private val isOllama: Boolean
        get() = !config.baseUrl.contains("/v1") &&
            (config.baseUrl.contains("ollama", ignoreCase = true) || config.baseUrl.contains(":11434"))

    // Lokaler Endpunkt (eigener Mac/PC im LAN, Emulator-Host, Gerät selbst):
    // braucht KEINEN API-Key und darf Klartext-HTTP nutzen.
    private val isLocalEndpoint: Boolean get() = isLocalAiEndpoint(config.baseUrl)

    suspend fun chat(
        system: String,
        prompt: String,
        temperature: Double = 0.8,
        maxTokens: Int = 2000,
    ): String = withContext(Dispatchers.IO) {
        // Lokale Server (Ollama/llama.cpp) brauchen keinen Key – nur Cloud verlangt einen.
        if (config.apiKey.isBlank() && !isLocalEndpoint)
            throw AiException("Kein API-Key hinterlegt (Einstellungen).")

        val url: String
        val body: JSONObject
        if (isOllama) {
            url = ollamaBaseUrl() + "/api/chat"
            body = ollamaBody(system, prompt, temperature, maxTokens)
        } else {
            url = config.baseUrl.trimEnd('/') + "/chat/completions"
            body = openAiBody(system, prompt, temperature, maxTokens)
        }
        requestWithRetry(url, body)
    }

    private suspend fun requestWithRetry(url: String, body: JSONObject): String {
        val builder = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody(json))
        // Nur bei vorhandenem Key authentifizieren; lokale Server lehnen sonst ab.
        if (config.apiKey.isNotBlank()) builder.addHeader("Authorization", "Bearer ${config.apiKey}")
        val request = builder.build()

        var lastError = "unbekannter Fehler"
        var attempt = 0
        while (attempt < MAX_RETRIES) {
            attempt++
            try {
                http.newCall(request).execute().use { response ->
                    val raw = response.body?.string().orEmpty()
                    if (response.isSuccessful) {
                        val parsed = try {
                            if (isOllama) parseOllama(raw) else parseOpenAi(raw)
                        } catch (e: AiException) {
                            throw e // inhaltlicher Fehler (leere Antwort) ist endgültig
                        } catch (e: Exception) {
                            lastError = "Ungültige Serverantwort"
                            null   // defektes/abgeschnittenes JSON trotz 200 → erneut versuchen
                        }
                        if (parsed != null) return parsed
                    } else if (response.code != 429 && response.code !in 500..599) {
                        // 429 / 5xx sind erneut versuchbar; alles andere ist endgültig.
                        throw AiException("HTTP ${response.code}: ${raw.take(300)}")
                    } else {
                        lastError = "HTTP ${response.code}"
                    }
                }
            } catch (e: IOException) {
                lastError = "Netzwerkfehler: ${e.message}"
            }
            if (attempt < MAX_RETRIES) delay(attempt * 2000L) // 2s, 4s Backoff
        }
        throw AiException("Nach $MAX_RETRIES Versuchen fehlgeschlagen ($lastError).")
    }

    // ---- Ollama (native) ------------------------------------------------------

    private fun ollamaBaseUrl(): String =
        config.baseUrl.trimEnd('/').removeSuffix("/v1").trimEnd('/')

    private fun ollamaBody(system: String, prompt: String, temperature: Double, maxTokens: Int): JSONObject {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", system))
            .put(JSONObject().put("role", "user").put("content", prompt))
        val options = JSONObject()
            .put("temperature", temperature)
            .put("num_predict", maxTokens)
        return JSONObject()
            .put("model", config.model)
            .put("messages", messages)
            .put("stream", false)
            .put("think", false) // Reasoning-Denkschritte aus, damit echter Text kommt
            .put("options", options)
    }

    private fun parseOllama(raw: String): String {
        val message = JSONObject(raw).optJSONObject("message")
        val content = message?.optString("content").orEmpty().trim()
        if (content.isBlank()) {
            val thinking = message?.optString("thinking").orEmpty()
            if (thinking.isNotBlank()) {
                throw AiException("Modell lieferte nur Denkschritte und keinen Text – bitte ein Modell ohne Thinking-Modus wählen.")
            }
            throw AiException("Antwort ohne Inhalt.")
        }
        return content
    }

    // ---- OpenAI-kompatibel ----------------------------------------------------

    private fun openAiBody(system: String, prompt: String, temperature: Double, maxTokens: Int): JSONObject {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", system))
            .put(JSONObject().put("role", "user").put("content", prompt))
        return JSONObject()
            .put("model", config.model)
            .put("messages", messages)
            .put("temperature", temperature)
            .put("max_tokens", maxTokens)
    }

    private fun parseOpenAi(raw: String): String {
        val obj = JSONObject(raw)
        val choices = obj.optJSONArray("choices") ?: throw AiException("Antwort ohne 'choices'.")
        if (choices.length() == 0) throw AiException("Leere Antwort.")
        val message = choices.getJSONObject(0).optJSONObject("message")
        val content = message?.optString("content").orEmpty().trim()
        if (content.isBlank()) throw AiException("Antwort ohne Inhalt.")
        return content
    }

    private companion object {
        const val MAX_RETRIES = 3
    }
}
