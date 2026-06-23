package com.novelforge.android.ai

import com.novelforge.android.domain.ContentSafetyFilter
import com.novelforge.android.domain.SpiceLevel

/**
 * Deutsche Schreib-Prompts (Kern aus der macOS-App portiert). Tropes, Stil-DNA und
 * Sinnlichkeitsgrad werden als verbindliche Blöcke injiziert – das treibt Qualität,
 * Einzigartigkeit und KDP-Auffindbarkeit.
 */
object PromptFactory {

    private fun block(s: String) = if (s.isBlank()) "" else "\n$s\n"

    fun bookIdea(genre: String, language: String, avoid: List<String> = emptyList()): String {
        val avoidBlock = if (avoid.isEmpty()) "" else
            "\nVERMEIDE Wiederholungen – diese Titel/Ideen gab es in dieser Produktion schon, liefere etwas DEUTLICH anderes (Setting, Hook, Figuren): ${avoid.joinToString(" | ")}\n"
        return """
        Erfinde EINE starke, vermarktbare Buchidee (Genre: $genre, Sprache: $language) mit einem
        viralen, aber sofort verständlichen Titel (kein kryptisches Wortspiel, keine paradoxen
        Wort-Collagen, kein Berufs-Ort-Klischee). Der Titel klingt wie ein echter Verlags-Bestseller.
        $avoidBlock
        Antworte exakt in diesem Format:
        TITEL: [2-6 Wörter, klickstark UND klar]
        PRÄMISSE: [2 Sätze – Satz 1 ist der High-Concept-Hook, Satz 2 nennt Konflikt und Einsatz]
        """.trimIndent()
    }

    fun coverPrompt(title: String, genre: String, synopsis: String, mood: String): String = """
        Erstelle einen professionellen Cover-Bildprompt für das Buch "$title" (Genre: $genre, Stimmung: $mood).
        Der Prompt ist für einen KI-Bildgenerator (Midjourney/DALL·E) gedacht und beschreibt ein
        verkaufsstarkes, genre-typisches Buchcover (Motiv, Bildausschnitt, Farbwelt, Licht, Komposition,
        Platz für Titel oben/unten). KEINE Buchstaben/keinen Text im Bild beschreiben (Titel wird separat gesetzt).

        Inhalt (als Inspiration):
        ${synopsis.take(1200)}

        Antworte exakt in diesem Format:
        BILDPROMPT: [ein einziger, dichter englischer Prompt in EINER Zeile, bildgenerator-tauglich]
        STIL: [3-6 Stichworte zu Farbwelt & Stimmung, deutsch]
    """.trimIndent()

    fun optimizeOpening(
        title: String, genre: String, perspective: String, tense: String,
        currentText: String, targetWords: Int,
    ): String = """
        Überarbeite das ERSTE Kapitel des Romans "$title" (Genre: $genre) so, dass die ersten Sätze
        die Amazon-Leseprobe ("Blick ins Buch") sofort fesseln und zum Kauf führen.
        Erzählperspektive: $perspective. Zeitform: $tense.

        REGELN: Starte mitten in einer konkreten Szene/Handlung (kein Wetter-/Rückblick-Vorlauf), erzeuge
        sofort eine Frage oder Spannung im Kopf der Lesenden, zeige statt zu erklären, variiere Satzlängen stark.
        Inhalt, Figuren und Handlung des Kapitels bleiben erhalten – nur Sog und Anfang werden stärker.
        Umfang etwa $targetWords Wörter. Reiner deutscher Fließtext, KEINE Markdown-Symbole, keine Meta-Kommentare.

        AKTUELLES KAPITEL:
        ${currentText.take(6000)}

        Gib ausschließlich den überarbeiteten Kapiteltext zurück.
    """.trimIndent()

    /** Verbindlicher Fortsetzungs-Block für Serien/Reihen (leer, wenn kein Kontext). */
    private fun sequelBlock(sequelContext: String): String = if (sequelContext.isBlank()) "" else """

        FORTSETZUNG (VERBINDLICH): Dies ist ein weiterer Band einer Reihe. Führe dieselben Hauptfiguren und dieselbe Welt konsistent fort und ehre die bisherigen Ereignisse – ABER liefere einen eigenständigen, vollständigen Spannungsbogen mit neuer zentraler Frage und klarer Eskalation gegenüber dem Vorband. Wiederhole NICHT die Handlung des Vorbands. Neuleser müssen folgen können (knappe, organische Einordnung statt Zusammenfassung).
        KONTEXT DES VORBANDS:
        ${sequelContext.take(2500)}
        """

    fun sequelIdea(seriesName: String, genre: String, sequelContext: String): String = """
        Entwickle den nächsten Band der Reihe "$seriesName" (Genre: $genre).
        KONTEXT DES VORBANDS:
        ${sequelContext.take(2500)}

        Der neue Band setzt die Geschichte mit denselben Hauptfiguren fort, hat aber einen
        EIGENEN vollständigen Handlungsbogen und einen frischen, klaren Titel (KEIN "Band 2"
        im Titel, kein kryptisches Wortspiel). Der Titel passt zur Reihe und klingt wie ein
        echter Verlags-Bestseller.

        Antworte exakt in diesem Format:
        TITEL: [2-6 Wörter, klar und stark]
        PRÄMISSE: [2 Sätze – neuer Hook, der auf dem Vorband aufbaut]
    """.trimIndent()

