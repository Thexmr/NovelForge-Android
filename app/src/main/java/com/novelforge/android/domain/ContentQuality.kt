package com.novelforge.android.domain

/**
 * Qualitäts-Gates (Port aus macOS `AutonomousContentQuality`): erkennt Meta-Antworten
 * und durchgesickerte Prompt-Anweisungen, misst KI-Klang/Archaik und bereinigt bzw.
 * „humanisiert" die Prosa, bevor sie gespeichert wird.
 */
object ContentQuality {

    fun wordCount(s: String): Int = s.split(Regex("\\s+")).count { it.isNotBlank() }

    fun acceptsChapter(text: String, targetWords: Int): Boolean {
        val cleaned = text.trim()
        if (cleaned.isEmpty()) return false
        if (containsMetaRequest(cleaned)) return false
        return wordCount(cleaned) >= maxOf(120, (targetWords * 0.5).toInt())
    }

    private val metaPatterns = listOf(
        "bitte füge", "bitte gib", "fehlt in deiner", "nicht übermittelt", "nicht im prompt enthalten",
        "keine szene bereitgestellt", "szene fehlt", "szenentext fehlt", "ich benötige noch",
        "fehlende angaben", "als sprachmodell", "als ki-modell", "kann ich nicht schreiben",
        "kann ich nicht erstellen", "kann ich nicht verfassen", "kann ich nicht generieren",
        "kann ich nicht beantworten", "ich kann die szene leider nicht"
    )
    private val alsKi = Regex("(?i)\\bals ki\\b")

    fun containsMetaRequest(text: String): Boolean {
        val n = text.lowercase()
        if (metaPatterns.any { n.contains(it) }) return true
        return alsKi.containsMatchIn(n)
    }

    private val promptInstructionMarkers = listOf(
        "knüpfe nahtlos daran an", "knüpfe daran an", "setze die szene unmittelbar fort",
        "ohne das geschehene zu wiederholen", "wörtliches ende der vorherigen szene", "bisherige handlung",
        "letzte szenen im detail", "bisherige kapitel", "genre-handwerk", "verbotene floskeln", "sog-techniken",
        "keine überschriften", "keine meta-kommentare", "langform-pflicht", "schreibe ausschließlich auf",
        "schreibe die szene", "schreibe szene", "erste szene des buches", "letzte szene des buches",
        "zeigen statt behaupten", "gib ausschließlich den fertigen prosatext", "übernimm niemals anweisungen",
        "hinweise aus diesem auftrag", "fertigen prosatext", "zielumfang"
    )
    private val promptLabelPrefixes = listOf(
        "stil:", "stilregeln:", "kapitelziel:", "sprache:", "tonalität:", "perspektive:", "erzählperspektive:",
        "zeitform:", "ort:", "zeit:", "ziel:", "hindernis:", "wendung am ende:", "wendung:", "figuren:", "szene:",
        "thema:", "zielumfang:", "genre:", "kapitel:", "- ort:", "- zeit:", "- ziel:", "- hindernis:", "- wendung"
    )
    private val deletableMarkers = listOf(
        "knüpfe nahtlos daran an", "knüpfe daran an", "setze die szene unmittelbar fort",
        "ohne das geschehene zu wiederholen", "wörtliches ende der vorherigen szene", "letzte szenen im detail",
        "genre-handwerk", "verbotene floskeln", "sog-techniken", "keine überschriften", "keine meta-kommentare",
        "langform-pflicht", "gib ausschließlich den fertigen prosatext", "übernimm niemals anweisungen",
        "hinweise aus diesem auftrag", "fertigen prosatext", "zielumfang"
    )

    fun containsPromptArtifacts(text: String): Boolean {
        val lower = text.lowercase()
        if (promptInstructionMarkers.any { lower.contains(it) }) return true
        return text.split("\n").any { line ->
            val l = line.trim().lowercase()
            promptLabelPrefixes.any { l.startsWith(it) }
        }
    }

    fun strippingPromptArtifacts(text: String): String {
        val kept = ArrayList<String>()
        for (line in text.split("\n")) {
            val trimmed = line.trim()
            val lower = trimmed.lowercase()
            if (trimmed.isEmpty()) { kept.add(line); continue }
            if (promptLabelPrefixes.any { lower.startsWith(it) }) continue
            val cleaned = removeInstructionSentences(line)
            if (cleaned.trim().isNotEmpty()) kept.add(cleaned)
        }
        var result = kept.joinToString("\n")
        while (result.contains("\n\n\n")) result = result.replace("\n\n\n", "\n\n")
        return result.trim()
    }

