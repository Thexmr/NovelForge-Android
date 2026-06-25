package com.novelforge.android.export

import com.novelforge.android.domain.Chapter
import com.novelforge.android.domain.Project
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Baut die Export-Artefakte für ein Projekt: Manuskript-Text, KDP-Blatt und EPUB 3. */
object ExportBuilder {

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

    fun epubBytes(project: Project): ByteArray {
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

            val manifest = StringBuilder()
            val spine = StringBuilder()
            chapters.forEach { ch ->
                manifest.append("    <item id=\"c${ch.number}\" href=\"c${ch.number}.xhtml\" media-type=\"application/xhtml+xml\"/>\n")
                spine.append("    <itemref idref=\"c${ch.number}\"/>\n")
            }
            add("OEBPS/content.opf", opf(project, manifest.toString(), spine.toString()))
            add("OEBPS/nav.xhtml", nav(chapters))
            chapters.forEach { ch ->
                add("OEBPS/c${ch.number}.xhtml", chapterXhtml(ch.number, ch.title, ch.text.ifBlank { ch.goal }))
            }
        }
        return bos.toByteArray()
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
    <dc:language>de</dc:language>
  </metadata>
  <manifest>
    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
$manifest  </manifest>
  <spine>
$spine  </spine>
</package>"""

    private fun nav(chapters: List<Chapter>): String {
        val items = chapters.joinToString("\n") {
            "      <li><a href=\"c${it.number}.xhtml\">${escapeXml("Kapitel ${it.number}: ${it.title}")}</a></li>"
        }
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
