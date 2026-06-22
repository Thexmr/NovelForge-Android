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
    var styleSignature: String = "",
    var targetPageCount: Int = 300,
    var chapterTarget: Int = 24,
    var status: ProjectStatus = ProjectStatus.CREATED,
    var profile: BookProfile = BookProfile(),
    val chapters: MutableList<Chapter> = mutableListOf(),
    val createdAt: Long = 0L, // wird beim Anlegen gesetzt (kein Date in der Domain)
) {
    val wordCount: Int get() = chapters.sumOf { it.wordCount }
}

// Die umsatzstärksten Genres für den deutschen KDP-Markt (aus der Marktrecherche).
object Genres {
    val all = listOf(
        "Liebesroman", "New Adult", "Dark Romance", "Romantasy", "Fantasy",
        "Thriller", "Psychothriller", "Krimi", "Regionalkrimi", "Cosy Crime",
        "Science Fiction", "Historischer Roman", "Jugendbuch", "Erotik",
        "Spannung", "Drama", "Abenteuer", "Mystery"
    )
    val languages = listOf("Deutsch", "Englisch")
    val styles = listOf("atmosphärisch", "temporeich", "emotional", "düster", "humorvoll", "poetisch")
}
