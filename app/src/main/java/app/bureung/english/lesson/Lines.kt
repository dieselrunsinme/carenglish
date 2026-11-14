package app.bureung.english.lesson

/** Lines the app builds itself: the greeting, review games, replies, the rain surprise and goodbye. */
object Lines {
    val RAIN = Word("rain", "rain", "비", "🌧️", "Water falls from the sky. Drip, drop, drip, drop!")

    private val PRAISE = listOf("Great job!", "Wonderful!", "Yes! That's right!", "Super!", "Excellent!", "You did it!")
    private val OPEN_PRAISE = listOf("Wow, thank you for telling me!", "That sounds fun!", "I love that!")

    /** "하린" → "하린아", "지우" → "지우야" */
    fun koCall(name: String): String {
        val last = name.lastOrNull() ?: return name
        if (last !in '가'..'힣') return name
        val hasFinal = (last - '가') % 28 != 0
        return name + if (hasFinal) "아" else "야"
    }

    private fun en(text: String) = Step.Say(text, Lang.EN)
    private fun ko(text: String) = Step.Say(text, Lang.KO)

    fun hello(moment: Moment, mode: TripMode): Block {
        val name = moment.childName
        val steps = mutableListOf<Step>(Step.Sfx("chime"))
        if (mode == TripMode.FROM_SCHOOL) {
            steps += en("Hi, $name! Welcome back!")
            steps += en("How was your day?")
            steps += Step.Ask(
                answer = "I bet it was a great day!",
                accept = listOf("good", "great", "fun", "happy", "okay", "fine", "nice", "좋", "재밌", "재미"),
                waitSec = 5.0,
                open = true,
            )
            if (moment.raining) steps += en("Oh, it's still raining. Drip, drop!")
            return Block(BlockType.HELLO, steps)
        }
        steps += en(if (moment.hour < 12) "Good morning, $name!" else "Hello, $name!")
        val c = moment.outsideC
        when {
            moment.raining -> {
                steps += en("Look! It's raining today.")
                steps += ko("오늘은 비가 와요.")
                steps += en("Can you say, rain?")
                steps += Step.Ask("Rain!", listOf("rain", "raining", "rainy"), 4.0, wordId = RAIN.id)
            }
            c != null && c <= 5f -> {
                steps += en("Brrr! It's cold today.")
                steps += ko("오늘은 추워요. 부르르!")
                steps += en("Can you say, cold?")
                steps += Step.Ask("Cold!", listOf("cold"), 4.0)
            }
            c != null && c >= 27f -> {
                steps += en("Phew! It's hot today.")
                steps += ko("오늘은 더워요.")
                steps += en("Can you say, hot?")
                steps += Step.Ask("Hot!", listOf("hot"), 4.0)
            }
            else -> {
                steps += en("It's a ${season(moment.month)} day!")
                steps += en("Say hello to me!")
                steps += Step.Ask("Hello!", listOf("hello", "hi", "good morning", "morning", "안녕"), 4.0, open = false)
            }
        }
        return Block(BlockType.HELLO, steps)
    }

    fun season(month: Int): String = when (month) {
        3, 4, 5 -> "warm spring"
        6, 7, 8 -> "sunny summer"
        9, 10, 11 -> "cool fall"
        else -> "chilly winter"
    }

    fun review(words: List<Word>, name: String): Block {
        val steps = mutableListOf<Step>(
            en("Let's play a guessing game!"),
            ko("수수께끼 놀이해요. 무엇일까요?"),
        )
        words.forEach { w ->
            steps += en(w.clue)
            steps += en("What is it?")
            steps += Step.Ask(capitalize(w.en) + "!", listOf(w.en), 5.0, wordId = w.id)
            steps += Step.Card(w.id)
        }
        steps += en("You remember so many words, $name!")
        return Block(BlockType.REVIEW, steps)
    }

