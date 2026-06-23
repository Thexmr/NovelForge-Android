package com.novelforge.android.domain

import kotlin.math.abs

/**
 * Harte Inhaltssperre für die Buchgenerierung (Port des macOS-Filters).
 *
 * Zero-Tolerance: Sexuelle oder erotische Inhalte im Zusammenhang mit Kindern oder
 * Minderjährigen werden NIEMALS akzeptiert – unabhängig von Genre oder
 * Sinnlichkeitsgrad. Reine lokale Heuristik (kein Netzwerk).
 *
 * Eine Verletzung liegt vor, wenn ein expliziter sexueller Marker UND ein
 * Minderjährigkeits-Hinweis (Kind-Wort oder Altersangabe < 18) nah beieinander
 * stehen. Häufige harmlose Wendungen (Kinderwunsch, „wie ein Baby", spielende
 * Kinder, „junge Frau") sind über eine Ausnahmeliste ausgenommen.
 */
object ContentSafetyFilter {

    /** Immer aktiver Sicherheitsblock für Generierungs-Prompts. */
    val promptDirective: String = listOf(
        "SICHERHEIT (ABSOLUT, NICHT VERHANDELBAR): Jede Figur, die an romantischen,",
        "intimen oder sexuellen Handlungen beteiligt ist, ist eindeutig erwachsen",
        "(mindestens 18 Jahre). Die Sexualisierung von Kindern oder Minderjährigen ist",
        "unter allen Umständen verboten - auch angedeutet, in Rückblenden, Träumen,",
        "Vergleichen oder Nebensätzen. Kommen minderjährige Figuren vor, bleiben sie von",
        "jeder sexuellen oder erotischen Darstellung vollständig ausgenommen."
    ).joinToString(" ")

    private const val PROXIMITY = 110

    private const val WRITTEN =
        "(?:zwei|drei|vier|fünf|sechs|sieben|acht|neun|zehn|elf|zwölf|dreizehn|vierzehn|fünfzehn|sechzehn|siebzehn)"

    // (?i) = case-insensitive + unicode-aware \w/\b (damit Umlaute als Wortzeichen gelten).
    private val sexual = Regex(
        "(?i)(?:" + listOf(
            "geschlechtsverkehr", "geschlechtsteil", "geschlechtsorgan",
            "hatte sex", "sex mit", "beim sex", "\\bsex\\b",
            "penis", "vagina", "schamlippen", "klitoris", "ejakul\\w*", "samenerguss",
            "orgasmus", "masturb\\w*", "penetr\\w*", "drang in (?:sie|ihn)",
            "stieß in (?:sie|ihn)", "leckte (?:ihre|seinen|ihren)", "ihre nässe",
            "stöhnte vor lust", "ritt ihn", "blies ihm", "nahm ihn in den mund",
            "had sex", "sexual intercourse", "clitoris", "ejaculat\\w*", "orgasm",
            "masturbat\\w*", "penetrat\\w*", "blowjob", "thrust into", "fuck\\w*"
        ).joinToString("|") + ")"
    )

    private val minor = Regex(
        "(?i)\\b(?:" + listOf(
            "minderjährig\\w*", "kind", "kinder", "kindes", "kindlich\\w*", "kindeskörper",
            "kleinkind\\w*", "säugling\\w*", "neugeboren\\w*", "vorschulkind\\w*",
            "grundschul\\w*", "schulkind\\w*", "kindergarten\\w*", "vorpubertär\\w*",
            "pubertierend\\w*", "halbwüchsig\\w*", "teenager", "teenie\\w*",
            "mädchen", "töchterchen", "söhnchen", "stieftochter", "stiefsohn",
            "tochter", "sohn", "knabe\\w*", "bub\\w*", "bübchen", "enkelin", "enkelkind",
            "nichte", "neffe", "zögling\\w*", "pflegekind\\w*", "ziehkind\\w*",
            "junge(?!\\s+(?:frau|mann|männer|dame|damen|leute|menschen|paar|liebe|katze|hund|welt|garde|stars?))",
            WRITTEN + "jährig\\w*",
            WRITTEN + "\\s+jahre\\s+alt",
            "underage", "minor", "juvenile", "pubescent", "preteen", "prepubescent",
            "schoolgirl", "schoolboy", "schoolchild", "toddler", "infant",
            "child", "children", "kids?", "young girl", "young boy", "little (?:girl|boy)"
        ).joinToString("|") + ")\\b"
    )

    private val benign = Regex(
        "(?i)(?:" + listOf(
            "gemeinsame[sn]? kind(?:er)?", "eigene[sn]? kind(?:er)?", "unser(?:e|en)? kind(?:er)?",
            "kinderwunsch", "wie ein kind", "wie ein baby", "wie ein kleines kind",
            "kind(?:er)? (?:zu )?(?:bekommen|erwarten|zeugen|wollen|kriegen|großzu|haben wollt\\w*)",
            "spielten die kinder", "kinder spiel\\w*", "spielende kinder",
            "kinder im garten", "kinder im nebenzimmer", "kinder im nebenraum",
            "like a (?:kid|baby|child)", "wanted (?:a )?(?:child|children|kids?|baby)",
            "children playing", "kids? play\\w*", "raising (?:a )?(?:child|children|kids?)"
        ).joinToString("|") + ")"
    )

    private val ageRegex = Regex(
        "(?i)(?:\\bim alter von\\s+)?(\\d{1,2})\\s*[-‑ ]?\\s*(?:jährig\\w*|jahre alt|jahre|jahren|j\\.)"
    )

    /** true = Text darf gespeichert werden. */
    fun isSafe(text: String): Boolean = violation(text) == null

    /** null = unbedenklich, sonst eine kurze Begründung der Sperre. */
    fun violation(text: String): String? {
        val lower = text.lowercase()

        val sexualHits = sexual.findAll(lower).map { it.range.first }.toList()
        if (sexualHits.isEmpty()) return null

        val benignRanges = benign.findAll(lower).map { it.range }.toList()
        fun inBenign(pos: Int): Boolean = benignRanges.any { pos >= it.first && pos <= it.last }

        val minorHits = minor.findAll(lower).map { it.range.first }.filter { !inBenign(it) }.toList()
        if (minorHits.any { m -> sexualHits.any { abs(it - m) <= PROXIMITY } }) {
            return "sexueller Kontext nahe Minderjährigkeits-Hinweis"
        }

        for (match in ageRegex.findAll(lower)) {
            val age = match.groupValues.getOrNull(1)?.toIntOrNull() ?: continue
            if (age < 18 && !inBenign(match.range.first) &&
                sexualHits.any { abs(it - match.range.first) <= PROXIMITY }
            ) {
                return "Altersangabe $age Jahre (<18) im sexuellen Kontext"
            }
        }
        return null
    }
}
