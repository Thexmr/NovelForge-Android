package com.novelforge.android.export

import com.novelforge.android.domain.Chapter
import com.novelforge.android.domain.Project
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

class ExportBuilderTest {

    private fun book(vararg chapters: Chapter) = Project(
        title = "Testbuch", authorName = "Autor", genre = "Thriller"
    ).apply { this.chapters.addAll(chapters) }

    @Test
    fun exportBlockerBlocksOutlineOnlyBooks() {
        val outline = book(
            Chapter(1, "Anfang", goal = "Ziel 1"),
            Chapter(2, "Mitte", goal = "Ziel 2"),
        )
        assertNotNull(ExportBuilder.exportBlocker(outline))
    }

    @Test
    fun exportBlockerBlocksPlaceholderChapters() {
        val partial = book(
            Chapter(1, "Anfang", text = "Echter Prosatext, lang genug."),
            Chapter(2, "Mitte", text = "[Kapitel 2 vom Schutzfilter blockiert]"),
        )
        val reason = ExportBuilder.exportBlocker(partial)
        assertNotNull(reason)
        assertTrue(reason!!.contains("2"))
    }

    @Test
    fun exportBlockerAllowsFinishedBooks() {
        val done = book(
            Chapter(1, "Anfang", text = "Prosa eins."),
            Chapter(2, "Ende", text = "Prosa zwei."),
        )
        assertNull(ExportBuilder.exportBlocker(done))
    }

    @Test
    fun epubUsesUniqueEntryNamesEvenWithDuplicateChapterNumbers() {
        // Regression: doppelte Kapitelnummern führten zu ZipException (doppelter Entry-Name).
        val dup = book(
            Chapter(1, "Eins", text = "Text eins."),
            Chapter(1, "Auch eins", text = "Text zwei."),
            Chapter(2, "Zwei", text = "Text drei."),
        )
        val bytes = ExportBuilder.epubBytes(dup)
        val names = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            var e = z.nextEntry
            while (e != null) { names.add(e.name); e = z.nextEntry }
        }
        assertEquals("mimetype", names.first())
        assertEquals(names.size, names.toSet().size) // keine doppelten Entries
        assertTrue(names.containsAll(listOf("OEBPS/c1.xhtml", "OEBPS/c2.xhtml", "OEBPS/c3.xhtml")))
        assertTrue(names.contains("OEBPS/cover.xhtml"))
    }
}
