package com.novelforge.android.generator

import com.novelforge.android.ai.AiClient
import com.novelforge.android.ai.AiConfig
import com.novelforge.android.ai.PromptFactory
import com.novelforge.android.domain.Chapter
import com.novelforge.android.domain.Character
import com.novelforge.android.domain.ContentQuality
import com.novelforge.android.domain.ContentSafetyFilter
import com.novelforge.android.domain.CopyrightFilter
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

        // Romance-Genres ohne gewählten Sinnlichkeitsgrad nicht „clean" erzeugen – sinnvollen
        // Standard setzen, damit Dark Romance/Liebesroman die Genre-Erwartung (Wärme) einlöst.
        if (project.spiceLevel == 0) {
            val g = project.genre.lowercase()
            project.spiceLevel = when {
                g.contains("dark romance") || g.contains("erotik") || g.contains("erotic") || g.contains("spicy") -> 4
                g.contains("liebes") || g.contains("romance") || g.contains("romantik") ||
                    g.contains("new adult") || g.contains("romantasy") -> 2
                else -> 0
            }
        }

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

        // Genre-Direktive: Titel + Genre vorab analysieren → verbindliche Vorgaben fürs ganze Buch.
        onProgress(GenProgress("Genre-Direktive ableiten …", 0.04f))
        val genreBrief = try {
            ai.chat(
                system = "Du bist Verlagslektor und Genre-Stratege. Antworte nur mit der Direktive.",
                prompt = PromptFactory.genreBrief(
                    project.title, project.genre, project.tropes, project.spiceLevel, project.language
                ),
                temperature = 0.5, maxTokens = 700
            ).trim()
        } catch (e: Exception) { "" }

        // 1) Konzept
        onProgress(GenProgress("Konzept entwickeln …", 0.05f))
        val conceptText = ai.chat(
            system = "Du bist ein erfahrener Verlagslektor und entwickelst originelle Buchkonzepte. Antworte direkt, niemals mit Rückfragen.",
            prompt = PromptFactory.concept(
                project.title, project.genre, project.language, project.styleProfile,
                project.targetPageCount, project.tropes, project.styleSignature,
                sequelContext = project.sequelContext, genreBrief = genreBrief
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
                sequelContext = project.sequelContext, genreBrief = genreBrief
            ),
            temperature = 0.8, maxTokens = 2200
        )

        // 3) Kapitelplan
        onProgress(GenProgress("Kapitel planen …", 0.25f))
        val planText = ai.chat(
            system = "Du bist ein Bestseller-Lektor mit Gespür für Kapitelstruktur.",
            prompt = PromptFactory.chapterPlan(
                project.title, project.genre, plot, project.chapterTarget,
                project.targetPageCount * 250 / project.chapterTarget, project.styleSignature,
                genreBrief = genreBrief
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
                if (c.age.isNotBlank()) append(", ${c.age}")
                if (c.occupation.isNotBlank()) append(", ${c.occupation}")
                if (c.goal.isNotBlank()) append(" – Ziel: ${c.goal}")
                if (c.fear.isNotBlank()) append("; Angst: ${c.fear}")
                if (c.weakness.isNotBlank()) append("; Schwäche: ${c.weakness}")
                if (c.speech.isNotBlank()) append("; Sprechweise: ${c.speech}")
                if (c.appearance.isNotBlank()) append("; Merkmale: ${c.appearance}")
            }
        }

        // 4) Kapitel schreiben
        // maxOf(1, …) verhindert eine ArithmeticException (Division durch 0), falls die
        // Kapitelplanung 0 Kapitel lieferte (Parser-Aussetzer) – sonst stürzt die ganze
        // Generierung ab, statt sauber weiterzulaufen.
        val chapterCount = maxOf(1, project.chapters.size)
        val wordsPerChapter = project.targetPageCount * 250 / chapterCount
        var storySoFar = ""
        project.chapters.forEachIndexed { index, ch ->
            onProgress(GenProgress("Kapitel ${ch.number} schreiben …", 0.30f + 0.6f * index / chapterCount))
            // Strukturierter Buchkontext (statt nur eines 6000-Zeichen-Prosa-Tails):
            // (a) Plan-Zeilen aller GESCHRIEBENEN Kapitel als verbindlicher Verlauf,
            // (b) die nächsten 2 Kapitelziele (nicht vorwegnehmen, Vorausdeutungen säen),
            // (c) fürs Finale: Prämisse/Exposé-Ende + Eröffnungsmotiv aus Kapitel 1.
            val pastOutline = project.chapters.take(index)
                .joinToString("\n") { "Kapitel ${it.number} (${it.title}): ${it.goal}" }
            val upcomingOutline = project.chapters.drop(index + 1).take(2)
                .joinToString("\n") { "Kapitel ${it.number} (${it.title}): ${it.goal}" }
            val isLastChapter = index == project.chapters.size - 1
            val finaleMaterial = if (!isLastChapter) "" else buildString {
                if (project.profile.premise.isNotBlank())
                    append("ZENTRALE PRÄMISSE (hier einlösen): ${project.profile.premise}\n")
                if (project.profile.synopsis.isNotBlank())
                    append("GEPLANTER SCHLUSS LAUT EXPOSÉ: ${project.profile.synopsis.takeLast(600)}\n")
                val openingMotif = project.chapters.firstOrNull()?.text?.take(300).orEmpty()
                if (openingMotif.isNotBlank())
                    append("SO BEGINNT DAS BUCH (EIN Bild/Motiv daraus am Ende aufgreifen): $openingMotif")
            }
            val draftPrompt = PromptFactory.draftChapter(
                project.language, project.styleProfile, project.genre, project.title,
                ch.number, ch.title, ch.goal, ch.conflict,
                project.profile.narrativePerspective, project.profile.tense,
                storySoFar, wordsPerChapter,
                isFirst = index == 0, isLast = isLastChapter,
                project.styleSignature, project.spiceLevel, charactersSummary, genreBrief = genreBrief,
                pastOutline = pastOutline, upcomingOutline = upcomingOutline,
                finaleMaterial = finaleMaterial, totalChapters = project.chapters.size
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
            text = ContentQuality.stripLeadingTitleEcho(text, ch.title)
            // Chirurgischer Line-Edit für KI-klingende Kapitel ODER Kapitel mit akademischem
            // Fachvokabular („Mediävistiker"): ersetzt GENAU die erkannten Floskeln/Archaismen/
            // Fachwörter statt (teuer und riskant) neu zu schreiben. Nur für geflaggte Kapitel.
            val jargon = ContentQuality.jargonMatches(text)
            if (text.length > 400 && (ContentQuality.soundsLikeAI(text) || jargon.isNotEmpty())) {
                val offenders = (ContentQuality.aiTellMatches(text) + ContentQuality.archaicMatches(text) + jargon)
                    .distinct().take(12)
                if (offenders.isNotEmpty()) {
                    val edited = try {
                        writer.chat(
                            system = "Du bist ein präziser Lektor. Du ersetzt nur die genannten Formulierungen, sonst nichts.",
                            prompt = PromptFactory.lineEdit(project.language, text, offenders),
                            temperature = 0.4, maxTokens = (wordCount(text) * 3).coerceIn(2000, 8000)
                        ).trim()
                    } catch (e: Exception) { "" }
                    val cleanedEdit = ContentQuality.humanizeProse(
                        ContentQuality.strippingInlineFormatting(
                            ContentQuality.strippingPromptArtifacts(edited)))
                    if (wordCount(cleanedEdit) >= (wordCount(text) * 0.85).toInt() &&
                        !ContentQuality.containsMetaRequest(cleanedEdit) &&
                        ContentSafetyFilter.isSafe(cleanedEdit)
                    ) text = cleanedEdit
                }
            }
            // Kapitelende ohne Sog? Nur den LETZTEN Absatz zu einem Haken umformen
            // (nicht das Schlusskapitel – das darf ruhig ausklingen). Ein Mini-Call,
            // deterministisch zurückgespliced.
            if (!isLastChapter && text.length > 600 && ContentQuality.hasWeakChapterEnding(text)) {
                val parts = text.split("\n\n")
                val lastPara = parts.lastOrNull { it.isNotBlank() } ?: ""
                if (lastPara.length in 80..2000) {
                    val sharpened = try {
                        writer.chat(
                            system = "Du bist ein Bestseller-Autor. Gib ausschließlich Prosatext zurück.",
                            prompt = PromptFactory.sharpenEnding(project.language, project.genre, lastPara),
                            temperature = 0.8, maxTokens = 1200
                        ).trim()
                    } catch (e: Exception) { "" }
                    val cleanedSharp = ContentQuality.humanizeProse(
                        ContentQuality.strippingInlineFormatting(
                            ContentQuality.strippingPromptArtifacts(sharpened)))
                    if (cleanedSharp.length > 40 && !ContentQuality.containsMetaRequest(cleanedSharp) &&
                        ContentSafetyFilter.isSafe(cleanedSharp)
                    ) {
                        val idx = text.lastIndexOf(lastPara)
                        if (idx >= 0) text = text.substring(0, idx) + cleanedSharp
                    }
                }
            }
            if (text.isBlank() || ContentQuality.containsMetaRequest(text)) {
                text = "[Kapitel ${ch.number} konnte nicht erzeugt werden${lastErr?.let { " ($it)" } ?: ""}. Bitte einzeln neu erzeugen.]"
            }
            // HARTE SICHERHEITSSPERRE: sexuelle Inhalte mit Kindern/Minderjährigen werden NIE gespeichert.
            ch.text = if (ContentSafetyFilter.isSafe(text)) text
                else "[Kapitel ${ch.number} vom Schutzfilter blockiert – an intimen Szenen dürfen ausschließlich erwachsene (18+) Figuren beteiligt sein. Bitte neu erzeugen.]"
            ch.wordCount = wordCount(ch.text)
            // takeLast schneidet hart bei 6000 Zeichen ab – der Kontext begänne sonst mitten
            // im Wort/Satz. Auf die nächste Satz-/Absatzgrenze ausrichten, damit die
            // Fortsetzungs-Vorlage sauber startet (bessere Kontinuität, kein Token-Fragment).
            val tail = (storySoFar + "\n\n" + ch.text).takeLast(6000)
            val boundary = tail.withIndex()
                .firstOrNull { (i, c) -> i in 0..600 && (c == '\n' || c == '.' || c == '!' || c == '?') }
                ?.index ?: -1
            storySoFar = (if (boundary >= 0) tail.substring(boundary + 1) else tail).trimStart()
        }

        // 4b) „Blick ins Buch": NUR den EINSTIEG des ersten Kapitels neu schreiben und
        // deterministisch zurücksplicen. Vorher wurde das GANZE Kapitel ersetzt – die zweite
        // Hälfte ging verloren/wurde neu erfunden und die Naht zu Kapitel 2 (das aus dem
        // Original-Ende generiert wurde) brach genau in der Amazon-Leseprobe.
        val opener = project.chapters.firstOrNull()
        if (opener != null && opener.text.length > 200 && !opener.text.startsWith("[Kapitel")) {
            onProgress(GenProgress("Eröffnung optimieren …", 0.90f))
            // Einstieg = die ersten Absätze bis ~1500 Zeichen; der Rest bleibt wörtlich erhalten.
            val paras = opener.text.split("\n\n")
            val headParas = mutableListOf<String>()
            var headLen = 0
            for (p in paras) {
                headParas.add(p); headLen += p.length + 2
                if (headLen >= 1500) break
            }
            val head = headParas.joinToString("\n\n")
            val tail = opener.text.removePrefix(head).trimStart('\n')
            val improved = try {
                writer.chat(
                    system = "Du bist ein Bestseller-Autor und Spezialist für packende Romananfänge. Gib ausschließlich Prosatext zurück.",
                    prompt = PromptFactory.optimizeOpening(
                        project.title, project.genre,
                        project.profile.narrativePerspective, project.profile.tense,
                        head, tail
                    ),
                    temperature = 0.85, maxTokens = 3000
                ).trim()
            } catch (e: Exception) { "" }
            val cleaned = ContentQuality.humanizeProse(
                ContentQuality.strippingInlineFormatting(
                    ContentQuality.strippingPromptArtifacts(improved)))
            // Nur übernehmen, wenn brauchbar UND vom Schutzfilter freigegeben; Rest splicen.
            if (cleaned.length > 200 && !ContentQuality.containsMetaRequest(cleaned) && ContentSafetyFilter.isSafe(cleaned)) {
                opener.text = if (tail.isBlank()) cleaned else cleaned + "\n\n" + tail
                opener.wordCount = wordCount(opener.text)
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

        // VIRALER VERKAUFSTITEL: 10 Kandidaten (grounded in der Story) generieren und den
        // stärksten Kauf-Titel wählen – klare, neugierig machende Titel statt schwacher/kryptischer.
        onProgress(GenProgress("Viralen Titel wählen …", 0.95f))
        val viralResp = try {
            ai.chat(
                system = "Du bist Profi für virale Buchtitel im deutschsprachigen Amazon-KDP-Markt. Antworte nur im geforderten Format.",
                prompt = PromptFactory.viralTitles(
                    project.genre,
                    project.profile.synopsis.ifBlank { project.profile.premise },
                    project.language
                ),
                temperature = 0.85, maxTokens = 600
            )
        } catch (e: Exception) { "" }
        val viral = chooseViralTitle(viralResp, project.genre)
        if (isUsableTitle(viral, project.genre)) {
            project.profile.kdpTitle = viral
            if (isWeakTitle(project.title, project.genre)) project.title = viral
        }

        // Falls noch ein Platzhalter steht, den Verkaufstitel übernehmen.
        val lowerTitle = project.title.trim().lowercase()
        if (project.profile.kdpTitle.isNotBlank() &&
            (lowerTitle == "titel" || lowerTitle == "neues buch" || lowerTitle.isBlank() ||
                Regex("^kapitel\\s+\\d+$").matches(lowerTitle))) {
            project.title = project.profile.kdpTitle
        }

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
            // Emotionaler Schritt (5. Feld) wird ins Ziel gefaltet – so fließt der geplante
            // Gefühlsbogen ohne Schema-Änderung automatisch in den Kapitel-Prompt.
            var goal = parts.getOrElse(2) { "" }
            val emotion = parts.getOrElse(4) { "" }
            if (emotion.isNotBlank()) goal = if (goal.isBlank()) emotion else "$goal – Emotionaler Schritt: $emotion"
            result.add(
                Chapter(
                    number = number,
                    title = parts[1].ifBlank { "Kapitel $number" },
                    goal = goal,
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
                    speech = parts.getOrElse(7) { "" },
                    appearance = parts.getOrElse(8) { "" },
                )
            )
        }
        return result
    }

    // ---- Virale Titel-Auswahl ------------------------------------------------

    /** Wählt aus der Titel-Antwort (KANDIDATEN + BESTER) den stärksten brauchbaren Titel. */
    private fun chooseViralTitle(response: String, genre: String): String {
        var best = ""
        val candidates = ArrayList<String>()
        for (raw in response.split("\n")) {
            val line = raw.trim()
            if (line.lowercase().startsWith("bester")) {
                val colon = line.indexOf(':')
                if (colon >= 0) best = cleanTitle(line.substring(colon + 1))
            } else if (Regex("^\\d+[).\\-:]").containsMatchIn(line)) {
                val t = cleanTitle(line.replaceFirst(Regex("^\\d+[).\\-:]\\s*"), ""))
                if (t.isNotEmpty()) candidates.add(t)
            }
        }
        if (isUsableTitle(best, genre)) return best
        return candidates.firstOrNull { isUsableTitle(it, genre) } ?: best
    }

    private fun cleanTitle(s: String): String =
        s.trim().trim(' ', '\t', '"', '\'', '„', '“', '”', '»', '«', '*', '-', '–', '—', '_', '.')

    private fun isUsableTitle(title: String, genre: String): Boolean {
        val t = title.trim()
        val words = t.split(Regex("\\s+")).count { it.isNotBlank() }
        return t.length >= 4 && words <= 7 && !isWeakTitle(t, genre)
    }

    private fun isWeakTitle(title: String, genre: String): Boolean {
        val low = title.trim().lowercase()
        if (low == "titel" || low == "neues buch" || low == "unbenannt" || Regex("^kapitel\\s+\\d+$").matches(low)) return true
        if (CopyrightFilter.isInfringingTitle(title)) return true // kein geschützter Werk-/Reihentitel
        val labels = listOf(
            "liebesroman", "erotik-roman", "erotikroman", "erotik", "thriller", "krimi",
            "roman", "dark romance", "romance", "fantasy", "new adult", "romantasy"
        )
        return low in labels || low == genre.trim().lowercase()
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
