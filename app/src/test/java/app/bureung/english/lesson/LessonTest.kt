package app.bureung.english.lesson

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure lesson logic: matching, spaced repetition, fitting a lesson to a trip, and playing it. */
class LessonTest {

    // --- fixtures ----------------------------------------------------------------------------------------------------

    private val words = listOf(
        Word("elephant", "elephant", "코끼리", "🐘", "It has a long, long nose."),
        Word("lion", "lion", "사자", "🦁", "It says, roar!"),
        Word("monkey", "monkey", "원숭이", "🐒", "It loves bananas."),
        Word("giraffe", "giraffe", "기린", "🦒", "It has a very long neck."),
    )

    private fun teach(w: Word) = listOf(
        Step.Card(w.id),
        Step.Say("It's a ${w.en}!", Lang.EN),
        Step.Echo(w.en),
        Step.Say("Can you say, ${w.en}?", Lang.EN),
        Step.Ask(w.en.replaceFirstChar { it.uppercase() } + "!", listOf(w.en), 4.5, w.id),
    )

    private val episode = Episode(
        id = "T-01", level = 2, title = "At the Zoo", titleKo = "동물원", emoji = "🦁", words = words,
        blocks = listOf(
            Block(BlockType.GREETING, listOf(Step.Say("Today, we're going to the zoo, {name}!", Lang.EN))),
            Block(BlockType.EXPRESSION, words.flatMap { teach(it) } + words.flatMap { teach(it) }),
            Block(BlockType.CHANT, List(6) { Step.Echo("Elephant, elephant, toot, toot, toot!") }, drop = 1),
            Block(BlockType.ROLEPLAY, words.flatMap { teach(it) }, drop = 2),
            Block(BlockType.STORY, List(10) { Step.Say("Little Monkey was hungry. Ooh, ooh, ah, ah!", Lang.EN) }, drop = 1, extra = true),
            Block(BlockType.WRAPUP, listOf(Step.Say("Bye-bye, {name}!", Lang.EN), Step.Sfx("yay"))),
        ),
    )

    private val moment = Moment("Harin", "하린", hour = 8, month = 11)

    // --- matching ----------------------------------------------------------------------------------------------------

    @Test
    fun matcherAcceptsNearMissesAndPhrases() {
        assertTrue(Matcher.matches("Elephant!", listOf("elephant")))
        assertTrue(Matcher.matches("an elefant", listOf("elephant")))
        assertTrue(Matcher.matches("lions", listOf("lion")))
        assertTrue(Matcher.matches("I see lion", listOf("see a lion")))
        assertFalse(Matcher.matches("lion", listOf("elephant")))
        assertFalse(Matcher.matches("", listOf("lion")))
        assertFalse(Matcher.matches("go", listOf("no")))
    }

    @Test
    fun linesAreSpokenOneSentenceAtATime() {
        assertEquals(listOf("Hi, Harin!", "Welcome back!"), Sentences.split("Hi, Harin! Welcome back!"))
        assertEquals(listOf("Listen.", "Toot, toot!", "Who is it?"), Sentences.split("Listen. Toot, toot! Who is it?"))
        assertEquals(listOf("Mr. Sun said, I'm so hot!"), Sentences.split("Mr. Sun said, I'm so hot!"))
        assertEquals(listOf("오늘은 비가 와요."), Sentences.split("오늘은 비가 와요."))
        assertEquals(listOf("Elephant"), Sentences.split("Elephant"))
        assertEquals("Good morning!", Sentences.withoutName("Good morning, Jiwoo!", "Jiwoo"))
        assertEquals("Thank you, weather reporter!", Sentences.withoutName("Thank you, weather reporter Jiwoo!", "Jiwoo"))
        assertEquals(null, Sentences.withoutName("Good morning!", "Jiwoo"))
    }

    @Test
    fun everythingTheLessonSaysIsInTheInventory() {
        val inventory = Inventory.english(listOf(episode), "Harin", "하린")
        val (e, clock, rest) = engine { hints -> hints.first() }
        val (speaker, _) = rest
        e.start(Planner.plan(episode, TripMode.TO_SCHOOL, 25 * 60, moment.copy(raining = true), emptyList()))
        clock.run(untilMs = 30_000)
        e.offerSurprise(Lines.rainSurprise())
        clock.run()
        val (e2, clock2, rest2) = engine { "banana" }
        e2.start(Planner.plan(episode, TripMode.FROM_SCHOOL, 600, moment, words))
        clock2.run()
        val said = (speaker.said + rest2.first.said).flatMap { Sentences.split(it) }.filter { s -> s.none { it in '가'..'힣' } }
        val missing = said.filter { it !in inventory }.distinct()
        assertTrue("not recorded: $missing", missing.isEmpty())
    }

