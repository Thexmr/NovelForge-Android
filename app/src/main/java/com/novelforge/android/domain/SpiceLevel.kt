package com.novelforge.android.domain

/**
 * Sinnlichkeitsgrad (1–5) – branchenübliche Einstufung der erotischen Intensität.
 * 0 = nicht angegeben. Steuert die Ausführlichkeit intimer Szenen und fließt in
 * KDP-Verkaufstext/Keywords/Kategorien ein. (Aus der macOS-App portiert.)
 */
object SpiceLevel {
    val range = 1..5

    fun isValid(level: Int) = level in range

    fun label(level: Int): String = when (level) {
        1 -> "Dezent"
        2 -> "Andeutend"
        3 -> "Moderat"
        4 -> "Explizit"
        5 -> "Sehr explizit"
        else -> "Nicht angegeben"
    }

    fun pickerLabel(level: Int): String = when (level) {
        1 -> "Stufe 1 – Dezent (keine expliziten Szenen)"
        2 -> "Stufe 2 – Andeutend"
        3 -> "Stufe 3 – Moderat"
        4 -> "Stufe 4 – Explizit"
        5 -> "Stufe 5 – Sehr explizit"
        else -> "Nicht angegeben"
    }

    fun generationDirective(level: Int): String = when (level) {
        1 -> "SINNLICHKEITSGRAD 1/5 (dezent): Die Romantik lebt von Spannung, Sehnsucht und Gefühl. Körperliche Nähe nur angedeutet. Keine expliziten Szenen."
        2 -> "SINNLICHKEITSGRAD 2/5 (andeutend): Intimität wird aufgebaut, aber vor dem Expliziten ausgeblendet. Fokus auf Emotion und Begehren."
        3 -> "SINNLICHKEITSGRAD 3/5 (moderat): Es gibt explizite Liebesszenen, jedoch sparsam gesetzt; sie treiben die Beziehung voran."
        4 -> "SINNLICHKEITSGRAD 4/5 (explizit): Häufige, detaillierte Liebesszenen, zentral für die Beziehung – sinnlich, konkret, geschmackvoll. Alle Beteiligten sind erwachsen und einvernehmlich."
        5 -> "SINNLICHKEITSGRAD 5/5 (sehr explizit): Sehr häufige, sehr explizite Szenen mit hoher erotischer Intensität. Ausschließlich erwachsene, einvernehmliche Inhalte."
        else -> ""
    }

    fun kdpGuidance(level: Int): String = when (level) {
        1, 2 -> "\nSINNLICHKEITSGRAD: $level/5 (${label(level)}). Zurückhaltende KEYWORDS (z.B. Gefühlvolle Liebesgeschichte, Slow Burn). Keine expliziten Reizbegriffe."
        3 -> "\nSINNLICHKEITSGRAD: 3/5 (moderat). Sinnliche Note dezent aufgreifen. KEYWORDS z.B. Prickelnde Liebesgeschichte, Slow Burn."
        4 -> "\nSINNLICHKEITSGRAD: 4/5 (explizit). Erotische Intensität sachlich ausweisen. KEYWORDS u.a. Erotische Romance. Kurzer Inhaltshinweis (nur für Erwachsene)."
        5 -> "\nSINNLICHKEITSGRAD: 5/5 (sehr explizit). KEYWORDS u.a. Erotischer Roman, Dark Romance. Klarer Inhaltshinweis (nur für Erwachsene)."
        else -> ""
    }
}
