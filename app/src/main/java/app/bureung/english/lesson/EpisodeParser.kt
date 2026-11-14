package app.bureung.english.lesson

import org.json.JSONArray
import org.json.JSONObject

/**
 * Reads one episode from JSON (assets/episodes). Each step is an object with one key that says what it is:
 * `{"en": "..."}`, `{"ko": "..."}`, `{"echo": "..."}`, `{"ask": "Elephant!", "accept": [...], "wait": 5, "word": "elephant"}`,
 * `{"card": "elephant"}`, `{"pause": 1.5}`, `{"sfx": "ding"}`. Texts may use {name} (English name) and {call} ("하린아").
 */
object EpisodeParser {
    fun parse(json: String): Episode {
        val o = JSONObject(json)
        val words = o.getJSONArray("words").objects().map { w ->
            Word(
                id = w.getString("id"),
                en = w.getString("en"),
                ko = w.getString("ko"),
                emoji = w.optString("emoji", "⭐"),
                clue = w.optString("clue", "What is it?"),
            )
        }
        val blocks = o.getJSONArray("blocks").objects().map { b ->
            Block(
                type = BlockType.valueOf(b.getString("type")),
                steps = b.getJSONArray("steps").objects().mapNotNull(::step),
                drop = b.optInt("drop", 0),
                extra = b.optBoolean("extra", false),
            )
        }
        return Episode(
            id = o.getString("id"),
            level = o.getInt("level"),
            title = o.getString("title"),
            titleKo = o.getString("titleKo"),
            emoji = o.optString("emoji", "🚗"),
            words = words,
            blocks = blocks,
        )
    }

    private fun step(s: JSONObject): Step? = when {
        s.has("en") -> Step.Say(s.getString("en"), Lang.EN)
        s.has("ko") -> Step.Say(s.getString("ko"), Lang.KO)
        s.has("echo") -> Step.Echo(s.getString("echo"))
        s.has("ask") -> Step.Ask(
            answer = s.getString("ask"),
            accept = s.optJSONArray("accept")?.strings() ?: listOf(s.getString("ask")),
            waitSec = s.optDouble("wait", 5.0),
            wordId = s.optString("word", "").ifBlank { null },
            open = s.optBoolean("open", false),
        )
        s.has("card") -> Step.Card(s.getString("card"))
        s.has("pause") -> Step.Pause(s.getDouble("pause"))
        s.has("sfx") -> Step.Sfx(s.getString("sfx"))
        else -> null
    }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
    private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }
}
