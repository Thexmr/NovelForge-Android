package com.novelforge.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.novelforge.android.ai.AiConfig
import com.novelforge.android.domain.Genres
import com.novelforge.android.domain.Project
import com.novelforge.android.domain.ProjectStatus
import com.novelforge.android.domain.SpiceLevel

// ---- gemeinsame Bausteine --------------------------------------------------------

@Composable
private fun LabeledDropdown(
    label: String,
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
                Text(selected.ifBlank { "Bitte wählen" }, modifier = Modifier.fillMaxWidth())
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { opt ->
                    DropdownMenuItem(text = { Text(opt) }, onClick = { onSelect(opt); open = false })
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(status: ProjectStatus) {
    val (text, color) = when (status) {
        ProjectStatus.COMPLETED -> "Abgeschlossen" to MaterialTheme.colorScheme.tertiary
        ProjectStatus.GENERATING -> "Generiert …" to MaterialTheme.colorScheme.primary
        ProjectStatus.FAILED -> "Fehlgeschlagen" to MaterialTheme.colorScheme.error
        ProjectStatus.CREATED -> "Entwurf" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(text, style = MaterialTheme.typography.labelSmall, color = color, fontWeight = FontWeight.SemiBold)
}

// ---- Dashboard -------------------------------------------------------------------

@Composable
fun DashboardScreen(vm: AppViewModel, onOpen: (String) -> Unit) {
    val projects by vm.projects.collectAsState()
    val progress by vm.progress.collectAsState()

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("NovelForge", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("KI-Romanproduktion für Amazon KDP", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard("Projekte", projects.size.toString(), Modifier.weight(1f))
            StatCard("Fertig", projects.count { it.status == ProjectStatus.COMPLETED }.toString(), Modifier.weight(1f))
            StatCard("Wörter", projects.sumOf { it.wordCount }.toString(), Modifier.weight(1f))
        }
        Spacer(Modifier.height(16.dp))

        progress?.let { p ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text(p.phase, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(progress = { p.fraction }, modifier = Modifier.fillMaxWidth())
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        if (projects.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Noch keine Bücher. Tippe unten auf Neues Buch.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(projects) { project ->
                    Card(Modifier.fillMaxWidth().clickable { onOpen(project.id) }) {
                        Column(Modifier.padding(14.dp)) {
                            Text(project.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text("${project.authorName} · ${project.genre}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                StatusBadge(project.status)
                                Text("${project.wordCount} Wörter", style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(14.dp)) {
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---- Neues Buch ------------------------------------------------------------------

@Composable
fun NewBookScreen(vm: AppViewModel, onCreated: (String) -> Unit) {
    val config by vm.config.collectAsState()

    var title by remember { mutableStateOf("") }
    var author by remember { mutableStateOf("") }
    var genre by remember { mutableStateOf("Liebesroman") }
    var style by remember { mutableStateOf("atmosphärisch") }
    var tropes by remember { mutableStateOf("") }
    var series by remember { mutableStateOf("") }
    var spice by remember { mutableIntStateOf(0) }
    var pages by remember { mutableIntStateOf(300) }
    var chapters by remember { mutableIntStateOf(24) }

    val spiceOptions = listOf("Nicht angegeben") + SpiceLevel.range.map { SpiceLevel.pickerLabel(it) }
    val canCreate = title.isNotBlank() && author.isNotBlank() && config.apiKey.isNotBlank()

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Neues Buch", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        OutlinedTextField(title, { title = it }, label = { Text("Titel") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(author, { author = it }, label = { Text("Autor / Pseudonym") }, modifier = Modifier.fillMaxWidth())
        LabeledDropdown("Genre", Genres.all, genre) { genre = it }
        LabeledDropdown("Stil", Genres.styles, style) { style = it }
        OutlinedTextField(tropes, { tropes = it },
            label = { Text("Tropes (kommagetrennt)") }, modifier = Modifier.fillMaxWidth())

        LabeledDropdown("Sinnlichkeitsgrad", spiceOptions,
            if (spice == 0) "Nicht angegeben" else SpiceLevel.pickerLabel(spice)) { sel ->
            spice = SpiceLevel.range.firstOrNull { SpiceLevel.pickerLabel(it) == sel } ?: 0
        }
        Text("Branchenübliche Einstufung der erotischen Intensität (1–5). Steuert Szenen-Ausführlichkeit und KDP-Einordnung.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        OutlinedTextField(series, { series = it },
            label = { Text("Serie / Reihe (optional – für Read-Through)") }, modifier = Modifier.fillMaxWidth())

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(pages.toString(), { pages = it.toIntOrNull() ?: pages },
                label = { Text("Seiten") }, modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            OutlinedTextField(chapters.toString(), { chapters = it.toIntOrNull() ?: chapters },
                label = { Text("Kapitel") }, modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        }

        if (config.apiKey.isBlank()) {
            Text("Hinweis: Erst in den Einstellungen einen API-Key hinterlegen.",
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        Button(
            onClick = {
                val project = Project(
                    title = title.trim(), authorName = author.trim(), language = "Deutsch",
                    genre = genre, styleProfile = style, tropes = tropes.trim(),
                    spiceLevel = spice, seriesName = series.trim(),
                    seriesNumber = if (series.isBlank()) 0 else 1,
                    targetPageCount = pages.coerceIn(40, 1000),
                    chapterTarget = chapters.coerceIn(3, 120),
                    createdAt = System.currentTimeMillis(),
                )
                onCreated(vm.createAndGenerate(project))
            },
            enabled = canCreate,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Buch erstellen & generieren") }
    }
}

// ---- Einstellungen ---------------------------------------------------------------

@Composable
fun SettingsScreen(vm: AppViewModel) {
    val config by vm.config.collectAsState()
    var baseUrl by remember(config.baseUrl) { mutableStateOf(config.baseUrl) }
    var apiKey by remember(config.apiKey) { mutableStateOf(config.apiKey) }
    var model by remember(config.model) { mutableStateOf(config.model) }

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("KI-Anbieter", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("OpenAI-kompatibler Endpunkt (z.B. Ollama Cloud). Der Key wird nur lokal gespeichert.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        OutlinedTextField(baseUrl, { baseUrl = it }, label = { Text("Basis-URL") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(model, { model = it }, label = { Text("Modell") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(apiKey, { apiKey = it }, label = { Text("API-Key") },
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())

        Button(
            onClick = { vm.saveSettings(AiConfig(baseUrl.trim(), apiKey.trim(), model.trim())) },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Speichern") }

        Text(if (config.apiKey.isBlank()) "Kein API-Key hinterlegt" else "API-Key ist hinterlegt ✓",
            style = MaterialTheme.typography.bodySmall,
            color = if (config.apiKey.isBlank()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary)
    }
}

// ---- Projekt-Detail --------------------------------------------------------------

@Composable
fun ProjectScreen(vm: AppViewModel, projectId: String) {
    val projects by vm.projects.collectAsState()
    val progress by vm.progress.collectAsState()
    val error by vm.error.collectAsState()
    val generatingId by vm.generatingId.collectAsState()
    val project = projects.firstOrNull { it.id == projectId }

    if (project == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Projekt nicht gefunden.") }
        return
    }

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(project.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatusBadge(project.status)
            Text("${project.wordCount} Wörter · ${project.chapters.size} Kapitel",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        progress?.takeIf { generatingId == project.id }?.let { p ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text(p.phase, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(progress = { p.fraction }, modifier = Modifier.fillMaxWidth())
                }
            }
        }

        error?.let {
            Card(Modifier.fillMaxWidth()) {
                Text("Fehler: $it", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(14.dp))
            }
        }

        if (project.profile.kdpDescription.isNotBlank()) {
            SectionCard("KDP-Verkaufstext") { Text(project.profile.kdpDescription) }
        }
        if (project.profile.kdpKeywords.isNotBlank()) {
            SectionCard("Keywords") { Text(project.profile.kdpKeywords) }
        }

        project.chapters.forEach { ch ->
            SectionCard("Kapitel ${ch.number}: ${ch.title}") {
                Text(ch.text.ifBlank { ch.goal }.take(2000),
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            content()
        }
    }
}