    @Test
    fun everyFixedLineOfAConversationHasARecordingToFallBackOn() {
        val inventory = Inventory.english(listOf(episode), "Harin", "하린")
        // a conversation where the AI never answers: everything said is one of the app's own lines
        val t = Talk(child = { "블록" }) { null }
        t.engine.start(Planner.chat(TripMode.TO_SCHOOL, 300, moment.copy(outsideC = 1f)))
        t.clock.run()
        val t2 = Talk(child = { null }) { null }
        t2.engine.start(Planner.chat(TripMode.FROM_SCHOOL, 300, moment.copy(hour = 16, raining = true)))
        t2.clock.run(untilMs = 20_000)
        t2.engine.pause()
        t2.engine.resume()
        t2.clock.run(untilMs = 40_000)
        t2.engine.arrived("Harin", "하린")
        t2.clock.run()
        val openers = Scenarios.all.flatMap { s -> listOf(8, 14, 20).map { s.opener("Harin", it) } } +
            listOf(8, 14, 20).map { TalkRules.opener("Harin", it) }
        val said = (t.speaker.buddy + t2.speaker.buddy + TalkRules.QUESTIONS + openers).flatMap { Sentences.split(it) }
        assertTrue(said.size > 8)
        val missing = said.filter { it !in inventory }.distinct()
        assertTrue("not recorded: $missing", missing.isEmpty())
    }

    @Test
    fun koreanCallUsesTheRightParticle() {
        assertEquals("하린아", Lines.koCall("하린"))
        assertEquals("지우야", Lines.koCall("지우"))
        assertEquals("Harin", Lines.koCall("Harin"))
    }

    // --- spaced repetition -------------------------------------------------------------------------------------------

    @Test
    fun wordsComeBackSameDayThenAfterThreeAndSevenDays() {
        val day = 20_000L
        var s = Srs.introduce(day, null)
        assertEquals(day, s.due)
        s = Srs.reviewed(s, day, success = true)
        assertEquals(day + 3, s.due)
        s = Srs.reviewed(s, day + 3, success = true)
        assertEquals(day + 3 + 7, s.due)
        s = Srs.reviewed(s, day + 10, success = true)
        assertTrue(s.mastered)
        val missed = Srs.reviewed(Srs.introduce(day, null), day, success = false)
        assertEquals(day + 1, missed.due)
        assertEquals(listOf("a"), Srs.due(mapOf("a" to Srs.introduce(day, null), "b" to s), day))
    }

    // --- planning ----------------------------------------------------------------------------------------------------

    @Test
    fun aTenMinuteTripGetsTheWholeEpisodeAndStaysInBudget() {
        val plan = Planner.plan(episode, TripMode.TO_SCHOOL, 600, moment, emptyList())
        val types = plan.blocks.map { it.type }
        assertEquals(BlockType.HELLO, types.first())
        assertEquals(BlockType.WRAPUP, types.last())
        assertTrue(BlockType.CHANT in types)
        assertTrue(BlockType.ROLEPLAY in types)
        assertTrue("estimate ${plan.estimateSec} > budget ${plan.budgetSec}", plan.estimateSec <= plan.budgetSec)
        // names are filled in
        assertTrue(plan.blocks.flatMap { it.steps }.filterIsInstance<Step.Say>().none { "{name}" in it.text })
    }

    @Test
    fun aShortTripDropsTheSongFirst() {
        val full = Planner.plan(episode, TripMode.TO_SCHOOL, 600, moment, emptyList())
        val chantSec = Estimate.block(episode.block(BlockType.CHANT)!!).toInt()
        val coreOnly = full.blocks.filter { !it.extra && it.type != BlockType.CHANT && it.type != BlockType.ROLEPLAY }
        val roleplaySec = Estimate.block(episode.block(BlockType.ROLEPLAY)!!).toInt()
        // room for everything but the chant
        val trip = Planner.total(coreOnly).toInt() + roleplaySec + chantSec / 2 + Planner.ARRIVAL_MARGIN_SEC
        val short = Planner.plan(episode, TripMode.TO_SCHOOL, trip, moment, emptyList())
        val types = short.blocks.map { it.type }
        assertFalse(BlockType.CHANT in types)
        assertTrue(BlockType.ROLEPLAY in types)
        assertTrue(BlockType.EXPRESSION in types)
    }

    @Test
    fun aLongTripAddsTheStoryAndTheQuiz() {
        val plan = Planner.plan(episode, TripMode.TO_SCHOOL, 25 * 60, moment, emptyList())
        val types = plan.blocks.map { it.type }
        assertTrue(BlockType.STORY in types)
        assertTrue(BlockType.QUIZ in types)
        assertEquals(BlockType.WRAPUP, types.last())
    }

    @Test
    fun theWayHomeReviewsTodaysWords() {
        val plan = Planner.plan(episode, TripMode.FROM_SCHOOL, 600, moment, words.take(2))
        val types = plan.blocks.map { it.type }
        assertEquals(listOf(BlockType.HELLO, BlockType.REVIEW), types.take(2))
        assertFalse(BlockType.EXPRESSION in types)
        assertEquals(listOf("elephant", "lion"), plan.reviewIds)
        val wrap = plan.blocks.last().steps.filterIsInstance<Step.Say>().joinToString(" ") { it.text }
        assertTrue(wrap, "You remembered so many words!" in wrap)
    }

    @Test
    fun theWayHomeWithNothingToReviewTeachesInstead() {
        val plan = Planner.plan(episode, TripMode.FROM_SCHOOL, 600, moment, emptyList())
        assertEquals(TripMode.TO_SCHOOL, plan.mode)
        assertTrue(plan.blocks.any { it.type == BlockType.EXPRESSION })
    }

    @Test
    fun rainOnTheWayInIsInTheGreeting() {
        val hello = Lines.hello(moment.copy(raining = true), TripMode.TO_SCHOOL)
        assertTrue(hello.steps.any { it is Step.Ask && it.wordId == Lines.RAIN.id })
        val cold = Lines.hello(moment.copy(outsideC = 2f), TripMode.TO_SCHOOL)
        assertTrue(cold.steps.any { it is Step.Ask && it.answer == "Cold!" })
    }

