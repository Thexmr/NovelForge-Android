package com.novelforge.android.domain

import com.novelforge.android.export.PrintCoverBuilder
import java.io.File

/**
 * SELBSTBEWEIS: prüft ein fertiges Buch samt Dateien und Metadaten gegen harte,
 * nachrechenbare Kriterien – und liefert einen Bericht, in dem zu jedem Punkt der
 * GEMESSENE Wert steht.
 *
 * Warum das existiert: „fertig" war bisher eine Behauptung. Erst wenn Umfang,
 * EPUB-Struktur, Cover-Maße, Keyword-Deckung und Verkaufstext einzeln nachgewiesen
 * sind, darf das Buch zu KDP. Fällt eine Pflichtprüfung durch, wird NICHT
 * hochgeladen – der Bericht sagt dann genau, was fehlt.
 */
object Beweis {

    /** Ein einzelner Nachweis. [beleg] ist immer ein gemessener Wert. */
    data class Punkt(val name: String, val ok: Boolean, val beleg: String, val pflicht: Boolean = true)

    data class Gruppe(val titel: String, val punkte: List<Punkt>)

    data class Bericht(
        val gruppen: List<Gruppe>,
        val woerter: Int,
        val seiten: Int,
        val kapitel: Int,
    ) {
        val alle: List<Punkt> get() = gruppen.flatMap { it.punkte }
        val offen: List<Punkt> get() = alle.filter { it.pflicht && !it.ok }
        val bestanden: Boolean get() = offen.isEmpty()

        val text: String
            get() = buildString {
                appendLine("SELBSTBEWEIS")
                appendLine("$kapitel Kapitel · $woerter Wörter · ~$seiten Druckseiten")
                appendLine()
                for (g in gruppen) {
                    appendLine(g.titel.uppercase())
                    for (p in g.punkte) {
                        val zeichen = if (p.ok) "✓" else if (p.pflicht) "✗" else "·"
                        appendLine("  $zeichen ${p.name}: ${p.beleg}")
                    }
                    appendLine()
                }
                if (bestanden) {
                    appendLine("ERGEBNIS: alle Pflichtprüfungen bestanden – das Buch darf zu KDP.")
                } else {
                    appendLine("ERGEBNIS: ${offen.size} Pflichtprüfung(en) offen – KEIN Upload:")
                    for (p in offen) appendLine("  - ${p.name}: ${p.beleg}")
                }
            }.trim()
    }

    private fun woerter(s: String) = s.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.size

    /** Führt alle Prüfungen aus. */
    fun belege(
        project: Project,
        epub: File?,
        cover: File?,
        druckcover: File? = null,
        druckcoverMasse: PrintCoverBuilder.Masse? = null,
        zielSeiten: Int = 0,
    ): Bericht {
        val texte = project.chapters.sortedBy { it.number }.map { it.text }
        val gesamt = texte.sumOf { woerter(it) }
        val seiten = PrintCoverBuilder.schaetzeSeiten(gesamt)

        val gruppen = mutableListOf(
            Gruppe("Manuskript", manuskript(project, texte, gesamt, seiten, zielSeiten)),
            Gruppe("EPUB-Datei", epub(epub)),
            Gruppe("eBook-Cover", cover(cover)),
            Gruppe("Amazon-Metadaten", metadaten(project, texte.joinToString("\n").lowercase())),
        )
        if (druckcover != null && druckcoverMasse != null) {
            gruppen += Gruppe("Druckcover (Vorder- + Rückseite + Rücken)", druckcover(druckcover, druckcoverMasse))
        }
        return Bericht(gruppen, gesamt, seiten, texte.size)
    }

    // ---- Manuskript ----

