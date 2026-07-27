package com.novelforge.android.export

import com.novelforge.android.domain.Chapter
import com.novelforge.android.domain.Project
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Baut die Export-Artefakte für ein Projekt: Manuskript-Text, KDP-Blatt und EPUB 3. */
object ExportBuilder {

    /**
     * Export-Sperre: null = exportierbar, sonst eine klare Begründung. Verhindert, dass
     * eine Gliederung (leere Kapitel fallen sonst still auf `goal` zurück) oder
     * Fehler-Platzhalter als „fertiges Buch" exportiert und zu KDP hochgeladen werden.
     */
    fun exportBlocker(project: Project): String? {
        if (project.chapters.isEmpty()) return "Das Buch hat noch keine Kapitel."
        val unfinished = project.chapters.filter { it.text.isBlank() || it.text.startsWith("[Kapitel") }
        if (unfinished.size == project.chapters.size)
            return "Das Buch enthält noch keinen fertigen Text – nur die Gliederung."
        if (unfinished.isNotEmpty()) {
            val nums = unfinished.take(6).joinToString(", ") { it.number.toString() }
            return "Kapitel ohne fertigen Text: $nums${if (unfinished.size > 6) " …" else ""} – erst neu erzeugen, sonst würde ein unfertiges Buch exportiert."
        }
        return null
    }

    fun manuscriptText(project: Project): String {
        val sb = StringBuilder()
        sb.append(project.title).append("\n")
        sb.append("von ").append(project.authorName).append("\n\n")
        project.chapters.sortedBy { it.number }.forEach { ch ->
            sb.append("Kapitel ${ch.number}: ${ch.title}\n\n")
            sb.append(ch.text.ifBlank { ch.goal }).append("\n\n\n")
        }
        return sb.toString().trim()
    }

    fun kdpSheet(project: Project): String {
        val p = project.profile
        val sb = StringBuilder()
        sb.append("KDP-VERKAUFSBLATT\n====================\n\n")
        sb.append("VERKAUFSTITEL:\n").append(p.kdpTitle.ifBlank { project.title }).append("\n\n")
        if (p.kdpSubtitle.isNotBlank()) sb.append("UNTERTITEL:\n").append(p.kdpSubtitle).append("\n\n")
        if (p.kdpDescription.isNotBlank()) sb.append("VERKAUFSTEXT:\n").append(p.kdpDescription).append("\n\n")
        if (p.kdpKeywords.isNotBlank()) sb.append("KEYWORDS:\n").append(p.kdpKeywords).append("\n\n")
        if (p.kdpCategories.isNotBlank()) sb.append("KATEGORIEN:\n").append(p.kdpCategories).append("\n\n")
        if (p.coverPrompt.isNotBlank()) sb.append("COVER-PROMPT:\n").append(p.coverPrompt).append("\n\n")
        return sb.toString().trim()
    }

