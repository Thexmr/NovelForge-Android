package com.novelforge.android.domain

/**
 * Buchweiter Wiederholungs- und Stil-Tic-Schutz — deterministischer Port aus macOS
 * `AutonomousContentQuality`. Verhindert die „Lieblingsfloskeln", an denen Leser
 * KI-Prosa erkennen: kapitelübergreifende N-Gramme, wörtliche Satz-Duplikate und
 * gehäufte Verneinungs-Anfänge / Körper-Beats / Adverb-Krücken. Kein Modell-Call.
 */
object RepetitionScan {

    data class SentenceStat(val sentence: String, val occurrences: Int, val words: Int)

    private fun tokens(s: String): List<String> = s.split(Regex("\\s+")).filter { it.isNotBlank() }
    private fun wc(s: String): Int = tokens(s).size

    /** Satz-Normalisierung: klein, nur a–z/0–9/äöüß + Space, mehrfach-Spaces kollabiert. */
    private fun normKey(sentence: String): String =
        sentence.lowercase()
            .replace(Regex("[^a-z0-9äöüß ]+"), " ")
            .split(Regex("\\s+")).filter { it.isNotBlank() }
            .joinToString(" ")

    // MARK: - Stil-Ticks (dichteabhängige Budgets) ---------------------------------

    fun styleTicViolations(text: String): List<String> {
        val n = wc(text)
        if (n < 200) return emptyList()
        val out = mutableListOf<String>()
        val lower = text.lowercase()

        // 1) „Nicht/Kein …" als Satzanfang.
        var nichtStarts = 0
        for (raw in text.split(Regex("[.!?…\\n]"))) {
            val s = raw.trim().trim('„', '“', '”', '»', '«', '‚', '\'', '"', ' ', '\t')
            if (s.startsWith("Nicht ") || s.startsWith("Kein ") || s.startsWith("Keine ")) nichtStarts++
        }
        val nichtBudget = maxOf(4, n / 150)
        if (nichtStarts > nichtBudget)
            out.add("„Nicht/Kein …\"-Satzanfänge: ${nichtStarts}× (erlaubt $nichtBudget). Positiv formulieren, was IST – die „Nicht X. Sondern Y.\"-Rhetorik höchstens einmal.")

        // 2) Körper-Beat-Lexeme.
        val beats = listOf("schluckte", "atemzug", "hämmerte", "stockte", "zog sich zusammen",
            "krampfte", "kribbelte", "zitterte", "bebte")
        var beatTotal = 0
        for (b in beats) beatTotal += lower.split(b).size - 1
        val beatBudget = maxOf(3, n / 200)
        if (beatTotal > beatBudget)
            out.add("Körpersignal-Beats gehäuft (${beatTotal}×, erlaubt $beatBudget). Durch konkrete Handlung, Blickrichtung, Objekt oder Dialog ersetzen.")

        // 3) Adverb-Krücken.
        val crutches = listOf("leise", "langsam", "plötzlich", "einfach", "irgendwie")
        var crutchTotal = 0
        for (c in crutches) crutchTotal += lower.split(c).size - 1
        val crutchBudget = maxOf(5, n / 120)
        if (crutchTotal > crutchBudget)
            out.add("Adverb-Krücken gehäuft (${crutchTotal}×, erlaubt $crutchBudget). Tempo/Lautstärke über die Handlung selbst zeigen.")
        return out
    }

    // MARK: - Signifikante Sätze ---------------------------------------------------

    private data class Rec(val key: String, val spelling: String)

    private fun significantRecords(text: String): List<Rec> =
        text.split(Regex("[.!?…\\n]")).mapNotNull { raw ->
            val s = raw.trim()
            val w = wc(s)
            if (w < 5 || w > 40) return@mapNotNull null
            val key = normKey(s)
            if (key.length < 26) null else Rec(key, s)
        }

    // MARK: - Buchweite exakte Satzduplikate ---------------------------------------

