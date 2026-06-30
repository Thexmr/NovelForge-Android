package com.novelforge.android.domain

/**
 * Urheberrechts-Schutz: stellt sicher, dass kein fremdes Werk übernommen wird – über eine
 * proaktive Prompt-Direktive (Prävention) und eine Denylist geschützter Werke/Figuren/Welten
 * für Titel- und Textprüfung. Bewusst breit für die meistproduzierten Genres.
 */
object CopyrightFilter {

    val forbiddenTerms = listOf(
        // Meta / direkte Nachahmung
        "bestsellerautor", "kopiere", "fortsetzung von", "schreibe wie", "wie j.k. rowling",
        "wie stephen king", "wie george r.r. martin", "wie dan brown", "wie colleen hoover",
        "wie e.l. james", "wie sarah j. maas",
        // Fantasy / SciFi – Welten & Figuren
        "harry potter", "hermine granger", "hermione granger", "dumbledore", "voldemort",
        "hogwarts", "gryffindor", "slytherin", "herr der ringe", "mittelerde", "auenland",
        "frodo", "gandalf", "sauron", "game of thrones", "westeros", "daenerys", "jon snow",
        "khaleesi", "lannister", "targaryen", "star wars", "darth vader", "jedi", "skywalker",
        "star trek", "marvel", "dc comics", "spider-man", "batman", "hunger games", "katniss",
        "tribute von panem", "percy jackson", "narnia",
        // Romance-Bestseller (Hauptgenre)
        "fifty shades", "shades of grey", "christian grey", "anastasia steele", "bridgerton",
        "outlander", "twilight", "bella swan", "edward cullen", "jacob black", "it ends with us",
        "a court of thorns", "feyre", "rhysand", "throne of glass",
        // Thriller / Krimi
        "sherlock holmes", "james bond", "jack reacher", "hannibal lecter", "lisbeth salander",
        "kommissar wallander", "harry hole", "robert langdon", "da vinci code"
    )

    /** Verbindliche Eigenständigkeits-Direktive für die Generierungs-Prompts. */
    val promptDirective: String = """
        EIGENSTÄNDIGKEIT (verbindlich, Urheberrecht): Erfinde ALLE Figuren, Namen, Orte, Welten, Organisationen und Titel selbst. Übernimm KEINE Figuren, Schauplätze, Magie-/Weltensysteme oder Handlungsstränge aus existierenden Werken (z. B. Harry Potter/Hogwarts, Herr der Ringe, Game of Thrones, Twilight, Fifty Shades, Bridgerton). Zitiere NIEMALS reale Songtexte, Gedichte oder geschützte Passagen. Keine realen, identifizierbaren Personen in erfundenen ehrenrührigen Handlungen. Markennamen höchstens beiläufig, keine Slogans/Logos/Werbetexte.
    """.trimIndent()

    /** Liegt der Titel zu nah an einem geschützten Werk/Reihentitel? */
    fun isInfringingTitle(title: String): Boolean {
        val low = title.lowercase()
        return forbiddenTerms.any { low.contains(it) }
    }

    /** Scannt Text auf geschützte Namen/Begriffe (für einen Originalitäts-Hinweis). */
    fun checkText(text: String): List<String> {
        val low = text.lowercase()
        return forbiddenTerms.filter { low.contains(it) }.map { "Geschützter Name/Begriff im Text: \"$it\"" }
    }
}