    // --- playing -----------------------------------------------------------------------------------------------------

    private class FakeClock : Scheduler {
        var time = 0L
        private var seq = 0
        private val queue = sortedMapOf<Pair<Long, Int>, () -> Unit>(compareBy({ it.first }, { it.second }))

        override fun post(delayMs: Long, action: () -> Unit): Cancel {
            val key = (time + delayMs) to seq++
            queue[key] = action
            return Cancel { queue.remove(key) }
        }

        override fun now(): Long = time

        /** runs until nothing is left or [untilMs] is reached */
        fun run(untilMs: Long = Long.MAX_VALUE) {
            while (queue.isNotEmpty()) {
                val key = queue.firstKey()
                if (key.first > untilMs) break
                val action = queue.remove(key)!!
                time = key.first
                action()
            }
            if (untilMs != Long.MAX_VALUE) time = maxOf(time, untilMs)
        }
    }

    private class FakeSpeaker(val clock: FakeClock) : Speaker {
        val said = mutableListOf<String>()
        /** what 부릉이 (the AI chat's voice) said */
        val buddy = mutableListOf<String>()
        override fun speak(text: String, lang: Lang, done: () -> Unit) {
            said += text
            clock.post((Estimate.speech(text, lang) * 1000).toLong()) { done() }
        }

        /** 부릉이 lines whose voice is "already made" (the tiny words said while the answer is on its way) */
        val ready = mutableSetOf<String>()
        override fun speakBuddy(text: String, done: () -> Unit) {
            buddy += text
            speak(text, Lang.EN, done)
        }

        override fun hasBuddy(text: String) = text in ready

        override fun stop() = Unit
    }

    private class FakeEars(val clock: FakeClock, val answer: (List<String>) -> String?, val korean: () -> String?) : Ears {
        override val available = true
        val languages = mutableListOf<Lang>()
        override fun listen(waitSec: Double, hints: List<String>, lang: Lang, result: (String?) -> Unit) {
            languages += lang
            clock.post(1500) { result(if (lang == Lang.KO) korean() else answer(hints)) }
        }

        override fun cancel() = Unit
    }

    /** a stand-in for Gemini: answers after a second, with whatever [reply] makes of the turn */
    private class FakeTalker(val clock: FakeClock, val reply: (TalkTurn) -> TalkReply?) : Talker {
        override val available = true
        val turns = mutableListOf<TalkTurn>()
        override fun reply(turn: TalkTurn, result: (TalkReply?) -> Unit) {
            turns += turn
            clock.post(1000) { result(reply(turn)) }
        }

        override fun cancel() = Unit
    }

    private class Recorder : LessonEvents {
        var finished: LessonSummary? = null
        val results = mutableListOf<AnswerResult>()
        override fun onAnswer(ask: Step.Ask, heard: String?, result: AnswerResult) {
            results += result
        }

        override fun onFinished(summary: LessonSummary) {
            finished = summary
        }
    }

    private fun engine(answer: (List<String>) -> String?): Triple<LessonEngine, FakeClock, Pair<FakeSpeaker, Recorder>> {
        val clock = FakeClock()
        val speaker = FakeSpeaker(clock)
        val events = Recorder()
        val e = LessonEngine(speaker, FakeEars(clock, answer) { null }, object : Sounds {
            override fun play(name: String) = Unit
        }, clock, events)
        return Triple(e, clock, speaker to events)
    }

    // --- the conversation with 부릉이 ----------------------------------------------------------------------------------

    /** everything a conversation test looks at */
    /** ears that hand the child's voice to the AI instead of recognising it in the car */
    private class VoiceEars(val clock: FakeClock, val voice: () -> ByteArray?) : Ears {
        override val available = true
        override val hearsVoice = true
        var recognised = 0
        override fun listen(waitSec: Double, hints: List<String>, lang: Lang, result: (String?) -> Unit) {
            recognised += 1
            clock.post(1500) { result(null) }
        }

        override fun record(waitSec: Double, result: (ByteArray?) -> Unit) {
            clock.post(2500) { result(voice()) }
        }

        override fun cancel() = Unit
    }

    private class Talk(child: () -> String?, voice: (() -> ByteArray?)? = null, reply: (TalkTurn) -> TalkReply?) {
        val clock = FakeClock()
        val speaker = FakeSpeaker(clock)
        val events = Recorder()
        val talker = FakeTalker(clock, reply)
        val sounds = mutableListOf<String>()
        val ears: Ears = if (voice != null) VoiceEars(clock, voice) else FakeEars(clock, { it.firstOrNull() }, child)
        val engine = LessonEngine(speaker, ears, object : Sounds {
            override fun play(name: String) {
                sounds += name
            }
        }, clock, events, talker)
        val summary: LessonSummary get() = events.finished!!
        val seconds: Long get() = (summary.endedAtMs - summary.startedAtMs) / 1000
    }

    private val blocks = TalkReply(
        say = "Wow, blocks! Say it with me!",
        repeat = "I played with blocks.",
        ask = "What did you build, {name}?",
        diaryEn = "{name} played with blocks.",
        diaryKo = "블록 놀이를 했어요.",
        lang = "ko",
        worry = false,
        done = false,
    )

