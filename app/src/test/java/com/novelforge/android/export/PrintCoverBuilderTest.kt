package com.novelforge.android.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Prüft die Maßrechnung des Druckcovers gegen die KDP-Formeln.
 * Ein falsch gerechneter Buchrücken ist der häufigste Ablehnungsgrund bei KDP –
 * deshalb ist das hier festgenagelt und nicht dem Zufall überlassen.
 *
 * Nur reine Rechnung, kein Zeichnen: android.graphics gibt es im JVM-Test nicht.
 */
class PrintCoverBuilderTest {

    @Test
    fun `Rueckenbreite und Gesamtmass folgen der KDP-Formel`() {
        val m = PrintCoverBuilder.masse(500, PrintCoverBuilder.Format.F5x8, PrintCoverBuilder.Papier.WEISS)
        // 500 Blatt × 0,002252" = 1,126"
        assertEquals(1.126f, m.rueckenZoll, 0.0001f)
        // 2 × 5" + 1,126" + 2 × 0,125" Beschnitt
        assertEquals(11.376f, m.gesamtBreiteZoll, 0.0001f)
        assertEquals(8.25f, m.gesamtHoeheZoll, 0.0001f)
        assertEquals(3413, m.breitePx)
        assertEquals(2475, m.hoehePx)
    }

    @Test
    fun `Rueckentext erst ab 79 Seiten`() {
        assertFalse(PrintCoverBuilder.masse(60).rueckentextErlaubt)
        assertTrue(PrintCoverBuilder.masse(79).rueckentextErlaubt)
    }

    @Test
    fun `Cremefarbenes Papier traegt dicker auf als weisses`() {
        val weiss = PrintCoverBuilder.masse(400, papier = PrintCoverBuilder.Papier.WEISS).rueckenZoll
        val creme = PrintCoverBuilder.masse(400, papier = PrintCoverBuilder.Papier.CREME).rueckenZoll
        assertTrue("Creme muss dicker sein als Weiß", creme > weiss)
    }

    @Test
    fun `geschaetzte Seitenzahl ist immer gerade und mindestens 24`() {
        // KDP druckt nur paarweise – ungerade Seitenzahlen gibt es nicht.
        for (woerter in listOf(1, 30_000, 80_000, 128_192)) {
            val seiten = PrintCoverBuilder.schaetzeSeiten(woerter)
            assertEquals("$woerter Wörter ergaben ungerade Seitenzahl $seiten", 0, seiten % 2)
            assertTrue("KDP verlangt mindestens 24 Seiten", seiten >= 24)
        }
        assertTrue(PrintCoverBuilder.schaetzeSeiten(128_192) > 400)
    }

    @Test
    fun `groesseres Endformat braucht weniger Seiten fuer denselben Text`() {
        val klein = PrintCoverBuilder.schaetzeSeiten(100_000, PrintCoverBuilder.Format.F5x8)
        val gross = PrintCoverBuilder.schaetzeSeiten(100_000, PrintCoverBuilder.Format.F6x9)
        assertTrue("6x9 fasst mehr Text pro Seite", gross < klein)
    }
}
