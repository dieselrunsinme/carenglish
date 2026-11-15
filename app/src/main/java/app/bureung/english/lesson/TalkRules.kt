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

    /** after a child's answer, when the AI gives no question: ask for more of the same thing */
    const val TELL_MORE = "Tell me more about it!"

    /** spare questions for the morning and the afternoon: the day is still ahead */
    val AHEAD = listOf(
        "What are you going to do today?",
        "Who will you play with today?",
        "What do you want to play today?",
    )

    /** spare questions for the afternoon: half of the day is done, half is ahead (home, or a class next) */
    val MIDDAY = listOf(
        "What did you do today?",
        "What are you going to do next?",
        "Who did you play with today?",
    )

    /** spare questions for the evening: the day is done */
    val BACK = listOf(
        "What did you do today?",
        "What was the most fun today?",
        "Who did you play with today?",
        "What did you eat for lunch?",
    )

    /** the parts of the day, split the same way as the greeting */
    enum class DayPart(val en: String) { MORNING("morning"), AFTERNOON("afternoon"), EVENING("evening") }

    fun dayPart(hour: Int): DayPart = when (hour) {
        in 5..11 -> DayPart.MORNING
        in 12..17 -> DayPart.AFTERNOON
        else -> DayPart.EVENING
    }

    /** in the evening the talk is about what the child did today; in the morning, about what the child will do */
    fun looksBack(hour: Int): Boolean = dayPart(hour) == DayPart.EVENING

    /** what the talk is about at this hour, as the AI is told it */
    fun talkAbout(hour: Int): String = when (dayPart(hour)) {
        DayPart.MORNING -> "what the child is going to do today (the day is still ahead)"
        DayPart.AFTERNOON -> "the day is half done: going home, what the child did today; going somewhere (a class, a fun place), what the child is going to do there"
        DayPart.EVENING -> "what the child did today (the day is done)"
    }

    /**
     * The [n]th spare question when the AI gives none (or cannot be reached). After an answer ([followUp]) every
     * other one asks for more of what the child just said, so the talk stays on the child's topic; the rest are
     * about the child's day, ahead or back by the clock. Never an unrelated quiz question.
     */
    fun spare(hour: Int, n: Int, followUp: Boolean): String {
        val day = when (dayPart(hour)) {
            DayPart.MORNING -> AHEAD
            DayPart.AFTERNOON -> MIDDAY
            DayPart.EVENING -> BACK
        }
        return if (followUp) (if (n % 2 == 0) TELL_MORE else day[(n / 2) % day.size]) else day[n % day.size]
    }

    /** tiny words 부릉이 says while the answer is on its way */
    val FILLERS = listOf("Hmm!", "Oh!", "I see!", "Okay!")

    private val BLOCKED = listOf(
        "kill", "die", "dead", "blood", "gun", "knife", "hate", "stupid", "shut up", "sexy", "drunk", "beer", "wine",
    )

    /** 부릉이's hello, by the clock. Built in the app, so it is ready the moment the intro tune ends. */
    fun greeting(name: String, hour: Int): String = when (hour) {
        in 5..11 -> "Good morning, $name!"
        in 12..17 -> "Good afternoon, $name!"
        else -> "Good evening, $name!"
    }

    /** the question that opens every talk when the navigation does not tell where the family is going */
    const val WHERE = "Where are you going today?"

    /**
     * The first words are always the same: a greeting by the clock and "Where are you going today?". The car
     * cannot know whether this is the way to kindergarten or home (nobody sets the navigation on a daily route), so
     * the child tells it, and the talk starts from there. When the navigation does tell, [Scenario.opener] says so.
     */
    fun opener(name: String, hour: Int): String = "${greeting(name, hour)} $WHERE"

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

    /** whether a question from the AI passes the rules (for the log: a dropped one is replaced by a spare) */
    fun askOk(line: String): Boolean = ok(line.replace("{name}", "Name").replace("{next}", "Name"), MAX_ASK_WORDS)

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
