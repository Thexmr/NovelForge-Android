package com.novelforge.android.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.novelforge.android.ai.AiConfig
import com.novelforge.android.ai.isLocalAiEndpoint
import com.novelforge.android.domain.Chapter
import com.novelforge.android.domain.Genres
import com.novelforge.android.domain.Project
import com.novelforge.android.domain.ProjectStatus
import com.novelforge.android.export.ExportBuilder
import com.novelforge.android.ui.theme.Indigo
import com.novelforge.android.ui.theme.NoirGold
import com.novelforge.android.ui.theme.Violet
import java.text.NumberFormat
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---- Responsivität ---------------------------------------------------------------
// Eine einzige Schwelle entscheidet, ob breit (Querformat/Tablet) layoutet wird.
private const val WIDE_DP = 600

private fun words(n: Int): String = NumberFormat.getInstance(Locale.GERMANY).format(n)

// Markentreue Akzente (kein zufälliger Regenbogen – nur Indigo/Gold/Violett).
private val accentTrio = listOf(Indigo, NoirGold, Violet)
private fun accentFor(seed: String): Color = accentTrio[(seed.hashCode() and 0x7FFFFFFF) % accentTrio.size]

/**
 * Scrollender Bildschirm-Rahmen: zentriert, auf lesbare Breite begrenzt und mit
 * orientierungsabhängigem Innenabstand. `wide` steht dem Inhalt für Spaltenlayouts bereit.
 */
@Composable
private fun ScrollScreen(content: @Composable ColumnScope.(wide: Boolean) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= WIDE_DP.dp
        val maxW = if (wide) 760.dp else maxWidth
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = maxW)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = if (wide) 24.dp else 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) { content(wide) }
    }
}

/** Zwei Felder: nebeneinander auf breiten Schirmen, gestapelt auf schmalen. */
@Composable
private fun FieldPair(wide: Boolean, a: @Composable () -> Unit, b: @Composable () -> Unit) {
    if (wide) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.weight(1f)) { a() }
            Box(Modifier.weight(1f)) { b() }
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { a(); b() }
    }
}

// ---- gemeinsame Bausteine --------------------------------------------------------

@Composable
private fun Monogram(size: Int = 42) {
    val shape = RoundedCornerShape((size / 3.2f).dp)
    Box(
        Modifier
            .size(size.dp)
            .background(Brush.linearGradient(listOf(Indigo, Violet)), shape)
            .border(1.dp, NoirGold.copy(alpha = 0.55f), shape),
        contentAlignment = Alignment.Center
    ) {
        Text("N", color = Color.White, fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.SemiBold, fontSize = (size * 0.5f).sp)
    }
}

@Composable
private fun BrandHeader(title: String, subtitle: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Monogram()
        Column {
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Medium,
                letterSpacing = 0.5.sp)
            Text(subtitle.uppercase(), style = MaterialTheme.typography.labelSmall,
                color = NoirGold.copy(alpha = 0.85f), letterSpacing = 2.sp)
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 1.sp,
        fontWeight = FontWeight.Medium)
}

@Composable
private fun StatTile(value: String, label: String, color: Color, modifier: Modifier = Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 11.dp)) {
            Text(value, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold,
                fontSize = 20.sp, color = color)
            Spacer(Modifier.height(2.dp))
            SectionLabel(label)
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
    Box(
        Modifier
            .background(color.copy(alpha = 0.16f), RoundedCornerShape(999.dp))
            .padding(horizontal = 9.dp, vertical = 3.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = color, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            SectionLabel(title)
            Spacer(Modifier.height(6.dp))
            content()
        }
    }
}

// ---- Dashboard -------------------------------------------------------------------