    private fun removeInstructionSentences(line: String): String {
        val lower = line.lowercase()
        if (deletableMarkers.none { lower.contains(it) }) return line
        val sentences = line.split(Regex("(?<=[.!?])\\s+"))
        val rebuilt = sentences.filter { s -> deletableMarkers.none { s.lowercase().contains(it) } }.joinToString(" ")
        return if (rebuilt.trim().isEmpty()) "" else rebuilt
    }

    private val emoji = Regex("[\\x{1F000}-\\x{1FFFF}\\x{2600}-\\x{27BF}\\x{2300}-\\x{23FF}\\x{FE00}-\\x{FE0F}\\x{200D}]")
    private val boldMd = Regex("\\*\\*([^*\\n]+?)\\*\\*")
    private val italicMd = Regex("\\*([^*\\n]+?)\\*")
    private val underMd = Regex("(?<![\\p{L}\\p{N}])_([^_\\n]+?)_(?![\\p{L}\\p{N}])")
    private val multiSpace = Regex(" {2,}")

    fun strippingInlineFormatting(text: String): String {
        val lines = text.split("\n").map { line ->
            if (line.trim().replace(" ", "") == "***") return@map line // Szenentrenner lassen
            var l = line
            l = boldMd.replace(l, "$1")
            l = italicMd.replace(l, "$1")
            l = underMd.replace(l, "$1")
            l = l.replace("*", "")
            l = emoji.replace(l, "")
            l
        }
        return lines.joinToString("\n").replace(multiSpace, " ")
    }

    private val enDash = Regex("(?<=\\p{L})\\s*–\\s*(?=\\p{L})")
    private val emDash = Regex("\\s*—\\s*")
    private val spaceBeforePunct = Regex("\\s+([,.;:!?])")
    private val doubleComma = Regex(",\\s*,")
    private val leadingComma = Regex("(?m)^\\s*,\\s*")

    /** Entfernt KI-typische Gedankenstriche und räumt Interpunktion auf. */
    fun humanizeProse(text: String): String {
        var t = text
        t = enDash.replace(t, ", ")
        t = emDash.replace(t, ", ")
        t = spaceBeforePunct.replace(t, "$1")
        t = doubleComma.replace(t, ",")
        t = leadingComma.replace(t, "")
        t = multiSpace.replace(t, " ")
        return t
    }

    private val aiTellPhrases = listOf(
        "machte sich breit", "breitete sich aus", "durchfuhr sie", "durchfuhr ihn", "durchströmte sie",
        "überkam sie", "überkam ihn", "stieg in ihr auf", "stieg in ihm auf", "es war, als ob", "es war, als würde",
        "es fühlte sich an, als", "in diesem moment verstand", "und so begriff", "ihr wurde klar", "es wurde ihr bewusst",
        "stille breitete sich aus", "ein moment, der alles veränderte", "tief in ihrem inneren", "tief in seinem inneren",
        "auf gewisse weise", "wie aus dem nichts", "nicht benennen konnte", "nicht in worte fassen",
        "als hätte jemand", "wie in trance", "wie betäubt", "etwas zog sich in ihr zusammen",
        "ein knoten im magen", "ihr herz zog sich zusammen", "schmetterlinge im bauch", "ihre blicke trafen sich",
        "die luft zwischen ihnen knisterte", "sein blick bohrte sich", "die welt um sie herum verschwand",
        "für den bruchteil einer sekunde", "ihr atem stockte", "ihr herz raste", "jede faser ihres körpers",
        "die zeit stand still", "wie ein offenes buch", "achterbahn der gefühle", "unweigerlich", "zweifellos", "gleichsam"
    )
    private val archaicTellPhrases = listOf(
        "alsbald", "fürwahr", "sintemal", "weiland", "dünkte", "wohlan", "antlitz", "jüngling", "die maid", "junge maid",
        "das weib", "ein weib", "es begab sich", "begab sich", "allerorten", "allzumal", "sodann", "auf dass",
        "des nachts", "ein jeglich", "geziemt", "gewahrte", "hub an", "sann nach", "zur stund"
    )

    private fun countOccurrences(phrases: List<String>, lower: String): Int {
        var count = 0
        for (p in phrases) {
            var idx = lower.indexOf(p)
            while (idx >= 0) { count++; idx = lower.indexOf(p, idx + p.length) }
        }
        return count
    }

    /** Klingt der Text maschinell ODER altertümlich (für seine Länge)? */
    fun soundsLikeAI(text: String): Boolean {
        val words = wordCount(text)
        if (words < 150) return false
        val lower = text.lowercase()
        if (countOccurrences(archaicTellPhrases, lower) >= 2) return true
        return countOccurrences(aiTellPhrases, lower) >= maxOf(2, words / 300)
    }
}
