package com.novelforge.android.generator

import com.novelforge.android.ai.AiClient
import com.novelforge.android.ai.AiConfig
import com.novelforge.android.ai.PromptFactory
import com.novelforge.android.domain.Chapter
import com.novelforge.android.domain.Character
import com.novelforge.android.domain.ContentQuality
import com.novelforge.android.domain.ContentSafetyFilter
import com.novelforge.android.domain.NarrativeSignature
import com.novelforge.android.domain.Project
import com.novelforge.android.domain.ProjectStatus

data class GenProgress(val phase: String, val fraction: Float)

/**
 * Orchestriert die Buchproduktion auf Android (Koroutinen statt @MainActor/Tasks).
 * Mirror der macOS-Pipeline in schlanker Form: Konzept → Plot → Kapitelplan →
 * Kapitel schreiben → KDP-Metadaten.
 */
class NovelGenerator(config: AiConfig) {

    private val ai = AiClient(config)
    // Zweistufig: optional ein stärkeres Modell nur fürs Schreiben der Kapitel/Eröffnung.
    private val writer = AiClient(
        if (config.writingModel.isBlank()) config else config.copy(model = config.writingModel)
    )

    private fun wordCount(s: String) = s.split(Regex("\\s+")).count { it.isNotBlank() }

    suspend fun generate(project: Project, onProgress: (GenProgress) -> Unit) {
        project.status = ProjectStatus.GENERATING

        // Einzigartige Stil-DNA (respektiert vorgegebene Perspektive/Tempus, falls gesetzt).
        val signature = NarrativeSignature.make(
            NarrativeSignature.stableSeed("${project.id}|${project.title}|${project.genre}")
        )
        if (project.profile.narrativePerspective.isBlank()) project.profile.narrativePerspective = signature.pov
        if (project.profile.tense.isBlank()) project.profile.tense = signature.tense
        project.styleSignature = signature.directiveText(
            project.profile.narrativePerspective, project.profile.tense
        )

        // 0) Fortsetzung: für den nächsten Band einen frischen, passenden Titel finden.
        if (project.sequelContext.isNotBlank()) {
            onProgress(GenProgress("Bandtitel finden …", 0.03f))
            val ideaText = try {
                ai.chat(
                    "Du bist Verlagslektor für Bestseller-Reihen.",
                    PromptFactory.sequelIdea(
                        project.seriesName.ifBlank { project.title }, project.genre, project.sequelContext
                    ),
                    temperature = 0.9, maxTokens = 400
                )
            } catch (e: Exception) { "" }
            val newTitle = field(ideaText, "TITEL")
            if (newTitle.isNotBlank()) project.title = newTitle
        }

        // 1) Konzept
        onProgress(GenProgress("Konzept entwickeln …", 0.05f))
        val conceptText = ai.chat(
            system = "Du bist ein erfahrener Verlagslektor und entwickelst originelle Buchkonzepte. Antworte direkt, niemals mit Rückfragen.",
            prompt = PromptFactory.concept(
                project.title, project.genre, project.language, project.styleProfile,
                project.targetPageCount, project.tropes, project.styleSignature,
                sequelContext = project.sequelContext
            ),
            temperature = 0.85, maxTokens = 1400
        )
        parseConcept(conceptText, project)

        // 2) Plot
        onProgress(GenProgress("Plot aufbauen …", 0.15f))
        val plot = ai.chat(
            system = "Du bist ein Bestseller-Dramaturg.",
            prompt = PromptFactory.plot(
                project.title, project.genre, project.styleProfile,
                project.profile.synopsis.ifBlank { project.profile.premise },
                project.targetPageCount, project.chapterTarget, project.styleSignature,
                sequelContext = project.sequelContext
            ),
            temperature = 0.8, maxTokens = 2200
        )

        // 3) Kapitelplan
        onProgress(GenProgress("Kapitel planen …", 0.25f))
        val planText = ai.chat(
            system = "Du bist ein Bestseller-Lektor mit Gespür für Kapitelstruktur.",
            prompt = PromptFactory.chapterPlan(
                project.title, project.genre, plot, project.chapterTarget,
                project.targetPageCount * 250 / project.chapterTarget, project.styleSignature
            ),
            temperature = 0.7, maxTokens = 2200
        )
        val planned = parseChapters(planText)
        project.chapters.clear()
        project.chapters.addAll(planned)
        if (project.chapters.isEmpty()) {
            project.status = ProjectStatus.FAILED
            throw IllegalStateException("Kein Kapitelplan erhalten.")
        }

        // 3b) Figurenensemble (Story-Bible). Bei Fortsetzungen werden die etablierten
        //     Figuren des Vorbands übernommen (Kontinuität), sonst neu entwickelt.
        if (project.sequelContext.isBlank() || project.characters.isEmpty()) {
            onProgress(GenProgress("Figuren entwickeln …", 0.28f))
            val charText = try {
                ai.chat(
                    "Du bist ein Charakterentwickler für Romane.",
                    PromptFactory.characters(project.title, project.genre, plot),
                    temperature = 0.7, maxTokens = 1500
                )
            } catch (e: Exception) { "" }
            project.characters.clear()
            project.characters.addAll(parseCharacters(charText))
        }
        val charactersSummary = project.characters.joinToString("\n") { c ->
            buildString {
                append(c.name)
                if (c.role.isNotBlank()) append(" (${c.role})")
                if (c.goal.isNotBlank()) append(" – Ziel: ${c.goal}")
                if (c.weakness.isNotBlank()) append("; Schwäche: ${c.weakness}")
            }
        }

        // 4) Kapitel schreiben
        val wordsPerChapter = project.targetPageCount * 250 / project.chapters.size
        var storySoFar = ""
        project.chapters.forEachIndexed { index, ch ->
            onProgress(GenProgress("Kapitel ${ch.number} schreiben …", 0.30f + 0.6f * index / project.chapters.size))
            val draftPrompt = PromptFactory.draftChapter(
                project.language, project.styleProfile, project.genre, project.title,
                ch.number, ch.title, ch.goal, ch.conflict,
                project.profile.narrativePerspective, project.profile.tense,
                storySoFar, wordsPerChapter,
                isFirst = index == 0, isLast = index == project.chapters.size - 1,
                project.styleSignature, project.spiceLevel, charactersSummary
            )
            val minWords = maxOf(120, (wordsPerChapter * 0.6).toInt())
            var best = ""
            var lastErr: String? = null
            // Bis zu 2 Versuche: schwache, meta-haltige oder KI-klingende Fassungen werden neu geschrieben.
            for (attempt in 1..2) {
                val hint = if (attempt == 1) "" else
                    "\n\nDer vorige Versuch war zu kurz, generisch oder klang nach KI. Schreibe jetzt das vollständige Kapitel als reinen Fließtext, mindestens $minWords Wörter, ohne Meta-Kommentare. Betont menschlich: harte Satzlängen-Varianz, keine KI-Floskeln, kein deutender Schlusssatz."
                val candidate = try {
                    writer.chat(
                        system = "Du bist ein Bestseller-Autor. Gib ausschließlich den Prosatext zurück.",
                        prompt = draftPrompt + hint,
                        temperature = 0.85, maxTokens = (wordsPerChapter * 3).coerceIn(2000, 8000)
                    ).trim()
                } catch (e: Exception) { lastErr = e.message; continue }
                val candGood = candidate.isNotBlank() && !ContentQuality.containsPromptArtifacts(candidate) &&
                    !ContentQuality.soundsLikeAI(candidate) && !ContentQuality.containsMetaRequest(candidate)
                val bestGood = best.isNotBlank() && !ContentQuality.containsPromptArtifacts(best) &&
                    !ContentQuality.soundsLikeAI(best) && !ContentQuality.containsMetaRequest(best)
                if (best.isBlank() || (candGood && !bestGood) ||
                    (candGood == bestGood && wordCount(candidate) > wordCount(best))) best = candidate
                if (ContentQuality.acceptsChapter(best, wordsPerChapter) && candGood) break
            }
            // Prompt-Artefakte/Markdown raus + „menschlicher" machen, bevor gespeichert wird.
            var text = ContentQuality.humanizeProse(
                ContentQuality.strippingInlineFormatting(
                    ContentQuality.strippingPromptArtifacts(best)))
            if (text.isBlank() || ContentQuality.containsMetaRequest(text)) {
                text = "[Kapitel ${ch.number} konnte nicht erzeugt werden${lastErr?.let { " ($it)" } ?: ""}. Bitte einzeln neu erzeugen.]"
            }
            // HARTE SICHERHEITSSPERRE: sexuelle Inhalte mit Kindern/Minderjährigen werden NIE gespeichert.
            ch.text = if (ContentSafetyFilter.isSafe(text)) text
                else "[Kapitel ${ch.number} vom Schutzfilter blockiert – an intimen Szenen dürfen ausschließlich erwachsene (18+) Figuren beteiligt sein. Bitte neu erzeugen.]"
            ch.wordCount = wordCount(ch.text)
            storySoFar = (storySoFar + "\n\n" + ch.text).takeLast(6000)
        }

        // 4b) „Blick ins Buch": Eröffnung des ersten Kapitels auf Sog optimieren.
        val opener = project.chapters.firstOrNull()
        if (opener != null && opener.text.length > 200 && !opener.text.startsWith("[Kapitel")) {
            onProgress(GenProgress("Eröffnung optimieren …", 0.90f))
            val improved = try {
                writer.chat(
                    system = "Du bist ein Bestseller-Autor und Spezialist für packende Romananfänge. Gib ausschließlich Prosatext zurück.",
                    prompt = PromptFactory.optimizeOpening(
                        project.title, project.genre,
                        project.profile.narrativePerspective, project.profile.tense,
                        opener.text, wordsPerChapter
                    ),
                    temperature = 0.85, maxTokens = (wordsPerChapter * 3).coerceIn(2000, 8000)
                ).trim()
            } catch (e: Exception) { "" }
            val cleaned = ContentQuality.humanizeProse(
                ContentQuality.strippingInlineFormatting(
                    ContentQuality.strippingPromptArtifacts(improved)))
            // Nur übernehmen, wenn brauchbar UND vom Schutzfilter freigegeben.
            if (cleaned.length > 200 && !ContentQuality.containsMetaRequest(cleaned) && ContentSafetyFilter.isSafe(cleaned)) {
                opener.text = cleaned
                opener.wordCount = wordCount(cleaned)
            }
        }

        // 5) KDP-Metadaten
        onProgress(GenProgress("KDP-Verkaufstexte erstellen …", 0.92f))
        val kdpText = ai.chat(
            system = "Du bist ein Buchmarketing-Texter für Amazon KDP. Deine Beschreibungen verkaufen.",
            prompt = PromptFactory.kdpMetadata(
                project.title, project.authorName, project.genre, project.profile.audience,
                project.profile.synopsis.ifBlank { project.profile.premise },
                project.language, project.tropes, project.spiceLevel
            ),
            temperature = 0.8, maxTokens = 1200
        )
        parseKdp(kdpText, project)

        // 6) Cover-Bildprompt für das KDP-Coverdesign.
        onProgress(GenProgress("Cover-Prompt erstellen …", 0.97f))
        project.profile.coverPrompt = try {
            ai.chat(
                system = "Du bist Art-Director für Buchcover.",
                prompt = PromptFactory.coverPrompt(
                    project.title, project.genre,
                    project.profile.synopsis.ifBlank { project.profile.premise },
                    project.styleProfile
                ),
                temperature = 0.7, maxTokens = 600
            ).trim()
        } catch (e: Exception) { project.profile.coverPrompt }

        project.status = ProjectStatus.COMPLETED
        onProgress(GenProgress("Fertig", 1.0f))
    }