    @Test
    fun theCarSpeaksFirstFromWhatItKnows() {
        fun opener(hour: Int, outsideC: Float? = null, raining: Boolean = false, mode: TripMode = TripMode.TO_SCHOOL) =
            Planner.chat(mode, 600, moment.copy(hour = hour, outsideC = outsideC, raining = raining)).chat!!.opener
        // always the same: a greeting by the clock, then the child is asked where they are going
        assertEquals("Good morning, Harin! Where are you going today?", opener(8, 15f))
        assertEquals("Good morning, Harin! Where are you going today?", opener(8, 2f, raining = true))
        assertEquals("Good afternoon, Harin! Where are you going today?", opener(16, 31f))
        assertEquals("Good evening, Harin! Where are you going today?", opener(20, 15f))
        assertEquals(opener(16, 15f), opener(16, 15f, mode = TripMode.FROM_SCHOOL))

        // the plan is the intro tune, 부릉이's first words, open talk, and a goodbye: no curriculum blocks
        val plan = Planner.chat(TripMode.FROM_SCHOOL, 600, moment.copy(hour = 16))
        assertEquals(listOf(BlockType.HELLO, BlockType.TALK, BlockType.WRAPUP), plan.blocks.map { it.type })
        assertEquals(Step.Sfx("intro", Planner.INTRO_SEC), plan.blocks.first().steps.first())
        assertTrue("fills the trip: ${plan.estimateSec}", plan.estimateSec in 540..560)
    }

    @Test
    fun aConversationRecastsAndEndsBeforeArrival() {
        val t = Talk(child = { "오늘 블록 놀이 했어" }) { turn -> if (turn.last) blocks.copy(ask = null) else blocks }
        val plan = Planner.chat(TripMode.FROM_SCHOOL, 300, moment.copy(hour = 16))
        t.engine.start(plan)
        t.clock.run()
        val summary = t.summary
        assertTrue(summary.completed)
        assertTrue(summary.chat)
        // the tune, then 부릉이 first, in 부릉이's own voice
        assertEquals("intro", t.sounds.first())
        assertEquals("Good afternoon, Harin! Where are you going today?", t.speaker.buddy.first())
        // each turn starts with the "your turn" cue, before anything is heard
        assertEquals(listOf("intro", "listen", "over"), t.sounds.take(3))
        assertEquals(t.talker.turns.size, t.sounds.count { it == "listen" })
        // the child's Korean comes back as a correct sentence to repeat (one voice call with the reaction), then the question
        assertTrue(t.speaker.buddy.contains("Wow, blocks! Say it with me! I played with blocks."))
        assertTrue(t.speaker.buddy.contains("What did you build, Harin?"))
        // listening is in Korean (it also catches mixed speech); names never reach the AI as text
        assertTrue(t.talker.turns.all { it.heard == "오늘 블록 놀이 했어" && it.level == 2 && it.mode == TripMode.FROM_SCHOOL })
        // only the last turn is told to wrap up, and it comes before the time is gone
        assertEquals(listOf(true), t.talker.turns.map { it.last }.distinct().takeLast(1))
        assertEquals(1, t.talker.turns.count { it.last })
        assertTrue(t.talker.turns.size >= 4)
        // the goodbye is the app's, and the whole thing fits the trip with room to spare
        assertEquals(TalkRules.closing("Harin"), t.speaker.buddy.takeLast(2))
        assertTrue("took ${t.seconds}s of a 300s trip", t.seconds in 200..(300 - 20))
        // the parent's report: what was said, the sentence for it, a diary line
        val first = summary.diary.first()
        assertEquals("오늘 블록 놀이 했어", first.heard)
        assertEquals("I played with blocks.", first.recast)
        assertEquals("Harin played with blocks.", first.en)
        assertEquals("ko", first.lang)
        assertEquals(summary.diary.size, summary.hits)
    }

    @Test
    fun theConversationFollowsTheCar() {
        val t = Talk(child = { "좋아" }) { blocks.copy(repeat = null, say = "Nice!", ask = "Do you like puddles?") }
        t.engine.start(Planner.chat(TripMode.TO_SCHOOL, 900, moment))
        t.clock.run(untilMs = 30_000)
        val before = t.talker.turns.size
        assertTrue(t.talker.turns.all { it.events.isEmpty() && !it.scene.raining })
        // the wipers come on: 부릉이 hears about it once, in the very next answer
        t.engine.scene = Scene(outsideC = 9f, raining = true)
        t.engine.tell("It just started raining. The wipers are on.")
        t.clock.run(untilMs = 75_000)
        val after = t.talker.turns.drop(before)
        assertEquals(listOf("It just started raining. The wipers are on."), after.first().events)
        assertTrue(after.first().scene.raining)
        assertTrue(after.drop(1).all { it.events.isEmpty() && it.scene.raining })
        // the navigation says one minute left: the next answer is the last, then goodbye
        t.engine.updateRemaining(80)
        val at = t.clock.time
        t.clock.run()
        assertTrue(t.talker.turns.last().last)
        assertTrue("ended ${(t.clock.time - at) / 1000}s after the warning", t.clock.time - at <= 80_000)
        assertTrue(t.summary.completed)

        // arrival in the middle of talking: goodbye at once, in 부릉이's voice
        val t2 = Talk(child = { "좋아" }) { blocks }
        t2.engine.start(Planner.chat(TripMode.TO_SCHOOL, 900, moment))
        t2.clock.run(untilMs = 40_000)
        t2.engine.arrived("Harin", "하린")
        t2.clock.run()
        assertEquals(TalkRules.arrival("Harin"), t2.speaker.buddy.takeLast(2))
        assertTrue(t2.summary.completed)
    }

