package com.novelforge.android.shizuku

import kotlinx.coroutines.delay

/**
 * Liest den Bestätigungscode (2FA/OTP), den Amazon beim Anmelden per SMS schickt.
 *
 * Warum über Shizuku: Die App braucht dadurch KEINE `READ_SMS`-Berechtigung. Shizuku
 * stellt ADB-Rechte bereit, mit denen der SMS-Speicher gelesen werden kann – das ist
 * deutlich zurückhaltender, als der App dauerhaft Zugriff auf alle Nachrichten zu geben.
 *
 * Bewusst eng gehalten:
 * - Es werden nur Nachrichten gelesen, die NACH dem Anmeldeversuch eingegangen sind.
 * - Es wird ausschließlich der Zahlencode herausgezogen, nichts sonst gespeichert.
 * - Der Nachrichtentext wird nie protokolliert oder verschickt.
 */
object SmsCodeReader {

    /** Absender/Texte, die auf einen Amazon-Anmeldecode hindeuten. */
    private val hinweise = listOf("amazon", "kdp", "sicherheitscode", "security code",
        "bestätigungscode", "verification", "einmalkennwort", "otp")

    /** 4- bis 8-stellige Zahlenfolge – das übliche Format solcher Codes. */
    private val codeMuster = Regex("(?<!\\d)(\\d{4,8})(?!\\d)")

    /**
     * Wartet bis zu [timeoutMs] auf eine neue SMS mit Anmeldecode und liefert diesen.
     * [abZeitpunkt] ist die Zeit (epoch ms) des Anmeldeversuchs – ältere Nachrichten
     * werden ignoriert, damit kein alter Code aus dem Verlauf verwendet wird.
     */
    suspend fun warteAufCode(abZeitpunkt: Long, timeoutMs: Long = 120_000): String? {
        val ende = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < ende) {
            leseCode(abZeitpunkt)?.let { return it }
            delay(3000)
        }
        return null
    }

    /** Einmaliger Blick in den Posteingang; gibt den neuesten passenden Code zurück. */
    suspend fun leseCode(abZeitpunkt: Long): String? {
        val ausgabe = runCatching {
            // Nur die letzten Nachrichten, neueste zuerst.
            ShizukuBridge.exec(
                "content query --uri content://sms/inbox " +
                    "--projection body:date:address --sort \"date DESC\"",
                timeoutMs = 15_000,
            )
        }.getOrNull() ?: return null

        for (zeile in ausgabe.lineSequence()) {
            val datum = Regex("date=(\\d+)").find(zeile)?.groupValues?.get(1)?.toLongOrNull() ?: continue
            if (datum < abZeitpunkt) break          // ab hier nur noch ältere → abbrechen
            val text = Regex("body=(.*?)(?:, date=|$)").find(zeile)?.groupValues?.get(1).orEmpty()
            val absender = Regex("address=([^,]*)").find(zeile)?.groupValues?.get(1).orEmpty()
            val zusammen = (text + " " + absender).lowercase()
            if (hinweise.none { zusammen.contains(it) }) continue
            // Jahreszahlen o. Ä. ausschließen: bevorzugt 6-stellig (Amazons Format).
            val treffer = codeMuster.findAll(text).map { it.groupValues[1] }.toList()
            (treffer.firstOrNull { it.length == 6 } ?: treffer.firstOrNull())?.let { return it }
        }
        return null
    }

    /**
     * Erkennt ein Code-Eingabefeld auf dem Bildschirm, holt den Code aus der SMS und
     * trägt ihn ein. Gibt eine Klartext-Meldung für die Oberfläche zurück.
     */
    suspend fun codeEintragenFallsGefragt(abZeitpunkt: Long): String? {
        val felder = ChromeAutomation.leseOberflaeche()
        val brauchtCode = felder.any { n ->
            listOf("code", "otp", "einmalkennwort", "bestätigung", "verifizier")
                .any { n.passt(it) }
        }
        if (!brauchtCode) return null

        val code = warteAufCode(abZeitpunkt) ?: return "Bestätigungscode nicht gefunden – bitte einmal manuell eingeben."
        val feld = ChromeAutomation.warteAuf("code", timeoutMs = 8_000)
            ?: return "Code $code empfangen, aber kein Eingabefeld gefunden."
        ChromeAutomation.tippe(feld)
        ChromeAutomation.schreibe(code)
        // Bestätigen (Knopf heißt je nach Seite unterschiedlich).
        listOf("senden", "weiter", "bestätigen", "submit").firstOrNull {
            ChromeAutomation.tippeAuf(it, timeoutMs = 4_000)
        }
        return "Bestätigungscode automatisch eingetragen."
    }
}
