package com.novelforge.android.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RepetitionScanTest {

    @Test
    fun collisionDetectsRepeatedLongSentenceAcrossPriorText() {
        val prior = "Sie stand am Fenster und blickte lange auf den grauen Regen hinaus."
        val candidate = "Er betrat den Raum. Sie stand am Fenster und blickte lange auf den grauen Regen hinaus."
        val hits = RepetitionScan.repeatedSentenceCollisions(candidate, listOf(prior))
        assertTrue("Wiederholter langer Satz muss als Kollision erkannt werden", hits.isNotEmpty())
    }

    @Test
    fun collisionIgnoresShortEverydaySentences() {
        val prior = "Er nickte kurz."
        val candidate = "Sie lachte. Er nickte kurz."
        val hits = RepetitionScan.repeatedSentenceCollisions(candidate, listOf(prior))
        assertTrue("Kurze Alltagssätze sind keine Kollision", hits.isEmpty())
    }

    @Test
    fun blockingFlagsDistinctiveRepeatedSentence() {
        val s = "Der alte Leuchtturm warf sein Licht weit über die tosende schwarze See."
        val chapters = listOf("Kapitel eins. $s", "Kapitel zwei. $s")
        val blocking = RepetitionScan.blockingRepeatedSentences(chapters)
        assertTrue("Distinktiver 7+-Wort-Satz, 2× → blockierend", blocking.isNotEmpty())
    }

    @Test
    fun statsCountOccurrencesAcrossChapters() {
        val s = "Sie öffnete die schwere Tür und trat langsam in die dunkle Halle."
        val chapters = listOf(s, "Etwas anderes. $s", "Noch etwas. $s")
        val stats = RepetitionScan.repeatedSentenceStats(chapters, minimumOccurrences = 3)
        assertEquals(1, stats.size)
        assertEquals(3, stats.first().occurrences)
    }

    @Test
    fun styleTicFlagsExcessiveNegationStarts() {
        val neg = (1..40).joinToString(" ") { "Nicht das. " }
        val filler = (1..250).joinToString(" ") { "wort" }
        val violations = RepetitionScan.styleTicViolations("$neg $filler")
        assertTrue("Viele „Nicht …\"-Satzanfänge müssen auffallen",
            violations.any { it.contains("Nicht") })
    }

    @Test
    fun cleanProseHasNoStyleTicViolations() {
        val clean = (1..250).joinToString(" ") { "Der Wagen rollte über die Brücke und bog ab." }
        // Ein wiederholter, unauffälliger Satz löst KEINE Stil-Tic-Meldung aus.
        val violations = RepetitionScan.styleTicViolations(clean)
        assertFalse("Neutrale Prosa darf keine Nicht-Meldung erzeugen",
            violations.any { it.contains("Nicht") })
    }

    @Test
    fun overusedPhraseDetectedAcrossThreeChapters() {
        val phrase = "ein kalter Schauer lief ihr über den Rücken"
        val chapters = listOf(
            "Etwas geschah und $phrase als sie das hörte.",
            "Später am Abend $phrase erneut ohne Grund.",
            "Am Ende $phrase ein letztes Mal deutlich.",
        )
        val overused = RepetitionScan.overusedPhrases(chapters, minChapters = 3)
        assertTrue("Kapitelübergreifende Floskel muss erkannt werden", overused.isNotEmpty())
    }
}
