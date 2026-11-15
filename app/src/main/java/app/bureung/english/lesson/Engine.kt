package app.bureung.english.lesson

/** Runs [action] after [delayMs]; the returned handle cancels it. On Android this is a main-thread Handler. */
interface Scheduler {
    fun post(delayMs: Long, action: () -> Unit): Cancel
    fun now(): Long
}

fun interface Cancel {
    fun cancel()
}

/** Speaks one line and calls [done] exactly once when it has finished (or failed). */
interface Speaker {
    fun speak(text: String, lang: Lang, done: () -> Unit)

    /** 부릉이's voice for the AI chat (a cloud voice); by default the normal English voice */
    fun speakBuddy(text: String, done: () -> Unit) = speak(text, Lang.EN, done)

    /** start fetching 부릉이's lines that are about to be said */
    fun prefetchBuddy(texts: List<String>) = Unit

    /** true when 부릉이's voice for [text] is already made, so it can be said without waiting */
    fun hasBuddy(text: String): Boolean = false

    fun stop()
}

/** Listens for one short answer. [result] gets the recognised text, or null when nothing was heard. */
interface Ears {
    /** false when there is no way to hear the child (no recogniser, or the parent left listening off) */
    val available: Boolean
    fun listen(waitSec: Double, hints: List<String>, lang: Lang, result: (String?) -> Unit)
    fun cancel()

    /** true when the child's voice is recorded for the AI to listen to (instead of on-device recognition) */
    val hearsVoice: Boolean get() = false

    /** Records one answer (WAV bytes), or null when nobody spoke within [waitSec]. */
    fun record(waitSec: Double, result: (ByteArray?) -> Unit) = result(null)
}

interface Sounds {
    fun play(name: String)
}

enum class EngineState { IDLE, PLAYING, PAUSED, FINISHED }

data class LessonSummary(
    val episodeId: String,
    val episodeTitle: String,
    val mode: TripMode,
    val startedAtMs: Long,
    val endedAtMs: Long,
    /** the lesson reached its last block (or said goodbye on arrival) */
    val completed: Boolean,
    val cardsShown: List<String>,
    val asks: Int,
    val hits: Int,
    /** what the child said, as text only. Audio is never kept. */
    val heard: List<String>,
    val blocksDone: List<BlockType>,
    /** the conversation: what each child said, with the sentence they were given to repeat */
    val diary: List<DiaryEntry> = emptyList(),
    /** this trip was a conversation with 부릉이 (not a recorded lesson) */
    val chat: Boolean = false,
    /** where the navigation said the family was going, if it did */
    val scenarioId: String? = null,
    /** seconds actually spent talking: pauses, and a car or emulator left asleep, do not count */
    val activeSec: Int = ((endedAtMs - startedAtMs) / 1000).toInt(),
)

interface LessonEvents {
    fun onState(state: EngineState) {}
    fun onBlock(index: Int, block: Block) {}
    fun onPlanChanged() {}
    fun onLine(text: String, lang: Lang) {}
    fun onCard(word: Word) {}
    fun onListening(listening: Boolean) {}
    fun onAnswer(ask: Step.Ask, heard: String?, result: AnswerResult) {}
    fun onTalk(entry: DiaryEntry) {}
    fun onFinished(summary: LessonSummary) {}
}

/**
 * Plays a [Plan] one step at a time. Everything runs on one thread (the scheduler's): speaker, ears and scheduler
 * callbacks must come back on it. Each step carries a generation number so a callback that arrives after a pause,
 * skip or stop is ignored.
 */
