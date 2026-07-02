package com.novelforge.android.data

import com.novelforge.android.domain.BookProfile
import com.novelforge.android.domain.Chapter
import com.novelforge.android.domain.Character
import com.novelforge.android.domain.Project
import com.novelforge.android.domain.ProjectStatus
import org.json.JSONArray
import org.json.JSONObject

/** JSON-(De)Serialisierung der Projekte für die lokale Persistenz (org.json, ohne Zusatzlib). */
object ProjectJson {

    fun encodeList(projects: List<Project>): String {
        val arr = JSONArray()
        projects.forEach { arr.put(encode(it)) }
        return arr.toString()
    }

    fun decodeList(text: String): List<Project> {
        if (text.isBlank()) return emptyList()
        val arr = JSONArray(text)
        val out = ArrayList<Project>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(decode(o))
        }
        return out
    }

    private fun encode(p: Project): JSONObject {
        val o = JSONObject()
        o.put("id", p.id)
        o.put("title", p.title)
        o.put("authorName", p.authorName)
        o.put("language", p.language)
        o.put("genre", p.genre)
        o.put("subgenre", p.subgenre)
        o.put("styleProfile", p.styleProfile)
        o.put("tropes", p.tropes)
        o.put("spiceLevel", p.spiceLevel)
        o.put("seriesName", p.seriesName)
        o.put("seriesNumber", p.seriesNumber)
        o.put("sequelContext", p.sequelContext)
        o.put("styleSignature", p.styleSignature)
        o.put("targetPageCount", p.targetPageCount)
        o.put("chapterTarget", p.chapterTarget)
        o.put("status", p.status.name)
        o.put("createdAt", p.createdAt)
        o.put("profile", encodeProfile(p.profile))
        val chs = JSONArray(); p.chapters.forEach { chs.put(encodeChapter(it)) }
        o.put("chapters", chs)
        val crs = JSONArray(); p.characters.forEach { crs.put(encodeCharacter(it)) }
        o.put("characters", crs)
        return o
    }

    private fun encodeProfile(b: BookProfile): JSONObject = JSONObject()
        .put("premise", b.premise).put("logline", b.logline).put("synopsis", b.synopsis)
        .put("theme", b.theme).put("audience", b.audience)
        .put("narrativePerspective", b.narrativePerspective).put("tense", b.tense)
        .put("kdpTitle", b.kdpTitle).put("kdpSubtitle", b.kdpSubtitle)
        .put("kdpDescription", b.kdpDescription).put("kdpKeywords", b.kdpKeywords)
        .put("kdpCategories", b.kdpCategories).put("coverPrompt", b.coverPrompt)

    private fun encodeChapter(c: Chapter): JSONObject = JSONObject()
        .put("number", c.number).put("title", c.title).put("goal", c.goal)
        .put("conflict", c.conflict).put("text", c.text).put("wordCount", c.wordCount)

    private fun encodeCharacter(c: Character): JSONObject = JSONObject()
        .put("name", c.name).put("role", c.role).put("age", c.age)
        .put("occupation", c.occupation).put("goal", c.goal).put("fear", c.fear)
        .put("weakness", c.weakness)
        .put("speech", c.speech).put("appearance", c.appearance)

    private fun decode(o: JSONObject): Project {
        val chapters = ArrayList<Chapter>()
        o.optJSONArray("chapters")?.let { a ->
            for (i in 0 until a.length()) a.optJSONObject(i)?.let { chapters.add(decodeChapter(it)) }
        }
        val characters = ArrayList<Character>()
        o.optJSONArray("characters")?.let { a ->
            for (i in 0 until a.length()) a.optJSONObject(i)?.let { characters.add(decodeCharacter(it)) }
        }
        val status = runCatching { ProjectStatus.valueOf(o.optString("status", "CREATED")) }
            .getOrDefault(ProjectStatus.CREATED)
        return Project(
            id = o.optString("id"),
            title = o.optString("title"),
            authorName = o.optString("authorName"),
            language = o.optString("language", "Deutsch"),
            genre = o.optString("genre"),
            subgenre = o.optString("subgenre"),
            styleProfile = o.optString("styleProfile", "atmosphärisch"),
            tropes = o.optString("tropes"),
            spiceLevel = o.optInt("spiceLevel", 0),
            seriesName = o.optString("seriesName"),
            seriesNumber = o.optInt("seriesNumber", 0),
            sequelContext = o.optString("sequelContext"),
            styleSignature = o.optString("styleSignature"),
            targetPageCount = o.optInt("targetPageCount", 300),
            chapterTarget = o.optInt("chapterTarget", 24),
            status = status,
            profile = decodeProfile(o.optJSONObject("profile")),
            chapters = chapters.toMutableList(),
            characters = characters.toMutableList(),
            createdAt = o.optLong("createdAt", 0L),
        )
    }

    private fun decodeProfile(o: JSONObject?): BookProfile {
        if (o == null) return BookProfile()
        return BookProfile(
            premise = o.optString("premise"),
            logline = o.optString("logline"),
            synopsis = o.optString("synopsis"),
            theme = o.optString("theme"),
            audience = o.optString("audience"),
            narrativePerspective = o.optString("narrativePerspective"),
            tense = o.optString("tense"),
            kdpTitle = o.optString("kdpTitle"),
            kdpSubtitle = o.optString("kdpSubtitle"),
            kdpDescription = o.optString("kdpDescription"),
            kdpKeywords = o.optString("kdpKeywords"),
            kdpCategories = o.optString("kdpCategories"),
            coverPrompt = o.optString("coverPrompt"),
        )
    }

    private fun decodeChapter(o: JSONObject): Chapter = Chapter(
        number = o.optInt("number", 0),
        title = o.optString("title"),
        goal = o.optString("goal"),
        conflict = o.optString("conflict"),
        text = o.optString("text"),
        wordCount = o.optInt("wordCount", 0),
    )

    private fun decodeCharacter(o: JSONObject): Character = Character(
        name = o.optString("name"),
        role = o.optString("role"),
        age = o.optString("age"),
        occupation = o.optString("occupation"),
        goal = o.optString("goal"),
        fear = o.optString("fear"),
        weakness = o.optString("weakness"),
        speech = o.optString("speech"),
        appearance = o.optString("appearance"),
    )
}
