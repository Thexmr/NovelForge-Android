package com.novelforge.android.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import com.novelforge.android.export.ExportBuilder
import com.novelforge.android.ui.theme.NoirGold
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.MenuBook
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.novelforge.android.ai.AiConfig
import com.novelforge.android.domain.Genres
import com.novelforge.android.domain.Project
import com.novelforge.android.domain.ProjectStatus
import com.novelforge.android.domain.SpiceLevel
import java.text.NumberFormat
import java.util.Locale

// ---- gemeinsame Bausteine --------------------------------------------------------

private fun words(n: Int): String = NumberFormat.getInstance(Locale.GERMANY).format(n)

private val accents = listOf(Color(0xFF6B73FF), Color(0xFF8F80EB), Color(0xFF5DCAA5), Color(0xFFE3B24A))
private fun accentFor(seed: String): Color = accents[(seed.hashCode() and 0x7FFFFFFF) % accents.size]

@Composable
private fun Monogram(size: Int = 42) {
    val shape = RoundedCornerShape((size / 3.2f).dp)
    Box(
        Modifier
            .size(size.dp)
            .background(Brush.linearGradient(listOf(Color(0xFF6B73FF), Color(0xFF8F80EB))), shape)
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
            Text(title, style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Medium, letterSpacing = 0.5.sp)
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
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
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

// ---- Dashboard -------------------------------------------------------------------

@Composable
fun DashboardScreen(vm: AppViewModel, onOpen: (String) -> Unit) {
    val projects by vm.projects.collectAsState()
    val progress by vm.progress.collectAsState()
    val generatingId by vm.generatingId.collectAsState()
    val auto by vm.autoRunning.collectAsState()
    val completed by vm.completed.collectAsState()
    val config by vm.config.collectAsState()
    val apiMissing = config.apiKey.isBlank()

    Column(Modifier.fillMaxSize().padding(16.dp)) {
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
                    Button(
                        onClick = { vm.stopGeneration() },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) { Text("Stop") }
                } else {
                    Button(onClick = { vm.startAuto() }, enabled = !apiMissing) { Text("Start") }
                }
            }
        }
        Spacer(Modifier.height(16.dp))

        val active = projects.firstOrNull { it.id == generatingId }
        progress?.let { p ->
            Card(
                Modifier.fillMaxWidth(),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
            ) {
                Column(Modifier.padding(14.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        SectionLabel("In Produktion")
                        Text("${(p.fraction * 100).toInt()}%", fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (active != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(active.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(p.phase, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(9.dp))
                    LinearProgressIndicator(progress = { p.fraction }, modifier = Modifier.fillMaxWidth())
                    if (!auto) {
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(onClick = { vm.stopGeneration() }, modifier = Modifier.fillMaxWidth()) {
                            Text("Generierung stoppen")
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        SectionLabel("Deine Bücher")
        Spacer(Modifier.height(8.dp))

        if (projects.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
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
            LazyColumn(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                items(projects) { project ->
                    ProjectRow(project) { onOpen(project.id) }
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
                Text(project.title, style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium)
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
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(10.dp))
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

    var title by remember { mutableStateOf("") }
    var author by remember { mutableStateOf("") }
    var genre by remember { mutableStateOf("Liebesroman") }
    var style by remember { mutableStateOf("atmosphärisch") }
    var tropes by remember { mutableStateOf("") }
    var series by remember { mutableStateOf("") }
    var spice by remember { mutableIntStateOf(0) }
    var pages by remember { mutableIntStateOf(300) }
    var chapters by remember { mutableIntStateOf(24) }

    val canCreate = title.isNotBlank() && author.isNotBlank() && config.apiKey.isNotBlank()

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column {
            Text("Neues Buch", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            SectionLabel("In Minuten zum fertigen KDP-Roman")
        }

        OutlinedTextField(title, { title = it }, label = { Text("Titel") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(author, { author = it }, label = { Text("Autor / Pseudonym") }, singleLine = true, modifier = Modifier.fillMaxWidth())

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f)) { LabeledDropdown("Genre", Genres.all, genre) { genre = it } }
            Box(Modifier.weight(1f)) { LabeledDropdown("Stil", Genres.styles, style) { style = it } }
        }

        SegmentedSpice(spice) { spice = it }

        OutlinedTextField(tropes, { tropes = it }, label = { Text("Tropes (kommagetrennt)") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(series, { series = it }, label = { Text("Serie / Reihe (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(pages.toString(), { pages = it.toIntOrNull() ?: pages },
                label = { Text("Seiten") }, singleLine = true, modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            OutlinedTextField(chapters.toString(), { chapters = it.toIntOrNull() ?: chapters },
                label = { Text("Kapitel") }, singleLine = true, modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        }

        if (config.apiKey.isBlank()) {
            Text("Erst in den Einstellungen einen API-Key hinterlegen.",
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
    var baseUrl by remember(config.baseUrl) { mutableStateOf(config.baseUrl) }
    var apiKey by remember(config.apiKey) { mutableStateOf(config.apiKey) }
    var model by remember(config.model) { mutableStateOf(config.model) }

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Einstellungen", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        SectionLabel("KI-Anbieter")
        Text("Ollama Cloud oder ein OpenAI-kompatibler Endpunkt. Der API-Key wird nur lokal gespeichert.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        OutlinedTextField(baseUrl, { baseUrl = it }, label = { Text("Basis-URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(model, { model = it }, label = { Text("Modell") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(apiKey, { apiKey = it }, label = { Text("API-Key") },
            visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())

        Button(
            onClick = { vm.saveSettings(AiConfig(baseUrl.trim(), apiKey.trim(), model.trim())) },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Speichern") }

        StatusBadge(if (config.apiKey.isBlank()) ProjectStatus.FAILED else ProjectStatus.COMPLETED)
        Text(if (config.apiKey.isBlank()) "Kein API-Key hinterlegt" else "API-Key ist hinterlegt",
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

    val isGenerating = generatingId == project.id
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val epubLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/epub+zip")
    ) { uri -> uri?.let { context.contentResolver.openOutputStream(it)?.use { os -> os.write(ExportBuilder.epubBytes(project)) } } }
    val txtLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri -> uri?.let { context.contentResolver.openOutputStream(it)?.use { os -> os.write(ExportBuilder.manuscriptText(project).toByteArray()) } } }
    val pdfLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri -> uri?.let { context.contentResolver.openOutputStream(it)?.use { os -> os.write(ExportBuilder.pdfBytes(project)) } } }
    val docxLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.wordprocessingml.document")
    ) { uri -> uri?.let { context.contentResolver.openOutputStream(it)?.use { os -> os.write(ExportBuilder.docxBytes(project)) } } }

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(project.title, style = MaterialTheme.typography.headlineSmall,
            fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatusBadge(project.status)
            Text("${words(project.wordCount)} Wörter · ${project.chapters.size} Kapitel",
                fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (isGenerating) {
            progress?.let { p ->
                Card(
                    Modifier.fillMaxWidth(),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
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
            project.chapters.forEach { ch ->
                SectionCard("Kapitel ${ch.number}: ${ch.title}") {
                    Text(ch.text.ifBlank { ch.goal }.take(2000), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            SectionLabel(title)
            Spacer(Modifier.height(6.dp))
            content()
        }
    }
}