    private fun manuskript(project: Project, texte: List<String>, gesamt: Int,
                           seiten: Int, zielSeiten: Int): List<Punkt> {
        val p = mutableListOf<Punkt>()
        p += Punkt("Kapitel vorhanden", texte.size >= 3, "${texte.size} Kapitel")
        p += Punkt("Umfang", gesamt >= 5000, "$gesamt Wörter ≈ $seiten Druckseiten")
        if (zielSeiten > 0) {
            p += Punkt("Zielumfang $zielSeiten Seiten", seiten >= zielSeiten * 0.9,
                "$seiten von $zielSeiten Seiten (${seiten * 100 / zielSeiten} %)")
        }

        // Platzhalter aus fehlgeschlagenen Kapiteln dürfen nie im Buch landen.
        val platzhalter = texte.count { it.contains("konnte nicht erzeugt werden", ignoreCase = true) }
        p += Punkt("Keine Platzhalter-Kapitel", platzhalter == 0,
            if (platzhalter == 0) "keine gefunden" else "$platzhalter Kapitel betroffen")

        // Label-Reste des Modells („UNTERTITEL:", „KEYWORDS:") im Fließtext.
        val label = Regex(
            "^\\s*\\**\\s*(UNTERTITEL|UNTITEL|SUBTITLE|KEYWORDS?|KATEGORIEN?|KLAPPENTEXT)\\s*:",
            setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))
        val mitLabel = texte.count { label.containsMatchIn(it) }
        p += Punkt("Keine Formatvorlagen im Text", mitLabel == 0,
            if (mitLabel == 0) "keine gefunden" else "$mitLabel Kapitel betroffen")

        val kurz = texte.count { woerter(it) < 300 }
        p += Punkt("Keine leeren Kapitel", kurz == 0,
            if (kurz == 0) "alle Kapitel ausreichend lang" else "$kurz Kapitel unter 300 Wörtern")

        // Kapitel, die versehentlich aus einer Kopfzeile entstanden sind.
        val schlechteTitel = project.chapters.filter {
            it.title.trim().lowercase() in listOf("titel", "untertitel", "kapitel", "format")
        }
        p += Punkt("Kapitelüberschriften plausibel", schlechteTitel.isEmpty(),
            if (schlechteTitel.isEmpty()) "${texte.size} Überschriften geprüft"
            else schlechteTitel.joinToString(", ") { it.title })