    fun repeatedSentenceStats(chapters: List<String>, minimumOccurrences: Int = 3): List<SentenceStat> {
        val counts = HashMap<String, Int>()
        val spelling = HashMap<String, String>()
        for (ch in chapters) for (rec in significantRecords(ch)) {
            counts[rec.key] = (counts[rec.key] ?: 0) + 1
            spelling.putIfAbsent(rec.key, rec.spelling)
        }
        return counts.filter { it.value >= minimumOccurrences }.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenByDescending { it.key.length })
            .map { val s = spelling[it.key] ?: it.key; SentenceStat(s, it.value, wc(s)) }
    }

    /** Egregiöse, veröffentlichungs-blockierende Wiederholungen (distinktiv ODER gehämmert). */
    fun blockingRepeatedSentences(chapters: List<String>): List<String> =
        repeatedSentenceStats(chapters, minimumOccurrences = 2)
            .filter { (it.words >= 7 && it.occurrences >= 2) || it.occurrences >= 5 }
            .map { it.sentence }

    // MARK: - Kollision eines NEUEN Entwurfs mit dem bisherigen Manuskript ----------

    /**
     * Längere Sätze des Entwurfs, die im bisherigen Manuskript schon wörtlich vorkommen
     * ODER sich im Entwurf selbst wiederholen. Grundlage der Wiederholungs-VERMEIDUNG
     * beim Schreiben (Entwurf wird verworfen/neu geschrieben, bevor er gespeichert wird).
     */
    fun repeatedSentenceCollisions(candidate: String, priorTexts: List<String>, maxResults: Int = 12): List<String> {
        val priorKeys = priorTexts.flatMap { significantRecords(it).map { r -> r.key } }.toHashSet()
        val seen = HashSet<String>()
        val out = mutableListOf<String>()
        for (rec in significantRecords(candidate)) {
            val dupInside = !seen.add(rec.key)
            if (!(priorKeys.contains(rec.key) || dupInside)) continue
            if (out.any { normKey(it) == rec.key }) continue
            out.add(rec.spelling)
            if (out.size >= maxResults) break
        }
        return out
    }

    // MARK: - Kapitelübergreifende N-Gramme (4–6 Wörter) ---------------------------

    private val ngramStop = setOf(
        "sagte er und sah sie", "sagte sie und sah ihn", "sah sie an und sagte",
        "sah ihn an und sagte", "es war nicht das erste", "zum ersten mal seit langem",
    )

    fun overusedPhrases(chapters: List<String>, minChapters: Int = 3, maxResults: Int = 12): List<String> {
        if (chapters.size < minChapters) return emptyList()
        val chapterCounts = HashMap<String, Int>()
        val firstSpelling = HashMap<String, String>()
        for (ch in chapters) {
            val w = tokens(ch)
            if (w.size < 6) continue
            val seen = HashSet<String>()
            for (nn in 4..6) {
                if (w.size < nn) continue
                for (start in 0..(w.size - nn)) {
                    val gramWords = w.subList(start, start + nn)
                    val raw = gramWords.joinToString(" ")
                    val key = raw.lowercase().filter { it !in ",.;:!?…„“”»«\"'()" }
                    if (key.length < 18) continue
                    if (gramWords.none { it.length > 5 }) continue
                    if (ngramStop.contains(key) || seen.contains(key)) continue
                    seen.add(key)
                    chapterCounts[key] = (chapterCounts[key] ?: 0) + 1
                    firstSpelling.putIfAbsent(key, raw)
                }
            }
        }
        val hits = chapterCounts.filter { it.value >= minChapters }.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenByDescending { it.key.length })
        val results = mutableListOf<String>()
        for (e in hits) {
            if (results.size >= maxResults) break
            val spelled = firstSpelling[e.key] ?: e.key
            if (results.none { it.lowercase().contains(e.key) || e.key.contains(it.lowercase()) })
                results.add(spelled)
        }
        return results
    }
}
