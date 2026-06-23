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
)

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

    private val isOllama: Boolean get() = config.baseUrl.contains("ollama", ignoreCase = true)

    suspend fun chat(
        system: String,
        prompt: String,
        temperature: Double = 0.8,
        maxTokens: Int = 2000,
    ): String = withContext(Dispatchers.IO) {
        if (config.apiKey.isBlank()) throw AiException("Kein API-Key hinterlegt (Einstellungen).")

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
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody(json))
            .build()

        var lastError = "unbekannter Fehler"
        var attempt = 0
        while (attempt < MAX_RETRIES) {
            attempt++
            try {
                http.newCall(request).execute().use { response ->
                    val raw = response.body?.string().orEmpty()
                    if (response.isSuccessful) {
                        return if (isOllama) parseOllama(raw) else parseOpenAi(raw)
                    }
                    // 429 / 5xx sind erneut versuchbar; alles andere ist endgültig.
                    if (response.code != 429 && response.code !in 500..599) {
                        throw AiException("HTTP ${response.code}: ${raw.take(300)}")
                    }
                    lastError = "HTTP ${response.code}"
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