class LessonEngine(
    private val speaker: Speaker,
    private val ears: Ears,
    private val sounds: Sounds,
    private val scheduler: Scheduler,
    private val events: LessonEvents,
    private val talker: Talker = NoTalker,
) {
    var state = EngineState.IDLE
        private set
    var plan: Plan? = null
        private set
    /** the blocks still in play, after drops and inserts */
    val blocks: List<Block> get() = live
    var blockIndex = 0
        private set
    var lastCard: Word? = null
        private set
    /** seconds left to the destination as last reported, or null without guidance */
    var remainingSec: Int? = null
        private set

    private val live = mutableListOf<Block>()
    private var stepIndex = 0
    private val replies = ArrayDeque<Step>()
    private var current: Step? = null
    private var generation = 0
    private var pending: Cancel? = null
    private var wrapRequested = false
    private var surprise: Block? = null
    private var surpriseUsed = false
    private var storyAdded = false
    private var turn = 0

    /** what the car knows right now (rain, outside temperature); the app keeps it current */
    var scene = Scene()
    private val carEvents = mutableListOf<String>()
    /** when the talking should stop so the goodbye fits before arrival; 0 until known */
    private var chatEndsAt = 0L
    private var chatOver = false
    private var nearEnd = false
    private var nearTurns = 0
    private var chatWho = 0
    private var chatTurnsWithChild = 0
    private var chatTurn = 0
    private var chatSilences = 0
    private var chatErrors = 0
    private var chatQuestions = 0
    private val chatHistory = mutableListOf<Said>()

    // summary
    private var startedAt = 0L
    /** time spent playing, step by step; one step never counts for more than [MAX_STEP_MS] */
    private var activeMs = 0L
    private var lastMark = 0L
    private val cards = mutableListOf<String>()
    private val heard = mutableListOf<String>()
    private val done = mutableListOf<BlockType>()
    private val diary = mutableListOf<DiaryEntry>()
    private var asks = 0
    private var hits = 0

    val currentBlock: Block? get() = live.getOrNull(blockIndex)
    val isActive: Boolean get() = state == EngineState.PLAYING || state == EngineState.PAUSED

    fun start(plan: Plan) {
        if (isActive) stop()
        this.plan = plan
        live.clear(); live.addAll(plan.blocks)
        blockIndex = 0; stepIndex = 0
        replies.clear(); current = null
        wrapRequested = false; surprise = null; surpriseUsed = false; storyAdded = false
        cards.clear(); heard.clear(); done.clear(); diary.clear(); asks = 0; hits = 0; turn = 0
        lastCard = null
        carEvents.clear(); chatHistory.clear()
        chatEndsAt = 0L; chatOver = false; nearEnd = false; nearTurns = 0; chatWho = 0; chatTurnsWithChild = 0; chatTurn = 0
        chatSilences = 0; chatErrors = 0; chatQuestions = 0
        plan.chat?.let { c ->
            scene = Scene(c.moment.outsideC, c.moment.raining)
            chatHistory += Said(false, c.opener)
            // the first line first: it must be ready when the intro tune ends
            speaker.prefetchBuddy(listOf(c.opener) + TalkRules.FILLERS + TalkRules.closing(c.children.first()))
        }
        startedAt = scheduler.now()
        activeMs = 0L
        lastMark = startedAt
        setState(EngineState.PLAYING)
        live.firstOrNull()?.let { events.onBlock(0, it) }
        advance()
    }

    fun pause() {
        if (state != EngineState.PLAYING) return
        mark()
        interrupt()
        setState(EngineState.PAUSED)
    }

    fun resume() {
        if (state != EngineState.PAUSED) return
        lastMark = scheduler.now()
        setState(EngineState.PLAYING)
        val again = current
        when {
            // the question was asked before the pause: say a word first, so the child knows to go on
            again is Step.Chat -> {
                replies.addFirst(Step.Chat)
                run(Step.Say(TalkRules.RESUME_LINE, Lang.EN, buddy = true))
            }
            again != null -> run(again)
            else -> advance()
        }
    }

    fun togglePause() = if (state == EngineState.PLAYING) pause() else resume()

    /** Ends the lesson without a goodbye (the parent pressed stop). */
    fun stop() {
        if (!isActive) return
        interrupt()
        finish(completed = false)
    }

    /** Something just happened in the car ("It just started raining"): 부릉이 brings it up in the next answer. */
    fun tell(event: String) {
        if (isActive && plan?.chat != null && event !in carEvents) carEvents += event
    }

    /** Inserts the rain surprise at the next block boundary, once per lesson. */
    fun offerSurprise(block: Block) {
        if (!isActive || surpriseUsed || wrapRequested || plan?.chat != null) return
        if (currentBlock?.type == BlockType.WRAPUP) return
        surprise = block
        surpriseUsed = true
    }

    /**
     * The navigation's latest remaining time. Close to arrival the lesson jumps to its goodbye; if the rest will not
     * fit, optional blocks ahead are dropped; in a traffic jam the story is added.
     */
    /**
     * Less than a kilometre to go: the conversation starts to wind down. 부릉이 is told to wrap the topic up over
     * the next exchanges ([TalkRules.WIND_DOWN_TURNS]); after those, or when the time runs out, comes the last
     * turn and the goodbye.
     */
    fun nearDestination(near: Boolean) {
        if (!near) nearTurns = 0
        nearEnd = near
    }

    fun updateRemaining(sec: Int) {
        remainingSec = sec
        if (!isActive) return
        if (plan?.chat != null) {
            // a conversation has no blocks to drop: it simply talks until the goodbye has to start
            val bye = live.lastOrNull()?.takeIf { it.type == BlockType.WRAPUP }?.let { Estimate.block(it) } ?: 0.0
            chatEndsAt = scheduler.now() + ((sec - Planner.ARRIVAL_MARGIN_SEC - bye) * 1000).toLong()
            return
        }
        if (sec <= WRAP_AT_SEC) {
            if (!inOrPastWrapup()) wrapRequested = true
            return
        }
        var changed = false
        while (restEstimate() > sec - SAFETY_SEC) {
            // extras (story, quiz, chant again) go before the core song and role play
            val victim = live.withIndex()
                .filter { it.index > blockIndex && it.value.drop > 0 }
                .minWithOrNull(compareBy({ if (it.value.extra) 0 else 1 }, { it.value.drop })) ?: break
            live.removeAt(victim.index)
            changed = true
        }
        val story = plan?.episode?.block(BlockType.STORY)
        if (!storyAdded && story != null && live.none { it.type == BlockType.STORY }) {
            val room = sec - SAFETY_SEC - restEstimate()
            val wrapAt = live.indexOfFirst { it.type == BlockType.WRAPUP }
            if (room >= Estimate.block(story) + JAM_EXTRA_SEC && wrapAt > blockIndex) {
                live.add(wrapAt, story.copy(drop = 1, extra = true))
                storyAdded = true
                changed = true
            }
        }
        if (changed) events.onPlanChanged()
    }

    /** Arrival: whatever is playing stops and the child hears a short goodbye. */
    fun arrived(englishName: String, koreanName: String) {
        if (!isActive) return
        if (currentBlock?.type == BlockType.WRAPUP) return
        interrupt()
        replies.clear()
        while (live.size > blockIndex + 1) live.removeAt(live.size - 1)
        live.add(
            if (plan?.chat != null) {
                Block(BlockType.WRAPUP, listOf<Step>(Step.Sfx("yay")) + TalkRules.arrival(englishName).map { Step.Say(it, Lang.EN, buddy = true) })
            } else {
                Lines.goodbye(englishName, koreanName)
            },
        )
        currentBlock?.let { done += it.type }
        blockIndex = live.size - 1
        stepIndex = 0
        current = null
        events.onPlanChanged()
        events.onBlock(blockIndex, live[blockIndex])
        if (state == EngineState.PAUSED) finish(completed = true) else advance()
    }

    /** Seconds of lesson left from here, by estimate. */
    fun restEstimate(): Int {
        val block = currentBlock ?: return 0
        val here = block.steps.drop(stepIndex).sumOf { Estimate.step(it) } + replies.sumOf { Estimate.step(it) }
        val after = live.drop(blockIndex + 1).sumOf { Estimate.block(it) }
        return (here + after + chatRest()).toInt()
    }

    /** seconds of talking still ahead in a conversation (0 in a lesson, or once the talking is over) */
    private fun chatRest(): Int {
        val c = plan?.chat ?: return 0
        val talkAt = live.indexOfFirst { it.type == BlockType.TALK }
        if (chatOver || talkAt < blockIndex) return 0
        return if (chatEndsAt == 0L) c.budgetSec else chatLeft().coerceAtLeast(0)
    }

    private fun chatLeft(): Int = ((chatEndsAt - scheduler.now()) / 1000).toInt()

    private fun inOrPastWrapup(): Boolean {
        val wrapAt = live.indexOfFirst { it.type == BlockType.WRAPUP }
        return wrapAt in 0..blockIndex
    }

    /** counts the time since the last step as talking time, capped so a sleeping car does not add hours */
    private fun mark() {
        val now = scheduler.now()
        if (state == EngineState.PLAYING) activeMs += (now - lastMark).coerceIn(0L, MAX_STEP_MS)
        lastMark = now
    }

    private fun advance() {
        if (state != EngineState.PLAYING) return
        mark()
        current = null
        replies.removeFirstOrNull()?.let { return run(it) }

        if (wrapRequested) {
            wrapRequested = false
            val wrapAt = live.indexOfFirst { it.type == BlockType.WRAPUP }
            if (wrapAt > blockIndex) {
                currentBlock?.let { done += it.type }
                repeat(wrapAt - blockIndex - 1) { live.removeAt(blockIndex + 1) }
                replies.clear()
                blockIndex += 1
                stepIndex = 0
                events.onPlanChanged()
                events.onBlock(blockIndex, live[blockIndex])
            }
        }

        val block = currentBlock ?: return finish(completed = true)
        if (stepIndex >= block.steps.size) {
            done += block.type
            blockIndex += 1
            stepIndex = 0
            surprise?.let { s ->
                val next = currentBlock
                if (next != null && next.type != BlockType.WRAPUP) {
                    live.add(blockIndex, s)
                    events.onPlanChanged()
                }
                surprise = null
            }
            currentBlock?.let { events.onBlock(blockIndex, it) }
            return advance()
        }
        val step = block.steps[stepIndex]
        stepIndex += 1
        run(step)
    }

    private fun run(step: Step) {
        current = step
        val gen = ++generation
        when (step) {
            is Step.Say -> {
                events.onLine(step.text, step.lang)
                val next = { if (gen == generation) advance() }
                if (step.buddy) speaker.speakBuddy(step.text, next) else speaker.speak(step.text, step.lang, next)
            }
            is Step.Echo -> {
                events.onLine(step.text, Lang.EN)
                val next = { if (gen == generation) wait(Estimate.echoGap(step.text), gen) }
                if (step.buddy) speaker.speakBuddy(step.text, next) else speaker.speak(step.text, Lang.EN, next)
            }
            is Step.Chat -> chat(gen)
            is Step.Pause -> wait(step.sec, gen)
            is Step.Sfx -> {
                sounds.play(step.name)
                wait(step.sec, gen)
            }
            is Step.Card -> {
                plan?.words?.get(step.wordId)?.let { word ->
                    lastCard = word
                    if (step.wordId !in cards) cards += step.wordId
                    events.onCard(word)
                }
                wait(0.0, gen)
            }
            is Step.Ask -> listen(step, gen)
        }
    }

    private fun listen(ask: Step.Ask, gen: Int) {
        if (!ears.available) {
            wait(ask.waitSec * NO_EARS_WAIT_SHARE, gen) { answer(ask, null, AnswerResult.NOT_HEARD) }
            return
        }
        events.onListening(true)
        ears.listen(ask.waitSec, ask.accept, Lang.EN) { text ->
            if (gen != generation) return@listen
            events.onListening(false)
            val result = when {
                text.isNullOrBlank() -> AnswerResult.SILENT
                ask.open || Matcher.matches(text, ask.accept) -> AnswerResult.HIT
                else -> AnswerResult.RECAST
            }
            answer(ask, text, result)
            advance()
        }
    }

    /**
     * One turn of the conversation: listen to the child, let the [Talker] react, recast and ask again, then come
     * back here. It ends by itself when the time is up, the child stays silent or the network is gone; the goodbye
     * block follows. Every failure falls back to a recorded line, so there is never dead air.
     */
    private fun chat(gen: Int) {
        val c = plan?.chat ?: return wait(0.0, gen)
        if (chatEndsAt == 0L) chatEndsAt = scheduler.now() + c.budgetSec * 1000L
        if (chatOver || chatLeft() < TalkRules.MIN_TURN_SEC || !ears.available || !talker.available) {
            chatOver = true
            return wait(0.0, gen)
        }
        val child = c.children[chatWho % c.children.size]
        // a two-note cue says "your turn"; the microphone opens just after it, so the cue is not heard as speech
        events.onListening(true)
        sounds.play("listen")
        scheduler.post(TalkRules.CUE_MS) { if (gen == generation) listenToChild(c, child, gen) }
    }

    private fun listenToChild(c: ChatPlan, child: String, gen: Int) {
        if (ears.hearsVoice) {
            // the AI listens to the child's voice itself: mixed Korean and English come through as spoken
            ears.record(TalkRules.LISTEN_SEC) { audio ->
                if (gen != generation) return@record
                events.onListening(false)
                sounds.play("over")
                heardChild(c, child, gen, "", audio, audio == null)
            }
        } else {
            ears.listen(TalkRules.LISTEN_SEC, emptyList(), c.listen) { text ->
                if (gen != generation) return@listen
                events.onListening(false)
                sounds.play("over")
                val said = text?.trim().orEmpty()
                heardChild(c, child, gen, said, null, said.isEmpty())
            }
        }
    }

    /** [audio] set: what was said is not known yet; the AI's answer brings the words ([TalkReply.heard]). */
    private fun heardChild(c: ChatPlan, child: String, gen: Int, said: String, audio: ByteArray?, silent: Boolean) {
        fun count(wasSilent: Boolean, words: String) {
            asks += 1
            if (wasSilent) {
                chatSilences += 1
            } else {
                chatSilences = 0
                hits += 1
                if (words.isNotEmpty()) {
                    heard += words
                    chatHistory += Said(true, words)
                }
            }
        }
        val history = chatHistory.takeLast(TalkRules.HISTORY_LINES)
        if (audio == null) {
            count(silent, said)
            if (chatSilences >= TalkRules.MAX_SILENCES) return endOnSilence()
        }
        val last = chatLeft() < TalkRules.LAST_TURN_SEC || (nearEnd && nearTurns >= TalkRules.WIND_DOWN_TURNS)
        val nearing = nearEnd && !last
        if (nearEnd) nearTurns += 1
        val switch = !last && !silent && c.children.size > 1 && chatTurnsWithChild + 1 >= TalkRules.SWITCH_EVERY
        val next = if (switch) c.children[(chatWho + 1) % c.children.size] else child
        val turn = TalkTurn(
            child = child,
            level = c.level,
            mode = c.mode,
            hour = c.moment.hour,
            month = c.moment.month,
            scene = scene,
            events = carEvents.toList(),
            secondsLeft = chatLeft().coerceAtLeast(0),
            last = last,
            switch = switch,
            silent = silent,
            review = c.review,
            history = history,
            heard = said,
            situation = c.scenario?.situation,
            targets = emptyList(),
            audio = audio,
            nearing = nearing,
        )
        carEvents.clear()

        // while the answer is on its way: a tiny word in 부릉이's voice if it is already made
        var reply: TalkReply? = null
        var words = said
        var quiet = silent
        var answered = false
        var fillerDone = true
        fun join() {
            if (gen != generation || !answered || !fillerDone) return
            if (quiet && chatSilences >= TalkRules.MAX_SILENCES) return endOnSilence()
            respond(child, next, words, quiet, last, switch && !quiet, reply)
        }
        if (!silent) {
            val filler = TalkRules.FILLERS[chatTurn % TalkRules.FILLERS.size]
            if (speaker.hasBuddy(filler)) {
                fillerDone = false
                events.onLine(filler, Lang.EN)
                speaker.speakBuddy(filler) {
                    fillerDone = true
                    join()
                }
            }
        }
        chatTurn += 1
        talker.reply(turn) { raw ->
            if (gen != generation) return@reply
            reply = raw?.let { TalkRules.clean(it, child, next) }
            if (audio != null) {
                // the recording may have been only noise: then the AI heard nothing, and this was a silence
                words = raw?.heard?.trim().orEmpty()
                quiet = raw != null && words.isEmpty()
                count(quiet, words)
            }
            answered = true
            join()
        }
    }

    private fun endOnSilence() {
        chatOver = true
        replies.addFirst(Step.Say(TalkRules.SILENT_LINE, Lang.EN, buddy = true))
        advance()
    }

    private fun chatHour(): Int = plan?.chat?.moment?.hour ?: 12

    private fun respond(child: String, next: String, said: String, silent: Boolean, last: Boolean, switch: Boolean, reply: TalkReply?) {
        val steps = mutableListOf<Step>()
        fun say(text: String) {
            steps += Step.Say(text, Lang.EN, buddy = true)
        }
        if (reply == null) {
            // no answer from the AI (network, or it broke the rules): a recorded line and a spare question
            chatErrors += 1
            say(if (silent) TalkRules.SILENT_LINE else TalkRules.ERROR_LINE)
            if (last || chatErrors >= TalkRules.MAX_ERRORS) {
                chatOver = true
            } else {
                val question = TalkRules.spare(chatHour(), chatQuestions++, followUp = !silent)
                say(question)
                chatHistory += Said(false, question)
                steps += Step.Chat
            }
        } else {
            chatErrors = 0
            if (!silent) {
                val entry = DiaryEntry(child, said, reply.diaryEn, reply.diaryKo, reply.worry, reply.repeat, reply.lang)
                diary += entry
                events.onTalk(entry)
            }
            val ask = when {
                last || reply.done -> null
                else -> reply.ask ?: TalkRules.spare(chatHour(), chatQuestions++, followUp = !silent)
            }
            // as few voice calls as possible: the reaction and the sentence to repeat in one, then room to repeat
            // it, then the question; without a sentence to repeat, the reaction and the question in one
            val lines = if (reply.repeat != null) {
                listOfNotNull(listOfNotNull(reply.say, reply.repeat).joinToString(" "), ask)
            } else {
                listOfNotNull(listOfNotNull(reply.say, ask).joinToString(" ").ifBlank { null })
            }
            speaker.prefetchBuddy(lines)
            lines.forEachIndexed { i, line ->
                say(line)
                if (i == 0 && reply.repeat != null) steps += Step.Pause(Estimate.echoGap(reply.repeat))
            }
            chatHistory += Said(false, listOfNotNull(reply.say, reply.repeat, ask).joinToString(" "))
            if (ask == null) {
                chatOver = true
            } else {
                steps += Step.Chat
                if (switch) {
                    chatWho += 1
                    chatTurnsWithChild = 0
                } else if (!silent) {
                    chatTurnsWithChild += 1
                }
            }
        }
        replies.addAll(0, steps)
        advance()
    }

    private fun answer(ask: Step.Ask, text: String?, result: AnswerResult) {
        asks += 1
        if (result == AnswerResult.HIT) hits += 1
        if (!text.isNullOrBlank()) heard += text.trim()
        replies.addAll(Lines.reply(ask, result, turn++))
        events.onAnswer(ask, text, result)
    }

    private fun wait(sec: Double, gen: Int, before: (() -> Unit)? = null) {
        pending?.cancel()
        pending = scheduler.post((sec * 1000).toLong()) {
            if (gen != generation) return@post
            before?.invoke()
            advance()
        }
    }

    private fun interrupt() {
        generation += 1
        pending?.cancel()
        pending = null
        speaker.stop()
        ears.cancel()
        talker.cancel()
        events.onListening(false)
    }

    private fun finish(completed: Boolean) {
        val p = plan
        mark()
        generation += 1
        pending?.cancel()
        pending = null
        setState(EngineState.FINISHED)
        if (p != null) {
            events.onFinished(
                LessonSummary(
                    episodeId = p.episode.id,
                    episodeTitle = p.episode.title,
                    mode = p.mode,
                    startedAtMs = startedAt,
                    endedAtMs = scheduler.now(),
                    completed = completed,
                    cardsShown = cards.toList(),
                    asks = asks,
                    hits = hits,
                    heard = heard.toList(),
                    blocksDone = done.toList(),
                    diary = diary.toList(),
                    chat = p.chat != null,
                    scenarioId = p.chat?.scenario?.id,
                    activeSec = (activeMs / 1000).toInt(),
                ),
            )
        }
    }

    private fun setState(next: EngineState) {
        if (state == next) return
        state = next
        events.onState(next)
    }

    companion object {
        /** below this many seconds to arrival the lesson goes to its goodbye */
        const val WRAP_AT_SEC = 75
        const val SAFETY_SEC = 20
        /** extra room needed before a traffic jam earns the story */
        const val JAM_EXTRA_SEC = 60
        /** no single step (a line, a listen, an answer from the AI) takes longer than this */
        private const val MAX_STEP_MS = 60_000L
        /** without a recogniser the child still gets most of the time to answer */
        private const val NO_EARS_WAIT_SHARE = 0.7
    }
}

/** No AI: a conversation is never planned, and a stray Chat step ends at once. */
object NoTalker : Talker {
    override val available = false
    override fun reply(turn: TalkTurn, result: (TalkReply?) -> Unit) = result(null)
    override fun cancel() = Unit
}