    // ---- Parser ---------------------------------------------------------------

    private fun field(text: String, label: String): String {
        val regex = Regex("(?im)^\\**\\s*$label\\s*:?\\**\\s*(.+)$")
        return regex.find(text)?.groupValues?.get(1)?.trim().orEmpty()
    }

    private fun parseConcept(text: String, project: Project) {
        val p = project.profile
        p.premise = field(text, "PRÄMISSE").ifBlank { p.premise }
        p.logline = field(text, "LOGLINE")
        p.synopsis = field(text, "EXPOSÉ").ifBlank { field(text, "EXPOSE") }
        p.theme = field(text, "THEMA")
        p.audience = field(text, "ZIELGRUPPE")
    }

    private fun parseChapters(text: String): List<Chapter> {
        val result = mutableListOf<Chapter>()
        for (line in text.lines()) {
            if (!line.contains("KAPITEL|")) continue
            val parts = line.substringAfter("KAPITEL|").split("|").map { it.trim() }
            if (parts.size < 3) continue
            val number = parts[0].filter { it.isDigit() }.toIntOrNull() ?: (result.size + 1)
            result.add(
                Chapter(
                    number = number,
                    title = parts[1].ifBlank { "Kapitel $number" },
                    goal = parts.getOrElse(2) { "" },
                    conflict = parts.getOrElse(3) { "" },
                )
            )
        }
        return result
    }

