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

    /**
     * Werte aus der offiziellen KDP-Cover-Vorlage
     * PAPERBACK_6.000x9.000_500_STANDARD_WHITE_de_DE:
     *   Gesamtabmessungen 13.376" x 9.250"  (339.75 mm x 234.95 mm)
     *   Buchrückenbreite  1.126"            (28.60 mm)
     * Weicht die Rechnung davon ab, lehnt KDP das Cover ab.
     */
    @Test
    fun `Masse stimmen exakt mit der offiziellen KDP-Vorlage 6x9 und 500 Seiten`() {
        val m = PrintCoverBuilder.masse(500, PrintCoverBuilder.Format.F6x9, PrintCoverBuilder.Papier.WEISS)
        assertEquals(1.126f, m.rueckenZoll, 0.0001f)
        assertEquals(13.376f, m.gesamtBreiteZoll, 0.0001f)
        assertEquals(9.250f, m.gesamtHoeheZoll, 0.0001f)
        // Gegenprobe in Millimetern, wie sie in der Vorlage stehen.
        // Locale.US erzwingen: auf einem deutschen System liefert %.2f sonst ein Komma.
        fun mm(zoll: Float) = String.format(java.util.Locale.US, "%.2f", zoll * 25.4f)
        assertEquals("339.75", mm(m.gesamtBreiteZoll))
        assertEquals("234.95", mm(m.gesamtHoeheZoll))
        assertEquals("28.60", mm(m.rueckenZoll))
    }

    @Test
    fun `Panels fuellen die Leinwand genau aus`() {
        // Links Rückseite, mittig Buchrücken, rechts Vorderseite – und die Teile müssen
        // die Leinwand GENAU ausfüllen. Rundet man die Teile einzeln und addiert sie,
        // steht die Vorderseite je nach Format ein Pixel über den Rand hinaus.
        val faelle = listOf(
            PrintCoverBuilder.Format.F6x9 to 500,
            PrintCoverBuilder.Format.F5x8 to 48,
            PrintCoverBuilder.Format.F5_5x8_5 to 300,
        )
        for ((format, seiten) in faelle) {
            val m = PrintCoverBuilder.masse(seiten, format)
            val bleed = Math.round(PrintCoverBuilder.BLEED_IN * PrintCoverBuilder.DPI)
            val trimW = Math.round(format.breiteZoll * PrintCoverBuilder.DPI)
            val spineX = bleed + trimW
            val frontX = m.breitePx - bleed - trimW
            assertTrue("${format.bez}: Buchrücken liegt nicht zwischen den Deckeln", frontX > spineX)
            assertEquals("${format.bez}: Vorderseite endet nicht bündig", m.breitePx, frontX + trimW + bleed)
            assertTrue("${format.bez}: gezeichneter Rücken weicht ab",
                Math.abs((frontX - spineX) - m.rueckenPx) <= 1)
        }
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