    @Test
    fun theAiListensToTheVoiceItself() {
        val plan = Planner.chat(TripMode.FROM_SCHOOL, 300, moment.copy(hour = 16))
        val sound = ByteArray(64) { 1 }

        // Korean and English mixed in one sentence: the words come back with the AI's answer
        val t = Talk(child = { null }, voice = { sound }) { turn ->
            blocks.copy(heard = "Yesterday I went 캠핑 with my friends", ask = if (turn.last) null else blocks.ask)
        }
        t.engine.start(plan)
        t.clock.run()
        assertTrue(t.talker.turns.all { it.audio === sound && it.heard.isEmpty() && !it.silent })
        assertEquals(0, (t.ears as VoiceEars).recognised)
        assertTrue(t.summary.completed)
        assertEquals(t.talker.turns.size, t.summary.hits)
        assertEquals("Yesterday I went 캠핑 with my friends", t.summary.heard.first())
        assertEquals("Yesterday I went 캠핑 with my friends", t.summary.diary.first().heard)
        // from the second turn on, the AI is told what the child said before, as text
        assertTrue(t.talker.turns[1].history.any { it.byChild && it.text == "Yesterday I went 캠핑 with my friends" })

        // nothing recorded, or only noise (the AI hears no words): a silence, and after three a kind goodbye
        var n = 0
        val quiet = Talk(child = { null }, voice = { if (n++ == 0) null else sound }) {
            blocks.copy(heard = "", say = "That's okay!", repeat = null, ask = "Is it sunny or rainy?")
        }
        quiet.engine.start(plan)
        quiet.clock.run()
        assertEquals(0, quiet.summary.hits)
        assertTrue(quiet.summary.diary.isEmpty())
        assertTrue(quiet.speaker.buddy.contains(TalkRules.SILENT_LINE))
        assertTrue(quiet.summary.completed)
    }

    @Test
    fun theNextStepIsSuggestedFromWhatTheChildSaid() {
        fun answer(lang: String, child: String = "Harin") = DiaryEntry(child, "말", null, null, false, null, lang)
        fun day(day: Long, level: Int, english: Int, other: Int) =
            Talked(day, level, List(english) { answer("en") } + List(other) { answer(if (it % 2 == 0) "mixed" else "ko") })

        // 20 answers at step 2 over three days, 13 of them in English only: time to suggest step 3
        val talks = listOf(day(1, 2, 4, 3), day(2, 2, 4, 3), day(3, 2, 5, 1))
        val s = LevelCoach.standing(2, talks, "Harin")
        assertEquals(20, s.answers)
        assertEquals(13, s.english)
        assertEquals(3, s.days)
        assertTrue(s.ready)

        // not yet: too few answers, too little English, too few days, or answers from another step
        assertTrue(!LevelCoach.standing(2, talks.take(2), "Harin").ready)
        assertTrue(!LevelCoach.standing(2, listOf(day(1, 2, 3, 4), day(2, 2, 3, 4), day(3, 2, 3, 3)), "Harin").ready)
        assertTrue(!LevelCoach.standing(2, listOf(day(1, 2, 13, 7)), "Harin").ready)
        assertEquals(0, LevelCoach.standing(3, talks, "Harin").answers)
        // a brother's or sister's answers do not count for this child, silence is not an answer
        val shared = talks + Talked(4, 2, List(20) { answer("en", "Jiho") } + DiaryEntry("Harin", "", null, null, false, null, "en"))
        assertEquals(13, LevelCoach.standing(2, shared, "Harin").english)
        // only the last 20 answers count, and the top step has nowhere to go
        assertEquals(20, LevelCoach.standing(2, talks + day(4, 2, 0, 20), "Harin").answers)
        assertTrue(!LevelCoach.standing(2, talks + day(4, 2, 0, 20), "Harin").ready)
        assertTrue(!LevelCoach.standing(5, listOf(day(1, 5, 7, 0), day(2, 5, 7, 0), day(3, 5, 6, 0)), "Harin").ready)

        // the step the parent picked reaches the AI on every turn, and each step allows longer sentences
        for (n in Levels.MIN..Levels.MAX) {
            val t = Talk(child = { "블록" }) { turn -> if (turn.last) blocks.copy(ask = null) else blocks }
            t.engine.start(Planner.chat(TripMode.FROM_SCHOOL, 120, moment.copy(hour = 16, level = n)))
            t.clock.run()
            assertTrue(t.talker.turns.isNotEmpty() && t.talker.turns.all { it.level == n })
        }
        assertEquals(listOf(5, 6, 8, 10, 12), Levels.all.map { it.maxWords })
        assertTrue(Levels.all.zipWithNext().all { (a, b) -> a.maxRepeat < b.maxRepeat })
        assertEquals(5, TalkRules.words("Do you like blocks? Yes!"))

        // five steps, each with shorter-to-longer sentences; the plan keeps the step it is given
        assertEquals(listOf(1, 2, 3, 4, 5), Levels.all.map { it.n })
        assertEquals(5, Planner.chat(TripMode.TO_SCHOOL, 600, moment.copy(level = 5)).chat?.level)
        assertEquals(5, Planner.chat(TripMode.TO_SCHOOL, 600, moment.copy(level = 9)).chat?.level)
    }

