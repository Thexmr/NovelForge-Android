package com.novelforge.android.domain

import java.util.UUID

// ---- Kern-Datenmodelle (in-memory; Persistenz folgt via Room) -------------------

enum class ProjectStatus { CREATED, GENERATING, COMPLETED, FAILED }

data class Chapter(
    val number: Int,
    var title: String,
    var goal: String = "",
    var conflict: String = "",
    var text: String = "",
    var wordCount: Int = 0,
)

data class Character(
    val name: String,
    var role: String = "",
    var age: String = "",
    var occupation: String = "",
    var goal: String = "",
    var fear: String = "",
    var weakness: String = "",
    var speech: String = "",      // unverwechselbare Dialogstimme
    var appearance: String = "",  // kanonische äußere Merkmale
)

data class BookProfile(
    var premise: String = "",
    var logline: String = "",
    var synopsis: String = "",
    var theme: String = "",
    var audience: String = "",
    var narrativePerspective: String = "",
    var tense: String = "",
    var kdpTitle: String = "",
    var kdpSubtitle: String = "",
    var kdpDescription: String = "",
    var kdpKeywords: String = "",
    var kdpCategories: String = "",
    var coverPrompt: String = "",
)

data class Project(
    val id: String = UUID.randomUUID().toString(),
    var title: String,
    var authorName: String,
    var language: String = "Deutsch",
    var genre: String,
    var subgenre: String = "",
    var styleProfile: String = "atmosphärisch",
    var tropes: String = "",
    var spiceLevel: Int = 0,
    var seriesName: String = "",
    var seriesNumber: Int = 0,
    var sequelContext: String = "",
    var styleSignature: String = "",
    var targetPageCount: Int = 300,
    var chapterTarget: Int = 24,
    var status: ProjectStatus = ProjectStatus.CREATED,
    var profile: BookProfile = BookProfile(),
    val chapters: MutableList<Chapter> = mutableListOf(),
    val characters: MutableList<Character> = mutableListOf(),
    val createdAt: Long = 0L, // wird beim Anlegen gesetzt (kein Date in der Domain)
) {
    val wordCount: Int get() = chapters.sumOf { it.wordCount }
}

// Die umsatzstärksten Genres für den deutschen KDP-Markt (aus der Marktrecherche).
object Genres {
    val all = listOf(
        "Liebesroman", "New Adult", "Dark Romance", "Romantasy", "Fantasy",
        // Viral Hit ist kein klassisches Genre, sondern eine Arbeitsweise: das Konzept
        // wird auf Weitererzählbarkeit gebaut (starke Gefühlsreaktion, Titel der sich
        // selbst verkauft, Haken in einem Satz).
        "Viral Hit",
        "Thriller", "Psychothriller", "Krimi", "Regionalkrimi", "Cosy Crime",
        "Science Fiction", "Historischer Roman", "Jugendbuch", "Erotik",
        "Spannung", "Drama", "Abenteuer", "Mystery"
    )
    val languages = listOf("Deutsch", "Englisch")
    val styles = listOf("atmosphärisch", "temporeich", "emotional", "düster", "humorvoll", "poetisch")
}

/**
 * Baut einen kompakten Fortsetzungs-Kontext aus dem vorherigen Band (für Serien/Reihen).
 * Trägt Welt, etablierte Figuren und den Schlusszustand weiter, damit der nächste Band
 * konsistent anschließt – wie eine echte Buchreihe.
 */
object SeriesContext {
    fun build(prev: Project, bandNumber: Int): String = buildString {
        val reihe = prev.seriesName.ifBlank { prev.title }
        appendLine("Dies ist Band $bandNumber der Reihe \"$reihe\".")
        appendLine("Vorheriger Band: \"${prev.title}\" (${prev.genre}).")
        val recap = prev.profile.synopsis.ifBlank { prev.profile.premise }
        if (recap.isNotBlank()) appendLine("Was bisher geschah: $recap")
        val chars = prev.characters.joinToString("; ") { c ->
            c.name + if (c.role.isNotBlank()) " (${c.role})" else ""
        }
        if (chars.isNotBlank()) appendLine("Etablierte Hauptfiguren (fortführen): $chars")
        val lastText = prev.chapters.lastOrNull { it.text.isNotBlank() }?.text
        if (!lastText.isNullOrBlank()) appendLine("So endete der letzte Band: …${lastText.takeLast(600)}")
    }.trim()
}