    fun concept(
        title: String, genre: String, language: String, style: String,
        pageCount: Int, tropes: String = "", bookSignature: String = "",
        sequelContext: String = "",
    ): String {
        val tropeBlock = if (tropes.isBlank()) "" else
            "\nTROPE-VERTRAG (VERBINDLICH – die Zielgruppe kauft genau diese Tropes; liefere sie deutlich über den ganzen Bogen): $tropes\n"
        return """
        Entwickle ein eigenständiges Buchkonzept (keine Nachahmung geschützter Werke).
        Titel: $title
        Genre: $genre
        Sprache: $language
        Stilprofil: $style
        Zielumfang: ca. $pageCount Seiten
        $tropeBlock${block(bookSignature)}${sequelBlock(sequelContext)}
        VERBINDLICH: Entwickle das Konzept so, dass es exakt zum Titel "$title" und zum Genre "$genre" passt und den Titel erzählerisch einlöst. Diese Bindung gilt fürs GANZE Buch: jede Hauptfigur, der Hauptkonflikt und jede Szene erfüllen das Genre "$genre" und lösen das Titel-Versprechen ein – der fertige Roman liefert genau das, was Titel und Genre versprechen.
        BESTSELLER-KERN: zugespitzte High-Concept-Prämisse (in EINEM Satz fassbar, kein generisches "Frau kehrt heim und findet Geheimnisse"); eine AKTIVE Hauptfigur, die die Handlung durch eigene Entscheidungen treibt; ein scharfer, präsenter Gegenpart mit echter Chemie/Reibung; das Genre wird in Szenen wirklich GELIEFERT (bei (Dark) Romance/Slow Burn: spürbar eskalierende Anziehung mit Auszahlung, kein "No Burn").

        Antworte ausschließlich in diesem Format (Labels exakt so verwenden):
        PRÄMISSE: [1-2 Sätze]
        LOGLINE: [Ein Satz]
        EXPOSÉ: [5-8 Sätze, kompletter Handlungsbogen]
        HAUPTKONFLIKT: [1-2 Sätze]
        THEMA: [1-3 Wörter]
        ZIELGRUPPE: [Kurze Beschreibung]
        """.trimIndent()
    }

    fun plot(
        title: String, genre: String, style: String, concept: String,
        pageCount: Int, chapterCount: Int, bookSignature: String = "",
        sequelContext: String = "",
    ): String = """
        Erstelle den vollständigen Plot für den Roman "$title".
        Genre: $genre | Stil: $style | Umfang: ca. $pageCount Seiten in $chapterCount Kapiteln.

        Konzept:
        $concept
        ${block(bookSignature)}${sequelBlock(sequelContext)}
        Baue den Plot nach bewährter Bestseller-Dramaturgie in drei Akten (Eröffnungsbild & Alltag mit Riss, auslösendes Ereignis, erster Wendepunkt, steigende Komplikationen, Mittelpunkt-Umkehr, Tiefpunkt, finale Konfrontation, Höhepunkt & Auflösung). Falls die Stil-DNA eine andere Struktur vorgibt, ordne die Beats dieser Struktur unter.
        Formuliere die zentrale dramatische Frage, webe eine verstärkende Nebenhandlung ein und plane Kapitelenden mit offenen Haken. Schreibe als zusammenhängenden, klar gegliederten Text.
    """.trimIndent()

    fun chapterPlan(
        title: String, genre: String, plot: String, chapterCount: Int,
        wordsPerChapter: Int, bookSignature: String = "",
    ): String = """
        Plane die Kapitelstruktur für den Roman "$title" (Genre: $genre).
        Es sollen GENAU $chapterCount Kapitel mit je ca. $wordsPerChapter Wörtern sein.

        Plot:
        ${plot.take(6000)}
        ${block(bookSignature)}
        Regeln: JEDES Kapitel endet mit einem Haken (offene Frage, Bedrohung, Enthüllung). Variiere das Tempo. Kapiteltitel kreativ und doppelbödig, KEINE "Kapitel N"/Phasennamen.

        Gib für JEDES Kapitel GENAU eine Zeile aus (Felder mit | getrennt):
        KAPITEL|Nummer|Titel|Ziel des Kapitels|Zentraler Konflikt
    """.trimIndent()

    fun characters(title: String, genre: String, plot: String): String = """
        Entwickle das Figurenensemble für den Roman "$title" (Genre: $genre).

        Plot:
        ${plot.take(4000)}

        Erstelle Protagonist, Antagonist und 3-5 wichtige Nebenfiguren. Alle an romantischen oder
        intimen Handlungen beteiligten Figuren sind eindeutig erwachsen (mindestens 18 Jahre).
        Gib für JEDE Figur GENAU eine Zeile aus (Felder mit | getrennt):
        FIGUR|Name|Rolle|Alter|Beruf|Ziel|Angst|Schwäche
    """.trimIndent()