    /**
     * @param cover Optionales Titelbild. Ist es gesetzt, wird es ins EPUB eingebettet
     *   und als Cover ausgezeichnet. Ohne das enthält das Buch kein Titelbild – Lese-Apps
     *   und die KDP-Vorschau zeigen dann nur die Textseite, obwohl das Cover längst
     *   erzeugt wurde und als eigene Datei danebenliegt.
     */
    fun epubBytes(project: Project, cover: java.io.File? = null): ByteArray {
        val chapters = project.chapters.sortedBy { it.number }
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            // mimetype MUSS der erste Eintrag und unkomprimiert (STORED) sein.
            val mime = "application/epub+zip".toByteArray(Charsets.US_ASCII)
            val mimeEntry = ZipEntry("mimetype").apply {
                method = ZipEntry.STORED
                size = mime.size.toLong()
                compressedSize = mime.size.toLong()
                val crc32 = CRC32(); crc32.update(mime); crc = crc32.value
            }
            zos.putNextEntry(mimeEntry); zos.write(mime); zos.closeEntry()

            fun add(path: String, content: String) {
                zos.putNextEntry(ZipEntry(path))
                zos.write(content.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }

            add("META-INF/container.xml", CONTAINER)

            // Titelbild, falls vorhanden: das echte Cover kommt ins Buch. Ohne Bild bleibt
            // die typografische Titelseite – ein Buch ohne jede Titelseite wirkt unfertig.
            val coverDaten = runCatching {
                cover?.takeIf { it.exists() && it.length() > 1024 }?.readBytes()
            }.getOrNull()
            val coverJpeg = coverDaten != null && coverDaten.size > 2 &&
                coverDaten[0] == 0xFF.toByte() && coverDaten[1] == 0xD8.toByte()
            val coverName = if (coverJpeg) "cover.jpg" else "cover.png"

            add("OEBPS/style.css", COVER_CSS)
            add("OEBPS/cover.xhtml", coverXhtml(project, if (coverDaten != null) coverName else null))
            if (coverDaten != null) {
                zos.putNextEntry(ZipEntry("OEBPS/$coverName"))
                zos.write(coverDaten)
                zos.closeEntry()
            }

            val manifest = StringBuilder()
            val spine = StringBuilder()
            manifest.append("    <item id=\"css\" href=\"style.css\" media-type=\"text/css\"/>\n")
            manifest.append("    <item id=\"cover\" href=\"cover.xhtml\" media-type=\"application/xhtml+xml\"/>\n")
            // properties="cover-image" ist die Auszeichnung, an der Lese-Apps und Shops
            // das Titelbild erkennen.
            if (coverDaten != null) {
                manifest.append("    <item id=\"coverimg\" href=\"$coverName\" media-type=\"")
                    .append(if (coverJpeg) "image/jpeg" else "image/png")
                    .append("\" properties=\"cover-image\"/>\n")
            }
            spine.append("    <itemref idref=\"cover\"/>\n")
            // ID/Dateiname aus der Position (index+1) ableiten, NICHT aus ch.number.
            // ch.number ist nicht garantiert eindeutig -> doppelte Nummern erzeugten sonst
            // eine ZipException (doppelter Entry-Name) und doppelte Manifest-/Spine-IDs.
            chapters.forEachIndexed { i, _ ->
                val cid = "c${i + 1}"
                manifest.append("    <item id=\"$cid\" href=\"$cid.xhtml\" media-type=\"application/xhtml+xml\"/>\n")
                spine.append("    <itemref idref=\"$cid\"/>\n")
            }
            add("OEBPS/content.opf", opf(project, manifest.toString(), spine.toString()))
            add("OEBPS/nav.xhtml", nav(chapters))
            chapters.forEachIndexed { i, ch ->
                add("OEBPS/c${i + 1}.xhtml", chapterXhtml(ch.number, ch.title, ch.text.ifBlank { ch.goal }))
            }
        }
        return bos.toByteArray()
    }

    /** BCP-47-Sprachcode aus der Projekt-Sprache (für gültige EPUB-Metadaten). */
    private fun languageCode(language: String): String = when (language.trim().lowercase()) {
        "englisch", "english", "en" -> "en"
        "französisch", "franzoesisch", "french", "fr" -> "fr"
        "spanisch", "spanish", "es" -> "es"
        "italienisch", "italian", "it" -> "it"
        else -> "de"
    }

    private const val COVER_CSS = """
body.cover { margin: 0; padding: 0; text-align: center; }
.cover-inner { padding: 18% 10% 0 10%; }
.cover-title { font-size: 2em; font-weight: bold; line-height: 1.2; margin-bottom: 0.6em; }
.cover-rule { width: 40%; margin: 1.2em auto; border: 0; border-top: 2px solid #888; }
.cover-author { font-size: 1.2em; font-style: italic; }
"""

    private fun coverXhtml(project: Project, bildName: String? = null): String {
        // Mit echtem Titelbild: nur das Bild, seitenfüllend – so machen es Verlage.
        if (bildName != null) return """<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml">
<head><title>${escapeXml(project.title)}</title>
<style>html,body{margin:0;padding:0;height:100%;text-align:center}img{max-width:100%;max-height:100%}</style></head>
<body><img src="$bildName" alt="${escapeXml(project.title)}"/></body>
</html>"""
        return """<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml">
<head><title>${escapeXml(project.title)}</title><link rel="stylesheet" type="text/css" href="style.css"/></head>
<body class="cover">
  <div class="cover-inner">
    <div class="cover-title">${escapeXml(project.title)}</div>
    <hr class="cover-rule"/>
    <div class="cover-author">${escapeXml(project.authorName)}</div>
  </div>
</body>
</html>"""
    }