        // Wortwiederholungen quer durchs Buch – der wichtigste Qualitätsindikator bei KI-Text.
        val wieder = RepetitionScan.blockingRepeatedSentences(texte)
        p += Punkt("Keine wiederholten Sätze", wieder.isEmpty(),
            if (wieder.isEmpty()) "keine kapitelübergreifende Wiederholung"
            else "${wieder.size}: „${wieder.first().take(70)}…“")
        return p
    }

    // ---- EPUB ----

    private fun epub(datei: File?): List<Punkt> {
        if (datei == null || !datei.exists()) {
            return listOf(Punkt("EPUB vorhanden", false, "Datei fehlt: ${datei?.name ?: "(kein Pfad)"}"))
        }
        val bytes = datei.readBytes()
        val p = mutableListOf<Punkt>()
        p += Punkt("EPUB vorhanden", bytes.size > 2048, "${bytes.size / 1024} KB")

        // Pflicht der EPUB-Spezifikation: „mimetype" ist der ERSTE Eintrag und
        // UNKOMPRIMIERT. Fehlt das, lehnen strenge Prüfer die Datei ab.
        val ersterName = if (bytes.size > 38) String(bytes, 30, 8, Charsets.US_ASCII) else ""
        val methode = if (bytes.size > 10)
            (bytes[8].toInt() and 0xFF) or ((bytes[9].toInt() and 0xFF) shl 8) else -1
        p += Punkt("EPUB-Struktur (mimetype zuerst, unkomprimiert)",
            ersterName == "mimetype" && methode == 0,
            "erster Eintrag „$ersterName“, Methode ${if (methode == 0) "stored" else "deflate"}")

        val inhalt = String(bytes, Charsets.ISO_8859_1)
        p += Punkt("Pflichtdateien enthalten",
            inhalt.contains("META-INF/container.xml") && inhalt.contains(".opf"),
            "container.xml und OPF im Archiv")
        return p
    }

    // ---- Cover ----

    private fun cover(datei: File?): List<Punkt> {
        if (datei == null || !datei.exists()) {
            return listOf(Punkt("eBook-Cover vorhanden", false, "Datei fehlt: ${datei?.name ?: "(kein Pfad)"}"))
        }
        val mb = datei.length() / (1024.0 * 1024.0)
        val p = mutableListOf(Punkt("eBook-Cover vorhanden", mb > 0.02, "%.2f MB".format(mb)))
        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(datei.absolutePath, opts)
        p += Punkt("Cover-Maße mindestens 1600×2400",
            opts.outWidth >= 1600 && opts.outHeight >= 2400,
            "${opts.outWidth}×${opts.outHeight} px")
        p += Punkt("Cover unter 50 MB", mb < 50, "%.2f MB".format(mb))
        return p
    }

    /**
     * Prüft das Druckcover – inklusive der Frage, ob das Barcode-Feld WIRKLICH frei ist.
     * Nur wenn der Bereich nahezu weiß und ohne Struktur ist, kann Amazon dort den
     * EAN drucken.
     */
    private fun druckcover(datei: File, m: PrintCoverBuilder.Masse): List<Punkt> {
        val p = mutableListOf<Punkt>()
        val bmp = android.graphics.BitmapFactory.decodeFile(datei.absolutePath)
            ?: return listOf(Punkt("Druckcover lesbar", false, "Datei nicht lesbar: ${datei.name}"))
        val masseOk = Math.abs(bmp.width - m.breitePx) <= 2 && Math.abs(bmp.height - m.hoehePx) <= 2
        p += Punkt("Druckcover-Maße (Rücken aus Seitenzahl)", masseOk,
            if (masseOk) m.kurzfassung else "${bmp.width}×${bmp.height} statt ${m.breitePx}×${m.hoehePx}")

        // Barcode-Feld: 4 px Rand bleiben außen vor – an der harten Kante erzeugt
        // JPEG Über- und Unterschwinger.
        val rand = 4
        val links = Math.round((PrintCoverBuilder.BLEED_IN + m.format.breiteZoll - PrintCoverBuilder.SAFE_IN
            - PrintCoverBuilder.BARCODE_W_IN) * PrintCoverBuilder.DPI) + rand
        val oben = Math.round((PrintCoverBuilder.BLEED_IN + m.format.hoeheZoll - PrintCoverBuilder.SAFE_IN
            - PrintCoverBuilder.BARCODE_H_IN) * PrintCoverBuilder.DPI) + rand
        val breite = Math.round(PrintCoverBuilder.BARCODE_W_IN * PrintCoverBuilder.DPI) - 2 * rand
        val hoehe = Math.round(PrintCoverBuilder.BARCODE_H_IN * PrintCoverBuilder.DPI) - 2 * rand
        var dunkelster = 255
        var belegt = 0
        var proben = 0
        var y = oben
        while (y < oben + hoehe) {
            var x = links
            while (x < links + breite) {
                if (x in 0 until bmp.width && y in 0 until bmp.height) {
                    val c = bmp.getPixel(x, y)
                    val v = minOf(android.graphics.Color.red(c),
                        android.graphics.Color.green(c), android.graphics.Color.blue(c))
                    if (v < dunkelster) dunkelster = v
                    if (v < 230) belegt++
                    proben++
                }
                x += 8
            }
            y += 8
        }
        val anteil = if (proben > 0) belegt.toDouble() / proben else 1.0
        val frei = proben > 0 && dunkelster >= 200 && anteil < 0.01
        p += Punkt("Barcode-Feld frei (2,0\" × 1,2\")", frei,
            "dunkelster Punkt $dunkelster, %.2f %% belegt – %s".format(
                anteil * 100, if (frei) "weiß und leer" else "überdeckt"))
        bmp.recycle()

        // KDP nimmt Taschenbuch-Cover nur als PDF an.
        val pdf = File(datei.absolutePath.replace(Regex("\\.jpe?g$", RegexOption.IGNORE_CASE), "") + ".pdf")
        p += Punkt("Druckcover als PDF", pdf.exists() && pdf.length() > 1024,
            if (pdf.exists()) "${pdf.length() / 1024} KB (${Math.round(m.gesamtBreiteZoll * 72)} × "
                + "${Math.round(m.gesamtHoeheZoll * 72)} pt)"
            else "PDF fehlt – KDP lehnt ein JPEG als Taschenbuch-Cover ab",
            pflicht = false)
        return p
    }

    // ---- Amazon-Metadaten ----

    private val VERBOTEN = listOf("kostenlos", "gratis", "bestseller", "kindle", "ebook", "amazon", "taschenbuch")

    private fun metadaten(project: Project, volltext: String): List<Punkt> {
        val prof = project.profile
        val p = mutableListOf<Punkt>()
        val titel = prof.kdpTitle.ifBlank { project.title }.trim()

        p += Punkt("Titel gesetzt", titel.length in 3..200, "„$titel“ (${titel.length} Zeichen)")
        p += Punkt("Titel klickstark (≤ 32 Zeichen, kein Doppelpunkt)",
            titel.length <= 32 && !titel.contains(":") && !titel.contains("–"),
            "${titel.length} Zeichen" + if (titel.contains(":")) ", enthält Doppelpunkt" else "")

        val sub = prof.kdpSubtitle.trim()
        p += Punkt("Untertitel unterscheidet sich vom Titel",
            sub.isBlank() || !sub.equals(titel, ignoreCase = true),
            if (sub.isBlank()) "kein Untertitel" else "„$sub“", pflicht = false)

        val desc = prof.kdpDescription.trim()
        p += Punkt("Verkaufstext vorhanden (200–4000 Zeichen)", desc.length in 200..4000,
            "${woerter(desc)} Wörter, ${desc.length} Zeichen")
        val absaetze = desc.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        p += Punkt("Verkaufsdramaturgie (Haken, Einsatz, Einsatzverlust, Frage, Leseransprache)",
            absaetze.size >= 4, "${absaetze.size} Absätze")

        val kws = prof.kdpKeywords.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        p += Punkt("7 Suchphrasen", kws.size == 7, "${kws.size} Phrasen")
        val schlecht = kws.filter { k -> k.length > 50 || VERBOTEN.any { k.contains(it, true) } }
        p += Punkt("Suchphrasen regelkonform (≤ 50 Zeichen, keine Rang-/Preiswörter)", schlecht.isEmpty(),
            if (schlecht.isEmpty()) "alle in Ordnung" else schlecht.joinToString(" / "))
        val einWort = kws.filter { it.split(" ").size < 2 }
        p += Punkt("Suchphrasen mehrwortig (echte Suchanfragen)", einWort.isEmpty(),
            if (einWort.isEmpty()) "alle mit 2+ Wörtern" else einWort.joinToString(" / "))

        // Deckung: Jede Phrase muss mit mindestens einem inhaltstragenden Wort im BUCH
        // stehen. Sonst wird das Buch für Suchen ausgespielt, die es nicht bedient –
        // das kostet Ranking, weil Leser abspringen.
        val ungedeckt = kws.filter { k ->
            val w = k.split(" ").filter { it.length >= 5 }
            w.isNotEmpty() && w.none { volltext.contains(it.lowercase()) }
        }
        p += Punkt("Suchphrasen durch den Buchtext gedeckt", ungedeckt.isEmpty(),
            if (ungedeckt.isEmpty()) "${kws.size} Phrasen im Text belegt" else ungedeckt.joinToString(" / "))

        val cats = prof.kdpCategories.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        p += Punkt("Kategorien gesetzt (1–3 Pfade)", cats.size in 1..3,
            if (cats.isEmpty()) "keine" else cats.joinToString(" | "))
        return p
    }
}
