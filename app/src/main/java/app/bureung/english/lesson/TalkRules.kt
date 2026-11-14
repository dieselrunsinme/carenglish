package app.bureung.english.lesson

/**
 * The guard rails of the AI chat. Whatever the model answers, a child only hears short, simple, kind English:
 * anything too long, not English, or on the block list is dropped and a recorded line is used instead.
 */
object TalkRules {
    /** how long 부릉이 waits for a child to answer */
    const val LISTEN_SEC = 8.0
    /** the "your turn" cue plays this long before the microphone opens */
    const val CUE_MS = 520L
    /** from a kilometre out: this many more easy exchanges, then the last turn and the goodbye */
    const val WIND_DOWN_TURNS = 2
    /** with less than this left after an answer, 부릉이 reacts and says goodbye instead of asking again */
    const val LAST_TURN_SEC = 40
    /** with less than this left, there is no time to listen at all */
    const val MIN_TURN_SEC = 12
    /** this many silences in a row end the conversation kindly */
    const val MAX_SILENCES = 3
    /** this many failed answers in a row (no network) end it */
    const val MAX_ERRORS = 2
    /** with several children, the question moves on after this many answers */
    const val SWITCH_EVERY = 2
    /** lines of the conversation sent along for context */
    const val HISTORY_LINES = 8
    private const val MAX_SAY_WORDS = 16
    private const val MAX_REPEAT_WORDS = 14
    private const val MAX_ASK_WORDS = 14
    private const val MAX_CHARS = 120

    /** recorded fallbacks, so there is never dead air */
    const val SILENT_LINE = "That's okay! You can tell me next time."
    const val ERROR_LINE = "Wow, that sounds fun!"
    /** after a pause (a phone call), before listening again */
    const val RESUME_LINE = "I'm back! Tell me more."

    /** easy questions for when the AI gives none (or cannot be reached) */
    val QUESTIONS = listOf(
        "What's your favorite color?",
        "What do you like to eat?",
        "Do you like animals?",
        "What's your favorite toy?",
        "Do you like to sing?",
    )

    /** tiny words 부릉이 says while the answer is on its way */
    val FILLERS = listOf("Hmm!", "Oh!", "I see!", "Okay!")

    private val BLOCKED = listOf(
        "kill", "die", "dead", "blood", "gun", "knife", "hate", "stupid", "shut up", "sexy", "drunk", "beer", "wine",
    )

    /**
     * 부릉이 speaks first: a greeting and one question from what the car knows (rain from the wipers, the outside
     * temperature, the way home). Built in the app, so it is ready the moment the intro tune ends.
     */
    fun greeting(name: String, hour: Int): String = if (hour < 12) "Good morning, $name!" else "Hi, $name!"

    fun opener(name: String, mode: TripMode, hour: Int, scene: Scene): String {
        val hi = greeting(name, hour)
        val c = scene.outsideC
        val question = when {
            scene.raining -> "It's raining today. Do you like rain?"
            c != null && c <= 5f -> "Brrr! It's cold today. Are you wearing a warm jacket?"
            c != null && c >= 28f -> "Phew! It's hot today. Do you like ice cream?"
            mode == TripMode.FROM_SCHOOL -> "What did you do today?"
            else -> "How are you today?"
        }
        return "$hi $question"
    }

    fun closing(name: String): List<String> = listOf("Great talking today, $name!", "See you next time. Bye-bye!")

    fun arrival(name: String): List<String> = listOf("We're here! Great job today, $name!", "Bye-bye!")

    /**
     * The reply with names filled in and every line checked, or null when nothing is safe to say. [next] is the
     * child the next question goes to.
     */
    fun clean(reply: TalkReply, child: String, next: String = child): TalkReply? {
        val say = reply.say?.let { fill(it, child, next) }?.takeIf { ok(it, MAX_SAY_WORDS) }
        val repeat = reply.repeat?.let { fill(it, child, next) }?.takeIf { ok(it, MAX_REPEAT_WORDS) }
        val ask = reply.ask?.let { fill(it, child, next) }?.takeIf { ok(it, MAX_ASK_WORDS) }
        if (say == null && repeat == null && ask == null) return null
        return reply.copy(
            say = say,
            repeat = repeat,
            ask = ask,
            diaryEn = reply.diaryEn?.let { fill(it, child, next) }?.takeIf { it.isNotBlank() },
            diaryKo = reply.diaryKo?.let { fill(it, child, next) }?.takeIf { it.isNotBlank() },
            lang = reply.lang?.lowercase()?.takeIf { it in setOf("en", "mixed", "ko") },
        )
    }

    /** how many words a sentence has (the unit the steps are written in) */
    fun words(line: String?): Int = line?.split(' ')?.count { it.any(Char::isLetterOrDigit) } ?: 0

    /** the word count of the longest sentence in what was said (the steps limit each sentence, not the whole turn) */
    fun longest(line: String?): Int = line?.let { Sentences.split(it).maxOfOrNull(::words) } ?: 0

    fun ok(line: String, maxWords: Int): Boolean {
        val words = line.split(' ').count { it.isNotBlank() }
        val lower = line.lowercase()
        return line.isNotBlank() &&
            words <= maxWords &&
            line.length <= MAX_CHARS &&
            line.none { it in '가'..'힣' || it in 'ㄱ'..'ㅣ' } &&
            '{' !in line &&
            line.any { it.isLetter() } &&
            BLOCKED.none { b -> Regex("\\b${Regex.escape(b)}\\b").containsMatchIn(lower) }
    }

    /** at most this many other children take a turn */
    const val MAX_COMPANIONS = 3

    /**
     * "Jiho(지호), Minjun" → [Jiho to 지호, Minjun to null]. The Korean spelling is optional; it lets the app hide
     * that name too before the child's words go out.
     */
    fun companions(raw: String): List<Pair<String, String?>> =
        raw.split(',', '/', '\n', '、')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { entry ->
                val m = Regex("^([^()（）]+?)\\s*[(（]\\s*([^()（）]*?)\\s*[)）]$").find(entry)
                val en = (m?.groupValues?.get(1) ?: entry).trim()
                val ko = m?.groupValues?.get(2)?.trim()?.ifEmpty { null }
                en.takeIf { it.any(Char::isLetter) }?.let { it to ko }
            }
            .distinctBy { it.first.lowercase() }
            .take(MAX_COMPANIONS)

    /**
     * Hides the children's names in what was heard before it leaves the car: the child speaking becomes {name},
     * other children "친구". [names] maps each child's English name to all its spellings.
     */
    fun mask(heard: String, child: String, names: Map<String, List<String>>): String {
        var out = heard
        names.entries
            .flatMap { (en, spellings) -> (spellings + en).map { it.trim() to (if (en == child) "{name}" else "친구") } }
            .filter { it.first.length >= 2 }
            .sortedByDescending { it.first.length }
            .forEach { (spelling, cover) -> out = out.replace(spelling, cover, ignoreCase = true) }
        return out
    }

    /** the model never sees a child's name: it writes {name} (who spoke) and {next} (who is asked next) */
    private fun fill(text: String, child: String, next: String): String =
        text.replace("{name}", child).replace("{next}", next).trim()
}
