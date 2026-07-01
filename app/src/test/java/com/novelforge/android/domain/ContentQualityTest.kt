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
}
