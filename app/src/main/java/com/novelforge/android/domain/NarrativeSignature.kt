package com.novelforge.android.domain

import kotlin.random.Random

/**
 * "Stil-DNA" pro Buch: zieht aus großen Pools eine einzigartige Kombination aus
 * Perspektive, Tempus, Struktur, Stimme usw. Verhindert die Template-Signatur, die
 * Amazon KDP als "Programmatic Content" erkennt. (Aus der macOS-App portiert.)
 */
data class NarrativeSignature(
    val pov: String,
    val tense: String,
    val structureModel: String,
    val openingType: String,
    val sentenceRhythm: String,
    val descriptionDensity: String,
    val dialogueBalance: String,
    val chapterRhythm: String,
    val narrativeDistance: String,
    val motifTechnique: String,
) {
    fun directiveText(povOverride: String? = null, tenseOverride: String? = null): String {
        val usedPov = povOverride?.trim()?.ifEmpty { null } ?: pov
        val usedTense = tenseOverride?.trim()?.ifEmpty { null } ?: tense
        return buildString {
            appendLine("STIL-DNA DIESES BUCHES (VERBINDLICH – macht das Buch unverwechselbar; NICHT im Text erwähnen):")
            appendLine("- Erzählperspektive: $usedPov")
            appendLine("- Zeitform: $usedTense")
            appendLine("- Erzählstruktur: $structureModel")
            appendLine("- Eröffnung des Buches: $openingType")
            appendLine("- Satzrhythmus: $sentenceRhythm")
            appendLine("- Beschreibungsdichte: $descriptionDensity")
            appendLine("- Verhältnis Dialog/Beschreibung: $dialogueBalance")
            appendLine("- Kapitelrhythmus: $chapterRhythm")
            appendLine("- Erzähldistanz und Ton: $narrativeDistance")
            appendLine("- Leitmotiv-Technik: $motifTechnique")
            append("EINZIGARTIGKEIT (Amazon-KDP-Schutz gegen \"Programmatic Content\"): Diese Geschichte folgt KEINER Standardvorlage und teilt mit keinem anderen Buch dieselbe Schablone. Halte diese Stil-DNA konsequent durch.")
        }
    }

    val directive: String get() = directiveText()

    companion object {
        private val pov = listOf(
            "Ich-Perspektive (Präsenz und Unmittelbarkeit)",
            "Personale 3. Person, eng an einer Figur",
            "Personale 3. Person mit wechselnder Fokusfigur",
            "Auktoriale Erzählstimme mit eigener Haltung",
        )
        private val tense = listOf("Präteritum (klassisch)", "Präsens (unmittelbar, drängend)")
        private val structure = listOf(
            "Klassische Drei-Akt-Dramaturgie", "In medias res",
            "Rahmenerzählung", "Nichtlinear mit Zeitsprüngen",
            "Zwei parallele Handlungsstränge", "Countdown-Struktur",
            "Mosaik aus eng verzahnten Episoden", "Über Kontrast statt Dauerkonflikt erzählt",
        )
        private val opening = listOf(
            "Konkrete Handlung unter Druck", "Aufgeladener Dialog",
            "Rätselhaftes Detail", "Starke, eigenwillige Erzählstimme",
            "Feiner Riss im normalen Alltag", "Beiläufige Vorausdeutung",
        )
        private val rhythm = listOf(
            "Kurze, treibende Sätze; harte Schnitte",
            "Lange Perioden mit kurzen Akzenten",
            "Stark wechselnder Rhythmus, stakkatohaft",
            "Ruhiger, beobachtender Rhythmus",
        )
        private val density = listOf(
            "Sparsam und cinematisch", "Sinnlich-dicht mit körperlichen Details",
            "Funktional und klar",
        )
        private val dialogue = listOf(
            "Dialoglastig", "Ausgewogen", "Beschreibungs- und handlungslastig",
        )
        private val chapterRhythm = listOf(
            "Kurze Kapitel mit hartem Cliffhanger", "Längere, atmende Kapitel",
            "Bewusst ungleiche Kapitellängen",
        )
        private val distance = listOf(
            "Nüchtern und beobachtend", "Emotional nah, fast unter der Haut",
            "Leicht ironisch-distanziert", "Poetisch verdichtet, nie überladen",
        )
        private val motif = listOf(
            "Wiederkehrendes konkretes Objekt", "Farb- oder Lichtmotiv",
            "Wetter als gebrochener Kontrapunkt", "Sinnesmotiv (Geruch/Klang)",
            "Kein aufgesetztes Leitmotiv",
        )

        fun make(seed: Long): NarrativeSignature {
            val r = Random(seed)
            fun pick(o: List<String>) = o[r.nextInt(o.size)]
            return NarrativeSignature(
                pov = pick(pov), tense = pick(tense), structureModel = pick(structure),
                openingType = pick(opening), sentenceRhythm = pick(rhythm),
                descriptionDensity = pick(density), dialogueBalance = pick(dialogue),
                chapterRhythm = pick(chapterRhythm), narrativeDistance = pick(distance),
                motifTechnique = pick(motif),
            )
        }

        /** Stabiler 64-Bit-Seed aus einem String (deterministisch, App-Start-übergreifend). */
        fun stableSeed(s: String): Long {
            var hash = 1125899906842597L // großer Prim-Startwert
            for (c in s) hash = 31 * hash + c.code  // Long-Überlauf wickelt sich definiert um
            return hash
        }
    }
}
