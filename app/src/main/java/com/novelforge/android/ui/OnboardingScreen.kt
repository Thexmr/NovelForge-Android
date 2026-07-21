package com.novelforge.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.novelforge.android.ai.AiConfig
import com.novelforge.android.ai.isLocalAiEndpoint
import kotlinx.coroutines.launch

/** Voreingestellte KI-Modi für den Assistenten. */
private enum class AiMode(
    val title: String,
    val hint: String,
    val baseUrl: String,
    val model: String,
    val needsKey: Boolean,
) {
    CLOUD("Cloud (empfohlen zum Start)", "Ollama Cloud / OpenAI-kompatibel. Bester Text, braucht einen API-Key.",
        "https://ollama.com", "kimi-k2.6", true),
    LAN("Lokaler Server im WLAN", "Mac/PC im selben Netz mit Ollama. Kostenlos, kein Key. IP eintragen.",
        "http://192.168.1.10:11434", "qwen2.5:7b", false),
    DEVICE("Modell auf dem Gerät", "Läuft offline direkt auf Handy/Handheld (Ollama/llama.cpp). Langsamer.",
        "http://127.0.0.1:11434", "qwen2.5:3b", false),
}

/**
 * First-Run-Einrichtung: führt in wenigen Schritten zu einer lauffähigen KI-Konfiguration.
 * Wird nur angezeigt, solange der Assistent nicht abgeschlossen und keine nutzbare Config da ist.
 */
@Composable
fun OnboardingScreen(vm: AppViewModel) {
    var step by rememberSaveable { mutableStateOf(0) }
    var mode by rememberSaveable { mutableStateOf(AiMode.CLOUD) }
    var baseUrl by rememberSaveable { mutableStateOf(AiMode.CLOUD.baseUrl) }
    var model by rememberSaveable { mutableStateOf(AiMode.CLOUD.model) }
    var apiKey by rememberSaveable { mutableStateOf("") }

    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testOk by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun applyMode(m: AiMode) {
        mode = m; baseUrl = m.baseUrl; model = m.model
        if (m.needsKey.not()) apiKey = ""
        testResult = null; testOk = false
    }

    val config = AiConfig(baseUrl = baseUrl.trim(), apiKey = apiKey.trim(), model = model.trim())
    val isLocal = isLocalAiEndpoint(baseUrl)
    val canProceed = config.usable

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("NovelForge einrichten", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        StepDots(step, total = 3)

        when (step) {
            0 -> {
                Text("Deine autonome Buch-Werkstatt", style = MaterialTheme.typography.titleLarge)
                Text(
                    "NovelForge schreibt vollständige Romane – Konzept, Kapitel, Überarbeitung und Export. " +
                        "In drei kurzen Schritten ist alles startklar.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Bullet("Cloud- oder lokale KI – deine Wahl")
                Bullet("Läuft im Hintergrund weiter, auch beim Wechsel der App")
                Bullet("Export als EPUB/PDF/DOCX")
            }
            1 -> {
                Text("Welche KI soll schreiben?", style = MaterialTheme.typography.titleLarge)
                AiMode.entries.forEach { m ->
                    ModeCard(m, selected = mode == m, onSelect = { applyMode(m) })
                }
            }
            2 -> {
                Text("Zugang eintragen", style = MaterialTheme.typography.titleLarge)
                Text(mode.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(baseUrl, { baseUrl = it; testResult = null }, label = { Text("Basis-URL") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(model, { model = it; testResult = null }, label = { Text("Modell") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                if (!isLocal) {
                    OutlinedTextField(apiKey, { apiKey = it; testResult = null }, label = { Text("API-Key") },
                        visualTransformation = PasswordVisualTransformation(), singleLine = true,
                        modifier = Modifier.fillMaxWidth())
                } else {
                    Text("Lokaler Server erkannt – kein API-Key nötig.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                }

                OutlinedButton(
                    onClick = {
                        testing = true; testResult = null
                        scope.launch {
                            val r = vm.testConnection(config)
                            testing = false
                            testOk = r.isSuccess
                            testResult = if (r.isSuccess) "Verbindung erfolgreich – KI antwortet."
                            else "Fehlgeschlagen: ${r.exceptionOrNull()?.message ?: "unbekannt"}"
                        }
                    },
                    enabled = canProceed && !testing,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (testing) {
                        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.height(18.dp))
                        Spacer(Modifier.height(0.dp))
                        Text("  Teste …")
                    } else Text("Verbindung testen")
                }
                testResult?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall,
                        color = if (testOk) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error)
                }
            }
        }

        Spacer(Modifier.height(4.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (step > 0) {
                OutlinedButton(onClick = { step-- }, modifier = Modifier.weight(1f)) { Text("Zurück") }
            }
            if (step < 2) {
                Button(onClick = { step++ }, modifier = Modifier.weight(1f)) { Text("Weiter") }
            } else {
                Button(
                    onClick = { vm.completeOnboarding(config) },
                    enabled = canProceed,
                    modifier = Modifier.weight(1f),
                ) { Text("Los geht's") }
            }
        }

        TextButton(onClick = { vm.skipOnboarding() }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("Später einrichten")
        }
    }
}

@Composable
private fun ModeCard(mode: AiMode, selected: Boolean, onSelect: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().selectable(selected = selected, onClick = onSelect),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant
        ),
    ) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = onSelect)
            Column(Modifier.padding(start = 8.dp)) {
                Text(mode.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(mode.hint, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun StepDots(step: Int, total: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(total) { i ->
            Text(if (i == step) "●" else "○",
                color = if (i == step) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
private fun Bullet(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("•", color = MaterialTheme.colorScheme.primary)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
