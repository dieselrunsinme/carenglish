package app.bureung.english.store

import android.content.Context
import android.content.SharedPreferences
import app.bureung.english.lesson.DiaryEntry
import app.bureung.english.lesson.LessonSummary
import app.bureung.english.lesson.LevelCoach
import app.bureung.english.lesson.Levels
import app.bureung.english.lesson.Talked
import app.bureung.english.lesson.Srs
import app.bureung.english.lesson.TripMode
import app.bureung.english.lesson.WordState
import org.json.JSONArray
import org.json.JSONObject

enum class ModeChoice { AUTO, TO_SCHOOL, FROM_SCHOOL }
enum class VoiceChoice { AUTO, PLEOS, ANDROID }

/** One finished lesson as the parent report shows it. Text only: no audio is ever stored. */
data class LessonRecord(
    val day: Long,
    val episodeId: String,
    val title: String,
    val mode: TripMode,
    val seconds: Int,
    val cards: List<String>,
    val asks: Int,
    val hits: Int,
    val heard: List<String>,
    /** the conversation: what the child said, the sentence to repeat, and 부릉이's one-line diary of it */
    val diary: List<DiaryEntry> = emptyList(),
    /** a conversation with 부릉이 (not a recorded lesson) */
    val chat: Boolean = false,
    /** the situation talked about (상황별 학습), if any */
    val scenarioId: String? = null,
    /** the step the conversation was at (0: recorded before steps were kept) */
    val level: Int = 0,
)

/**
 * Everything the app remembers, on this device only: the child's profile, settings, word review states, stickers
 * and a short lesson log.
 */
