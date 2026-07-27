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
    /** Alle Dateien eines Buches, die KDP braucht – plus die gerechneten Druckmaße. */
    data class Dateien(
        val epub: File,
        val cover: File?,
        val druckcover: File?,
        val druckcoverPdf: File?,
        val masse: com.novelforge.android.export.PrintCoverBuilder.Masse?,
    )

    private fun exportiereDateien(context: Context, project: Project): Dateien {
        val ziel = File("/sdcard/Download/NovelForge").apply { mkdirs() }
        val name = sicherName(project.title)
        val epub = File(ziel, "$name.epub")
        epub.writeBytes(ExportBuilder.epubBytes(project))
        val cover = CoverArtService.coverFile(context, project)
        val coverZiel = if (cover.exists()) {
            File(ziel, "$name-cover.jpg").also { cover.copyTo(it, overwrite = true) }
        } else null

        // DRUCKCOVER: Vorderseite + Buchrücken + Rückseite mit Verkaufstext, in
        // KDP-Maßen. Der Buchrücken folgt der Seitenzahl – deshalb wird er aus dem
        // Manuskript gerechnet. Kein Abbruchgrund: das eBook geht auch ohne Taschenbuch.
        var wrap: File? = null
        var wrapPdf: File? = null
        var masse: com.novelforge.android.export.PrintCoverBuilder.Masse? = null
        if (coverZiel != null) {
            runCatching {
                val r = com.novelforge.android.export.PrintCoverBuilder.baueFuer(
                    project = project,
                    motiv = coverZiel,
                    jpegZiel = File(ziel, "$name-druckcover.jpg"),
                    pdfZiel = File(ziel, "$name-druckcover.pdf"),
                )
                wrap = r.jpeg; wrapPdf = r.pdf; masse = r.masse
            }
        }
        return Dateien(epub, coverZiel, wrap, wrapPdf, masse)
    }

    private fun sicherName(s: String) =
        s.replace(Regex("[^A-Za-z0-9äöüÄÖÜß _-]"), "").replace(" ", "_").take(60).ifBlank { "buch" }

    /**
     * Führt den kompletten Ablauf aus. Meldet jeden Schritt über [fortschritt].
     * Gibt eine Klartext-Zusammenfassung zurück (was gefüllt wurde, was der Mensch
     * noch prüfen muss).
     */
    /**
     * Füllt ein Feld und PRÜFT DANN MIT DEN AUGEN (Bildschirmfoto + multimodales Modell),
     * ob wirklich der erwartete Wert drinsteht. Bei sichtbarer Abweichung wird das Feld
     * geleert und einmal neu geschrieben. Ohne Vision-Modell verhält es sich wie bisher.
     */
    private suspend fun fuelleGeprueft(
        ai: com.novelforge.android.ai.AiConfig?,
        suche: String,
        feldName: String,
        wert: String,
    ): Pair<Boolean, String> {
        if (wert.isBlank()) return false to "leer"
        if (!ChromeAutomation.fuelle(suche, wert)) return false to "Feld nicht gefunden"
        if (ai == null) return true to "gefüllt (ohne Sicht-Prüfung)"
        return when (ScreenVision.pruefeFeld(ai, feldName, wert)) {
            true -> true to "gefüllt und visuell bestätigt"
            null -> true to "gefüllt (Sicht-Prüfung nicht möglich)"
            false -> {
                // Sichtbar falsch → Feld leeren und genau einmal neu schreiben.
                ChromeAutomation.tippeAuf(suche, timeoutMs = 6_000)
                ShizukuBridge.exec("input keyevent KEYCODE_MOVE_END")
                repeat(80) { ShizukuBridge.exec("input keyevent KEYCODE_DEL") }
                ChromeAutomation.schreibe(wert)
                when (ScreenVision.pruefeFeld(ai, feldName, wert)) {
                    true -> true to "nach Korrektur visuell bestätigt"
                    else -> true to "korrigiert, visuell unsicher"
                }
            }
        }
    }

    suspend fun ladeHoch(
        context: Context,
        project: Project,
        aiConfig: com.novelforge.android.ai.AiConfig? = null,
        fortschritt: (Schritt) -> Unit = {},
    ): String = withContext(Dispatchers.IO) {
        val p = project.profile
        fortschritt(Schritt("Prüfe Shizuku …", 0.02f))
        val status = ShizukuBridge.status(context)
        if (!status.bereit) return@withContext "Shizuku nicht bereit: ${status.hinweis}"
        if (!ChromeAutomation.chromeVorhanden()) return@withContext "Chrome ist auf diesem Gerät nicht installiert."

        fortschritt(Schritt("Exportiere Buch, Cover und Druckcover …", 0.10f))
        val dateien = runCatching { exportiereDateien(context, project) }
            .getOrElse { return@withContext "Export fehlgeschlagen: ${it.message}" }
        val epub = dateien.epub
        val cover = dateien.cover
        if (dateien.masse != null) {
            fortschritt(Schritt("Druckcover: ${dateien.masse!!.kurzfassung}", 0.14f))
        }

        // SELBSTBEWEIS vor dem Upload: Das Programm misst sein eigenes Ergebnis –
        // Umfang, EPUB-Struktur, Cover-Maße, Keyword-Deckung, Verkaufstext. Fällt eine
        // Pflichtprüfung durch, wird NICHT hochgeladen: ein fehlerhaftes Buch im echten
        // KDP-Konto kostet mehr Zeit, als der Abbruch hier spart.
        fortschritt(Schritt("Prüfe das eigene Ergebnis …", 0.16f))
        val nachweis = com.novelforge.android.domain.Beweis.belege(
            project = project, epub = epub, cover = cover,
            druckcover = dateien.druckcover, druckcoverMasse = dateien.masse,
            zielSeiten = project.targetPageCount,
        )
        if (!nachweis.bestanden) {
            return@withContext "Selbstprüfung nicht bestanden – deshalb kein Upload.\n\n" + nachweis.text
        }
        fortschritt(Schritt("Selbstprüfung bestanden (${nachweis.alle.size} Nachweise)", 0.18f))

        fortschritt(Schritt("Öffne KDP in deinem Chrome …", 0.20f))
        ChromeAutomation.oeffne(KDP_NEUES_EBOOK)

        // Steht die Anmeldeseite und sind Zugangsdaten hinterlegt, meldet sich die App
        // selbst an. Ohne hinterlegte Daten bleibt es bei der bestehenden Chrome-Sitzung.
        val seit = System.currentTimeMillis()
        val zugang = com.novelforge.android.data.KdpCredentials.lesen(context)
        if (zugang != null && ChromeAutomation.warteAuf("passwort", timeoutMs = 5_000) != null) {
            fortschritt(Schritt("Melde mit hinterlegten Zugangsdaten an …", 0.22f))
            // E-Mail (nur, wenn das Feld noch leer/sichtbar ist), dann Passwort.
            ChromeAutomation.fuelle("mail", zugang.first, timeoutMs = 5_000)
            ChromeAutomation.tippeAuf("weiter", timeoutMs = 4_000)
            delay(2500)
            ChromeAutomation.fuelle("passwort", zugang.second, timeoutMs = 6_000)
            ChromeAutomation.tippeAuf("angemeldet bleiben", timeoutMs = 3_000)
            ChromeAutomation.tippeAuf("anmelden", timeoutMs = 5_000)
            delay(6000)
        }

        // Verlangt Amazon einen per SMS geschickten Bestätigungscode (2FA), wird dieser
        // automatisch aus dem Posteingang geholt und eingetragen – ohne dass die App die
        // Berechtigung READ_SMS braucht (Shizuku liest den SMS-Speicher). Es werden nur
        // Nachrichten ab JETZT betrachtet, damit kein alter Code verwendet wird.
        val codeMeldung = runCatching { SmsCodeReader.codeEintragenFallsGefragt(seit) }.getOrNull()
        if (codeMeldung != null) {
            fortschritt(Schritt(codeMeldung, 0.25f))
            delay(6000) // Amazon prüft den Code und leitet weiter
        }

        // Anmeldung prüfen: erscheint immer noch die Anmeldeseite (z. B. Passwort nötig),
        // muss der Mensch einmal ran – das Passwort trägt die App bewusst nicht ein.
        val anmeldung = ChromeAutomation.warteAuf("anmelden", timeoutMs = 6_000)
        if (anmeldung != null) {
            return@withContext "Bitte einmalig in Chrome bei KDP anmelden – danach diesen Upload erneut starten. " +
                (codeMeldung?.let { "($it) " } ?: "") +
                "Die Dateien liegen bereit unter ${epub.parent}."
        }

        fortschritt(Schritt("Trage Titel und Autor ein (mit Sicht-Kontrolle) …", 0.35f))
        val titel = p.kdpTitle.ifBlank { project.title }
        val gefuellt = mutableListOf<String>()
        fuelleGeprueft(aiConfig, "Buchtitel", "Buchtitel", titel).let { if (it.first) gefuellt += "Titel (${it.second})" }
        if (p.kdpSubtitle.isNotBlank()) {
            fuelleGeprueft(aiConfig, "Untertitel", "Untertitel", p.kdpSubtitle).let { if (it.first) gefuellt += "Untertitel" }
        }

        val teile = project.authorName.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        val vorname = if (teile.size > 1) teile.dropLast(1).joinToString(" ") else ""
        val nachname = if (teile.size > 1) teile.last() else teile.firstOrNull().orEmpty()
        if (vorname.isNotBlank()) {
            fuelleGeprueft(aiConfig, "Vorname", "Vorname des Autors", vorname).let { if (it.first) gefuellt += "Autor-Vorname" }
        }
        if (nachname.isNotBlank()) {
            fuelleGeprueft(aiConfig, "Nachname", "Nachname des Autors", nachname).let { if (it.first) gefuellt += "Autor-Nachname" }
        }

        fortschritt(Schritt("Trage Beschreibung ein …", 0.55f))
        ChromeAutomation.scrolleRunter()
        if (p.kdpDescription.isNotBlank()) {
            fuelleGeprueft(aiConfig, "Beschreibung", "Beschreibung", p.kdpDescription).let { if (it.first) gefuellt += "Beschreibung" }
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

        // Schluss-Sichtprüfung: hat KDP den Entwurf wirklich angenommen oder steht eine
        // Fehlermeldung / ein Pflichtfeld offen? Das sieht nur ein Blick auf den Bildschirm.
        var sichtBefund = ""
        if (aiConfig != null) {
            delay(2500)
            sichtBefund = ScreenVision.beschreibeBildschirm(
                aiConfig,
                "Sieh dir diesen Bildschirm der Amazon-KDP-Seite an. Wurde gespeichert, oder werden Fehler " +
                    "bzw. fehlende Pflichtfelder angezeigt? Antworte in EINEM kurzen deutschen Satz.",
            )
        }

        fortschritt(Schritt("Fertig", 1f))
        buildString {
            append(if (gespeichert) "KDP-Entwurf gespeichert. " else "Felder gefüllt, Entwurf noch nicht gespeichert. ")
            if (sichtBefund.isNotBlank()) append("Sicht-Prüfung: ${sichtBefund.take(200)} ")
            append("Übernommen: ${gefuellt.joinToString(", ").ifBlank { "nichts" }}. ")
            append("Manuskript: ${epub.absolutePath}")
            if (cover != null) append(" · Cover: ${cover.absolutePath}")
            if (dateien.druckcoverPdf != null) append(" · Druckcover (PDF): ${dateien.druckcoverPdf!!.absolutePath}")
            append(". Manuskript und Cover im Reiter „Inhalt“ hochladen (Dateiauswahl), Preis prüfen – veröffentlicht wird nichts automatisch.")
        }
    }

    /** Öffnet nur das Bücherregal – praktisch zum Nachsehen/Anmelden. */
    suspend fun oeffneRegal() = ChromeAutomation.oeffne(KDP_REGAL)
}
