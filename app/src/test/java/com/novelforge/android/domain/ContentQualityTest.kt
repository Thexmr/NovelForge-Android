package com.novelforge.android.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentQualityTest {

    @Test
    fun wordCountIgnoresExtraWhitespace() {
        assertEquals(3, ContentQuality.wordCount("Hallo   Welt\n hier"))
        assertEquals(0, ContentQuality.wordCount("   \n  "))
    }

    @Test
    fun detectsMetaRequests() {
        assertTrue(ContentQuality.containsMetaRequest("Als KI kann ich das nicht schreiben."))
        assertTrue(ContentQuality.containsMetaRequest("Die Szene fehlt im Prompt."))
        assertFalse(ContentQuality.containsMetaRequest("Sie ging langsam nach Hause."))
    }

    @Test
    fun stripsMarkdownButKeepsWords() {
        assertEquals(
            "Das war wichtig und kursiv.",
            ContentQuality.strippingInlineFormatting("Das war **wichtig** und *kursiv*.")
        )
    }

    @Test
    fun collapsesImmediateRepeatedLines() {
        val input = "Sie log.\nSie log.\nDann ging sie."
        assertEquals("Sie log.\nDann ging sie.", ContentQuality.collapseImmediateRepeats(input))
    }

    @Test
    fun stripsLeadingTitleEcho() {
        val out = ContentQuality.stripLeadingTitleEcho("Der Schatten. Es war kalt draußen.", "Der Schatten")
        assertEquals("Es war kalt draußen.", out)
    }

    @Test
    fun acceptsChapterRequiresRealProse() {
        assertFalse(ContentQuality.acceptsChapter("", 500))
        assertFalse(ContentQuality.acceptsChapter("Als KI kann ich das nicht.", 500))
        val longEnough = (1..200).joinToString(" ") { "Wort" }
        assertTrue(ContentQuality.acceptsChapter(longEnough, 100))
    }

    @Test
    fun aiTellMatchesListsOnlyPresentPhrases() {
        val text = "Ihr Atem stockte. Die Luft zwischen ihnen knisterte. Dann ging sie zum Auto."
        val hits = ContentQuality.aiTellMatches(text)
        assertTrue(hits.contains("ihr atem stockte"))
        assertTrue(hits.contains("die luft zwischen ihnen knisterte"))
        assertTrue(ContentQuality.aiTellMatches("Sie fuhr zur Arbeit und trank Kaffee.").isEmpty())
    }

    @Test
    fun archaicMatchesFindsArchaicWords() {
        assertTrue(ContentQuality.archaicMatches("Alsbald erblickte er ihr Antlitz.").isNotEmpty())
        assertTrue(ContentQuality.archaicMatches("Er sah ihr Gesicht sofort.").isEmpty())
    }

    @Test
    fun jargonMatchesFlagsAcademicVocabulary() {
        val hits = ContentQuality.jargonMatches("Der Mediävistiker deutete den Fleck kartographisch.")
        assertTrue(hits.contains("mediävist"))
        assertTrue(hits.contains("kartographisch"))
        assertTrue(ContentQuality.jargonMatches("Sie tranken Kaffee und stritten über Geld.").isEmpty())
    }

    @Test
    fun romanceHeatLadderEscalates() {
        assertEquals(2, ContentQuality.romanceHeatTarget(0, 40))
        assertEquals(10, ContentQuality.romanceHeatTarget(39, 40))
        assertTrue(ContentQuality.romanceHeatTarget(20, 40) in 5..8)
        assertTrue(ContentQuality.isRomanceGenre("Dark Romance"))
        assertTrue(ContentQuality.isRomanceGenre("Liebesroman"))
        assertFalse(ContentQuality.isRomanceGenre("Psychothriller"))
    }

    @Test
    fun circumlocutionDensityTriggersRewrite() {
        val filler = "Sie ging weiter durch die Stadt und sah sich die Fenster an. ".repeat(15)
        val crypto = "Es war das, was sie nie sagten. Kein Umzug, sondern eine Auslöschung. " +
            "Es blieb so etwas wie Wärme, etwas, das sie nicht benennen konnte."
        assertTrue(ContentQuality.circumlocutionCount(crypto) >= 4)
        assertTrue(ContentQuality.soundsLikeAI(filler + crypto))
        assertFalse(ContentQuality.soundsLikeAI(filler))
    }

    @Test
    fun weakChapterEndingIsDetected() {
        val filler = "Sie ging weiter durch die Stadt und sah sich die Fenster an. ".repeat(15)
        val weak = filler + "Der Abend legte sich ruhig über die Dächer der kleinen Stadt und alles wurde still und friedlich an diesem langen Tag."
        assertTrue(ContentQuality.hasWeakChapterEnding(weak))
        assertFalse(ContentQuality.hasWeakChapterEnding(filler + "Aber warum war die Tür offen?"))
        assertFalse(ContentQuality.hasWeakChapterEnding(filler + "Dann sah sie das Blut."))
    }
}
