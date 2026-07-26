package com.novelforge.android.shizuku

import android.util.Xml
import kotlinx.coroutines.delay
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

/**
 * Steuert das ECHTE Chrome des Nutzers über Shizuku (ADB-Rechte).
 *
 * Wichtig: Es wird NICHT blind auf Koordinaten getippt. Stattdessen liest
 * `uiautomator dump` den echten UI-Baum aus; daraus werden die Bildschirm-Koordinaten
 * des gesuchten Elements berechnet und exakt getroffen. Das ist derselbe Mechanismus,
 * den Androids eigene Test-Automatisierung nutzt.
 */
object ChromeAutomation {

    private const val CHROME = "com.android.chrome"
    private const val DUMP_PFAD = "/sdcard/novelforge_ui.xml"

    /** Ein Element des UI-Baums mit Bildschirmposition. */
    data class Node(
        val text: String,
        val resourceId: String,
        val contentDesc: String,
        val klasse: String,
        val links: Int, val oben: Int, val rechts: Int, val unten: Int,
    ) {
        val mitteX: Int get() = (links + rechts) / 2
        val mitteY: Int get() = (oben + unten) / 2
        fun passt(suche: String): Boolean {
            val s = suche.lowercase()
            return text.lowercase().contains(s) ||
                contentDesc.lowercase().contains(s) ||
                resourceId.lowercase().contains(s)
        }
    }

    /** Ist Chrome installiert? */
    suspend fun chromeVorhanden(): Boolean =
        ShizukuBridge.exec("pm list packages $CHROME").contains(CHROME)

    /** Öffnet eine Adresse im echten Chrome (nutzt dessen bestehende Anmeldung). */
    suspend fun oeffne(url: String) {
        ShizukuBridge.exec("am start -a android.intent.action.VIEW -d \"$url\" $CHROME")
        delay(2500)
    }

    /** Liest den aktuellen UI-Baum aus und liefert alle Elemente. */
    suspend fun leseOberflaeche(): List<Node> {
        ShizukuBridge.exec("uiautomator dump $DUMP_PFAD")
        val xml = ShizukuBridge.exec("cat $DUMP_PFAD")
        return parse(xml)
    }

    /** Sucht ein Element, bis es erscheint (oder die Zeit abläuft). */
    suspend fun warteAuf(suche: String, timeoutMs: Long = 20_000): Node? {
        val ende = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < ende) {
            leseOberflaeche().firstOrNull { it.passt(suche) }?.let { return it }
            delay(1200)
        }
        return null
    }

    suspend fun tippe(node: Node) {
        ShizukuBridge.exec("input tap ${node.mitteX} ${node.mitteY}")
        delay(700)
    }

    /** Tippt auf ein Element, sobald es gefunden wird. true = getippt. */
    suspend fun tippeAuf(suche: String, timeoutMs: Long = 20_000): Boolean {
        val n = warteAuf(suche, timeoutMs) ?: return false
        tippe(n)
        return true
    }

    /**
     * Schreibt Text in das aktuell fokussierte Feld. `input text` versteht keine
     * Leerzeichen – die müssen als %s kodiert werden; Sonderzeichen werden maskiert.
     */
    suspend fun schreibe(text: String) {
        val sicher = text
            .replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("$", "\\$").replace("&", "\\&")
            .replace("<", "\\<").replace(">", "\\>")
            .replace("|", "\\|").replace(";", "\\;")
            .replace("(", "\\(").replace(")", "\\)")
            .replace("\n", " ")
            .replace(" ", "%s")
        // In Blöcken senden: sehr lange Texte lässt `input text` sonst abschneiden.
        sicher.chunked(180).forEach {
            ShizukuBridge.exec("input text \"$it\"")
            delay(350)
        }
    }

    /** Feld antippen und befüllen. */
    suspend fun fuelle(suche: String, wert: String, timeoutMs: Long = 15_000): Boolean {
        if (wert.isBlank()) return false
        if (!tippeAuf(suche, timeoutMs)) return false
        delay(400)
        schreibe(wert)
        return true
    }

    suspend fun scrolleRunter() {
        ShizukuBridge.exec("input swipe 540 1600 540 700 300")
        delay(900)
    }

    // ---- XML-Auswertung ------------------------------------------------------

    private val bounds = Regex("\\[(\\d+),(\\d+)]\\[(\\d+),(\\d+)]")

    fun parse(xml: String): List<Node> {
        val liste = mutableListOf<Node>()
        runCatching {
            val p = Xml.newPullParser()
            p.setInput(StringReader(xml))
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG && p.name == "node") {
                    val b = bounds.find(p.getAttributeValue(null, "bounds") ?: "")
                    if (b != null) {
                        liste.add(
                            Node(
                                text = p.getAttributeValue(null, "text").orEmpty(),
                                resourceId = p.getAttributeValue(null, "resource-id").orEmpty(),
                                contentDesc = p.getAttributeValue(null, "content-desc").orEmpty(),
                                klasse = p.getAttributeValue(null, "class").orEmpty(),
                                links = b.groupValues[1].toInt(), oben = b.groupValues[2].toInt(),
                                rechts = b.groupValues[3].toInt(), unten = b.groupValues[4].toInt(),
                            )
                        )
                    }
                }
                ev = p.next()
            }
        }
        return liste
    }
}
