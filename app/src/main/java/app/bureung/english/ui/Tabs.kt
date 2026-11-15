package app.bureung.english.ui

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.Toast
import app.bureung.english.Hub
import app.bureung.english.LessonService
import app.bureung.english.lesson.Lang
import app.bureung.english.lesson.LevelCoach
import app.bureung.english.lesson.Levels
import app.bureung.english.lesson.Srs
import app.bureung.english.lesson.TripMode
import app.bureung.english.lesson.Word
import app.bureung.english.lesson.WordState
import app.bureung.english.lesson.Scenario
import app.bureung.english.lesson.Scenarios
import app.bureung.english.store.ModeChoice
import app.bureung.english.store.VoiceChoice

/** The right-hand tabs of the parked screen: situations to practise, today's sentences, the parent report, settings. */
class Tabs(private val a: MainActivity) {

    /** Changes when a tab's content would change; the screen rebuilds the tab only then (typing is never lost). */
    fun stamp(tab: MainActivity.Tab): String {
        if (!Hub.started) return "${tab.name}:waiting"
        return when (tab) {
            MainActivity.Tab.WORDS -> {
                val plan = Hub.engine.plan?.takeIf { Hub.engine.isActive }
                "W:${plan?.episode?.id}:${plan?.reviewIds}:${Hub.engine.lastCard?.id}:${Hub.progress.words().hashCode()}:${Hub.progress.lessons().size}"
            }
            MainActivity.Tab.REPORT -> "R:${Hub.progress.lessons().size}:${Hub.today()}:${Hub.progress.words().hashCode()}"
            // the AI test result shows up without a tap; nothing else rebuilds settings while the parent types
            MainActivity.Tab.SETTINGS -> "S:${Hub.talkTest}:${Hub.levelTest}"
        }
    }

    fun build(tab: MainActivity.Tab, body: LinearLayout) {
        if (!Hub.started) {
            body.addView(a.text("권한을 허용하면 이곳에 오늘의 표현과 리포트가 보여요.", 17, Palette.SUB), matchWidth())
            return
        }
        when (tab) {
            MainActivity.Tab.WORDS -> words(body)
            MainActivity.Tab.REPORT -> report(body)
            MainActivity.Tab.SETTINGS -> settings(body)
        }
    }

    private fun title(body: LinearLayout, value: String, caption: String? = null, top: Int = 4) {
        body.addView(a.vbox(4).apply {
            addView(a.text(value, 20, bold = true), matchWidth())
            caption?.let { addView(a.text(it, 14, Palette.SUB), matchWidth()) }
        }.lp(matchWidth(), top = top))
    }

    // --- words -----------------------------------------------------------------------------------------------------

    private fun words(body: LinearLayout) {
        val plan = Hub.engine.plan?.takeIf { Hub.engine.isActive }
        val today = Hub.today()
        val entries = Hub.progress.lessons().filter { it.day == today }.flatMap { it.diary }.filter { it.recast != null }
        val practised = entries.mapNotNull { it.recast }.distinct()
        val coming = Hub.progress.duePhrases(today).filter { it !in practised }

        run {
            title(body, "오늘 연습한 문장", "아이가 한 말을 부릉이가 바른 영어로 바꿔 준 문장이에요. 정차 중에 누르면 다시 들려줘요")
            if (practised.isEmpty()) {
                body.addView(a.card(24).apply {
                    addView(a.text("아직 없어요. 대화를 하면 여기에 쌓여요.", 16, Palette.SUB), matchWidth())
                }, matchWidth())
            }
            practised.reversed().forEach { sentence ->
                val from = entries.lastOrNull { it.recast == sentence }
                body.addView(phraseCard(sentence, from?.heard?.let { "아이가 한 말: “$it”" }), matchWidth())
            }
            if (coming.isNotEmpty()) {
                title(body, "다시 만날 문장", "예전 대화의 문장이에요. 다음 대화에서 부릉이가 자연스럽게 다시 꺼내요", top = 12)
                coming.forEach { body.addView(phraseCard(it, null), matchWidth()) }
            }
        }

        // the recorded lesson's word cards, while one is playing (when the AI conversation is not available)
        if (plan != null && plan.chat == null) {
            val states = Hub.progress.words()
            val fresh = plan.episode.words
            val review = plan.reviewIds.mapNotNull { plan.words[it] }.filter { r -> fresh.none { it.id == r.id } }
            title(body, "오늘의 단어", "정차 중에 카드를 누르면 발음을 들려줘요")
            grid(body, fresh, states)
            if (review.isNotEmpty()) {
                title(body, "다시 만나는 단어", "예전에 배운 단어를 수수께끼로 복습해요", top = 12)
                grid(body, review, states)
            }
        }
    }

