package app.bureung.english.lesson

/**
 * Fits a lesson to the trip. A short ride drops the song first, then the role play; a longer one adds the story,
 * then a word quiz, then the chant once more. On the way home (하원) the lesson reviews what was learned that
 * morning, then words due from earlier days.
 */
object Planner {
    /** never plan below this: hello, the core, goodbye */
    const val MIN_BUDGET_SEC = 150
    /** a few seconds are always kept free before arrival */
    const val ARRIVAL_MARGIN_SEC = 40
    private const val MAX_REVIEW_WORDS_FROM_SCHOOL = 6
    private const val MAX_REVIEW_WORDS_TO_SCHOOL = 3

    fun plan(
        episode: Episode,
        mode: TripMode,
        tripSec: Int,
        moment: Moment,
        dueWords: List<Word>,
    ): Plan {
        val budget = (tripSec - ARRIVAL_MARGIN_SEC).coerceAtLeast(MIN_BUDGET_SEC)
        @Suppress("NAME_SHADOWING")
        val episode = episode.copy(blocks = episode.blocks.map { personalize(it, moment) })
        val words = LinkedHashMap<String, Word>()
        words[Lines.RAIN.id] = Lines.RAIN
        episode.words.forEach { words[it.id] = it }
        dueWords.forEach { words.putIfAbsent(it.id, it) }
        val chant = episode.block(BlockType.CHANT)
        val story = episode.block(BlockType.STORY)
        return if (mode == TripMode.FROM_SCHOOL && dueWords.isNotEmpty()) {
            val review = dueWords.take(MAX_REVIEW_WORDS_FROM_SCHOOL)
            val blocks = mutableListOf(Lines.hello(moment, mode))
            blocks += Lines.review(review, moment.childName)
            chant?.let { blocks += it.copy(drop = 1) }
            blocks += Lines.reviewWrapup(review, moment.childName)
            val extras = listOfNotNull(story?.copy(drop = 1, extra = true), chant?.let { Lines.chantAgain(it) })
            Plan(episode, mode, fit(blocks, budget, extras), budget, words, review.map { it.id })
        } else {
            // earlier days' words that are due come back for a minute before the new expressions
            val earlier = dueWords.filter { due -> episode.words.none { it.id == due.id } }.take(MAX_REVIEW_WORDS_TO_SCHOOL)
            val blocks = mutableListOf(Lines.hello(moment, TripMode.TO_SCHOOL))
            if (earlier.isNotEmpty()) blocks += Lines.review(earlier, moment.childName).copy(drop = 3)
            episode.blocks.filter { !it.extra }.forEach { blocks += it }
            val extras = listOfNotNull(
                story?.copy(drop = 1, extra = true),
                Lines.quiz(episode.words, moment.childName),
                chant?.let { Lines.chantAgain(it) },
            )
            val fitted = fit(blocks, budget, extras)
            val reviewIds = if (fitted.any { it.type == BlockType.REVIEW }) earlier.map { it.id } else emptyList()
            Plan(episode, TripMode.TO_SCHOOL, fitted, budget, words, reviewIds)
        }
    }

    /**
     * A trip that is a conversation: the intro tune, 부릉이's first question, then open talk until the time set aside
     * is used up, and a goodbye. [tripSec] comes from the navigation (or the length the parent chose).
     */
    fun chat(
        mode: TripMode,
        tripSec: Int,
        moment: Moment,
        review: List<String> = emptyList(),
        listen: Lang = Lang.KO,
        scenario: Scenario? = null,
    ): Plan {
        val name = moment.childName
        // where the navigation says the family is going, or else the child is asked
        val opener = scenario?.opener(name, moment.hour)
            ?: TalkRules.opener(name, moment.hour)
        val intro = Block(
            BlockType.HELLO,
            listOf(Step.Sfx("intro", INTRO_SEC), Step.Say(opener, Lang.EN, buddy = true)),
            label = "시작",
        )
        val talk = Block(BlockType.TALK, listOf(Step.Chat))
        val bye = Block(BlockType.WRAPUP, TalkRules.closing(name).map { Step.Say(it, Lang.EN, buddy = true) } + Step.Sfx("yay"))
        val total = (tripSec - ARRIVAL_MARGIN_SEC).coerceAtLeast(MIN_BUDGET_SEC)
        val talking = (total - Estimate.block(intro) - Estimate.block(bye)).toInt().coerceAtLeast(MIN_CHAT_SEC)
        val chat = ChatPlan(
            children = (listOf(name) + moment.companions).filter { it.isNotBlank() }.distinct(),
            level = moment.level.coerceIn(Levels.MIN, Levels.MAX),
            mode = mode,
            moment = moment,
            budgetSec = talking,
            opener = opener,
            review = review,
            listen = listen,
            scenario = scenario,
        )
        val episode = if (scenario != null) {
            Episode("chat:${scenario.id}", chat.level, scenario.placeKo, scenario.situation, scenario.emoji, emptyList(), emptyList())
        } else {
            Episode("chat", chat.level, "부릉이와 자유 대화", "부릉이와 영어로 이야기해요", "🚗", emptyList(), emptyList())
        }
        return Plan(episode, mode, listOf(intro, talk, bye), total, emptyMap(), emptyList(), chat)
    }

    /** the intro tune: it tells the child English time is starting, and covers fetching 부릉이's first line */
    const val INTRO_SEC = 4.0
    private const val MIN_CHAT_SEC = 45

    /**
     * Drops optional blocks (lowest [Block.drop] first) until the lesson fits, then adds [extras] in order, each
     * only if it still fits, just before the goodbye.
     */
    fun fit(blocks: List<Block>, budgetSec: Int, extras: List<Block> = emptyList()): List<Block> {
        val kept = blocks.toMutableList()
        while (total(kept) > budgetSec) {
            val victim = kept.filter { it.drop > 0 }.minByOrNull { it.drop } ?: break
            kept.remove(victim)
        }
        for (extra in extras) {
            // a dropped core block never comes back as an extra
            if (extra.type == BlockType.CHANT && kept.none { it.type == BlockType.CHANT }) continue
            if (total(kept) + Estimate.block(extra) > budgetSec) continue
            val at = kept.indexOfFirst { it.type == BlockType.WRAPUP }.let { if (it < 0) kept.size else it }
            kept.add(at, extra)
        }
        return kept
    }

    fun total(blocks: List<Block>): Double = blocks.sumOf { Estimate.block(it) }

    /** Fills {name} (the English name), {ko} ("하린") and {call} ("하린아") in an episode's lines. */
    fun personalize(block: Block, moment: Moment): Block = block.copy(
        steps = block.steps.map { s ->
            when (s) {
                is Step.Say -> s.copy(text = fill(s.text, moment))
                is Step.Echo -> s.copy(text = fill(s.text, moment))
                is Step.Ask -> s.copy(answer = fill(s.answer, moment))
                else -> s
            }
        },
    )

    private fun fill(text: String, moment: Moment): String =
        text.replace("{name}", moment.childName)
            .replace("{call}", Lines.koCall(moment.childNameKo))
            .replace("{ko}", moment.childNameKo)
}
