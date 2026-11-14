package app.bureung.english.lesson

/**
 * The five steps of the conversation, for children of about 5 to 10. The age is only a guide: the parent picks by
 * how much English the child can say. What changes with the step is how long 부릉이's sentences are, what kind of
 * question it asks, and how long the sentence is that the child is given to repeat. The AI gets the same rules in
 * English (GeminiTalker's instructions).
 */
data class Level(
    val n: Int,
    val nameKo: String,
    val agesKo: String,
    /** how the child answers at this step */
    val childKo: String,
    /** 부릉이's sentences */
    val buddyKo: String,
    val questionKo: String,
    /** the sentence the child repeats */
    val repeatKo: String,
    val example: String,
    /** the longest sentence 부릉이 says at this step, in words (the AI is told the same number) */
    val maxWords: Int,
    /** the longest sentence the child is given to repeat, in words */
    val maxRepeat: Int,
)

object Levels {
    const val MIN = 1
    const val MAX = 5

    val all: List<Level> = listOf(
        Level(1, "첫 단어", "5~6세", "영어가 처음이에요. 단어 하나나 Yes/No로 대답해요.",
            "5단어 이하, 현재형만", "예/아니오, 둘 중 하나 고르기", "2~3단어",
            "\"Do you like apples or bananas?\" → \"Apples!\" → \"I like apples.\"", 5, 3),
        Level(2, "짧은 말", "6~7세", "아는 단어가 조금 있어요. 두세 단어로 대답해요.",
            "6단어 이하, 현재형", "What, Which (한두 단어로 답할 수 있는 질문)", "3~5단어의 짧은 문장",
            "\"What color is your bag?\" → \"노란색\" → \"It's yellow.\"", 6, 5),
        Level(3, "문장", "7~8세", "짧은 문장 하나를 말해 봐요.",
            "8단어 이하, 현재·현재진행", "What, Who, Where", "짧지만 완전한 문장",
            "\"Where do you play?\" → \"놀이터에서 놀아\" → \"I play at the playground.\"", 8, 8),
        Level(4, "이야기", "8~9세", "있었던 일과 할 일을 문장으로 말해요.",
            "10단어 이하, 과거·미래 포함", "Why, How, 있었던 일 묻기", "자연스러운 한 문장",
            "\"What did you eat for lunch?\" → \"김밥 먹었어\" → \"I ate gimbap for lunch.\"", 10, 10),
        Level(5, "대화", "9~10세", "이유와 생각을 붙여 길게 말해요.",
            "12단어 이하, 시제 제한 없음", "이유·생각 묻기, 대답에 이어 한 번 더 묻기", "because·and·but으로 이은 문장",
            "\"Why do you like it?\" → \"재밌으니까\" → \"I like it because it's fun.\"", 12, 12),
    )

    fun of(n: Int): Level = all[n.coerceIn(MIN, MAX) - 1]

    fun name(n: Int): String = of(n).nameKo
}

/** One conversation's answers, for [LevelCoach]. */
data class Talked(val day: Long, val level: Int, val answers: List<DiaryEntry>)

/**
 * Suggests the next step from what the child has actually said. It looks at the child's last [WINDOW] answers at
 * the current step: when [NEED] of them were English only (no Korean mixed in), over at least [MIN_DAYS] different
 * days, the parent is asked whether to move up. It only suggests: the parent decides.
 */
object LevelCoach {
    const val WINDOW = 20
    const val NEED = 12
    const val MIN_DAYS = 3

    data class Standing(
        val level: Int,
        /** answers looked at (at most [WINDOW]) */
        val answers: Int,
        /** of those, answered in English only */
        val english: Int,
        /** different days those answers were on */
        val days: Int,
    ) {
        val ready: Boolean
            get() = level < Levels.MAX && answers >= WINDOW && english >= NEED && days >= MIN_DAYS
    }

    /** [child]: only this child's answers count (brothers and sisters take turns in the same conversation). */
    fun standing(level: Int, talks: List<Talked>, child: String): Standing {
        val recent = talks.filter { it.level == level }
            .flatMap { t -> t.answers.filter { it.child == child && it.heard.isNotBlank() }.map { t.day to it } }
            .takeLast(WINDOW)
        return Standing(level, recent.size, recent.count { it.second.lang == "en" }, recent.map { it.first }.distinct().size)
    }
}