    /** PDF über das Android-Framework (PdfDocument + StaticLayout, mehrseitig, A4). */
    fun pdfBytes(project: Project): ByteArray {
        val text = manuscriptText(project)
        val pageW = 595; val pageH = 842; val margin = 54
        val contentW = pageW - 2 * margin
        val contentH = pageH - 2 * margin
        val paint = android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.BLACK
            textSize = 11.5f
        }
        val layout = android.text.StaticLayout.Builder
            .obtain(text, 0, text.length, paint, contentW)
            .setLineSpacing(3f, 1f)
            .build()
        val pdf = android.graphics.pdf.PdfDocument()
        var y = 0
        var pageNum = 1
        val total = layout.height.coerceAtLeast(1)
        while (y < total) {
            val info = android.graphics.pdf.PdfDocument.PageInfo.Builder(pageW, pageH, pageNum).create()
            val page = pdf.startPage(info)
            val canvas = page.canvas
            canvas.save()
            canvas.clipRect(margin.toFloat(), margin.toFloat(), (margin + contentW).toFloat(), (margin + contentH).toFloat())
            canvas.translate(margin.toFloat(), (margin - y).toFloat())
            layout.draw(canvas)
            canvas.restore()
            pdf.finishPage(page)
            y += contentH
            pageNum++
        }
        val bos = ByteArrayOutputStream()
        pdf.writeTo(bos)
        pdf.close()
        return bos.toByteArray()
    }

    /** DOCX (Office Open XML) – wird von Google Docs, Word und Pages importiert. */
    fun docxBytes(project: Project): ByteArray {
        val body = StringBuilder()
        fun para(t: String, bold: Boolean = false) {
            val rpr = if (bold) "<w:rPr><w:b/></w:rPr>" else ""
            body.append("<w:p><w:r>$rpr<w:t xml:space=\"preserve\">${escapeXml(t)}</w:t></w:r></w:p>")
        }
        para(project.title, bold = true)
        para("von ${project.authorName}")
        project.chapters.sortedBy { it.number }.forEach { ch ->
            para("Kapitel ${ch.number}: ${ch.title}", bold = true)
            ch.text.ifBlank { ch.goal }.split("\n").map { it.trim() }.filter { it.isNotEmpty() }.forEach { para(it) }
        }
        val document = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>$body<w:sectPr/></w:body></w:document>"""

        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            fun add(path: String, content: String) {
                zos.putNextEntry(ZipEntry(path))
                zos.write(content.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
            add("[Content_Types].xml", CONTENT_TYPES)
            add("_rels/.rels", DOCX_RELS)
            add("word/document.xml", document)
        }
        return bos.toByteArray()
    }

    private const val CONTENT_TYPES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
</Types>"""

    private const val DOCX_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>"""

    private const val CONTAINER = """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>"""

    private fun opf(project: Project, manifest: String, spine: String): String = """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="bookid">urn:uuid:${project.id}</dc:identifier>
    <dc:title>${escapeXml(project.title)}</dc:title>
    <dc:creator>${escapeXml(project.authorName)}</dc:creator>
    <dc:language>${languageCode(project.language)}</dc:language>
  </metadata>
  <manifest>
    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
$manifest  </manifest>
  <spine>
$spine  </spine>
</package>"""

    private fun nav(chapters: List<Chapter>): String {
        // href aus der Position (index+1) – muss exakt zur Vergabe in epubBytes passen.
        val items = chapters.mapIndexed { i, ch ->
            "      <li><a href=\"c${i + 1}.xhtml\">${escapeXml("Kapitel ${ch.number}: ${ch.title}")}</a></li>"
        }.joinToString("\n")
        return """<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
<head><title>Inhalt</title></head>
<body><nav epub:type="toc"><h1>Inhalt</h1><ol>
$items
</ol></nav></body>
</html>"""
    }

    private fun chapterXhtml(number: Int, title: String, text: String): String {
        val paras = text.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
            .joinToString("\n") { "  <p>${escapeXml(it)}</p>" }
        return """<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml">
<head><title>${escapeXml(title)}</title></head>
<body>
  <h2>${escapeXml("Kapitel $number: $title")}</h2>
$paras
</body>
</html>"""
    }

    private fun escapeXml(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            val c = ch.code
            if (c < 0x20 && c != 0x9 && c != 0xA && c != 0xD) continue // XML-1.0-illegale Steuerzeichen raus
            when (ch) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&apos;")
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }
}
