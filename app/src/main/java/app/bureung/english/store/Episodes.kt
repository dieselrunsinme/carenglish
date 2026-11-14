package app.bureung.english.store

import android.content.Context
import app.bureung.english.lesson.Episode
import app.bureung.english.lesson.EpisodeParser
import app.bureung.english.lesson.Lines
import app.bureung.english.lesson.Word

/** The episodes shipped in assets/episodes, by level, plus a word index for review. */
class Episodes(context: Context, log: (String) -> Unit) {
    val all: List<Episode> = context.assets.list("episodes").orEmpty()
        .filter { it.endsWith(".json") }
        .sorted()
        .mapNotNull { file ->
            runCatching {
                EpisodeParser.parse(context.assets.open("episodes/$file").bufferedReader(Charsets.UTF_8).use { it.readText() })
            }.onFailure { log("에피소드 읽기 실패 $file: ${it.message}") }.getOrNull()
        }

    private val words: Map<String, Word> = buildMap {
        put(Lines.RAIN.id, Lines.RAIN)
        all.forEach { e -> e.words.forEach { putIfAbsent(it.id, it) } }
    }

    fun forLevel(level: Int): List<Episode> = all.filter { it.level == level }.ifEmpty { all }

    fun byId(id: String): Episode? = all.firstOrNull { it.id == id }

    fun word(id: String): Word? = words[id]

    /** levels that have content in this build */
    val levels: Set<Int> = all.map { it.level }.toSet()
}