    fun draftChapter(
        language: String, style: String, genre: String, bookTitle: String,
        chapterNumber: Int, chapterTitle: String, chapterGoal: String, chapterConflict: String,
        perspective: String, tense: String, storySoFar: String, targetWords: Int,
        isFirst: Boolean, isLast: Boolean, bookSignature: String = "", spiceLevel: Int = 0,
        charactersSummary: String = "",
    ): String {
        val spice = SpiceLevel.generationDirective(spiceLevel)
        val position = when {
            isFirst -> "\nERSTE SZENE DES BUCHES: Der erste Satz entscheidet über den Kauf (Amazon-Leseprobe). Sofort fesseln, kein Vorgeplänkel."
            isLast -> "\nLETZTE SZENE DES BUCHES: Löse den zentralen Konflikt emotional befriedigend auf, greife ein Motiv vom Anfang wieder auf."
            else -> ""
        }
        return """
        Schreibe Kapitel $chapterNumber ("$chapterTitle") des Romans "$bookTitle".
        SPRACHE: ausschließlich $language. STIL: $style. Erzählperspektive: $perspective. Zeitform: $tense.
        ${block(bookSignature)}${block(spice)}
        Kapitelziel: $chapterGoal
        Zentraler Konflikt: $chapterConflict
        ${if (charactersSummary.isBlank()) "" else "FIGUREN (Namen und Eigenschaften konsistent halten):\n$charactersSummary"}
        Zielumfang: ca. $targetWords Wörter (Szene ausschreiben, nicht zusammenfassen).

        Bisherige Handlung:
        ${if (storySoFar.isBlank()) "Dies ist der Anfang des Buches." else storySoFar.take(6000)}
        $position

        ${ContentSafetyFilter.promptDirective}

        HANDWERK: Zeigen statt benennen (Emotion nie behaupten). Variiere Satzlänge stark. Beginne mitten in der Handlung. Konkrete Sinnesdetails statt generischer. Kapitelende mit einem Haken. Reiner Fließtext – keine Markdown-Symbole, keine Überschriften.
        ZEITGEMÄSSE SPRACHE: Schreibe wie ein aktueller deutschsprachiger Bestseller von heute – klar, natürlich, modern. KEINE altertümliche oder geschwollene Sprache ("alsbald", "ward", "Antlitz", "Maid", "auf dass") und kein Pathos. Der Text muss inhaltlich Sinn ergeben und logisch zusammenhängen.
        ERZÄHLTEMPO VARIIEREN: Action, Konfrontation und Wendepunkte schnell und knapp (kurze Sätze, wenig Innenschau); ruhige Momente dürfen atmen, aber kein durchgehend langsames Tempo. Lange Wetter-/Stimmungspassagen, die die Handlung nicht vorantreiben, vermeiden.
        Gib ausschließlich den fertigen Prosatext aus.
    """.trimIndent()
    }

    fun kdpMetadata(
        title: String, author: String, genre: String, audience: String,
        synopsis: String, language: String, tropes: String = "", spiceLevel: Int = 0,
    ): String {
        val tropesLine = if (tropes.isBlank()) "" else "\nTropes (in KEYWORDS & VERKAUFSTEXT aufgreifen): $tropes"
        val spiceLine = SpiceLevel.kdpGuidance(spiceLevel)
        return """
        Erstelle Amazon-KDP-Metadaten für das Buch "$title" von $author.
        Genre: $genre | Zielgruppe: $audience | Sprache: $language$tropesLine$spiceLine

        Inhalt:
        ${synopsis.take(3000)}

        Keine Hinweise auf KI/Automatisierung. Optimiere TITEL, UNTERTITEL und KEYWORDS für die Amazon-Suche (ohne Keyword-Spam).

        Antworte exakt in diesem Format:
        VERKAUFSTITEL: [EXTREM starker, viraler Titel (2-6 Wörter), der beim Scrollen sofort zum Klicken zwingt – ABER sofort verständlich und natürlich wie ein echter Verlags-Bestseller, KEINE komischen/kryptischen oder paradoxen Wort-Collagen. Muss zum tatsächlichen Inhalt oben und zum Genre "$genre" passen, kein irreführender Clickbait. Keine Anführungszeichen.]
        UNTERTITEL: [SEO-Untertitel mit den stärksten Suchbegriffen, 5-12 Wörter]
        VERKAUFSTEXT: [150-200 Wörter, scanbare Absätze, Hook-Taglinie, steigende Stakes, Schlusszeile, eine "Für Fans von …"-Zeile]
        KEYWORDS: [genau 7 Long-Tail-Suchbegriffe, kommagetrennt]
        KATEGORIEN: [3 echte Amazon-Kindle-Kategorien, eine pro Zeile, Format Ober > Unter]
        """.trimIndent()
    }
}