    @Test
    fun aKilometreFromTheDestinationTheTalkWindsDown() {
        val t = Talk(child = { "블록" }) { turn -> if (turn.last) blocks.copy(ask = null) else blocks }
        t.engine.start(Planner.chat(TripMode.FROM_SCHOOL, 600, moment.copy(hour = 16)))
        t.clock.run(untilMs = 60_000)
        val before = t.talker.turns.size
        assertTrue(t.talker.turns.none { it.last || it.nearing })
        t.engine.nearDestination(true)
        t.clock.run()
        // not cut short: two more easy exchanges that wind the talk down, then the last turn and the goodbye
        val after = t.talker.turns.drop(before)
        assertEquals(listOf(true, true, false), after.map { it.nearing })
        assertEquals(listOf(false, false, true), after.map { it.last })
        assertTrue(t.summary.completed)
        assertTrue("ended at ${t.seconds}s", t.seconds < 240)
        assertEquals(7, TalkRules.longest("Breakfast gives you so much energy! What did you eat for breakfast today?"))
    }

    @Test
    fun silenceAndFailuresNeverLeaveDeadAir() {
        val plan = Planner.chat(TripMode.FROM_SCHOOL, 600, moment.copy(hour = 16))

        // a silent child: 부릉이 tries something easier twice, then lets go kindly
        val quiet = Talk(child = { null }) { blocks.copy(say = "That's okay!", repeat = null, ask = "Is it sunny or rainy?") }
        quiet.engine.start(plan)
        quiet.clock.run()
        assertEquals(listOf(true, true), quiet.talker.turns.map { it.silent })
        assertTrue(quiet.speaker.buddy.contains(TalkRules.SILENT_LINE))
        assertTrue(quiet.summary.completed)
        assertTrue(quiet.summary.diary.isEmpty())
        assertEquals(0, quiet.summary.hits)

        // no network: a recorded reaction and a spare question, and after two failures a goodbye
        val offline = Talk(child = { "블록" }) { null }
        offline.engine.start(plan)
        offline.clock.run()
        assertEquals(2, offline.talker.turns.size)
        assertEquals(2, offline.speaker.buddy.count { it == TalkRules.ERROR_LINE })
        assertTrue(offline.speaker.buddy.contains(TalkRules.QUESTIONS.first()))
        assertTrue(offline.summary.completed)

        // an answer that breaks the rules (Korean, far too long) is never spoken
        val bad = TalkReply("블록 놀이 재밌었구나", List(30) { "very" }.joinToString(" "), "너는?", null, null, null, false, false)
        val rude = Talk(child = { "블록" }) { bad }
        rude.engine.start(plan)
        rude.clock.run()
        assertTrue(rude.speaker.buddy.none { "블록" in it || "very very" in it || "너는" in it })
        assertTrue(rude.speaker.buddy.contains(TalkRules.ERROR_LINE))

        // an answer without a question still keeps the talk going, with one of the spare questions
        val t = Talk(child = { "블록" }) { turn -> blocks.copy(ask = null) }
        t.engine.start(Planner.chat(TripMode.FROM_SCHOOL, 200, moment.copy(hour = 16)))
        t.clock.run()
        assertTrue(t.speaker.buddy.contains(TalkRules.QUESTIONS.first()))
        assertTrue(t.talker.turns.size >= 2)

        // a worry is kept for the parent; the talk goes on gently
        val w = Talk(child = { "넘어져서 아팠어" }) { blocks.copy(say = "Oh no. I'm sorry.", repeat = null, ask = "Do you like songs?", worry = true) }
        w.engine.start(Planner.chat(TripMode.FROM_SCHOOL, 200, moment.copy(hour = 16)))
        w.clock.run()
        assertTrue(w.summary.diary.all { it.worry })
        assertTrue(w.summary.diary.size >= 2)
    }

    @Test
    fun aTinyWordFillsTheWaitAndAPauseComesBackWithAWord() {
        val t = Talk(child = { "블록" }) { blocks }
        t.speaker.ready += TalkRules.FILLERS
        t.engine.start(Planner.chat(TripMode.FROM_SCHOOL, 300, moment.copy(hour = 16)))
        t.clock.run(untilMs = 30_000)
        val firstFiller = t.speaker.buddy.indexOf(TalkRules.FILLERS.first())
        assertEquals(1, firstFiller) // right after the opener
        assertTrue(t.sounds.contains("over"))
        // a pause (a phone call) and back: 부릉이 says a word before listening again
        t.engine.pause()
        t.clock.run(untilMs = 100_000)
        t.engine.resume()
        t.clock.run()
        assertTrue(t.speaker.buddy.contains(TalkRules.RESUME_LINE) || t.summary.completed)
        assertTrue(t.summary.completed)
    }

