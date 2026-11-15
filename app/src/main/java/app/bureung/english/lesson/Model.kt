package app.bureung.english.lesson

/** Language of one spoken line. English is the lesson; Korean is only scaffolding. */
enum class Lang { EN, KO }

/** 등원 = new expressions on the way in, 하원 = same-day review on the way home. */
enum class TripMode { TO_SCHOOL, FROM_SCHOOL }

data class Word(
    val id: String,
    val en: String,
    val ko: String,
    val emoji: String,
    /** a spoken English riddle used in review: "It's big and gray. It has a long nose." */
    val clue: String,
)

sealed interface Step {
    /** One spoken line. [buddy] lines are 부릉이's (the AI chat), spoken by the cloud voice. */
    data class Say(val text: String, val lang: Lang, val buddy: Boolean = false) : Step

    /** Say a line, then leave room for the child to repeat it (chants, recasts). Nothing is recognised. */
    data class Echo(val text: String, val buddy: Boolean = false) : Step

    /**
     * The open conversation with 부릉이: listen to a child (Korean, English or a mix), let the [Talker] react, recast
     * and ask again, until the trip's time is up. The details are in the plan's [ChatPlan]; the step repeats itself.
     */
    object Chat : Step

    /**
     * Ask the child to say something. [answer] is the model line; [accept] are the words that count as a hit.
     * An [open] question (how was school?) counts any answer as a hit and never models a "right" answer.
     */
    data class Ask(
        val answer: String,
        val accept: List<String>,
        val waitSec: Double = 5.0,
        val wordId: String? = null,
        val open: Boolean = false,
    ) : Step

    data class Pause(val sec: Double) : Step
    data class Sfx(val name: String, val sec: Double = 0.6) : Step

    /** Shows a word card on the parked screen and marks the word as introduced. */
    data class Card(val wordId: String) : Step
}

enum class BlockType(val ko: String) {
    HELLO("인사"),
    GREETING("오늘의 주제"),
    REVIEW("복습"),
    EXPRESSION("오늘의 표현"),
    CHANT("챈트"),
    ROLEPLAY("역할극"),
    STORY("이야기"),
    QUIZ("단어 퀴즈"),
    TALK("대화"),
    SURPRISE("깜짝 단어"),
    WRAPUP("마무리"),
}

/**
 * A block of a lesson. [drop] > 0 means the block may be left out when the trip is short; the lowest number goes
 * first. [extra] blocks are only added when the trip is long enough.
 */
data class Block(
    val type: BlockType,
    val steps: List<Step>,
    val drop: Int = 0,
    val extra: Boolean = false,
    /** shown instead of the type's name ("챈트 한 번 더") */
    val label: String? = null,
) {
    val title: String get() = label ?: type.ko
}

data class Episode(
    val id: String,
    val level: Int,
    val title: String,
    val titleKo: String,
    val emoji: String,
    val words: List<Word>,
    val blocks: List<Block>,
) {
    fun block(type: BlockType): Block? = blocks.firstOrNull { it.type == type }
}

/** What the lesson knows about this moment, for the greeting. */
data class Moment(
    /** the name the English lines use ("Harin") */
    val childName: String,
    /** the name the Korean lines use ("하린") */
    val childNameKo: String,
    val hour: Int,
    val month: Int,
    val outsideC: Float? = null,
    val raining: Boolean = false,
    /** other children riding along (English names); the conversation takes turns by name */
    val companions: List<String> = emptyList(),
    /** 1 words, 2 short sentences, 3 conversation */
    val level: Int = 2,
)

/** The open conversation of one trip: who is in the car, how hard, how long, and how it starts. */
data class ChatPlan(
    val children: List<String>,
    val level: Int,
    val mode: TripMode,
    val moment: Moment,
    /** seconds of conversation, so it ends before the car arrives */
    val budgetSec: Int,
    /** 부릉이's first words: a greeting and a question, known before any network call */
    val opener: String,
    /** sentences from earlier conversations that are due to come back (spaced repetition) */
    val review: List<String> = emptyList(),
    /** the language the recogniser listens in: Korean also catches mixed speech, English suits fluent children */
    val listen: Lang = Lang.KO,
    /** where the navigation says the family is going, or null (the child is asked) */
    val scenario: Scenario? = null,
)

