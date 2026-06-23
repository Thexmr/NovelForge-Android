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

    fun bookIdea(genre: String, language: String): String = """
        Erfinde EINE starke, vermarktbare Buchidee (Genre: $genre, Sprache: $language) mit einem
        viralen, aber sofort verständlichen Titel (kein kryptisches Wortspiel, keine paradoxen
        Wort-Collagen, kein Berufs-Ort-Klischee). Der Titel klingt wie ein echter Verlags-Bestseller.

        Antworte exakt in diesem Format:
        TITEL: [2-6 Wörter, klickstark UND klar]
        PRÄMISSE: [2 Sätze – Satz 1 ist der High-Concept-Hook, Satz 2 nennt Konflikt und Einsatz]
    """.trimIndent()

    fun concept(
        title: String, genre: String, language: String, style: String,
        pageCount: Int, tropes: String = "", bookSignature: String = "",
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
        $tropeBlock${block(bookSignature)}
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
    ): String = """
        Erstelle den vollständigen Plot für den Roman "$title".
        Genre: $genre | Stil: $style | Umfang: ca. $pageCount Seiten in $chapterCount Kapiteln.

        Konzept:
        $concept
        ${block(bookSignature)}
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

    fun draftChapter(
        language: String, style: String, genre: String, bookTitle: String,
        chapterNumber: Int, chapterTitle: String, chapterGoal: String, chapterConflict: String,
        perspective: String, tense: String, storySoFar: String, targetWords: Int,
        isFirst: Boolean, isLast: Boolean, bookSignature: String = "", spiceLevel: Int = 0,
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
