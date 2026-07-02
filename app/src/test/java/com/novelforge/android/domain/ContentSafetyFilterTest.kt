package com.novelforge.android.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentSafetyFilterTest {

    @Test
    fun plainRomanceIsSafe() {
        val text = "Sie sahen sich lange an, und zwischen ihnen lag eine zarte, erwachsene Sehnsucht."
        assertTrue(ContentSafetyFilter.isSafe(text))
        assertNull(ContentSafetyFilter.violation(text))
    }

    @Test
    fun sexualContextNearMinorWordIsBlocked() {
        val text = "Das Kind saß am Tisch. Kurz darauf hatte er Sex mit ihr im selben Raum."
        assertFalse(ContentSafetyFilter.isSafe(text))
        assertNotNull(ContentSafetyFilter.violation(text))
    }

    @Test
    fun jungeFrauIsNotTreatedAsMinor() {
        // "junge Frau" darf NICHT als Minderjährigkeit gewertet werden (Negativ-Lookahead).
        val text = "Die junge Frau hatte Sex mit ihrem Geliebten, beide erwachsen."
        assertTrue(ContentSafetyFilter.isSafe(text))
    }

    @Test
    fun benignChildWishIsSafeDespiteSexualMarker() {
        // Sexueller Marker + "Kind", aber in eindeutig harmlosem Kontext (Ausnahmeliste).
        val text = "Sie hatten Sex, weil sie sich sehnlich ein gemeinsames Kind wünschten."
        assertTrue(ContentSafetyFilter.isSafe(text))
    }

    @Test
    fun underageNumericAgeNearSexualIsBlocked() {
        val text = "Sie war 15 Jahre alt, als die Szene in Geschlechtsverkehr überging."
        assertFalse(ContentSafetyFilter.isSafe(text))
    }

    @Test
    fun threeDigitAdultAgeIsNotFlagged() {
        // Regression: „115 Jahre" darf nicht als „15 Jahre" gewertet werden (Ziffern-Grenze).
        val text = "Der Vampir war 115 Jahre alt, als sie leidenschaftlichen Sex hatten."
        assertTrue(ContentSafetyFilter.isSafe(text))
    }
}
