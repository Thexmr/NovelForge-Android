package com.novelforge.android.export

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.novelforge.android.domain.Project
import java.io.File
import java.io.FileOutputStream
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Vollcover für das gedruckte Taschenbuch: Rückseite + Buchrücken + Vorderseite
 * in EINER Datei, exakt in den Maßen, die Amazon KDP für den Druck verlangt.
 *
 * Warum eigenes Modul: [CoverArtService] baut das eBook-Cover (nur Vorderseite).
 * Beim Druck hängt die Breite von der SEITENZAHL ab – der Buchrücken wächst mit
 * dem Umfang. Ein falsch gerechneter Rücken ist der häufigste Ablehnungsgrund bei
 * KDP, deshalb wird hier exakt gerechnet statt geschätzt.
 *
 * KDP-Vorgaben (Zoll, gerendert bei 300 dpi):
 *   Beschnitt      0,125" an Ober-, Unter- und Außenkante
 *   Gesamthöhe     Endformat-Höhe + 2 × 0,125"
 *   Gesamtbreite   2 × Endformat-Breite + Rückenbreite + 2 × 0,125"
 *   Rückenbreite   Seitenzahl × Papierfaktor
 *   Rückentext     erst ab 79 Seiten erlaubt
 *   Barcode-Feld   2,0" × 1,2" unten rechts auf der Rückseite MUSS frei bleiben
 */
object PrintCoverBuilder {

    const val DPI = 300
    const val BLEED_IN = 0.125f
    const val SAFE_IN = 0.25f
    const val BARCODE_W_IN = 2.0f
    const val BARCODE_H_IN = 1.2f
    private const val SPINE_TEXT_MIN_PAGES = 79
    private const val SPINE_SAFE_IN = 0.0625f

    /** Papierstärke pro Blatt in Zoll (KDP-Werte). */
    enum class Papier(val faktor: Float, val deutsch: String) {
        WEISS(0.002252f, "weiß"),
        CREME(0.0025f, "creme"),
        FARBE(0.002347f, "Farbe"),
    }

    /** Gängige KDP-Endformate. [F5x8] ist das übliche Roman-Format. */
    enum class Format(val breiteZoll: Float, val hoeheZoll: Float, val woerterProSeite: Int, val bez: String) {
        F5x8(5f, 8f, 280, "5x8"),
        F5_25x8(5.25f, 8f, 300, "5.25x8"),
        F5_5x8_5(5.5f, 8.5f, 300, "5.5x8.5"),
        F6x9(6f, 9f, 330, "6x9"),
    }

    /** Alle gerechneten Maße – auch ohne zu zeichnen abrufbar. */
    data class Masse(
        val format: Format,
        val papier: Papier,
        val seiten: Int,
        val rueckenZoll: Float,
        val gesamtBreiteZoll: Float,
        val gesamtHoeheZoll: Float,
        val breitePx: Int,
        val hoehePx: Int,
        val rueckenPx: Int,
    ) {
        val rueckentextErlaubt: Boolean get() = seiten >= SPINE_TEXT_MIN_PAGES
        val kurzfassung: String
            get() = "%d×%d px @ 300 dpi · Rücken %.4f\" bei %d Seiten"
                .format(breitePx, hoehePx, rueckenZoll, seiten)
    }

    private fun px(zoll: Float) = Math.round(zoll * DPI)

    /** Schätzt die Seitenzahl aus der Wortzahl. KDP druckt nur GERADE Seitenzahlen. */
    fun schaetzeSeiten(woerter: Int, format: Format = Format.F5x8): Int {
        val roh = ceil(woerter.toDouble() / format.woerterProSeite).toInt() + 6  // Titelei, Impressum
        val s = max(24, roh)                                                     // KDP-Mindestumfang
        return if (s % 2 == 0) s else s + 1
    }

    /** Rechnet alle Maße aus, ohne zu zeichnen. */
    fun masse(seiten: Int, format: Format = Format.F5x8, papier: Papier = Papier.WEISS): Masse {
        val s = max(24, seiten)
        val ruecken = s * papier.faktor
        val breite = 2 * format.breiteZoll + ruecken + 2 * BLEED_IN
        val hoehe = format.hoeheZoll + 2 * BLEED_IN
        return Masse(
            format = format, papier = papier, seiten = s,
            rueckenZoll = ruecken, gesamtBreiteZoll = breite, gesamtHoeheZoll = hoehe,
            breitePx = px(breite), hoehePx = px(hoehe), rueckenPx = px(ruecken),
        )
    }