    /** A riddle on each of today's words, added when the trip has room: retrieval right after learning. */
    fun quiz(words: List<Word>, name: String): Block {
        val steps = mutableListOf<Step>(
            Step.Sfx("chime"),
            en("Quiz time, $name! Listen and guess."),
            ko("잘 듣고 맞혀 봐요!"),
        )
        words.shuffledBy(name.length).forEach { w ->
            steps += en(w.clue)
            steps += en("What is it?")
            steps += Step.Ask(capitalize(w.en) + "!", listOf(w.en), 5.0, wordId = w.id)
            steps += Step.Card(w.id)
        }
        steps += en("Wow! You know them all!")
        return Block(BlockType.QUIZ, steps, drop = 1, extra = true)
    }

    /** The chant once more, a little faster, when the trip has room. */
    fun chantAgain(chant: Block): Block = Block(
        BlockType.CHANT,
        listOf(en("One more time! A little faster!")) + chant.steps.filterNot { it is Step.Say && it.lang == Lang.EN && it.text.startsWith("Let's chant") },
        drop = 1,
        extra = true,
        label = "챈트 한 번 더",
    )

    /** a fixed order that is not the teaching order, so the quiz is not a replay */
    private fun <T> List<T>.shuffledBy(seed: Int): List<T> {
        if (size < 2) return this
        val k = (seed % size).let { if (it == 0) 1 else it }
        return drop(k) + take(k).reversed()
    }

    fun reviewWrapup(words: List<Word>, name: String): Block = Block(
        BlockType.WRAPUP,
        listOf(
            en("Great job today, $name!"),
            en("You remembered so many words!"),
            ko("오늘 배운 말, 다음에 또 만나요!"),
            en("See you next time. Bye-bye!"),
            Step.Sfx("yay"),
        ),
    )

    fun rainSurprise(): Block = Block(
        BlockType.SURPRISE,
        listOf(
            Step.Sfx("chime"),
            en("Oh, look! The wipers are on. Swish, swish!"),
            ko("와이퍼가 움직여요. 비가 오나 봐요!"),
            en("It's raining! Can you say, rain?"),
            Step.Ask("Rain!", listOf("rain", "raining", "rainy"), 4.0, wordId = RAIN.id),
            Step.Card(RAIN.id),
            Step.Echo("Rain, rain, go away!"),
        ),
    )

    fun goodbye(name: String, childNameKo: String): Block = Block(
        BlockType.WRAPUP,
        listOf(
            Step.Sfx("yay"),
            en("We're here! Great job today, $name!"),
            ko("${koCall(childNameKo)}, 도착했어요! 오늘도 정말 잘했어요."),
            en("Bye-bye!"),
        ),
    )

    /** The reply after an Ask. Never "wrong": a near miss or another answer gets a recast of the model line. */
    fun reply(ask: Step.Ask, result: AnswerResult, turn: Int): List<Step> {
        val praise = PRAISE[turn % PRAISE.size]
        if (ask.open) {
            return when (result) {
                AnswerResult.HIT, AnswerResult.RECAST -> listOf(Step.Sfx("ding"), en(OPEN_PRAISE[turn % OPEN_PRAISE.size]))
                AnswerResult.SILENT, AnswerResult.NOT_HEARD -> listOf(en(ask.answer))
            }
        }
        val model = ask.answer
        return when (result) {
            AnswerResult.HIT -> listOf(Step.Sfx("ding"), en("${trimEnd(model)}! $praise"))
            AnswerResult.RECAST -> listOf(en("Ooh, ${decapitalize(trimEnd(model))}! Let's say it together."), Step.Echo(model))
            AnswerResult.SILENT -> listOf(en(model), en("Your turn!"), Step.Pause(Estimate.echoGap(model)))
            AnswerResult.NOT_HEARD -> listOf(en("${trimEnd(model)}! $praise"))
        }
    }

    fun joinWords(words: List<String>): String = when (words.size) {
        0 -> "lots of words"
        1 -> words[0]
        2 -> "${words[0]} and ${words[1]}"
        else -> words.dropLast(1).joinToString(", ") + ", and " + words.last()
    }

    fun capitalize(text: String): String = text.replaceFirstChar { it.uppercase() }

    private fun trimEnd(text: String): String = text.trimEnd('!', '.', '?', ' ')

    /** "It's a monkey" → "it's a monkey", but "I like milk" keeps its capital I */
    fun decapitalize(text: String): String =
        if (text == "I" || text.startsWith("I ") || text.startsWith("I'")) text else text.replaceFirstChar { it.lowercase() }
}