    private fun parseCharacters(text: String): List<Character> {
        val result = mutableListOf<Character>()
        for (line in text.lines()) {
            if (!line.contains("FIGUR|")) continue
            val parts = line.substringAfter("FIGUR|").split("|").map { it.trim() }
            val name = parts.getOrElse(0) { "" }
            if (name.isBlank()) continue
            result.add(
                Character(
                    name = name,
                    role = parts.getOrElse(1) { "" },
                    age = parts.getOrElse(2) { "" },
                    occupation = parts.getOrElse(3) { "" },
                    goal = parts.getOrElse(4) { "" },
                    fear = parts.getOrElse(5) { "" },
                    weakness = parts.getOrElse(6) { "" },
                )
            )
        }
        return result
    }

    private fun parseKdp(text: String, project: Project) {
        val p = project.profile
        p.kdpTitle = field(text, "VERKAUFSTITEL")
        p.kdpSubtitle = field(text, "UNTERTITEL")
        p.kdpKeywords = field(text, "KEYWORDS")
        // Verkaufstext und Kategorien können mehrzeilig sein → Blockextraktion.
        p.kdpDescription = block(text, "VERKAUFSTEXT", listOf("KEYWORDS", "KATEGORIEN"))
        p.kdpCategories = block(text, "KATEGORIEN", emptyList())
    }

    private fun block(text: String, label: String, stops: List<String>): String {
        val lines = text.lines()
        val start = lines.indexOfFirst { it.trimStart().startsWith(label, ignoreCase = true) }
        if (start < 0) return ""
        val sb = StringBuilder()
        val firstInline = lines[start].substringAfter(":", "").trim()
        if (firstInline.isNotBlank()) sb.appendLine(firstInline)
        for (i in (start + 1) until lines.size) {
            val l = lines[i]
            if (stops.any { l.trimStart().startsWith(it, ignoreCase = true) }) break
            sb.appendLine(l)
        }
        return sb.toString().trim()
    }
}