    data class Texte(val titel: String, val autor: String, val haken: String, val verkaufstext: String)

    data class Ergebnis(val jpeg: File, val pdf: File?, val masse: Masse)

    /**
     * Baut das druckfertige Vollcover als JPEG UND als PDF.
     * KDP nimmt das Taschenbuch-Cover nur als PDF an – das JPEG dient der Vorschau.
     *
     * @param motiv dasselbe Bild wie beim eBook-Cover (ein Buch, ein Bild).
     */
    fun baue(
        motiv: File,
        seiten: Int,
        texte: Texte,
        jpegZiel: File,
        pdfZiel: File?,
        format: Format = Format.F5x8,
        papier: Papier = Papier.WEISS,
    ): Ergebnis {
        val m = masse(seiten, format, papier)
        val bg = BitmapFactory.decodeFile(motiv.absolutePath)
            ?: error("Das Motiv konnte nicht geladen werden: ${motiv.name}")
        val bmp = zeichne(bg, m, texte)
        jpegZiel.parentFile?.mkdirs()
        FileOutputStream(jpegZiel).use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }

        var pdf: File? = null
        if (pdfZiel != null) {
            schreibePdf(bmp, m, pdfZiel)
            pdf = pdfZiel
        }
        bmp.recycle()
        bg.recycle()
        return Ergebnis(jpegZiel, pdf, m)
    }

    /** Reine Zeichenfunktion – ohne Dateisystem, damit sie prüfbar bleibt. */
    fun zeichne(motiv: Bitmap, m: Masse, t: Texte): Bitmap {
        val w = m.breitePx
        val h = m.hoehePx
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)

        val bleed = px(BLEED_IN).toFloat()
        val safe = px(SAFE_IN).toFloat()
        val trimW = px(m.format.breiteZoll).toFloat()
        val trimH = px(m.format.hoeheZoll).toFloat()

        // Panels (Android: y = 0 OBEN): links Rückseite, mittig Buchrücken, rechts
        // Vorderseite – genau wie in der KDP-Cover-Vorlage.
        //
        // Die Vorderseite wird vom RECHTEN RAND her gesetzt und der Buchrücken bekommt
        // den Rest dazwischen. Rundet man Beschnitt, Endformat und Rücken einzeln und
        // addiert sie, kommt je nach Format ein Pixel zu viel heraus – die Vorderseite
        // stünde dann über den Rand hinaus.
        val backX = bleed
        val spineX = bleed + trimW
        val frontX = w - bleed - trimW
        val rueckenBreite = max(0f, frontX - spineX)
        val trimTop = bleed

        // 1) Motiv formatfüllend über die volle Fläche – Vorder- und Rückseite ein Bild.
        val skala = max(w.toFloat() / motiv.width, h.toFloat() / motiv.height)
        val dw = motiv.width * skala
        val dh = motiv.height * skala
        c.drawBitmap(
            motiv, null,
            RectF((w - dw) / 2f, (h - dh) / 2f, (w - dw) / 2f + dw, (h - dh) / 2f + dh),
            Paint(Paint.FILTER_BITMAP_FLAG),
        )

        // 2) Rückseite abdunkeln – der Verkaufstext muss sicher lesbar sein.
        c.drawRect(0f, 0f, spineX, h.toFloat(), Paint().apply { color = Color.argb(184, 8, 8, 13) })

        // 3) Vorderseite: Verläufe oben (Titel) und unten (Autor).
        verlauf(c, frontX, 0f, w.toFloat(), h * 0.36f, oben = true)
        verlauf(c, frontX, h * 0.64f, w.toFloat(), h.toFloat(), oben = false)

        // 4) Rückseite beschriften.
        val textBreite = trimW - 2 * safe
        val barcode = RectF(
            backX + trimW - safe - px(BARCODE_W_IN),
            trimTop + trimH - safe - px(BARCODE_H_IN),
            backX + trimW - safe,
            trimTop + trimH - safe,
        )
        // Der Rückseitentext darf NIE über sein Panel hinauslaufen. Sonst schiebt er sich
        // unter den Buchrücken und wird dort abgeschnitten – im Druck sähe das aus wie
        // ein Satzfehler. Die Begrenzung erzwingt das unabhängig davon, wie gut die
        // Breitenschätzung des Umbruchs trifft.
        c.save()
        c.clipRect(backX + safe, trimTop, backX + safe + textBreite, trimTop + trimH)

        var y = trimTop + safe + 54f
        if (t.haken.isNotBlank()) {
            val hakenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(232, 199, 102)
                textSize = 54f
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                letterSpacing = 0.04f
            }
            for (zeile in umbrich(t.haken.uppercase(), hakenPaint, textBreite)) {
                c.drawText(zeile, backX + safe, y, hakenPaint)
                y += hakenPaint.textSize * 1.18f
            }
            y += 40f
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(239, 233, 220)
            textSize = 40f
            typeface = Typeface.SERIF
        }
        // Der Verkaufstext darf das Barcode-Feld nicht berühren.
        val untereGrenze = barcode.top - 70f
        // Der Haken IST der erste Satz des Klappentexts. Stünde er groß oben und gleich
        // darunter noch einmal als erster Absatz, läse sich die Rückseite wie ein Fehler.
        fun norm(x: String) = x.split(Regex("\\s+")).filter { it.isNotBlank() }.joinToString(" ").lowercase()
        val hakenNorm = norm(t.haken)
        val absaetze = t.verkaufstext.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
            .filterIndexed { i, abs -> !(i == 0 && hakenNorm.isNotBlank() && norm(abs) == hakenNorm) }
        for (absatz in absaetze) {
            if (y > untereGrenze) break
            for (zeile in umbrich(absatz, textPaint, textBreite)) {
                if (y > untereGrenze) break
                c.drawText(zeile, backX + safe, y, textPaint)
                y += textPaint.textSize * 1.45f
            }
            y += textPaint.textSize * 0.5f
        }
        if (y + 50f < barcode.top) {
            c.drawText(
                t.autor.uppercase(), backX + safe, y + 40f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.rgb(201, 162, 75)
                    textSize = 38f
                    typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                    letterSpacing = 0.08f
                },
            )
        }

        c.restore()

        // 5) Barcode-Feld: KDP druckt hier den EAN und empfiehlt ausdrücklich, den
        //    Bereich mit dem eigenen HINTERGRUND zu füllen statt mit Weiß – ein weißer
        //    Kasten sähe im Regal wie ein Druckfehler aus. Freigehalten wird er trotzdem:
        //    die Textausgabe oben endet vor dieser Zone.

        // 6) Buchrücken.
        c.drawRect(spineX, 0f, spineX + rueckenBreite, h.toFloat(),
            Paint().apply { color = Color.argb(209, 8, 8, 13) })
        if (m.rueckentextErlaubt) {
            zeichneRuecken(c, t, spineX, rueckenBreite, trimTop, trimH, safe)
        }

        // 7) Vorderseite: Titel, Linie, Autor.
        zeichneVorderseite(c, t, frontX, trimW, trimTop, trimH)
        return bmp
    }

    private fun verlauf(c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, oben: Boolean) {
        // In Stufen gezeichnet: android.graphics.LinearGradient bräuchte einen Shader,
        // 24 Streifen sind bei 300 dpi optisch stufenlos und robuster im Test.
        val stufen = 24
        val hoehe = (y1 - y0) / stufen
        for (i in 0 until stufen) {
            val t = i.toFloat() / (stufen - 1)
            val a = if (oben) (1f - t) else t
            val alpha = (a * a * 255).toInt().coerceIn(0, 255)
            c.drawRect(x0, y0 + i * hoehe, x1, y0 + (i + 1) * hoehe,
                Paint().apply { color = Color.argb(alpha, 8, 8, 13) })
        }
    }

    private fun zeichneVorderseite(c: Canvas, t: Texte, x: Float, breite: Float, top: Float, hoehe: Float) {
        val titel = t.titel.uppercase()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(247, 243, 234)
            textSize = when {
                titel.length <= 12 -> 190f
                titel.length <= 20 -> 150f
                titel.length <= 30 -> 120f
                else -> 96f
            }
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            letterSpacing = 0.03f
        }
        val zeilen = umbrich(titel, paint, breite * 0.84f)
        var y = top + hoehe * 0.13f + paint.textSize
        for (z in zeilen) { c.drawText(z, x + breite / 2f, y, paint); y += paint.textSize * 1.08f }

        c.drawRect(x + breite / 2f - 95f, y + 20f, x + breite / 2f + 95f, y + 25f,
            Paint().apply { color = Color.rgb(201, 162, 75) })

        c.drawText(t.autor.uppercase(), x + breite / 2f, top + hoehe * 0.905f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(239, 233, 220)
                textSize = 62f
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
                letterSpacing = 0.13f
            })
    }

    private fun zeichneRuecken(c: Canvas, t: Texte, x: Float, breite: Float,
                               top: Float, hoehe: Float, safe: Float) {
        val sicher = px(SPINE_SAFE_IN)
        val groesse = min(64f, max(18f, breite - 2 * sicher - 8f))
        val kurz = if (t.titel.length > 42) t.titel.take(40) + "…" else t.titel

        c.save()
        c.translate(x + breite / 2f, top + hoehe / 2f)
        c.rotate(90f)   // Rückentext läuft von oben nach unten
        c.drawText(kurz.uppercase(), 0f, groesse / 3f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(247, 243, 234)
                textSize = groesse
                typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
                letterSpacing = 0.06f
            })
        c.restore()

        c.save()
        c.translate(x + breite / 2f, top + hoehe - safe)
        c.rotate(90f)
        c.drawText(t.autor.uppercase(), 0f, groesse * 0.62f / 3f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(201, 162, 75)
                textSize = groesse * 0.62f
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                textAlign = Paint.Align.RIGHT
                letterSpacing = 0.05f
            })
        c.restore()
    }

    /** Greedy-Wortumbruch anhand der tatsächlichen Textbreite. */
    private fun umbrich(text: String, paint: Paint, maxBreite: Float): List<String> {
        val worte = text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        val zeilen = mutableListOf<String>()
        var cur = ""
        for (wort in worte) {
            val test = if (cur.isEmpty()) wort else "$cur $wort"
            if (paint.measureText(test) <= maxBreite) cur = test
            else { if (cur.isNotEmpty()) zeilen += cur; cur = wort }
        }
        if (cur.isNotEmpty()) zeilen += cur
        return zeilen
    }

    /**
     * Schreibt das Cover als druckfertiges PDF in exakter Seitengröße.
     * KDP misst genau daran, ob Rücken und Beschnitt stimmen (1 Punkt = 1/72 Zoll).
     *
     * EINSCHRÄNKUNG: Androids PdfDocument kennt nur RGB. KDP verlangt für den Druck
     * ein PDF mit CMYK-Profil und rechnet ein RGB-PDF selbst um – die Farben können
     * dadurch leicht von der Vorschau abweichen. Wer das exakt haben will, baut das
     * Druckcover auf dem Desktop (dort wird CMYK erzeugt). Das hier zu verschweigen
     * wäre schlimmer als die Einschränkung selbst.
     */
    fun schreibePdf(bmp: Bitmap, m: Masse, ziel: File) {
        val breitePt = Math.round(m.gesamtBreiteZoll * 72)
        val hoehePt = Math.round(m.gesamtHoeheZoll * 72)
        val doc = PdfDocument()
        try {
            val seite = doc.startPage(PdfDocument.PageInfo.Builder(breitePt, hoehePt, 1).create())
            seite.canvas.drawBitmap(bmp, null, Rect(0, 0, breitePt, hoehePt), Paint(Paint.FILTER_BITMAP_FLAG))
            doc.finishPage(seite)
            ziel.parentFile?.mkdirs()
            FileOutputStream(ziel).use { doc.writeTo(it) }
        } finally {
            doc.close()
        }
    }

    /** Bequemer Einstieg aus der App heraus: leitet alles aus dem Projekt ab. */
    fun baueFuer(project: Project, motiv: File, jpegZiel: File, pdfZiel: File?): Ergebnis {
        val p = project.profile
        val seiten = schaetzeSeiten(project.wordCount)
        val haken = p.kdpDescription.trim().split(Regex("(?<=[.!?])\\s|\n")).firstOrNull().orEmpty()
        return baue(
            motiv = motiv,
            seiten = seiten,
            texte = Texte(
                titel = p.kdpTitle.ifBlank { project.title },
                autor = project.authorName,
                haken = if (haken.length in 10..110) haken else "",
                verkaufstext = p.kdpDescription.ifBlank { p.synopsis },
            ),
            jpegZiel = jpegZiel,
            pdfZiel = pdfZiel,
        )
    }
}