@Composable
fun DashboardScreen(vm: AppViewModel, onOpen: (String) -> Unit) {
    val projects by vm.projects.collectAsState()
    val progress by vm.progress.collectAsState()
    val generatingId by vm.generatingId.collectAsState()
    val auto by vm.autoRunning.collectAsState()
    val completed by vm.completed.collectAsState()
    val config by vm.config.collectAsState()
    val apiMissing = !config.usable

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= WIDE_DP.dp
        val maxW = if (wide) 860.dp else maxWidth
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = maxW)
                .fillMaxSize()
                .padding(horizontal = if (wide) 24.dp else 16.dp, vertical = 16.dp)
        ) {
            BrandHeader("NovelForge", "KI-Romanproduktion · KDP")
            Spacer(Modifier.height(18.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile(projects.size.toString(), "Projekte", MaterialTheme.colorScheme.primary, Modifier.weight(1f))
                StatTile(projects.count { it.status == ProjectStatus.COMPLETED }.toString(), "Fertig",
                    MaterialTheme.colorScheme.tertiary, Modifier.weight(1f))
                StatTile(words(projects.sumOf { it.wordCount }), "Wörter",
                    MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
            }
            Spacer(Modifier.height(16.dp))

            val active = projects.firstOrNull { it.id == generatingId }
            // Auto-Karte und Produktions-Karte: auf breiten Schirmen nebeneinander.
            val autoCard: @Composable () -> Unit = {
                AutoModeCard(auto, completed, apiMissing, onStart = { vm.startAuto() }, onStop = { vm.stopGeneration() })
            }
            val prodCard: (@Composable () -> Unit)? = progress?.let { p ->
                {
                    ProductionCard(p.phase, p.fraction, active?.title, showStop = !auto) { vm.stopGeneration() }
                }
            }
            if (wide && prodCard != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.weight(1f)) { autoCard() }
                    Box(Modifier.weight(1f)) { prodCard() }
                }
                Spacer(Modifier.height(16.dp))
            } else {
                autoCard()
                Spacer(Modifier.height(16.dp))
                if (prodCard != null) {
                    prodCard()
                    Spacer(Modifier.height(16.dp))
                }
            }

            SectionLabel("Deine Bücher")
            Spacer(Modifier.height(8.dp))

            if (projects.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.MenuBook, contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(40.dp))
                        Spacer(Modifier.height(10.dp))
                        Text("Noch keine Bücher", style = MaterialTheme.typography.titleSmall)
                        Text("Tippe unten auf Neues Buch, um zu starten.", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    items(projects) { project ->
                        ProjectRow(project) { onOpen(project.id) }
                    }
                }
            }
        }
    }
}

@Composable
private fun AutoModeCard(
    auto: Boolean, completed: Int, apiMissing: Boolean,
    onStart: () -> Unit, onStop: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Auto-Modus", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    if (auto) "Läuft im Hintergrund · $completed fertig"
                    else "Erzeugt automatisch Buch um Buch – läuft weiter, während du dein Handy nutzt.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(10.dp))
            if (auto) {
                Button(onClick = onStop,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Stop") }
            } else {
                Button(onClick = onStart, enabled = !apiMissing) { Text("Start") }
            }
        }
    }
}

@Composable
private fun ProductionCard(phase: String, fraction: Float, title: String?, showStop: Boolean, onStop: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                SectionLabel("In Produktion")
                Text("${(fraction * 100).toInt()}%", fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (title != null) {
                Spacer(Modifier.height(4.dp))
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(3.dp))
            Text(phase, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(9.dp))
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            if (showStop) {
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) {
                    Text("Generierung stoppen")
                }
            }
        }
    }
}