class Progress(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("bureung", Context.MODE_PRIVATE)

    var nameKo: String
        get() = prefs.getString("nameKo", "하린") ?: "하린"
        set(v) = prefs.edit().putString("nameKo", v.trim()).apply()
    var nameEn: String
        get() = prefs.getString("nameEn", "Harin") ?: "Harin"
        set(v) = prefs.edit().putString("nameEn", v.trim()).apply()
    /** how much English the child has: 1 words, 2 short sentences, 3 conversation */
    /** the conversation step, 1 to 5 (see Levels). Earlier versions had three: 1, 2, 3 are now 1, 3, 4. */
    var level: Int
        get() {
            if (prefs.contains("level5")) return prefs.getInt("level5", 2).coerceIn(Levels.MIN, Levels.MAX)
            return when (prefs.getInt("level", 0)) { 1 -> 1; 2 -> 3; 3 -> 4; else -> 2 }
        }
        set(v) {
            // a new step starts its own count toward the next suggestion
            if (v != level) levelAskAfterDay = 0
            prefs.edit().putInt("level5", v.coerceIn(Levels.MIN, Levels.MAX)).apply()
        }
    /** "not now" on the suggestion to move up: it is not shown again before this day */
    var levelAskAfterDay: Long
        get() = prefs.getLong("levelAskAfterDay", 0)
        set(v) = prefs.edit().putLong("levelAskAfterDay", v).apply()

    /** How the child is doing at the current step, from what was said in the conversations. */
    fun levelStanding(): LevelCoach.Standing =
        LevelCoach.standing(level, lessons().filter { it.chat }.map { Talked(it.day, it.level, it.diary) }, nameEn)
    /** a word in the navigation destination that means "this is the kindergarten / academy" */
    var schoolKeyword: String
        get() = prefs.getString("schoolKeyword", "유치원") ?: "유치원"
        set(v) = prefs.edit().putString("schoolKeyword", v.trim()).apply()
    var autoStart: Boolean
        get() = prefs.getBoolean("autoStart", true)
        set(v) = prefs.edit().putBoolean("autoStart", v).apply()
    /** lesson length when no route is being guided */
    /** seconds of quiet after which the turn passes back to 부릉이 */
    var pauseSec: Int
        get() = prefs.getInt("pauseSec", 3).coerceIn(2, 5)
        set(v) = prefs.edit().putInt("pauseSec", v).apply()
    var defaultTripMin: Int
        get() = prefs.getInt("defaultTripMin", 10)
        set(v) = prefs.edit().putInt("defaultTripMin", v).apply()
    var modeChoice: ModeChoice
        get() = runCatching { ModeChoice.valueOf(prefs.getString("mode", "AUTO")!!) }.getOrDefault(ModeChoice.AUTO)
        set(v) = prefs.edit().putString("mode", v.name).apply()
    var voiceChoice: VoiceChoice
        get() = runCatching { VoiceChoice.valueOf(prefs.getString("voice", "AUTO")!!) }.getOrDefault(VoiceChoice.AUTO)
        set(v) = prefs.edit().putString("voice", v.name).apply()
    /** the parent agreed that the app may listen to the child (recognised on device, never recorded) */
    var listenConsent: Boolean
        get() = prefs.getBoolean("listenConsent", false)
        set(v) = prefs.edit().putBoolean("listenConsent", v).apply()
    /** the parent has answered the first-launch consent screen (either way); a parent who agreed earlier has too */
    var onboarded: Boolean
        get() = prefs.getBoolean("onboarded", false) || listenConsent
        set(v) = prefs.edit().putBoolean("onboarded", v).apply()
    /**
     * The AI chat on the way home. Off until the parent turns it on: the text of the child's answer (names
     * hidden) goes to Google Gemini. Audio never leaves the car.
     */
    var aiTalk: Boolean
        get() = prefs.getBoolean("aiTalk", false)
        set(v) = prefs.edit().putBoolean("aiTalk", v).apply()
    /** the recogniser listens in English only (for a child who answers in full English); otherwise Korean, which also catches mixed speech */
    var listenEnglish: Boolean
        get() = prefs.getBoolean("listenEnglish", false)
        set(v) = prefs.edit().putBoolean("listenEnglish", v).apply()
    /** other children riding along, "Jiho(지호), Minjun" */
    var companions: String
        get() = prefs.getString("companions", "") ?: ""
        set(v) = prefs.edit().putString("companions", v).apply()
    var stickers: Int
        get() = prefs.getInt("stickers", 0)
        private set(v) = prefs.edit().putInt("stickers", v).apply()

    fun nextEpisodeIndex(level: Int): Int = prefs.getInt("next_L$level", 0)

    fun advanceEpisode(level: Int, size: Int) {
        if (size <= 0) return
        prefs.edit().putInt("next_L$level", (nextEpisodeIndex(level) + 1) % size).apply()
    }

    /** the episode learned on the way in today, for the way home */
    fun todayEpisode(today: Long): String? =
        if (prefs.getLong("todayEpisodeDay", -1) == today) prefs.getString("todayEpisode", null) else null

    fun setTodayEpisode(today: Long, id: String) =
        prefs.edit().putLong("todayEpisodeDay", today).putString("todayEpisode", id).apply()

    // --- words -------------------------------------------------------------------------------------------------

    fun words(): Map<String, WordState> {
        val o = JSONObject(prefs.getString("words", "{}") ?: "{}")
        return o.keys().asSequence().associateWith { id ->
            val w = o.getJSONObject(id)
            WordState(w.getInt("stage"), w.getLong("due"), w.optInt("seen"), w.optInt("hits"))
        }
    }

    private fun saveWords(states: Map<String, WordState>) {
        val o = JSONObject()
        states.forEach { (id, s) ->
            o.put(id, JSONObject().put("stage", s.stage).put("due", s.due).put("seen", s.seen).put("hits", s.hits))
        }
        prefs.edit().putString("words", o.toString()).apply()
    }

    fun introduce(ids: Collection<String>, today: Long) {
        if (ids.isEmpty()) return
        val states = words().toMutableMap()
        ids.forEach { states[it] = Srs.introduce(today, states[it]) }
        saveWords(states)
    }

    fun reviewed(id: String, today: Long, success: Boolean) {
        val states = words().toMutableMap()
        val state = states[id] ?: Srs.introduce(today, null)
        states[id] = Srs.reviewed(state, today, success)
        saveWords(states)
    }

    fun dueWordIds(today: Long): List<String> = Srs.due(words(), today)

    // --- sentences from conversations: kept with the words, under a "p:" id, on the same review schedule ------------

    /** sentences the child was given to repeat that are due to come back today */
    fun duePhrases(today: Long): List<String> = dueWordIds(today).filter { it.startsWith(PHRASE) }.map { it.removePrefix(PHRASE) }

    fun learnPhrases(sentences: Collection<String>, today: Long) {
        val known = words()
        introduce(sentences.map { PHRASE + it }.filter { it !in known }, today)
    }

    fun phraseHeardAgain(sentence: String, today: Long) = reviewed(PHRASE + sentence, today, success = true)

    // --- lessons -----------------------------------------------------------------------------------------------

    fun record(summary: LessonSummary, today: Long, stickerEarned: Int) {
        val log = JSONArray(prefs.getString("log", "[]") ?: "[]")
        log.put(
            JSONObject()
                .put("day", today)
                .put("episodeId", summary.episodeId)
                .put("title", summary.episodeTitle)
                .put("mode", summary.mode.name)
                .put("seconds", summary.activeSec)
                .put("scenario", summary.scenarioId ?: "")
                .put("cards", JSONArray(summary.cardsShown))
                .put("asks", summary.asks)
                .put("hits", summary.hits)
                .put("heard", JSONArray(summary.heard))
                .put("chat", summary.chat)
                .put("level", level)
                .put("diary", JSONArray().apply {
                    summary.diary.forEach { d ->
                        put(JSONObject().put("child", d.child).put("heard", d.heard).put("en", d.en ?: "")
                            .put("ko", d.ko ?: "").put("worry", d.worry)
                            .put("recast", d.recast ?: "").put("lang", d.lang ?: ""))
                    }
                }),
        )
        // keep the last 40 lessons
        val trimmed = JSONArray()
        for (i in maxOf(0, log.length() - 40) until log.length()) trimmed.put(log.get(i))
        prefs.edit().putString("log", trimmed.toString()).apply()
        stickers += stickerEarned
    }

    fun lessons(): List<LessonRecord> {
        val log = JSONArray(prefs.getString("log", "[]") ?: "[]")
        return (0 until log.length()).map { i ->
            val o = log.getJSONObject(i)
            LessonRecord(
                day = o.getLong("day"),
                episodeId = o.getString("episodeId"),
                title = o.getString("title"),
                mode = runCatching { TripMode.valueOf(o.getString("mode")) }.getOrDefault(TripMode.TO_SCHOOL),
                // older versions counted a sleeping car as talking time: no trip is longer than an hour
                seconds = o.getInt("seconds").coerceIn(0, MAX_RECORD_SEC),
                cards = o.getJSONArray("cards").let { a -> (0 until a.length()).map { a.getString(it) } },
                asks = o.getInt("asks"),
                hits = o.getInt("hits"),
                heard = o.getJSONArray("heard").let { a -> (0 until a.length()).map { a.getString(it) } },
                diary = o.optJSONArray("diary")?.let { a ->
                    (0 until a.length()).map { i ->
                        val d = a.getJSONObject(i)
                        DiaryEntry(
                            child = d.optString("child"),
                            heard = d.optString("heard"),
                            en = d.optString("en").ifBlank { null },
                            ko = d.optString("ko").ifBlank { null },
                            worry = d.optBoolean("worry"),
                            recast = d.optString("recast").ifBlank { null },
                            lang = d.optString("lang").ifBlank { null },
                        )
                    }
                }.orEmpty(),
                chat = o.optBoolean("chat"),
                scenarioId = o.optString("scenario").ifBlank { null },
                level = o.optInt("level"),
            )
        }
    }

    companion object {
        const val PHRASE = "p:"
        private const val MAX_RECORD_SEC = 3600
    }

    /** Clears learning history (for a fresh demo). Profile and settings stay. */
    fun resetLearning() {
        prefs.edit()
            .remove("words").remove("log").remove("stickers")
            .remove("todayEpisode").remove("todayEpisodeDay")
            .apply()
        (1..4).forEach { prefs.edit().remove("next_L$it").apply() }
    }
}