data class Plan(
    val episode: Episode,
    val mode: TripMode,
    val blocks: List<Block>,
    val budgetSec: Int,
    /** every word a Card or Ask step may refer to (episode words plus review words from earlier episodes) */
    val words: Map<String, Word>,
    /** ids of the words this plan reviews (하원 and earlier-day reviews) */
    val reviewIds: List<String>,
    /** set when this trip is a conversation with 부릉이 rather than a recorded lesson */
    val chat: ChatPlan? = null,
) {
    val estimateSec: Int get() = chat?.let { Estimate.chat(this) } ?: blocks.sumOf { Estimate.block(it) }.toInt()
}

enum class AnswerResult { HIT, RECAST, SILENT, NOT_HEARD }

/** What the car can tell about right now; the conversation uses it as something to talk about. */
data class Scene(val outsideC: Float? = null, val raining: Boolean = false)

/** One line of the conversation so far. */
data class Said(val byChild: Boolean, val text: String)

/** Everything the [Talker] gets for one answer. No names, no location; the voice only when the AI listens itself. */
data class TalkTurn(
    /** who just spoke (filled in for {name} by the app) */
    val child: String,
    val level: Int,
    val mode: TripMode,
    val hour: Int,
    val month: Int,
    val scene: Scene,
    /** things that just happened in the car ("It just started raining") */
    val events: List<String>,
    val secondsLeft: Int,
    /** time is nearly up: react warmly and say goodbye, no new question */
    val last: Boolean,
    /** the next question goes to another child ({next}) */
    val switch: Boolean,
    /** the child said nothing this time */
    val silent: Boolean,
    val review: List<String>,
    val history: List<Said>,
    val heard: String,
    /** where the family is going, when the navigation tells */
    val situation: String? = null,
    /** sentences to bring in for the child to say, one at a time */
    val targets: List<String> = emptyList(),
    /** the child's answer as recorded sound (WAV), when the AI listens itself; then [heard] is empty */
    val audio: ByteArray? = null,
    /** less than a kilometre to go: the talk winds down (not the last turn yet) */
    val nearing: Boolean = false,
)

/** What 부릉이 answers. Checked by [TalkRules] before anything is said to a child. */
data class TalkReply(
    /** a short reaction to what the child said, without a question */
    val say: String?,
    /** the child's meaning as one correct, simple English sentence, to repeat (null when it was already right) */
    val repeat: String?,
    /** the next question (null on the last turn) */
    val ask: String?,
    /** for the parent's report: what the child told, in English and Korean */
    val diaryEn: String?,
    val diaryKo: String?,
    /** how the child answered: "en", "mixed" or "ko" */
    val lang: String?,
    /** the child mentioned being hurt, sick, scared or sad: the parent should look */
    val worry: Boolean,
    /** the child wants to stop: end the conversation */
    val done: Boolean,
    /** what the child said, written down by the AI from the recording (Korean in Hangul, English in English) */
    val heard: String? = null,
)

/** One exchange in the parent report: what the child said, the correct sentence, and a diary line. */
data class DiaryEntry(
    val child: String,
    val heard: String,
    val en: String?,
    val ko: String?,
    val worry: Boolean,
    /** the sentence the child was given to repeat */
    val recast: String? = null,
    /** "en", "mixed" or "ko" */
    val lang: String? = null,
)

/** The AI that understands the child and answers as 부릉이 (Gemini on Android, a fake in tests). */
interface Talker {
    val available: Boolean
    fun reply(turn: TalkTurn, result: (TalkReply?) -> Unit)
    fun cancel()
}
