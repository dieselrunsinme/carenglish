package app.bureung.english.ai

import android.os.Handler
import app.bureung.english.lesson.Said
import app.bureung.english.lesson.TalkReply
import app.bureung.english.lesson.TalkRules
import app.bureung.english.lesson.TalkTurn
import app.bureung.english.lesson.Talker
import app.bureung.english.lesson.TripMode
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject

/**
 * 부릉이's brain: Gemini hears what the child said (the recorded answer, or speech recognition text when the microphone cannot be opened)
 * and what the car knows (trip, time left, weather, the wipers), and answers with a short reaction, the child's
 * meaning as a correct sentence to repeat, and the next question. Besides that one answer, only text goes out: no names (the
 * model writes {name}), no destination.
 */
class GeminiTalker(
    private val gemini: Gemini,
    private val main: Handler,
    private val enabled: () -> Boolean,
    /** every child's English name → its spellings, so names are hidden before anything is sent */
    private val names: () -> Map<String, List<String>>,
    private val log: (String) -> Unit,
    /** a model name from local.properties, when the default has moved on */
    preferredModel: String = "",
) : Talker {
    private val pool = Executors.newSingleThreadExecutor()
    @Volatile private var session = 0
    private var model = preferredModel.ifBlank { MODEL }
    private var useSchema = true
    private var useThinking = true
    var lastError: String? = null
        private set
    var lastMs: Long = 0
        private set
    /** when the AI last answered (wall clock), so a working network is not mistaken for none */
    @Volatile var lastOkAt: Long = 0
        private set
    val modelName: String get() = model

    /** Another model from now on (the faster one, or back): its optional settings are tried afresh. */
    fun useModel(name: String) {
        if (name.isBlank() || name == model) return
        model = name
        useSchema = true
        useThinking = true
    }

    override val available: Boolean get() = gemini.hasKey && enabled()

    override fun reply(turn: TalkTurn, result: (TalkReply?) -> Unit) {
        val my = ++session
        val known = names()
        val masked = turn.copy(
            heard = TalkRules.mask(turn.heard, turn.child, known),
            history = turn.history.map { if (it.byChild) it.copy(text = TalkRules.mask(it.text, turn.child, known)) else maskBuddy(it, known) },
        )
        pool.execute {
            val startedAt = System.currentTimeMillis()
            val reply = runCatching { ask(masked) }
                .onFailure {
                    lastError = it.message
                    main.post { log("AI 대화 실패: ${it.message}") }
                }
                .getOrNull()
            if (reply != null) {
                lastError = null
                lastMs = System.currentTimeMillis() - startedAt
                lastOkAt = System.currentTimeMillis()
                val took = lastMs
                val used = model
                reply.ask?.takeIf { !TalkRules.askOk(it) }?.let { dropped ->
                    main.post { log("AI 질문이 규칙(길이·한글·금지어)에 걸려 예비 질문으로 바꿈: $dropped") }
                }
                main.post { log("⏱ AI 대답 ${"%.1f".format(took / 1000.0)}초 ($used${if (turn.audio != null) ", 목소리 ${turn.audio.size / 32000}초 들음" else ""})") }
            }
            main.post { if (my == session) result(reply) }
        }
    }

    /** 부릉이's own earlier lines had the names filled in for speaking: take them out again */
    private fun maskBuddy(said: Said, known: Map<String, List<String>>): Said {
        var text = said.text
        known.keys.filter { it.length >= 2 }.forEach { text = text.replace(it, "{name}") }
        return said.copy(text = text)
    }

    override fun cancel() {
        session += 1
    }

    private fun ask(turn: TalkTurn): TalkReply {
        var tries = 0
        while (true) {
            try {
                val response = gemini.generate(model, body(turn), readTimeoutMs = READ_TIMEOUT_MS)
                val text = Gemini.parts(response).firstNotNullOfOrNull { it.optString("text").ifBlank { null } }
                    ?: error("빈 응답")
                return parse(text)
            } catch (e: GeminiError) {
                tries += 1
                val aboutThinking = e.message.orEmpty().contains("think", ignoreCase = true)
                when {
                    tries > 3 -> throw e
                    // an API version that does not take one of the optional settings: drop it and ask again
                    e.code == 400 && useThinking && (aboutThinking || !useSchema) -> useThinking = false
                    e.code == 400 && useSchema -> useSchema = false
                    // a model name that moved on: the rolling alias
                    e.code == 404 && model != FALLBACK_MODEL -> model = FALLBACK_MODEL
                    // busy for a moment: once more
                    e.code in setOf(500, 503) && tries == 1 -> Thread.sleep(300)
                    else -> throw e
                }
            }
        }
    }

    private fun body(turn: TalkTurn): JSONObject = JSONObject()
        .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM))))
        .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray()
            .put(JSONObject().put("text", prompt(turn)))
            .apply {
                turn.audio?.let { wav ->
                    put(JSONObject().put("inlineData", JSONObject()
                        .put("mimeType", "audio/wav")
                        .put("data", java.util.Base64.getEncoder().encodeToString(wav))))
                }
            })))
        .put("generationConfig", JSONObject()
            .put("temperature", 0.8)
            .put("maxOutputTokens", 700)
            .put("responseMimeType", "application/json")
            .apply {
                if (useSchema) put("responseSchema", SCHEMA)
                // a child is waiting: answer as fast as the model can
                if (useThinking) put("thinkingConfig", JSONObject().put("thinkingLevel", "minimal"))
            })

    companion object {
        const val MODEL = "gemini-3.8-flash"
        const val FALLBACK_MODEL = "gemini-flash-latest"
        private const val READ_TIMEOUT_MS = 15_000

        /** What the model is told about this turn. Built from the car's signals; no names, no places. */
        fun prompt(turn: TalkTurn): String = buildString {
            append("Context:\n")
            append("- Level: ${turn.level}\n")
            val trip = turn.situation
                ?: "a car ride with the family (where to is not known: you asked, so take the child's answer as the trip)"
            append("- Trip: $trip (${TalkRules.dayPart(turn.hour).en}, ${MONTHS[(turn.month - 1).mod(12)]})\n")
            append("- Talk about: ${TalkRules.talkAbout(turn.hour)}\n")
            if (turn.targets.isNotEmpty()) append("- Sentences to practise on this trip: ${turn.targets.joinToString(" / ") { "\"$it\"" }}\n")
            val weather = listOfNotNull(
                turn.scene.outsideC?.let { "${it.toInt()}°C outside, ${feel(it)}" },
                if (turn.scene.raining) "it is raining (the wipers are on)" else null,
            )
            if (weather.isNotEmpty()) append("- Weather: ${weather.joinToString("; ")}\n")
            turn.events.forEach { append("- NEW: $it\n") }
            if (turn.last) {
                append("- Time left: LAST TURN\n")
            } else {
                append("- Time left: about ${(turn.secondsLeft + 30) / 60} min\n")
                if (turn.nearing) append("- Almost there: less than 1 km to go\n")
            }
            if (turn.switch) append("- The next question is for another child: {next}\n")
            if (turn.review.isNotEmpty()) append("- Earlier sentences: ${turn.review.joinToString(" / ") { "\"$it\"" }}\n")
            append("Conversation so far:\n")
            turn.history.forEach { append(if (it.byChild) "Child: " else "Bung-bung: ").append(it.text).append('\n') }
            if (turn.audio != null) {
                append("The child's answer is the attached audio. Listen to it; the child may mix Korean and English in one sentence.")
            } else if (turn.silent) {
                append("The child said nothing (silence).")
            } else {
                append("The child just said: \"${turn.heard}\"")
            }
        }

        private val MONTHS = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")

        private fun feel(c: Float) = when {
            c <= 5f -> "cold"
            c <= 12f -> "chilly"
            c >= 28f -> "hot"
            c >= 22f -> "warm"
            else -> "mild"
        }

        fun parse(text: String): TalkReply {
            val json = JSONObject(text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim())
            fun string(key: String): String? =
                if (json.isNull(key)) null else json.optString(key).trim().takeIf { it.isNotEmpty() && it != "null" }
            val say = json.optJSONArray("say")?.let { a -> (0 until a.length()).joinToString(" ") { a.optString(it) }.ifBlank { null } }
                ?: string("say")
            return TalkReply(
                say = say,
                repeat = string("repeat"),
                ask = string("ask"),
                diaryEn = string("diary_en"),
                diaryKo = string("diary_ko"),
                lang = string("lang"),
                worry = json.optBoolean("worry", false),
                done = json.optBoolean("done", false),
                heard = string("heard"),
            )
        }

        // built on first use, so the prompt text can be read without the JSON library (tests)
        private val SCHEMA: JSONObject by lazy { JSONObject("""
            {"type":"OBJECT","properties":{
              "heard":{"type":"STRING"},
              "say":{"type":"STRING","nullable":true},
              "repeat":{"type":"STRING","nullable":true},
              "ask":{"type":"STRING","nullable":true},
              "diary_en":{"type":"STRING"},
              "diary_ko":{"type":"STRING"},
              "lang":{"type":"STRING","enum":["en","mixed","ko"]},
              "worry":{"type":"BOOLEAN"},
              "done":{"type":"BOOLEAN"}},
             "required":["heard","say","repeat","ask","diary_en","diary_ko","lang","worry","done"]}
        """.trimIndent()) }

        val SYSTEM = """
            You are Bung-bung (부릉이), a cheerful little yellow car who loves to talk. A Korean child aged 5 to 9 is riding in you, and you are having an English conversation. The goal: the child enjoys talking, and picks up English by hearing their own meaning said correctly. You never teach or test. You chat.

            What the child says reaches you as audio, or as speech-recognition text. The child may answer in Korean, English, or a mix in one sentence. In speech-recognition text, English may be spelled in Hangul ("블록스" is "blocks", "아이 라이크 애플" is "I like apple"). Guess kindly what they meant.

            The child's turn ends after a few seconds of quiet, so a child who was still thinking may be cut off mid-sentence ("Yesterday I went...", "어제 나는..."). When the sentence is unfinished, do not guess the rest and do not give "repeat": say something short and warm, and make "ask" a small follow-up that helps the child finish ("You went where?", "Yesterday you... what did you do?").

            Follow the child. This matters most: every answer is about what the child JUST said.
            - In "say", pick up one concrete thing the child told you (the soccer, the teacher, the cake), not a general "That's nice!".
            - In "ask", go deeper into that same thing: who with, where, what happened, how it felt, what was best, what comes next ("Did you score a goal?", "Is your teacher fast?"). Stay on the child's topic for at least three exchanges while the child keeps answering.
            - Change the topic only when the child has little more to say (silence, "I don't know", or the same short answer twice). Then move on to another part of the child's day ("Talk about" in Context). Never jump to an unrelated quiz question (favorite color, favorite animal, favorite food) while the child is telling you something.
            - If the child tells you two things, ask about the one that sounds more exciting.
            Bad: Child: "I played soccer with my teacher today." You: "Nice! What's your favorite color?"
            Good: Child: "I played soccer with my teacher today." You: "Soccer with your teacher, cool! Did you score a goal?"

            Time of day ("Talk about" in Context): in the morning the day is still ahead, so talk about what the child is going to do today: at kindergarten, after school, at the place you are driving to ("What are you going to play today?", "Who will you see?"). In the evening the day is done, so talk about what the child did today ("What did you do today?", "What was the most fun today?", "What did you eat for lunch?"). In the afternoon it depends on where the child is going: going home, ask what the child did today; going to a class or somewhere fun, ask what the child is going to do there. If the child brings up something else (in the morning, "I played soccer yesterday"), follow the child.

            Answer with JSON only:
            - "heard": what the child said, word for word. When the answer comes as audio, write it down exactly as spoken: Korean in Hangul, English in English letters, mixed speech mixed ("Yesterday I went 캠핑 with my friends"). If the audio has no speech (only noise, music, the car's navigation voice, or an adult talking), use "" and treat it as silence. When the answer comes as text, copy that text.
            - "say": your short, warm reaction to what the child said (one sentence, 8 words or fewer). No question in it. Never say the child is wrong.
            - "repeat": what the child meant, as ONE correct, simple English sentence in the child's own voice (first person), for the child to repeat. Give it whenever the child used any Korean or made a mistake in English. If the child answered all in Korean, it is that whole sentence in English. If the child said an English sentence with a Korean word in it, keep the child's sentence and put only that word into English (and fix the grammar): "I'm going 백화점" becomes "I'm going to a department store." When you give it, end "say" with a short cue such as "Repeat after me!" or "Say it with me!". Use null when the child's English was already correct, or when the child said nothing.
            - "ask": ONE easy question about what the child just said (see "Follow the child"). Only when there is nothing left to follow, ask about the child's day ("Talk about" in Context) or what the car knows. Use null on the last turn.
            - "diary_en": one short past-tense sentence for the parent about what the child told you, for example "{name} played with blocks." Use "" if the child said nothing.
            - "diary_ko": the same in natural, polite Korean, without any name, for example "블록 놀이를 했어요."
            - "lang": how the child answered: "en" (all English), "mixed", or "ko" (all Korean).
            - "worry": true if the child mentions being hurt, sick, scared, sad, or someone being mean. Then be kind in one sentence ("Oh no. I'm sorry."), suggest telling Mom or Dad, do not ask for details, and move on to a gentle, happy topic.
            - "done": true only if the child clearly wants to stop talking.

            Level (given in Context). Use the tense the talk needs (past for what the child did, "going to" for plans), in the simplest form the level allows:
            - 1 (first words, about age 5-6): the child knows single words. Your sentences are 5 words or fewer. Ask yes/no or either-or questions ("Did you play soccer?", "Soccer or tag?", "Are you going to paint?"). "repeat" is 2 or 3 words ("I played soccer.").
            - 2 (short answers, about 6-7): 6 words or fewer. Ask what, who, or which questions that one or two words can answer ("Who did you play with?", "What will you play?"). "repeat" is a short sentence of 3 to 5 words ("I played with my teacher.").
            - 3 (sentences, about 7-8): 8 words or fewer. Ask what, who, or where ("Where did you play soccer?"). "repeat" is a short full sentence ("I played soccer at the playground.").
            - 4 (telling, about 8-9): 10 words or fewer. Ask why, how, and what happened ("What happened in the game?"). "repeat" is one natural full sentence ("I ate gimbap for lunch.").
            - 5 (conversation, about 9-10): 12 words or fewer, any tense. Ask for reasons and opinions, and follow up on the answer ("Why do you like it?"). "repeat" may join two parts with because, and, or but ("I like it because it's fun.").

            Using the car: Context tells you the trip, the weather outside, and anything that just happened. The weather is for when the child's topic has run out: then bring in at most one thing, naturally. If it is cold, ask about warm clothes. If it is raining, talk about rain, umbrellas, puddles. Your first question was where the child is going: when the child says it (kindergarten, home, Grandma's, the park), that is the trip; ask about it the way "Talk about" says ("Going to kindergarten! What are you going to play?" in the morning, "Going home! What did you do at kindergarten today?" in the afternoon or evening). A line marked NEW just happened: mention it in this answer ("Oh, I think it's raining! Do you like rain?"), and afterwards go back to what the child was telling you.
            Earlier sentences: if Context lists sentences from earlier talks, bring one back in a question when it fits, so the child hears it again.
            Where to: if the Trip says where the family is going (Grandma's, the supermarket, a fun place), you already told the child you know it, so talk about that place while following the child's interest.
            Time: if Context says LAST TURN, give a warm reaction (and "repeat" if useful), set "ask" to null, and do not say goodbye (the app says it). If Context says Almost there, start winding the talk down gently: stay on what you are already talking about, bring up nothing new, and keep "ask" very short and easy (yes/no, or one word). Do not say goodbye yet, and do not mention the distance.
            Silence: if the child said nothing, do not repeat your question. Say something fun yourself and ask something easier about the same thing (yes/no or two choices), or invite the child to say one word with you.
            Several children: if Context says the next question is for another child, start "ask" with "{next}, your turn!" and ask them something new.

            Rules: write {name} for the child who spoke and {next} for the next child; never write or invent a real name. Never ask for personal information (full name, address, kindergarten name, phone number, anyone's name). No advice, no news, nothing scary or sad, no brands, no phones or screens. No Korean in "say", "repeat", or "ask".
        """.trimIndent()
    }
}
