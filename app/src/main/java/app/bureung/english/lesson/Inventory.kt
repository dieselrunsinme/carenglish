package app.bureung.english.lesson

/**
 * Every English sentence the app can say, for pre-recording into assets/voice (the Pleos emulator has no English
 * voice engine). Covers the episodes, every reply to every question, the greetings for any weather, time and trip,
 * reviews, quizzes, the rain surprise and goodbye. Sentences with the child's name also come without it, so a
 * child with another name still hears the recorded voice.
 */
object Inventory {
    fun english(episodes: List<Episode>, nameEn: String, nameKo: String): Set<String> {
        val lines = LinkedHashSet<String>()

        fun add(step: Step) {
            when (step) {
                is Step.Say -> if (step.lang == Lang.EN) lines += step.text
                is Step.Echo -> lines += step.text
                is Step.Ask -> AnswerResult.values().forEach { result ->
                    (0 until REPLY_VARIANTS).forEach { turn -> Lines.reply(step, result, turn).forEach(::add) }
                }
                else -> Unit
            }
        }

        fun add(block: Block) = block.steps.forEach(::add)

        for (mode in TripMode.values()) for (raining in listOf(false, true)) for (temp in listOf(null, 2f, 15f, 30f)) {
            for (hour in listOf(8, 16)) for (month in listOf(1, 4, 7, 10)) {
                add(Lines.hello(Moment(nameEn, nameKo, hour, month, temp, raining), mode))
            }
        }
        val moment = Moment(nameEn, nameKo, 8, 11)
        episodes.forEach { e ->
            e.blocks.forEach { add(Planner.personalize(it, moment)) }
            e.block(BlockType.CHANT)?.let { add(Lines.chantAgain(Planner.personalize(it, moment))) }
            add(Lines.review(e.words, nameEn))
            add(Lines.quiz(e.words, nameEn))
            add(Lines.reviewWrapup(e.words, nameEn))
        }
        add(Lines.review(listOf(Lines.RAIN), nameEn))
        // the conversation's fixed lines, for when 부릉이's cloud voice cannot be made: every opener, the fallbacks,
        // the spare questions and the goodbyes
        lines += TalkRules.SILENT_LINE
        lines += TalkRules.ERROR_LINE
        lines += TalkRules.RESUME_LINE
        lines += TalkRules.QUESTIONS
        for (hour in listOf(8, 14, 20)) lines += TalkRules.opener(nameEn, hour)
        for (s in Scenarios.all) for (hour in listOf(8, 14, 20)) lines += s.opener(nameEn, hour)
        lines += TalkRules.closing(nameEn)
        lines += TalkRules.arrival(nameEn)
        add(Lines.rainSurprise())
        add(Lines.goodbye(nameEn, nameKo))

        val out = LinkedHashSet<String>()
        lines.forEach { line ->
            Sentences.split(line).forEach { s ->
                out += s
                Sentences.withoutName(s, nameEn)?.let { out += it }
            }
        }
        return out
    }

    /** Lines.reply rotates through at most this many praise lines */
    private const val REPLY_VARIANTS = 6
}