    private fun phraseCard(sentence: String, sub: String?): View = a.card(22, 4).apply {
        addView(a.text(sentence, 21, Palette.INK, bold = true), matchWidth())
        sub?.let { addView(a.text(it, 14, Palette.SUB), matchWidth()) }
        isClickable = true
        setOnClickListener { Hub.sayPhrase(sentence) }
    }

    private fun grid(body: LinearLayout, words: List<Word>, states: Map<String, WordState>) {
        words.chunked(3).forEach { row ->
            val line = a.hbox(14)
            row.forEach { w -> line.addView(wordCard(w, states[w.id]), weightWrap(1f)) }
            repeat(3 - row.size) { line.addView(View(a), weightWrap(1f)) }
            body.addView(line, matchWidth())
        }
    }

    private fun wordCard(w: Word, state: WordState?): View {
        val now = Hub.engine.isActive && Hub.engine.lastCard?.id == w.id
        val (status, color) = when {
            state == null -> "새 단어" to Palette.BLUE
            state.mastered -> "다 외웠어요" to Palette.GOOD
            else -> "복습 ${state.stage}/${Srs.GAPS.size}" to Palette.SUB
        }
        return a.vbox(4).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            background = a.rounded(Palette.CARD, 20, if (now) Palette.ACTIVE else null, 2)
            setPadding(a.dp(12), a.dp(20), a.dp(12), a.dp(18))
            addView(a.text(w.emoji, 46).apply { gravity = Gravity.CENTER }, wrap())
            addView(a.text(w.en, 21, bold = true, lines = 1).apply { gravity = Gravity.CENTER }.lp(wrap(), top = 6))
            addView(a.text(w.ko, 15, Palette.SUB, lines = 1).apply { gravity = Gravity.CENTER }, wrap())
            addView(a.tag(status, color, 13).apply { gravity = Gravity.CENTER }.lp(wrap(), top = 6))
            isClickable = true
            setOnClickListener { Hub.sayWord(w) }
        }
    }

    // --- report ----------------------------------------------------------------------------------------------------

    private fun report(body: LinearLayout) {
        val today = Hub.today()
        val all = Hub.progress.lessons()
        val todays = all.filter { it.day == today }
        val minutes = (todays.sumOf { it.seconds } + 30) / 60
        val spoke = todays.sumOf { it.hits }
        val diary = todays.flatMap { it.diary }
        val english = diary.count { it.lang == "en" || it.lang == "mixed" }

        a.levelUpCard()?.let { body.addView(it, matchWidth()) }
        title(body, "오늘 ${Hub.progress.nameKo}의 영어")
        body.addView(a.card(22).apply {
            addView(a.hbox().apply {
                addView(stat("${todays.size}번", "대화"), weightWrap(1f))
                addView(stat("${minutes}분", "이야기한 시간"), weightWrap(1f))
                addView(stat("${spoke}번", "아이가 말한 횟수"), weightWrap(1f))
                addView(stat(if (diary.isEmpty()) "-" else "$english/${diary.size}", "영어를 써서 대답"), weightWrap(1f))
            }, matchWidth())
        }, matchWidth())

        // where the child stands at this step, from the answers so far
        val standing = Hub.progress.levelStanding()
        val step = Levels.of(standing.level)
        body.addView(a.card(22, 6).apply {
            addView(a.text("Level ${step.n} ${step.nameKo} · ${step.agesKo}", 18, bold = true), matchWidth())
            addView(a.text(
                if (standing.level >= Levels.MAX) "가장 높은 단계예요. 이유와 생각을 붙여 말하는 대화를 해요."
                else "이 단계의 최근 대답 ${standing.answers}번 중 ${standing.english}번을 영어로만 말했어요(${standing.days}일). " +
                    "${LevelCoach.WINDOW}번 중 ${LevelCoach.NEED}번이 되면 Level ${step.n + 1}을 권해 드려요.",
                15, Palette.INK2,
            ), matchWidth())
        }, matchWidth())

        week(body, all, today)
        diary(body, diary)

        val covered = diary.map { it.heard }.toSet()
        val heard = todays.flatMap { it.heard }.distinct().filter { it !in covered }.takeLast(12)
        if (heard.isNotEmpty()) {
            body.addView(a.card(24, 4).apply {
                addView(a.text("아이가 한 말", 18, bold = true).lp(matchWidth(), bottom = 6))
                heard.reversed().forEach { addView(a.text("“$it”", 17, Palette.INK2).lp(matchWidth(), top = 4)) }
            }, matchWidth())
        }

        val states = Hub.progress.words()
        val upcoming = states.entries.filter { !it.value.mastered }.sortedBy { it.value.due }.take(12)
        body.addView(a.card(24).apply {
            addView(a.text("다음 복습", 18, bold = true).lp(matchWidth(), bottom = 6))
            if (upcoming.isEmpty()) {
                addView(a.text("연습한 문장은 그날 다음 대화, 3일 뒤, 7일 뒤 대화에서 부릉이가 다시 꺼내요.", 15, Palette.SUB), matchWidth())
            }
            upcoming.groupBy { it.value.due }.forEach { (due, list) ->
                val label = when (val d = due - today) {
                    in Long.MIN_VALUE..0L -> "오늘"
                    1L -> "내일"
                    else -> "${d}일 뒤"
                }
                val names = list.joinToString("\n") { e -> Hub.episodes.word(e.key)?.en ?: e.key.removePrefix(app.bureung.english.store.Progress.PHRASE) }
                addView(a.row(label, names), matchWidth())
            }
            val mastered = states.values.count { it.mastered }
            if (mastered > 0) addView(a.row("몸에 밴 문장·단어", "${mastered}개", Palette.GOOD), matchWidth())
        }, matchWidth())

        body.addView(a.card(24).apply {
            addView(a.text("최근 기록", 18, bold = true).lp(matchWidth(), bottom = 6))
            if (all.isEmpty()) addView(a.text("첫 대화를 기다리고 있어요.", 15, Palette.SUB), matchWidth())
            all.takeLast(6).reversed().forEach { r ->
                val day = when (today - r.day) { 0L -> "오늘"; 1L -> "어제"; else -> "${today - r.day}일 전" }
                val mode = if (r.mode == TripMode.TO_SCHOOL) "오전" else "오후"
                val what = if (r.chat) "${Scenarios.byId(r.scenarioId)?.placeKo ?: "대화"} · ${(r.seconds + 30) / 60}분 · ${r.hits}번 말함"
                else "${r.title} · ${(r.seconds + 30) / 60}분" + if (r.asks == 0) "" else " · ${r.hits}/${r.asks}"
                addView(a.row("$day $mode", what), matchWidth())
            }
        }, matchWidth())

        body.addView(a.card(24, 6).apply {
            addView(a.text("아이 목소리는 저장하지 않아요", 17, bold = true), matchWidth())
            addView(a.text("아이가 대답한 목소리는 Google Gemini로 보내 알아듣는 데만 쓰고, 앱은 저장하지 않아요. 글자 기록은 이 기기에만 남아요. 목적지와 위치는 어디로도 보내지 않아요.", 15, Palette.SUB), matchWidth())
        }, matchWidth())

        body.addView(a.card(24, 6).apply {
            background = a.rounded(Palette.BLUE_SOFT, 24)
            addView(a.text("부릉부릉 플러스", 17, bold = true), matchWidth())
            addView(a.text("하루 10분 대화는 무료예요. 구독하면 시간 제한 없는 대화, 형제·친구와 함께 대화, 주간 성장 리포트가 열려요(AI 사용료는 구독료에 들어 있어요). 출품 버전은 모두 열려 있어요.", 15, Palette.INK2), matchWidth())
        }, matchWidth())
    }

    /** Minutes of English per day over the last week, as seven small bars. */
    private fun week(body: LinearLayout, all: List<app.bureung.english.store.LessonRecord>, today: Long) {
        val days = (6 downTo 0).map { today - it }
        val minutes = days.map { d -> (all.filter { it.day == d }.sumOf { it.seconds } + 30) / 60 }
        val top = (minutes.maxOrNull() ?: 0).coerceAtLeast(1)
        val names = listOf("월", "화", "수", "목", "금", "토", "일")
        body.addView(a.card(24).apply {
            addView(a.hbox().apply {
                addView(a.text("이번 주", 18, bold = true), weightWrap(1f))
                addView(a.text("${minutes.sum()}분 · ${minutes.count { it > 0 }}일", 15, Palette.SUB), wrap())
            }.lp(matchWidth(), bottom = 10))
            addView(a.hbox(8).apply {
                gravity = Gravity.BOTTOM
                days.forEachIndexed { i, d ->
                    addView(a.vbox(6).apply {
                        gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
                        val bar = View(a).apply { background = a.rounded(if (minutes[i] > 0) Palette.ACTIVE else Palette.LINE, 6) }
                        addView(bar, LinearLayout.LayoutParams(a.dp(26), a.dp(6 + 54 * minutes[i] / top)))
                        val label = if (d == today) "오늘" else names[java.time.LocalDate.ofEpochDay(d).dayOfWeek.value - 1]
                        addView(a.text(label, 13, if (d == today) Palette.INK else Palette.SUB, bold = d == today).apply { gravity = Gravity.CENTER }, wrap())
                    }, weightWrap(1f))
                }
            }, matchWidth())
        }, matchWidth())
    }

    /** The day's conversation: what the child told, the English sentence for it, and what the child actually said. */
    private fun diary(body: LinearLayout, entries: List<app.bureung.english.lesson.DiaryEntry>) {
        body.addView(a.card(24, 4).apply {
            addView(a.text("오늘 나눈 이야기", 18, bold = true), matchWidth())
            if (entries.isEmpty()) {
                addView(a.text("아직 없어요. 부릉이와 이야기하면 아이가 한 말과 바른 영어 문장이 여기에 남아요.", 15, Palette.SUB), matchWidth())
                return@apply
            }
            val several = entries.map { it.child }.distinct().size > 1
            entries.takeLast(10).forEach { d ->
                addView(a.vbox(2).apply {
                    if (d.worry) {
                        background = a.rounded(Palette.RED_SOFT, 14)
                        setPadding(a.dp(14), a.dp(10), a.dp(14), a.dp(10))
                    } else {
                        setPadding(0, a.dp(8), 0, a.dp(8))
                    }
                    val who = if (several) "${d.child} · " else ""
                    if (d.worry) addView(a.tag("보호자 확인이 필요해요", Palette.RED, 13), wrap())
                    addView(a.text(who + (d.ko ?: d.en ?: "이야기를 나눴어요"), 17, Palette.INK), matchWidth())
                    (d.recast ?: d.en)?.let { addView(a.text(it, 16, Palette.BLUE, bold = true), matchWidth()) }
                    addView(a.text("아이가 한 말: “${d.heard}”", 14, Palette.SUB), matchWidth())
                }.lp(matchWidth(), top = 4))
            }
        }, matchWidth())
    }

    private fun stat(value: String, label: String): View = a.vbox(4).apply {
        gravity = Gravity.CENTER_HORIZONTAL
        addView(a.text(value, 26, bold = true).apply { gravity = Gravity.CENTER }, wrap())
        addView(a.text(label, 14, Palette.SUB).apply { gravity = Gravity.CENTER }, wrap())
    }

    // --- settings --------------------------------------------------------------------------------------------------

    private fun settings(body: LinearLayout) {
        val p = Hub.progress

        body.addView(group("검증 모드") {
            addView(a.text("책상에서 대화를 끝까지 시험해 보는 화면이에요. 아이의 대답을 타이핑으로 넣고, 기어·와이퍼·바깥 온도·내비 같은 차량 정보를 손으로 바꿀 수 있어요. 부릉이의 말은 단어 수와 함께 글로 남아요.", 14, Palette.SUB), matchWidth())
            addView(a.button("검증 모드 켜기", ButtonStyle.PRIMARY, 16) { Hub.verifyMode(true) }.lp(matchWidth(), top = 8))
        }, matchWidth())

        body.addView(group("아이") {
            addView(field("이름 (한국어)", p.nameKo) { p.nameKo = it }, matchWidth())
            addView(field("부릉이가 영어로 부를 이름", p.nameEn) { p.nameEn = it }.lp(matchWidth(), top = 12))
            addView(choices("영어 단계 (5~10세)", Levels.all.map { it.n to "${it.n} ${it.nameKo}" }, p.level) {
                p.level = it
                true
            }.lp(matchWidth(), top = 12))
            val l = Levels.of(p.level)
            addView(a.text("${l.agesKo} · ${l.childKo}\n부릉이: ${l.buddyKo} · 질문: ${l.questionKo} · 따라 말할 문장: ${l.repeatKo}\n예: ${l.example}", 14, Palette.SUB), matchWidth())
            val s = p.levelStanding()
            addView(a.text(
                if (s.level >= Levels.MAX) "가장 높은 단계예요."
                else "다음 단계 권유: 이 단계의 최근 ${LevelCoach.WINDOW}번 대답 중 ${LevelCoach.NEED}번 이상을 영어로만 말하면(서로 다른 ${LevelCoach.MIN_DAYS}일 이상) 알려 드려요. 지금 ${s.english}/${s.answers}번, ${s.days}일.",
                14, Palette.SUB,
            ), matchWidth())
            addView(field("같이 타는 아이 (영어 이름(한국어 이름), 쉼표로 · 최대 3명)", p.companions) { p.companions = it }.lp(matchWidth(), top = 12))
            addView(a.text("예: Jiho(지호), Minjun(민준). 부릉이가 이름을 불러 차례로 물어봐요.", 14, Palette.SUB), matchWidth())
        }, matchWidth())

        body.addView(group("대화") {
            addView(toggle("부릉이와 영어 대화 (AI)", p.aiTalk) {
                p.aiTalk = it
                if (it && !p.listenConsent) {
                    p.listenConsent = true
                    a.askMicrophone()
                }
                a.refreshTab()
            }, matchWidth())
            addView(toggle("아이 말 듣기 (보호자 동의)", p.listenConsent) {
                p.listenConsent = it
                if (it) a.askMicrophone()
                a.refreshTab()
            }, matchWidth())
            addView(a.text(
                "한국어와 영어를 섞어 말해도 알아듣도록, 아이가 대답한 목소리를 AI(Google Gemini)로 보내요. 알아듣는 데만 쓰고 앱은 저장하지 않아요. " +
                    "둘 중 하나라도 끄면 녹음 수업으로 진행해요.",
                14, Palette.SUB,
            ), matchWidth())
            // only for the in-car recogniser (the fallback): the AI itself understands both languages at once
            if (!Hub.ears.hearsVoice) addView(choices("아이가 대답하는 말", listOf(false to "한국어 섞어서", true to "영어로만"), p.listenEnglish) {
                p.listenEnglish = it
                true
            }.lp(matchWidth(), top = 12))
            addView(choices("응답 속도", listOf(false to "기본", true to "빠름"), p.fastReply) {
                Hub.setFastReply(it)
                true
            }.lp(matchWidth(), top = 12))
            addView(a.text("빠름은 더 가벼운 AI와 목소리 모델을 써서 부릉이가 빨리 대답해요. 말이 조금 덜 자연스러울 수 있어요. 지금: AI ${Hub.talker.modelName} · 목소리 ${Hub.buddy?.modelName ?: "없음"}", 14, Palette.SUB), matchWidth())
            addView(choices("아이 말을 기다리는 시간", listOf(2 to "2초", 3 to "3초", 5 to "5초"), p.pauseSec) {
                p.pauseSec = it
                true
            }.lp(matchWidth(), top = 12))
            addView(a.text("띵동(↗) 소리가 나면 아이 차례예요. 이만큼 조용하면 띵동(↘) 소리와 함께 부릉이 차례로 넘어가요.", 14, Palette.SUB), matchWidth())
            addView(choices("길안내가 없을 때 대화 길이", listOf(5 to "5분", 10 to "10분", 15 to "15분"), p.defaultTripMin) {
                p.defaultTripMin = it
                true
            }.lp(matchWidth(), top = 12))
            addView(a.text("길안내가 있으면 도착 시간에 맞춰 부릉이가 알아서 마무리해요.", 14, Palette.SUB), matchWidth())
            addView(toggle("차량 운행 시 자동 시작", p.autoStart) {
                p.autoStart = it
                if (it) LessonService.start(a) else if (!Hub.engine.isActive) LessonService.stop(a)
            }.lp(matchWidth(), top = 8))
            addView(a.text("켜 두면 시동을 걸고 출발할 때(기어 D)마다 부릉이가 3초 뒤에 말을 걸어요. 꺼 두면 '대화 시작'을 누를 때만 시작해요.", 14, Palette.SUB), matchWidth())
            addView(a.row("AI 대화 상태", Hub.talkBlocker() ?: "준비됨", if (Hub.talkReady()) Palette.GOOD else Palette.SUB).lp(matchWidth(), top = 8))
            addView(a.row("모델", Hub.talker.modelName), matchWidth())
            addView(a.button("AI 대화 시험", ButtonStyle.SECONDARY, 15) { Hub.testTalk() }.lp(matchWidth(), top = 10))
            Hub.talkTest?.let { addView(a.text(it, 14, Palette.INK2).lp(matchWidth(), top = 8)) }
            addView(a.button("단계별 말하기 시험 (AI 5번 호출)", ButtonStyle.SECONDARY, 15) { Hub.testLevels() }.lp(matchWidth(), top = 10))
            addView(a.text("같은 대답을 1~5단계로 보내, 부릉이의 질문과 따라 말할 문장이 단계마다 달라지는지 단어 수로 확인해요.", 14, Palette.SUB), matchWidth())
            Hub.levelTest?.let { addView(a.text(it, 14, Palette.INK2).lp(matchWidth(), top = 8)) }
        }, matchWidth())

        body.addView(group("목소리") {
            addView(choices(
                "녹음 수업·한국어 안내 목소리 엔진",
                listOf(VoiceChoice.AUTO to "자동", VoiceChoice.PLEOS to "Pleos 우선", VoiceChoice.ANDROID to "시스템 우선"),
                p.voiceChoice,
            ) {
                p.voiceChoice = it
                true
            }, matchWidth())
            addView(a.text("부릉이 목소리는 Gemini TTS로 만들어요. 만들 수 없을 때는 앱에 든 녹음 목소리가 대신해요.", 14, Palette.SUB), matchWidth())
        }, matchWidth())

        body.addView(group("상태") {
            val car = Hub.car
            addView(a.row("영어 목소리", Hub.voice.engineFor(Lang.EN)), matchWidth())
            addView(a.row("한국어 목소리", Hub.voice.engineFor(Lang.KO)), matchWidth())
            addView(a.row("Pleos TTS", Hub.voice.pleos.status()), matchWidth())
            addView(a.row("시스템 TTS", Hub.voice.system.status()), matchWidth())
            addView(a.row("녹음 파일", "${Hub.voice.clips.count}개"), matchWidth())
            addView(a.row("듣기", Hub.ears.status()), matchWidth())
            addView(a.text(Hub.micReport(), 14, Palette.INK2), matchWidth())
            addView(a.row("기어", "${car.gear}${if (car.demoGear != null) " (시연)" else ""}"), matchWidth())
            addView(a.row("와이퍼", if (car.raining) "작동 중" else "멈춤"), matchWidth())
            addView(a.row("내비", if (car.navi.connected) "연결됨" else "연결 안 됨"), matchWidth())
            addView(a.row("목적지", car.destination?.let { "${it.name} · ${MainActivity.minutes(it.remainNow())}분" } ?: "없음"), matchWidth())
            addView(a.row("바깥 온도", car.outsideCelsius()?.let { "${it.toInt()}°C" } ?: "모름"), matchWidth())
            addView(a.button("새로고침", ButtonStyle.SECONDARY, 15) { a.refreshTab() }.lp(matchWidth(), top = 10))
        }, matchWidth())

        body.addView(group("시연 도구") {
            addView(a.text("영상 촬영용이에요. 쓰는 동안 수업 카드에 '시연 도구 사용 중'이 보여요.", 14, Palette.SUB), matchWidth())
            val car = Hub.car
            val demo = listOf<Pair<String, () -> Unit>>(
                "주행 (D)" to { car.demoSetDriving(true) },
                "정차 (P)" to { car.demoSetDriving(false) },
                "해님유치원 10분" to { car.demoRoute("해님유치원", 600) },
                "우리집 8분" to { car.demoRoute("우리집", 480) },
                if (car.demoRain) "비 그치기" to { car.demoSetRain(false) } else "비 오기" to { car.demoSetRain(true) },
                // 2 minutes at the demo pace of 8 m/s is 960 m: inside the last kilometre, so 부릉이 wraps up
                "도착 1km 전" to { car.demoRoute(car.destination?.name ?: "우리집", 120) },
                "도착" to { car.demoArrive() },
                "실제 신호로" to { car.clearDemo() },
            )
            demo.chunked(2).forEach { row ->
                addView(a.hbox(10).apply {
                    row.forEach { (label, act) ->
                        addView(a.button(label, ButtonStyle.SECONDARY, 15) { act(); a.refreshTab() }, weightWrap(1f))
                    }
                }.lp(matchWidth(), top = 10))
            }
            addView(a.button("학습 기록 지우기", ButtonStyle.GHOST, 15) {
                Hub.progress.resetLearning()
                a.refreshTab()
            }.apply { setTextColor(Palette.RED) }.lp(matchWidth(), top = 10))
        }, matchWidth())

        body.addView(group("기록") {
            addView(a.text(Hub.log.toList().takeLast(14).joinToString("\n"), 12, Palette.SUB).apply {
                typeface = Typeface.MONOSPACE
            }, matchWidth())
        }, matchWidth())
    }

    private fun group(name: String, content: LinearLayout.() -> Unit): View = a.card(24, 6).apply {
        addView(a.text(name, 18, bold = true).lp(matchWidth(), bottom = 6))
        content()
    }

    private fun field(label: String, value: String, save: (String) -> Unit): View = a.vbox(6).apply {
        addView(a.text(label, 14, Palette.SUB), matchWidth())
        addView(EditText(a).apply {
            setText(value)
            textSize = 18f * Ui.scale
            setTextColor(Palette.INK)
            inputType = InputType.TYPE_CLASS_TEXT
            isSingleLine = true
            background = a.rounded(Palette.FILL, 14)
            setPadding(a.dp(16), a.dp(14), a.dp(16), a.dp(14))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) = save(s?.toString().orEmpty())
            })
        }, matchWidth())
    }

    private fun toggle(label: String, on: Boolean, change: (Boolean) -> Unit): View = a.hbox(12).apply {
        setPadding(0, a.dp(6), 0, a.dp(6))
        addView(a.text(label, 17), weightWrap(1f))
        addView(Switch(a).apply {
            isChecked = on
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            trackTintList = ColorStateList(states, intArrayOf(Palette.ACTIVE, Palette.LINE))
            thumbTintList = ColorStateList(states, intArrayOf(Palette.WHITE, Palette.WHITE))
            setOnCheckedChangeListener { _, checked -> change(checked) }
        }, wrap())
    }

    /** A labelled segmented control; [pick] returns false to keep the old choice. */
    private fun <T> choices(label: String, options: List<Pair<T, String>>, current: T, pick: (T) -> Boolean): View =
        a.vbox(8).apply {
            addView(a.text(label, 14, Palette.SUB), matchWidth())
            addView(a.segmented(options, current, 15) { if (pick(it)) a.refreshTab() }, matchWidth())
        }
}
