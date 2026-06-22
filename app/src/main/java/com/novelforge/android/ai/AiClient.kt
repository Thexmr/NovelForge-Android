package com.novelforge.android.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class AiConfig(
    val baseUrl: String = "https://ollama.com/v1",
    val apiKey: String = "",
    val model: String = "kimi-k2.6",
)

class AiException(message: String) : Exception(message)

/**
 * Minimaler OpenAI-kompatibler Chat-Client (POST /chat/completions).
 * Funktioniert mit Ollama Cloud und anderen OpenAI-kompatiblen Anbietern.
 */
class AiClient(private val config: AiConfig) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val json = "application/json; charset=utf-8".toMediaType()

    suspend fun chat(
        system: String,
        prompt: String,
        temperature: Double = 0.8,
        maxTokens: Int = 2000,
    ): String = withContext(Dispatchers.IO) {
        if (config.apiKey.isBlank()) throw AiException("Kein API-Key hinterlegt (Einstellungen).")

        val messages = JSONArray().apply {
            put(JSONObject().put("role", "system").put("content", system))
            put(JSONObject().put("role", "user").put("content", prompt))
        }
        val body = JSONObject()
            .put("model", config.model)
            .put("messages", messages)
            .put("temperature", temperature)
            .put("max_tokens", maxTokens)
            .toString()
            .toRequestBody(json)

        val url = config.baseUrl.trimEnd('/') + "/chat/completions"
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .post(body)
            .build()

        http.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw AiException("HTTP ${response.code}: ${raw.take(300)}")
            }
            parseContent(raw)
        }
    }

    private fun parseContent(raw: String): String {
        val obj = JSONObject(raw)
        val choices = obj.optJSONArray("choices")
            ?: throw AiException("Antwort ohne 'choices'.")
        if (choices.length() == 0) throw AiException("Leere Antwort.")
        val message = choices.getJSONObject(0).optJSONObject("message")
        val content = message?.optString("content").orEmpty()
        if (content.isBlank()) throw AiException("Antwort ohne Inhalt.")
        return content.trim()
    }
}