    @Test
    fun severalChildrenTakeTurnsByName() {
        val t = Talk(child = { "강아지 좋아" }) { turn ->
            blocks.copy(repeat = "I like dogs.", say = "Me too! Say it with me!", diaryEn = "{name} likes dogs.",
                ask = if (turn.switch) "{next}, your turn! Do you like cats?" else "What color is it?")
        }
        t.engine.start(Planner.chat(TripMode.FROM_SCHOOL, 400, moment.copy(hour = 16, companions = listOf("Jiho"))))
        t.clock.run()
        val who = t.talker.turns.map { it.child }
        assertEquals(listOf("Harin", "Harin", "Jiho", "Jiho", "Harin"), who.take(5))
        assertEquals(listOf(false, true, false, true), t.talker.turns.take(4).map { it.switch })
        assertTrue(t.speaker.buddy.contains("Jiho, your turn! Do you like cats?"))
        assertTrue(t.speaker.buddy.contains("Harin, your turn! Do you like cats?"))
        assertEquals("Jiho likes dogs.", t.summary.diary[2].en)
        assertEquals(setOf("Harin", "Jiho"), t.summary.diary.map { it.child }.toSet())
    }

    @Test
    fun theDestinationTellsWhereTheFamilyIsGoing() {
        val grandma = Scenarios.GRANDMA
        val plan = Planner.chat(TripMode.TO_SCHOOL, 300, moment.copy(hour = 8), scenario = grandma)
        assertEquals("Good morning, Harin! I see you're going to Grandma's house! What are you going to do there?", plan.chat!!.opener)
        assertEquals("Good afternoon, Harin! I see you're going to see the doctor. How are you feeling today?", Scenarios.HOSPITAL.opener("Harin", 15))
        val t = Talk(child = { "할머니랑 놀 거야" }) { blocks }
        t.engine.start(plan)
        t.clock.run()
        assertEquals(plan.chat!!.opener, t.speaker.buddy.first())
        // every turn tells the AI the kind of place, never the destination's name; no sentences to practise
        assertTrue(t.talker.turns.isNotEmpty())
        assertTrue(t.talker.turns.all { it.situation == grandma.situation && it.targets.isEmpty() })
        assertEquals("grandma", t.summary.scenarioId)
        // without a destination the child is asked, and the AI is told nothing about where
        val free = Talk(child = { "유치원" }) { blocks }
        free.engine.start(Planner.chat(TripMode.FROM_SCHOOL, 200, moment.copy(hour = 16)))
        free.clock.run()
        assertEquals("Good afternoon, Harin! Where are you going today?", free.speaker.buddy.first())
        assertTrue(free.talker.turns.all { it.situation == null && it.targets.isEmpty() })
        assertEquals(null, free.summary.scenarioId)
        // the navigation's destination suggests the situation
        assertEquals(Scenarios.GRANDMA, Scenarios.forDestination("우리 할머니댁"))
        assertEquals(Scenarios.TRIP, Scenarios.forDestination("인천국제공항 제1터미널"))
        assertEquals(Scenarios.OUTING, Scenarios.forDestination("서울대공원"))
        assertEquals(Scenarios.MARKET, Scenarios.forDestination("이마트 성수점"))
        assertEquals(Scenarios.HOSPITAL, Scenarios.forDestination("튼튼소아과의원"))
        assertEquals(Scenarios.FRIEND, Scenarios.forDestination("뽀로로 키즈카페"))
        assertEquals(Scenarios.OUTING, Scenarios.forDestination("에버랜드"))
        // a name that tells nothing (Grandma's house found by the school next to it): the parent picks the card
        assertEquals(null, Scenarios.forDestination("한빛중학교"))
        assertEquals(null, Scenarios.forDestination("햇살유치원"))
        assertEquals(null, Scenarios.forDestination(null))
    }

    @Test
    fun aPauseIsNotCountedAsTalkingTime() {
        val t = Talk(child = { "블록" }) { blocks }
        t.engine.start(Planner.chat(TripMode.FROM_SCHOOL, 300, moment.copy(hour = 16)))
        t.clock.run(untilMs = 30_000)
        // three hours paused (a phone call, or a laptop closed on the emulator)
        t.engine.pause()
        t.clock.run(untilMs = 3 * 3600_000L)
        t.engine.resume()
        t.clock.run()
        assertTrue(t.summary.completed)
        assertTrue("wall clock ${t.seconds}s", t.seconds > 3 * 3600)
        assertTrue("talking ${t.summary.activeSec}s", t.summary.activeSec in 25..120)
        // without a pause, talking time is the trip
        val u = Talk(child = { "블록" }) { blocks }
        u.engine.start(Planner.chat(TripMode.FROM_SCHOOL, 300, moment.copy(hour = 16)))
        u.clock.run()
        assertTrue("talking ${u.summary.activeSec}s of ${u.seconds}s", u.summary.activeSec >= u.seconds - 2 && u.summary.activeSec <= u.seconds)
    }

    @Test
    fun namesAreHiddenBeforeTheChatLeavesTheCar() {
        val kids = TalkRules.companions("Jiho(지호), Minjun , , harin2(하린이)/Seo-yeon（서연）, Extra")
        assertEquals(listOf("Jiho" to "지호", "Minjun" to null, "harin2" to "하린이"), kids)
        val names = mapOf("Harin" to listOf("Harin", "하린"), "Jiho" to listOf("Jiho", "지호"))
        assertEquals("{name}이랑 친구랑 놀았어", TalkRules.mask("하린이랑 지호랑 놀았어", "Harin", names))
        assertEquals("친구이가 {name} 블록 줬어", TalkRules.mask("하린이가 지호 블록 줬어", "Jiho", names))
        assertEquals("I played with {name}", TalkRules.mask("I played with jiho", "Jiho", names))
        // clean() fills the names back in and keeps only short, English, kind lines
        val cleaned = TalkRules.clean(blocks.copy(ask = "{next}, your turn!"), "Harin", "Jiho")!!
        assertEquals("Wow, blocks! Say it with me!", cleaned.say)
        assertEquals("Jiho, your turn!", cleaned.ask)
        assertEquals("Harin played with blocks.", cleaned.diaryEn)
        assertEquals(null, TalkRules.clean(blocks.copy(say = "I hate you", repeat = "너무 좋아", ask = "{who} are you?"), "Harin"))
        // a line that fails is dropped on its own; the rest is still used
        val part = TalkRules.clean(blocks.copy(say = "블록!", lang = "KO"), "Harin")!!
        assertEquals(null, part.say)
        assertEquals("I played with blocks.", part.repeat)
        assertEquals("ko", part.lang)
    }