@Composable
private fun ProjectRow(project: Project, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable { onClick() }) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(4.dp).fillMaxHeight().background(accentFor(project.id)))
            Column(Modifier.padding(14.dp)) {
                Text(project.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                Text("${project.authorName} · ${project.genre}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatusBadge(project.status)
                    Text("${words(project.wordCount)} Wörter", fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

// ---- gemeinsame Eingabe-Bausteine ------------------------------------------------

@Composable
private fun LabeledDropdown(label: String, options: List<String>, selected: String, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        SectionLabel(label)
        Spacer(Modifier.height(4.dp))
        Box {
            OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
                Text(selected.ifBlank { "Bitte wählen" }, modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium)
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
private fun SegmentedSpice(selected: Int, onSelect: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        SectionLabel("Sinnlichkeitsgrad")
        Spacer(Modifier.height(5.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(10.dp))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            for (level in 0..5) {
                val on = selected == level
                Box(
                    Modifier
                        .weight(1f)
                        .clickable { onSelect(level) }
                        .background(
                            if (on) MaterialTheme.colorScheme.primary else Color.Transparent,
                            RoundedCornerShape(8.dp)
                        )
                        .padding(vertical = 7.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (level == 0) "—" else level.toString(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (on) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
            }
        }
    }
}

// ---- Neues Buch ------------------------------------------------------------------

@Composable
fun NewBookScreen(vm: AppViewModel, onCreated: (String) -> Unit) {
    val config by vm.config.collectAsState()

    // rememberSaveable: Eingaben überleben Drehung/Rotation sauber.
    var title by rememberSaveable { mutableStateOf("") }
    var author by rememberSaveable { mutableStateOf("") }
    var genre by rememberSaveable { mutableStateOf("Liebesroman") }
    var style by rememberSaveable { mutableStateOf("atmosphärisch") }
    var tropes by rememberSaveable { mutableStateOf("") }
    var series by rememberSaveable { mutableStateOf("") }
    var spice by rememberSaveable { mutableIntStateOf(0) }
    var pages by rememberSaveable { mutableStateOf("300") }
    var chapters by rememberSaveable { mutableStateOf("24") }

    val canCreate = title.isNotBlank() && author.isNotBlank() && config.usable

    ScrollScreen { wide ->
        Column {
            Text("Neues Buch", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Medium)
            SectionLabel("In Minuten zum fertigen KDP-Roman")
        }

        FieldPair(wide,
            { OutlinedTextField(title, { title = it }, label = { Text("Titel") }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
            { OutlinedTextField(author, { author = it }, label = { Text("Autor / Pseudonym") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
        )

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f)) { LabeledDropdown("Genre", Genres.all, genre) { genre = it } }
            Box(Modifier.weight(1f)) { LabeledDropdown("Stil", Genres.styles, style) { style = it } }
        }

        SegmentedSpice(spice) { spice = it }

        FieldPair(wide,
            { OutlinedTextField(tropes, { tropes = it }, label = { Text("Tropes (kommagetrennt)") }, modifier = Modifier.fillMaxWidth()) },
            { OutlinedTextField(series, { series = it }, label = { Text("Serie / Reihe (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(pages, { v -> pages = v.filter { it.isDigit() }.take(4) },
                label = { Text("Seiten") }, singleLine = true, modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            OutlinedTextField(chapters, { v -> chapters = v.filter { it.isDigit() }.take(3) },
                label = { Text("Kapitel") }, singleLine = true, modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        }

        if (!config.usable) {
            Text("Erst in den Einstellungen einen API-Key hinterlegen (oder einen lokalen Server eintragen).",
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        Button(
            onClick = {
                val project = Project(
                    title = title.trim(), authorName = author.trim(), language = "Deutsch",
                    genre = genre, styleProfile = style, tropes = tropes.trim(),
                    spiceLevel = spice, seriesName = series.trim(),
                    seriesNumber = if (series.isBlank()) 0 else 1,
                    targetPageCount = (pages.toIntOrNull() ?: 300).coerceIn(40, 1000),
                    chapterTarget = (chapters.toIntOrNull() ?: 24).coerceIn(3, 120),
                    createdAt = System.currentTimeMillis(),
                )
                onCreated(vm.createAndGenerate(project))
            },
            enabled = canCreate,
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) {
            Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Buch erstellen & generieren")
        }
    }
}

// ---- Einstellungen ---------------------------------------------------------------

@Composable
fun SettingsScreen(vm: AppViewModel) {
    val config by vm.config.collectAsState()
    var baseUrl by rememberSaveable { mutableStateOf(AiConfig().baseUrl) }
    var apiKey by rememberSaveable { mutableStateOf("") }
    var model by rememberSaveable { mutableStateOf(AiConfig().model) }
    var writingModel by rememberSaveable { mutableStateOf("") }
    var seeded by rememberSaveable { mutableStateOf(false) }
    // Gespeicherte Werte EINMAL übernehmen, sobald die echte (vom Default abweichende) Konfiguration
    // aus DataStore eintrifft – laufende Eingaben bleiben erhalten.
    LaunchedEffect(config) {
        if (!seeded && config != AiConfig()) {
            baseUrl = config.baseUrl; model = config.model
            apiKey = config.apiKey; writingModel = config.writingModel
            seeded = true
        }
    }

    // Lokaler Endpunkt (LAN-IP, localhost, Emulator-Host) → kein API-Key nötig.
    val isLocal = isLocalAiEndpoint(baseUrl)
    val configOk = config.usable

    ScrollScreen {
        Text("Einstellungen", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Medium)
        SectionLabel("KI-Anbieter")
        Text("Cloud (Ollama / OpenAI-kompatibel) ODER lokal (Ollama, llama.cpp, LM Studio). Lokal braucht keinen API-Key. Key wird nur auf dem Gerät gespeichert.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        // Schnellvorlagen: Cloud vs. lokaler Mac im LAN vs. Modell direkt auf dem Gerät.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                baseUrl = "https://ollama.com"; model = "kimi-k2.6"
            }, modifier = Modifier.weight(1f)) { Text("Cloud", maxLines = 1) }
            OutlinedButton(onClick = {
                // Mac/PC im selben WLAN mit laufendem Ollama. IP anpassen!
                baseUrl = "http://192.168.1.10:11434"; model = "qwen2.5:7b"; apiKey = ""
            }, modifier = Modifier.weight(1f)) { Text("Lokal LAN", maxLines = 1) }
            OutlinedButton(onClick = {
                // Modell direkt auf dem Gerät (z. B. Ollama/Termux, llama.cpp-Server).
                baseUrl = "http://127.0.0.1:11434"; model = "qwen2.5:3b"; apiKey = ""
            }, modifier = Modifier.weight(1f)) { Text("Gerät", maxLines = 1) }
        }
        if (isLocal) Text("Lokaler Endpunkt erkannt – kein API-Key nötig. IP/Port ggf. an deinen Server anpassen (Ollama: :11434, llama.cpp/LM Studio: .../v1).",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)

        OutlinedTextField(baseUrl, { baseUrl = it }, label = { Text("Basis-URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(model, { model = it }, label = { Text("Modell") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(apiKey, { apiKey = it }, label = { Text("API-Key") },
            visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(writingModel, { writingModel = it }, label = { Text("Schreibmodell (optional)") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        Text("Leer = überall dasselbe Modell. Sonst wird dieses (stärkere) Modell nur fürs Schreiben der Kapitel genutzt.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Button(
            onClick = { vm.saveSettings(AiConfig(baseUrl.trim(), apiKey.trim(), model.trim(), writingModel.trim())) },
            modifier = Modifier.fillMaxWidth().height(50.dp)
        ) { Text("Speichern") }

        StatusBadge(if (configOk) ProjectStatus.COMPLETED else ProjectStatus.FAILED)
        Text(
            when {
                config.apiKey.isNotBlank() -> "API-Key ist hinterlegt"
                configOk -> "Lokaler Server – kein API-Key nötig"
                else -> "Kein API-Key hinterlegt"
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (configOk) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error)
    }
}

// ---- Projekt-Detail --------------------------------------------------------------

@Composable
fun ProjectScreen(
    vm: AppViewModel, projectId: String,
    onOpenProject: (String) -> Unit = {}, onBack: () -> Unit = {},
    onKdpUpload: (String) -> Unit = {},
) {
    val projects by vm.projects.collectAsState()
    val progress by vm.progress.collectAsState()
    val error by vm.error.collectAsState()
    val generatingId by vm.generatingId.collectAsState()
    val project = projects.firstOrNull { it.id == projectId }

    if (project == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Projekt nicht gefunden.") }
        return
    }

    val isGenerating = generatingId == project.id
    var confirmDelete by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    // Export-Bytes (ganzes Buch) im Hintergrund schreiben → kein ANR; Rückmeldung per Toast.
    fun export(uri: Uri?, label: String, bytes: () -> ByteArray) {
        if (uri == null) return
        // Export-Sperre: Gliederungen/Platzhalter werden nie als „Buch" exportiert (KDP-Schutz).
        ExportBuilder.exportBlocker(project)?.let { reason ->
            Toast.makeText(context, reason, Toast.LENGTH_LONG).show()
            return
        }
        scope.launch(Dispatchers.IO) {
            val failure = runCatching {
                context.contentResolver.openOutputStream(uri)?.use { it.write(bytes()) } ?: error("kein Stream")
            }.exceptionOrNull()
            withContext(Dispatchers.Main) {
                val msg = if (failure == null) "$label gespeichert"
                    else "$label fehlgeschlagen: ${failure.message ?: "unbekannter Fehler"}"
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }
    val epubLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/epub+zip")
    ) { uri -> export(uri, "EPUB") { ExportBuilder.epubBytes(project) } }
    val txtLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri -> export(uri, "Manuskript") { ExportBuilder.manuscriptText(project).toByteArray() } }
    val pdfLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri -> export(uri, "PDF") { ExportBuilder.pdfBytes(project) } }
    val docxLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.wordprocessingml.document")
    ) { uri -> export(uri, "Word-Datei") { ExportBuilder.docxBytes(project) } }

    ScrollScreen {
        Text(project.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Medium)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatusBadge(project.status)
            Text(
                "${words(project.wordCount)} Wörter · ${project.chapters.size} Kapitel" +
                    if (project.seriesNumber > 0) " · Band ${project.seriesNumber}" else "",
                fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Reihe fortsetzen: aus einem fertigen Band den nächsten erzeugen (Figuren/Welt wandern mit).
        if (project.status == ProjectStatus.COMPLETED) {
            Button(
                onClick = {
                    val id = vm.createSequel(project.id)
                    if (id != null) {
                        Toast.makeText(context, "Nächster Band wird erstellt …", Toast.LENGTH_SHORT).show()
                        onOpenProject(id)
                    } else {
                        Toast.makeText(context, "Fortsetzung konnte nicht erstellt werden.", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.fillMaxWidth().height(50.dp)
            ) {
                Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (project.seriesNumber > 0) "Band ${project.seriesNumber + 1} dieser Reihe erzeugen"
                    else "Fortsetzung (Band 2) erzeugen"
                )
            }
        }

        if (isGenerating) {
            progress?.let { p ->
                Card(
                    Modifier.fillMaxWidth(),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(p.phase, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(progress = { p.fraction }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }

        error?.let {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.12f))
            ) {
                Text("Fehler: $it", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(14.dp),
                    style = MaterialTheme.typography.bodySmall)
            }
        }

        if (project.chapters.any { it.text.isNotBlank() }) {
            SectionCard("Export") {
                Text("Wähle ein Format – auf dem Gerät speichern oder teilen.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { epubLauncher.launch("${project.title}.epub") }, modifier = Modifier.weight(1f)) { Text("EPUB") }
                    OutlinedButton(onClick = { pdfLauncher.launch("${project.title}.pdf") }, modifier = Modifier.weight(1f)) { Text("PDF") }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { docxLauncher.launch("${project.title}.docx") }, modifier = Modifier.weight(1f)) { Text("Google Docs") }
                    OutlinedButton(onClick = { txtLauncher.launch("${project.title}.txt") }, modifier = Modifier.weight(1f)) { Text(".txt") }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                                .putExtra(Intent.EXTRA_SUBJECT, project.title)
                                .putExtra(Intent.EXTRA_TEXT, ExportBuilder.manuscriptText(project))
                            context.startActivity(Intent.createChooser(send, "Manuskript teilen"))
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Teilen") }
                    OutlinedButton(
                        onClick = { clipboard.setText(AnnotatedString(ExportBuilder.kdpSheet(project))) },
                        modifier = Modifier.weight(1f)
                    ) { Text("KDP kopieren") }
                }
                Spacer(Modifier.height(8.dp))
                // Autonomer KDP-Upload (WebView): Login bleibt, EPUB+Cover automatisch,
                // speichert nur als Entwurf.
                Button(
                    onClick = { onKdpUpload(project.id) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Autonom zu KDP hochladen (Entwurf)") }
            }
        }

        if (project.profile.kdpTitle.isNotBlank()) {
            SectionCard("KDP-Verkaufstitel") {
                Text(project.profile.kdpTitle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (project.profile.kdpSubtitle.isNotBlank()) {
                    Text(project.profile.kdpSubtitle, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (project.profile.kdpDescription.isNotBlank()) {
            SectionCard("KDP-Verkaufstext") { Text(project.profile.kdpDescription, style = MaterialTheme.typography.bodySmall) }
        }
        if (project.profile.kdpKeywords.isNotBlank()) {
            SectionCard("Keywords") {
                Text(project.profile.kdpKeywords, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        if (project.profile.coverPrompt.isNotBlank()) {
            SectionCard("Cover-Prompt") {
                Text(project.profile.coverPrompt, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { clipboard.setText(AnnotatedString(project.profile.coverPrompt)) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Cover-Prompt kopieren") }
            }
        }

        if (project.characters.isNotEmpty()) {
            SectionCard("Figuren") {
                project.characters.forEach { c ->
                    Text(
                        c.name + if (c.role.isNotBlank()) " · ${c.role}" else "",
                        style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium
                    )
                    if (c.goal.isNotBlank()) {
                        Text("Ziel: ${c.goal}", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
        }

        if (project.chapters.isNotEmpty()) {
            SectionLabel("Manuskript")
            project.chapters.forEach { ch -> ChapterCard(ch) }
        }

        if (!isGenerating) {
            Spacer(Modifier.height(4.dp))
            OutlinedButton(
                onClick = { confirmDelete = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) { Text("Buch löschen") }
        }

        if (confirmDelete) {
            AlertDialog(
                onDismissRequest = { confirmDelete = false },
                title = { Text("Buch löschen?") },
                text = { Text("${project.title} wird dauerhaft entfernt.") },
                confirmButton = {
                    TextButton(onClick = {
                        confirmDelete = false
                        vm.deleteProject(project.id)
                        onBack()
                    }) { Text("Löschen", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmDelete = false }) { Text("Abbrechen") }
                }
            )
        }
    }
}

@Composable
private fun ChapterCard(ch: Chapter) {
    var expanded by rememberSaveable(ch.number) { mutableStateOf(false) }
    val full = ch.text.ifBlank { ch.goal }
    SectionCard("Kapitel ${ch.number}: ${ch.title}") {
        Text(
            if (expanded || full.length <= 600) full else full.take(600) + " …",
            style = MaterialTheme.typography.bodySmall
        )
        if (full.length > 600) {
            Spacer(Modifier.height(6.dp))
            OutlinedButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
                Text(if (expanded) "Einklappen" else "Ganzes Kapitel lesen")
            }
        }
    }
}
