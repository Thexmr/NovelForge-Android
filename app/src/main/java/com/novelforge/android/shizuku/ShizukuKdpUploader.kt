package com.novelforge.android.shizuku

import android.content.Context
import com.novelforge.android.domain.Project
import com.novelforge.android.export.CoverArtService
import com.novelforge.android.export.ExportBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Autonomer KDP-Entwurf über das ECHTE Chrome des Nutzers – der Android-Weg,
 * der dem Desktop entspricht: dort steuert ein Node-Sidecar das installierte Chrome,
 * hier übernimmt Shizuku diese Rolle. Vorteil: die in Chrome bereits bestehende
 * Amazon-/KDP-Anmeldung wird genutzt, es ist kein zweiter Login in der App nötig.
 *
 * Es wird IMMER nur ein ENTWURF angelegt – „Veröffentlichen" bleibt beim Menschen.
 *
 * Ohne Shizuku bleibt der bisherige WebView-Weg (KdpUploadScreen) die Alternative.
 */
object ShizukuKdpUploader {

    private const val KDP_NEUES_EBOOK = "https://kdp.amazon.com/de_DE/title-setup/kindle/new/details"
    private const val KDP_REGAL = "https://kdp.amazon.com/de_DE/bookshelf"

    data class Schritt(val text: String, val anteil: Float)

    /**
     * Legt EPUB und Cover im gemeinsamen Download-Ordner ab, damit Chromes Dateiauswahl
     * sie erreichen kann (Chrome sieht die App-internen Dateien sonst nicht).
     */
    private fun exportiereDateien(context: Context, project: Project): Pair<File, File?> {
        val ziel = File("/sdcard/Download/NovelForge").apply { mkdirs() }
        val epub = File(ziel, "${sicherName(project.title)}.epub")
        epub.writeBytes(ExportBuilder.epubBytes(project))
        val cover = CoverArtService.coverFile(context, project)
        val coverZiel = if (cover.exists()) {
            File(ziel, "${sicherName(project.title)}-cover.jpg").also { cover.copyTo(it, overwrite = true) }
        } else null
        return epub to coverZiel
    }

    private fun sicherName(s: String) =
        s.replace(Regex("[^A-Za-z0-9äöüÄÖÜß _-]"), "").replace(" ", "_").take(60).ifBlank { "buch" }

    /**
     * Führt den kompletten Ablauf aus. Meldet jeden Schritt über [fortschritt].
     * Gibt eine Klartext-Zusammenfassung zurück (was gefüllt wurde, was der Mensch
     * noch prüfen muss).
     */
    suspend fun ladeHoch(
        context: Context,
        project: Project,
        fortschritt: (Schritt) -> Unit = {},
    ): String = withContext(Dispatchers.IO) {
        val p = project.profile
        fortschritt(Schritt("Prüfe Shizuku …", 0.02f))
        val status = ShizukuBridge.status(context)
        if (!status.bereit) return@withContext "Shizuku nicht bereit: ${status.hinweis}"
        if (!ChromeAutomation.chromeVorhanden()) return@withContext "Chrome ist auf diesem Gerät nicht installiert."

        fortschritt(Schritt("Exportiere Buch und Cover …", 0.10f))
        val (epub, cover) = runCatching { exportiereDateien(context, project) }
            .getOrElse { return@withContext "Export fehlgeschlagen: ${it.message}" }

        fortschritt(Schritt("Öffne KDP in deinem Chrome …", 0.20f))
        ChromeAutomation.oeffne(KDP_NEUES_EBOOK)

        // Anmeldung prüfen: erscheint die Anmeldeseite, muss der Mensch einmal ran.
        val anmeldung = ChromeAutomation.warteAuf("anmelden", timeoutMs = 6_000)
        if (anmeldung != null) {
            return@withContext "Bitte einmalig in Chrome bei KDP anmelden – danach diesen Upload erneut starten. " +
                "Die Dateien liegen bereit unter ${epub.parent}."
        }

        fortschritt(Schritt("Trage Titel und Autor ein …", 0.35f))
        val titel = p.kdpTitle.ifBlank { project.title }
        val gefuellt = mutableListOf<String>()
        if (ChromeAutomation.fuelle("Buchtitel", titel)) gefuellt += "Titel"
        if (p.kdpSubtitle.isNotBlank() && ChromeAutomation.fuelle("Untertitel", p.kdpSubtitle)) gefuellt += "Untertitel"

        val teile = project.authorName.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        val vorname = if (teile.size > 1) teile.dropLast(1).joinToString(" ") else ""
        val nachname = if (teile.size > 1) teile.last() else teile.firstOrNull().orEmpty()
        if (vorname.isNotBlank() && ChromeAutomation.fuelle("Vorname", vorname)) gefuellt += "Autor-Vorname"
        if (nachname.isNotBlank() && ChromeAutomation.fuelle("Nachname", nachname)) gefuellt += "Autor-Nachname"

        fortschritt(Schritt("Trage Beschreibung ein …", 0.55f))
        ChromeAutomation.scrolleRunter()
        if (p.kdpDescription.isNotBlank() && ChromeAutomation.fuelle("Beschreibung", p.kdpDescription)) {
            gefuellt += "Beschreibung"
        }

        fortschritt(Schritt("Trage Keywords ein …", 0.70f))
        ChromeAutomation.scrolleRunter()
        val keywords = p.kdpKeywords.split(",").map { it.trim() }.filter { it.isNotBlank() }.take(7)
        var kwZahl = 0
        for (kw in keywords) {
            if (ChromeAutomation.fuelle("Keyword", kw, timeoutMs = 6_000)) kwZahl++
            delay(300)
        }
        if (kwZahl > 0) gefuellt += "Keywords($kwZahl)"

        fortschritt(Schritt("Speichere als Entwurf …", 0.90f))
        ChromeAutomation.scrolleRunter()
        val gespeichert = ChromeAutomation.tippeAuf("Entwurf speichern", timeoutMs = 12_000)

        fortschritt(Schritt("Fertig", 1f))
        buildString {
            append(if (gespeichert) "KDP-Entwurf gespeichert. " else "Felder gefüllt, Entwurf noch nicht gespeichert. ")
            append("Übernommen: ${gefuellt.joinToString(", ").ifBlank { "nichts" }}. ")
            append("Manuskript: ${epub.absolutePath}")
            if (cover != null) append(" · Cover: ${cover.absolutePath}")
            append(". Manuskript und Cover im Reiter „Inhalt“ hochladen (Dateiauswahl), Preis prüfen – veröffentlicht wird nichts automatisch.")
        }
    }

    /** Öffnet nur das Bücherregal – praktisch zum Nachsehen/Anmelden. */
    suspend fun oeffneRegal() = ChromeAutomation.oeffne(KDP_REGAL)
}