    @Test
    fun aLessonPlaysToTheEndAndCountsAnswers() {
        val (e, clock, rest) = engine { hints -> hints.first() }
        val (speaker, events) = rest
        val plan = Planner.plan(episode, TripMode.TO_SCHOOL, 600, moment, emptyList())
        e.start(plan)
        clock.run()
        val summary = events.finished
        assertNotNull(summary)
        assertTrue(summary!!.completed)
        assertEquals(summary.asks, summary.hits)
        assertTrue(summary.asks > 8)
        assertEquals(words.map { it.id }.toSet(), summary.cardsShown.toSet())
        assertTrue(speaker.said.any { "Great job" in it || "Wonderful" in it || "Super" in it })
        // the estimate is in the right range of the real (fake-clock) length
        val real = (summary.endedAtMs - summary.startedAtMs) / 1000
        assertTrue("real $real vs estimate ${plan.estimateSec}", real in (plan.estimateSec * 0.6).toLong()..(plan.estimateSec * 1.2).toLong())
    }

    @Test
    fun anotherAnswerGetsARecastNotAWrong() {
        val (e, clock, rest) = engine { "banana" }
        val (speaker, events) = rest
        e.start(Planner.plan(episode, TripMode.TO_SCHOOL, 600, moment, emptyList()))
        clock.run()
        assertTrue(events.results.contains(AnswerResult.RECAST))
        assertTrue(speaker.said.any { it.startsWith("Ooh, ") && "say it together" in it })
        assertTrue(speaker.said.none { "wrong" in it.lowercase() || "no!" == it.lowercase() })
    }

    @Test
    fun nearArrivalTheLessonJumpsToGoodbye() {
        val (e, clock, rest) = engine { hints -> hints.first() }
        val (_, events) = rest
        e.start(Planner.plan(episode, TripMode.TO_SCHOOL, 600, moment, emptyList()))
        clock.run(untilMs = 8_000)
        assertEquals(BlockType.HELLO, e.currentBlock?.type)
        e.updateRemaining(60)
        clock.run(untilMs = 120_000)
        val summary = events.finished
        assertNotNull("finished within two minutes of the warning", summary)
        assertTrue(BlockType.EXPRESSION !in summary!!.blocksDone)
    }

    @Test
    fun arrivalSaysGoodbyeAtOnce() {
        val (e, clock, rest) = engine { hints -> hints.first() }
        val (speaker, events) = rest
        e.start(Planner.plan(episode, TripMode.TO_SCHOOL, 600, moment, emptyList()))
        clock.run(untilMs = 60_000)
        e.arrived("Harin", "하린")
        clock.run()
        assertTrue(events.finished!!.completed)
        assertTrue(speaker.said.any { "We're here" in it })
    }

    @Test
    fun pauseHoldsAndResumeContinues() {
        val (e, clock, rest) = engine { hints -> hints.first() }
        val (speaker, events) = rest
        e.start(Planner.plan(episode, TripMode.TO_SCHOOL, 600, moment, emptyList()))
        clock.run(untilMs = 10_000)
        e.pause()
        val count = speaker.said.size
        clock.run(untilMs = 200_000)
        assertEquals(count, speaker.said.size)
        assertEquals(EngineState.PAUSED, e.state)
        e.resume()
        clock.run()
        assertTrue(events.finished!!.completed)
    }

    @Test
    fun aTrafficJamAddsTheStoryAndShortTimeDropsTheSong() {
        val (e, clock, _) = engine { hints -> hints.first() }
        val plan = Planner.plan(episode, TripMode.TO_SCHOOL, 290, moment, emptyList())
        assertTrue(plan.blocks.none { it.type == BlockType.STORY })
        e.start(plan)
        clock.run(untilMs = 5_000)
        e.updateRemaining(30 * 60)
        assertTrue(e.blocks.any { it.type == BlockType.STORY })

        val (e2, clock2, _) = engine { hints -> hints.first() }
        e2.start(Planner.plan(episode, TripMode.TO_SCHOOL, 600, moment, emptyList()))
        clock2.run(untilMs = 5_000)
        // a little less time: the extras go first, the core song stays
        e2.updateRemaining(e2.restEstimate() - 40)
        assertTrue(e2.blocks.none { it.type == BlockType.STORY })
        assertTrue(e2.blocks.any { it.type == BlockType.CHANT && !it.extra })
        // much less time: the song goes too
        e2.updateRemaining(e2.restEstimate() - 120)
        assertTrue(e2.blocks.none { it.type == BlockType.CHANT })
        assertTrue(e2.blocks.any { it.type == BlockType.EXPRESSION })
    }
}
