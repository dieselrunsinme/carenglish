package app.bureung.english.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.Toast
import android.text.InputType
import android.widget.EditText
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.bureung.english.Hub
import app.bureung.english.LessonService
import app.bureung.english.R
import app.bureung.english.lesson.BlockType
import app.bureung.english.lesson.Scenario
import app.bureung.english.lesson.Scenarios
import app.bureung.english.store.ModeChoice
import android.view.inputmethod.EditorInfo
import app.bureung.english.lesson.EngineState
import app.bureung.english.lesson.Estimate
import app.bureung.english.lesson.Lang
import app.bureung.english.lesson.LevelCoach
import app.bureung.english.lesson.Levels
import app.bureung.english.lesson.Plan
import app.bureung.english.lesson.TripMode

/**
 * One screen, two faces. Parked (P): the parent's view with today's conversation, the sentences practised, the
 * report and settings. Driving: one picture and one pause button, nothing to read (Pleos also covers third-party
 * apps in D; the conversation keeps going behind it).
 */
class MainActivity : Activity() {
    enum class Tab(val label: String) { SCENES("상황별 학습"), WORDS("오늘의 표현"), REPORT("부모 리포트"), SETTINGS("설정") }

    private lateinit var parked: LinearLayout
    private lateinit var header: LinearLayout
    private lateinit var lessonCard: LinearLayout
    private lateinit var tabBar: FrameLayout
    private lateinit var tabBody: LinearLayout
    private lateinit var driving: LinearLayout
    private lateinit var driveEmoji: TextView
    private lateinit var driveTitle: TextView
    private lateinit var driveSub: TextView
    private lateinit var driveButton: TextView
    private lateinit var driveLevel: LevelBar
    private lateinit var driveStop: TextView
    private var verifyView: View? = null
    private lateinit var verifyStatus: TextView
    private lateinit var verifyLogBox: LinearLayout
    private lateinit var verifyScroll: ScrollView
    private lateinit var verifyControls: LinearLayout
    private var verifyStamp = -1
    private var verifyScenario: Scenario? = null
    private var parkedLevel: LevelBar? = null
    private lateinit var celebration: TextView
    private lateinit var root: FrameLayout
    private var consentView: View? = null
    private lateinit var tabs: Tabs

    var tab = Tab.SCENES
        private set
    private var tabStamp = ""
    private val watcher: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Hub.init(this)
        Ui.update(this)
        tabs = Tabs(this)
        setContentView(build())
        // first launch: the parent's consent comes before anything else; later the settings can turn it off
        if (!Hub.progress.onboarded) showConsent(null) else requestStartPermissions()
    }

    override fun onStart() {
        super.onStart()
        Hub.watch(watcher)
        Hub.onMicLevel = { driveLevel.invalidate(); parkedLevel?.invalidate() }
        if (Hub.started) LessonService.start(this)
        tabStamp = ""
        render()
    }

    override fun onStop() {
        Hub.unwatch(watcher)
        Hub.onMicLevel = null
        super.onStop()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQ_START -> {
                val mic = permissions.indexOf(Manifest.permission.RECORD_AUDIO)
                if (mic >= 0 && grantResults.getOrNull(mic) != PackageManager.PERMISSION_GRANTED) {
                    Hub.progress.listenConsent = false
                    Toast.makeText(this, "마이크를 허용해야 부릉이가 아이 말을 들을 수 있어요. 설정에서 다시 켤 수 있어요", Toast.LENGTH_LONG).show()
                }
                Hub.startSignals()
                LessonService.start(this)
            }
            REQ_MIC -> {
                val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
                if (!granted) {
                    Hub.progress.listenConsent = false
                    Toast.makeText(this, "마이크를 허용해야 부릉이가 아이 말을 들을 수 있어요", Toast.LENGTH_LONG).show()
                } else if (startAfterMic) {
                    startAfterMic = false
                    startChat(pendingScenario)
                }
                startAfterMic = false
            }
        }
        refreshTab()
    }

    /** set when the parent tapped "start" and the conversation should begin as soon as the microphone is allowed */
    private var startAfterMic = false

    fun askMicrophone() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
        }
    }

    /** the situation to start once the microphone is allowed (null = a free conversation) */
    private var pendingScenario: Scenario? = null

    /**
     * The one way a conversation starts from the screen (the big button or a situation): asks for whatever is still
     * missing (consent, the microphone) and then starts; without a recogniser or a network the recorded lesson plays.
     */
    fun startChat(scenario: Scenario?) {
        if (!Hub.started) return requestStartPermissions()
        if (Hub.engine.isActive) {
            Toast.makeText(this, "지금 이야기하고 있어요. 끝나고 시작해 주세요", Toast.LENGTH_SHORT).show()
            return
        }
        val p = Hub.progress
        when {
            !p.listenConsent || !p.aiTalk -> showConsent(scenario)
            !Hub.micGranted() -> {
                pendingScenario = scenario
                startAfterMic = true
                askMicrophone()
            }
            !Hub.chatAllowed() -> {
                Toast.makeText(this, "${Hub.talkBlocker() ?: "AI 대화를 쓸 수 없어요"} · 녹음 수업을 들려줄게요", Toast.LENGTH_LONG).show()
                Hub.startLesson(recorded = true)
            }
            !Hub.online() -> {
                Toast.makeText(this, "인터넷 연결이 없어서 녹음 수업을 들려줄게요", Toast.LENGTH_LONG).show()
                Hub.startLesson(recorded = true)
            }
            else -> Hub.startLesson(scenario)
        }
    }

    /**
     * The parent's consent, first thing on the first launch (and again if it was turned off and a conversation is
     * asked for). Agreeing asks for the microphone with the car permissions; [then] starts that conversation after.
     */
    private fun showConsent(then: Scenario?) {
        if (consentView != null) return
        val firstLaunch = !Hub.progress.onboarded
        val view = FrameLayout(this).apply {
            setBackgroundColor(Palette.PAGE)
            isClickable = true
        }
        var points = 0
        fun point(icon: String, head: String, body: String) = hbox(18).apply {
            gravity = Gravity.TOP
            addView(badge(icon, 56, 28, Palette.TILES[points++ % Palette.TILES.size]))
            addView(vbox(4).apply {
                addView(text(head, 19, bold = true), matchWidth())
                addView(text(body, 16, Palette.INK2), matchWidth())
            }, weightWrap(1f))
        }
        val card = card(40, 22).apply {
            addView(ImageView(this@MainActivity).apply { setImageResource(R.drawable.ic_mark) }, LinearLayout.LayoutParams(dp(56), dp(56)))
            addView(text("부릉이와 영어로 이야기해요", 30, bold = true), matchWidth())
            addView(text("차 안 10분, 부릉이가 먼저 영어로 말을 걸어요. 시작하기 전에 보호자의 동의가 필요해요.", 17, Palette.SUB), matchWidth())
            addView(point("🚗", "한국어를 섞어도 괜찮아요", "아이가 틀리거나 한국어로 대답하면, 부릉이가 바른 영어 문장으로 다시 들려주고 따라 말하게 해요."), matchWidth())
            addView(point("🎤", "아이 목소리는 저장하지 않아요", "한국어와 영어를 섞어 말해도 알아듣도록, 아이가 대답한 목소리를 AI(Google Gemini)로 보내 알아듣는 데만 써요. 앱은 목소리를 남기지 않고 글자 기록만 이 기기에 둬요. 목적지와 위치는 보내지 않아요."), matchWidth())
            addView(point("👪", "보호자가 동의해요", "만 14세 미만 아이의 목소리를 AI 대화에 쓰는 데 보호자로서 동의해요. 동의는 설정에서 언제든 끌 수 있어요."), matchWidth())
            // first launch: the child's name comes with the consent, so 부릉이 can say hello by name from the first ride
            var names: (() -> Boolean)? = null
            if (firstLaunch) {
                val p = Hub.progress
                val ko = nameField("하린")
                val en = nameField("Harin").apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS }
                val others = nameField("Jiho(지호), Minjun(민준)")
                val warn = text("", 14, Palette.RED)
                var picked = p.level
                val about = text("", 15, Palette.INK2)
                val steps = FrameLayout(this@MainActivity)
                fun showSteps() {
                    steps.removeAllViews()
                    steps.addView(segmented(Levels.all.map { it.n to "${it.n} ${it.nameKo}" }, picked, 15) {
                        picked = it
                        showSteps()
                    }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                    val l = Levels.of(picked)
                    about.text = "${l.agesKo} · ${l.childKo}\n부릉이: ${l.buddyKo} · 질문: ${l.questionKo}\n예: ${l.example}"
                }
                showSteps()
                addView(vbox(8).apply {
                    addView(text("아이 이름", 19, bold = true), matchWidth())
                    addView(hbox(12).apply {
                        addView(labelled("한국어 이름", ko), weightWrap(1f))
                        addView(labelled("부릉이가 영어로 부를 이름", en), weightWrap(1f))
                    }, matchWidth())
                    addView(labelled("같이 타는 형제자매 (선택 · 영어 이름(한국어 이름), 쉼표로)", others), matchWidth())
                    addView(text("영어 단계 (5~10세)", 19, bold = true).lp(matchWidth(), top = 10))
                    addView(text("나이는 참고예요. 아이가 지금 영어로 말할 수 있는 만큼으로 골라 주세요. 대화가 쌓이면 부릉이가 다음 단계를 권해 드려요.", 14, Palette.SUB), matchWidth())
                    addView(steps, matchWidth())
                    addView(about, matchWidth())
                    addView(warn, matchWidth())
                }.lp(matchWidth(), top = 8))
                names = {
                    val k = ko.text.toString().trim()
                    val e = en.text.toString().trim()
                    when {
                        k.isEmpty() -> { warn.text = "아이 이름을 적어 주세요."; false }
                        e.isEmpty() || e.any { it in '가'..'힣' } -> { warn.text = "부릉이가 부를 이름은 영어로 적어 주세요. (예: Harin)"; false }
                        else -> {
                            p.nameKo = k
                            p.nameEn = e
                            p.companions = others.text.toString().trim()
                            p.level = picked
                            true
                        }
                    }
                }
            }
            addView(button("동의하고 시작하기", ButtonStyle.PRIMARY, 20) { if (names?.invoke() != false) answerConsent(true, then) }.lp(matchWidth(), top = 8))
            addView(button(if (firstLaunch) "동의하지 않고 둘러보기" else "지금은 안 할래요", ButtonStyle.GHOST, 16) { names?.invoke(); answerConsent(false, null) }, matchWidth())
            addView(text("동의하지 않으면 아이 말을 듣지 않고, 녹음된 영어 수업만 들려줘요.", 14, Palette.SUB).apply { gravity = Gravity.CENTER }, matchWidth())
        }
        view.addView(ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(FrameLayout(this@MainActivity).apply {
                setPadding(dp(32), dp(32), dp(32), dp(32))
                addView(card, FrameLayout.LayoutParams(dp(720), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        consentView = view
        root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    /**
     * The suggestion to move up a step, when the child's answers have earned it (see [LevelCoach]). The parent
     * decides: "올리기" changes the step, "다음에" hides it for a week.
     */
    fun levelUpCard(): View? {
        val p = Hub.progress
        val s = p.levelStanding()
        if (!s.ready || Hub.today() < p.levelAskAfterDay) return null
        val next = Levels.of(s.level + 1)
        return vbox(10).apply {
            background = rounded(Palette.BLUE_SOFT, 20)
            setPadding(dp(22), dp(18), dp(22), dp(18))
            addView(text("🎉 다음 단계로 올려 볼까요?", 19, bold = true), matchWidth())
            addView(text(
                "${p.nameKo}(이)가 최근 ${s.answers}번의 대답 중 ${s.english}번을 한국어 없이 영어로만 말했어요. " +
                    "Level ${next.n} ${next.nameKo}(${next.agesKo})에서는 부릉이가 ${next.buddyKo}로 말하고, ${next.questionKo} 질문을 해요.",
                15, Palette.INK2,
            ), matchWidth())
            addView(hbox(10).apply {
                addView(button("Level ${next.n}로 올리기", ButtonStyle.PRIMARY, 16) {
                    p.level = next.n
                    refreshTab()
                    render()
                }, weightWrap(1f))
                addView(button("다음에", ButtonStyle.SECONDARY, 16) {
                    p.levelAskAfterDay = Hub.today() + 7
                    refreshTab()
                    render()
                }, weightWrap(1f))
            }, matchWidth())
        }
    }

    private fun nameField(hint: String) = EditText(this).apply {
        this.hint = hint
        textSize = 18f * Ui.scale
        setTextColor(Palette.INK)
        setHintTextColor(Palette.SUB)
        inputType = InputType.TYPE_CLASS_TEXT
        isSingleLine = true
        background = rounded(Palette.FILL, 14)
        setPadding(dp(16), dp(14), dp(16), dp(14))
    }

    private fun labelled(label: String, field: View) = vbox(6).apply {
        addView(text(label, 14, Palette.SUB), matchWidth())
        addView(field, matchWidth())
    }

    private fun answerConsent(agreed: Boolean, then: Scenario?) {
        consentView?.let { root.removeView(it) }
        consentView = null
        val p = Hub.progress
        p.onboarded = true
        p.listenConsent = agreed
        p.aiTalk = agreed
        if (!Hub.started) {
            // first launch: one round of permission prompts, the microphone among them when the parent agreed
            val ask = Hub.missingPermissions(this) +
                if (agreed && !Hub.micGranted()) listOf(Manifest.permission.RECORD_AUDIO) else emptyList()
            if (ask.isEmpty()) Hub.startSignals() else requestPermissions(ask.toTypedArray(), REQ_START)
        } else if (agreed) {
            startChat(then)
        }
        refreshTab()
    }

    fun requestStartPermissions() {
        val missing = Hub.missingPermissions(this)
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), REQ_START) else Hub.startSignals()
    }

    // --- building ------------------------------------------------------------------------------------------------

    private fun build(): View {
        root = FrameLayout(this).apply { setBackgroundColor(Palette.PAGE) }

        header = hbox(14)
        lessonCard = card(30)
        val left = vbox(20).apply {
            addView(header, matchWidth())
            addView(lessonCard, matchWidth())
        }
        tabBar = FrameLayout(this)
        tabBody = vbox(16)
        val right = vbox(18).apply {
            addView(tabBar, matchWidth())
            addView(ScrollView(this@MainActivity).apply {
                isVerticalScrollBarEnabled = false
                addView(tabBody, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        parked = hbox().apply {
            gravity = Gravity.TOP
            setPadding(dp(32), dp(28), dp(32), dp(20))
            addView(ScrollView(this@MainActivity).apply {
                isVerticalScrollBarEnabled = false
                addView(left, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }, weight(1.1f))
            addView(View(this@MainActivity), LinearLayout.LayoutParams(dp(28), 1))
            addView(right, weight(1f))
        }
        root.addView(parked, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        driveEmoji = text("", 150).apply { gravity = Gravity.CENTER }
        driveTitle = text("", 34, Palette.WHITE, bold = true).apply { gravity = Gravity.CENTER }
        driveSub = text("", 22, 0xFFC9CCEE.toInt()).apply { gravity = Gravity.CENTER }
        driveLevel = LevelBar(this, Palette.ACTIVE, 0x66FFFFFF)
        driveButton = text("", 60, Palette.INK, bold = true).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Palette.WHITE) }
            isClickable = true
            setOnClickListener { Hub.togglePause() }
        }
        // 그만하기: ends the conversation for this trip (it does not start again by itself until the next P → D)
        driveStop = text("■", 38, Palette.WHITE, bold = true).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x33FFFFFF) }
            isClickable = true
            setOnClickListener { Hub.stopLesson() }
        }
        driving = vbox(18).apply {
            gravity = Gravity.CENTER
            background = SkyDrawable(Palette.NIGHT, Palette.NIGHT_BOTTOM, 0x24FFFFFF, 0f, stars = true)
            addView(driveEmoji, wrap())
            addView(driveTitle, wrap())
            addView(driveSub, wrap())
            addView(driveLevel, LinearLayout.LayoutParams(dp(260), dp(64)).apply { topMargin = dp(8) })
            addView(hbox(28).apply {
                gravity = Gravity.CENTER
                addView(driveButton, LinearLayout.LayoutParams(dp(160), dp(160)))
                addView(driveStop, LinearLayout.LayoutParams(dp(112), dp(112)))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })
            visibility = View.GONE
        }
        root.addView(driving, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        celebration = text("", 36, Palette.INK, bold = true).apply {
            gravity = Gravity.CENTER
            background = rounded(Palette.CARD, 28)
            setPadding(dp(48), dp(32), dp(48), dp(32))
            visibility = View.GONE
            elevation = dp(16).toFloat()
        }
        root.addView(celebration, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        return root
    }

    // --- rendering -----------------------------------------------------------------------------------------------

    private fun render() {
        // 검증 모드 takes the whole screen, parked or driving
        if (Hub.verify) {
            driving.visibility = View.GONE
            parked.visibility = View.GONE
            return renderVerify()
        }
        verifyView?.let { root.removeView(it) }
        verifyView = null
        val showDriving = Hub.started && Hub.car.driving
        driving.visibility = if (showDriving) View.VISIBLE else View.GONE
        parked.visibility = if (showDriving) View.GONE else View.VISIBLE
        if (showDriving) renderDriving() else renderParked()
        celebrate(showDriving)
    }

    // --- 검증 모드 -------------------------------------------------------------------------------------------------

    /**
     * The trial screen: on the left everything that is said (with word counts) and a box to type the child's
     * answer; on the right the conversation's controls and the car's signals, set by hand.
     */
    private fun renderVerify() {
        if (verifyView == null) buildVerify()
        val e = Hub.engine
        verifyStatus.text = when {
            !e.isActive -> "대화가 멈춰 있어요. 오른쪽에서 시작해 주세요."
            e.state == EngineState.PAUSED -> "잠깐 멈췄어요."
            Hub.listening -> "✍️ 아이 차례예요. 대답을 적고 보내기를 눌러 주세요."
            else -> "🔊 부릉이가 말하는 중이에요. 미리 적어 보내면 차례가 오자마자 들어가요."
        }
        verifyStatus.setTextColor(if (Hub.listening) Palette.BLUE else Palette.INK2)
        if (Hub.verifyStamp != verifyStamp) {
            verifyStamp = Hub.verifyStamp
            verifyLogBox.removeAllViews()
            Hub.verifyLog.forEach { (who, said) ->
                val mine = who == "부릉이"
                verifyLogBox.addView(hbox(12).apply {
                    gravity = Gravity.TOP
                    addView(text(who, 13, when (who) { "부릉이" -> Palette.BLUE; "아이" -> Palette.GOLD; else -> Palette.SUB }, bold = true), LinearLayout.LayoutParams(dp(96), ViewGroup.LayoutParams.WRAP_CONTENT))
                    addView(text(said, if (mine || who == "아이") 17 else 14, if (mine || who == "아이") Palette.INK else Palette.INK2, bold = mine), weightWrap(1f))
                }, matchWidth())
            }
            verifyScroll.post { verifyScroll.fullScroll(View.FOCUS_DOWN) }
        }
        verifyControls(verifyControls)
    }

    private fun buildVerify() {
        val input = nameField("아이의 대답을 적어 주세요 (예: 응, 핑크 잠바 입었어 / I like 사과)").apply {
            imeOptions = EditorInfo.IME_ACTION_SEND
        }
        fun send() {
            val said = input.text.toString().trim()
            if (said.isEmpty()) return
            input.setText("")
            Hub.typeAnswer(said)
        }
        input.setOnEditorActionListener { _, _, _ -> send(); true }
        verifyStatus = text("", 16, Palette.INK2, bold = true)
        verifyLogBox = vbox(10)
        verifyScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(verifyLogBox, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        verifyControls = vbox(12)
        val left = card(26, 12).apply {
            addView(text("검증 모드 · 대화 기록", 22, bold = true), matchWidth())
            addView(verifyStatus, matchWidth())
            // the answer box sits at the top so the on-screen keyboard never covers it
            addView(hbox(10).apply {
                addView(input, weightWrap(1f))
                addView(button("보내기", ButtonStyle.PRIMARY, 16) { send() }, wrap())
                addView(button("말 없음", ButtonStyle.SECONDARY, 16) { Hub.typeAnswer(null) }, wrap())
            }, matchWidth())
            addView(divider())
            addView(verifyScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        val right = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(card(26).apply { addView(verifyControls, matchWidth()) }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val view = hbox().apply {
            setBackgroundColor(Palette.PAGE)
            isClickable = true
            setPadding(dp(28), dp(24), dp(28), dp(20))
            addView(left, weight(1.15f))
            addView(View(this@MainActivity), LinearLayout.LayoutParams(dp(24), 1))
            addView(right, weight(1f))
        }
        verifyStamp = -1
        verifyView = view
        root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun verifyControls(box: LinearLayout) {
        box.removeAllViews()
        val car = Hub.car
        val p = Hub.progress
        val e = Hub.engine
        fun head(name: String, sub: String) = vbox(2).apply {
            addView(text(name, 18, bold = true), matchWidth())
            addView(text(sub, 13, Palette.SUB), matchWidth())
        }
        fun said(what: String) {
            Hub.verifyNote("차", what)
            render()
        }

        box.addView(head("대화", "실제 AI(Gemini)와 목소리를 그대로 써요. 기록은 부모 리포트에 남지 않아요."), matchWidth())
        box.addView(labelled("상황", segmented(listOf<Pair<Scenario?, String>>(null to "자유") + Scenarios.all.map { it to it.emoji }, verifyScenario, 16) {
            verifyScenario = it
            render()
        }), matchWidth())
        box.addView(labelled("영어 단계 · ${Levels.of(p.level).nameKo} (${Levels.of(p.level).buddyKo}) · 다음 대화부터", segmented(Levels.all.map { it.n to "${it.n}" }, p.level, 16) {
            p.level = it
            render()
        }), matchWidth())
        box.addView(hbox(10).apply {
            if (!e.isActive) {
                addView(button("▶ 대화 시작", ButtonStyle.PRIMARY, 16) {
                    if (Hub.chatAllowed()) Hub.startLesson(verifyScenario)
                    else Toast.makeText(this@MainActivity, "AI 대화: ${Hub.talkBlocker() ?: "쓸 수 없어요"}", Toast.LENGTH_LONG).show()
                }, weightWrap(1f))
            } else {
                addView(button(if (e.state == EngineState.PLAYING) "⏸ 잠깐 멈춤" else "▶ 이어서", ButtonStyle.SECONDARY, 16) { Hub.togglePause() }, weightWrap(1f))
                addView(button("■ 그만하기", ButtonStyle.SECONDARY, 16) { Hub.stopLesson() }, weightWrap(1f))
            }
        }, matchWidth())

        box.addView(divider())
        box.addView(head("차량 정보", "Pleos·차량 신호를 손으로 바꿔요. 대화 중에 바꾸면 바로 반영돼요."), matchWidth())
        box.addView(labelled("기어 (D에 두면 3초 뒤 자동 시작)", segmented(listOf(false to "P 정차", true to "D 주행"), car.driving, 16) {
            car.demoSetDriving(it)
            render()
        }), matchWidth())
        box.addView(labelled("와이퍼 (비)", segmented(listOf(false to "꺼짐", true to "켜짐"), car.demoRain, 16) {
            car.demoSetRain(it)
            if (!it) said("와이퍼 꺼짐") else render()
        }), matchWidth())
        box.addView(labelled("바깥 온도", segmented(listOf<Pair<Float?, String>>(null to "실제", 2f to "2°C", 15f to "15°C", 30f to "30°C"), car.demoTemp, 16) {
            car.demoSetTemp(it)
            said("바깥 온도 ${it?.let { c -> "${c.toInt()}°C" } ?: "실제 값"}")
        }), matchWidth())
        val routes = listOf("" to "없음", "해님유치원" to "유치원 10분", "우리집" to "집 8분", "할머니댁" to "할머니댁 15분")
        box.addView(labelled("내비 목적지와 남은 시간", segmented(routes, car.demoDestination?.name ?: "", 15) {
            when (it) {
                "" -> car.demoClearRoute()
                "해님유치원" -> car.demoRoute(it, 600)
                "우리집" -> car.demoRoute(it, 480)
                else -> car.demoRoute(it, 900)
            }
            said(if (it.isEmpty()) "길안내 없음" else "목적지 $it")
        }), matchWidth())
        box.addView(hbox(10).apply {
            addView(button("도착 1km 전", ButtonStyle.SECONDARY, 15) {
                car.demoRoute(car.destination?.name ?: "우리집", 120)
                said("남은 거리 1km 아래 → 부릉이가 마무리해요")
            }, weightWrap(1f))
            addView(button("도착", ButtonStyle.SECONDARY, 15) { car.demoArrive() }, weightWrap(1f))
        }, matchWidth())
        box.addView(labelled("등원길·하원길", segmented(listOf(ModeChoice.AUTO to "자동", ModeChoice.TO_SCHOOL to "등원", ModeChoice.FROM_SCHOOL to "하원"), p.modeChoice, 16) {
            p.modeChoice = it
            render()
        }), matchWidth())
        val d = car.destination
        val now = listOf(
            "기어 ${car.gear}",
            if (car.raining) "와이퍼 켜짐" else "와이퍼 꺼짐",
            car.outsideCelsius()?.let { "${it.toInt()}°C" } ?: "온도 모름",
            d?.let { "${it.name} · ${minutes(it.remainNow())}분 · ${it.metersNow()}m" } ?: "길안내 없음",
        ).joinToString("  ·  ")
        box.addView(text("지금: $now", 14, Palette.INK2), matchWidth())

        box.addView(divider())
        box.addView(button("검증 모드 끄기", ButtonStyle.PRIMARY, 17) { Hub.verifyMode(false) }, matchWidth())
        box.addView(text("끄면 손으로 바꾼 차량 정보가 모두 실제 신호로 돌아가요.", 13, Palette.SUB).apply { gravity = Gravity.CENTER }, matchWidth())
    }

    private fun renderDriving() {
        val e = Hub.engine
        driveEmoji.text = e.lastCard?.emoji ?: e.plan?.episode?.emoji ?: "🚗"
        val chat = e.plan?.chat != null
        driveTitle.text = when (e.state) {
            EngineState.PLAYING -> if (chat) "부릉이와 영어로 이야기 중" else "영어 수업 중"
            EngineState.PAUSED -> "잠깐 쉬는 중"
            else -> if (Hub.progress.autoStart) "곧 이야기를 시작해요" else "정차 중에 대화를 켜 주세요"
        }
        val block = when {
            !e.isActive -> null
            chat && Hub.listening -> "듣고 있어요"
            chat -> null
            else -> e.currentBlock?.title
        }
        val left = Hub.car.destination?.remainNow()?.let { "도착까지 ${minutes(it)}분" }
        driveSub.text = listOfNotNull(block, left).joinToString("  ·  ")
        driveLevel.visibility = if (chat && Hub.listening) View.VISIBLE else View.INVISIBLE
        driveButton.text = if (e.state == EngineState.PLAYING) "❚❚" else "▶"
        driveStop.visibility = if (e.isActive) View.VISIBLE else View.GONE
    }

    private fun renderParked() {
        renderHeader()
        renderLessonCard()
        renderTabBar()
        val stamp = tabs.stamp(tab)
        if (stamp != tabStamp) {
            tabStamp = stamp
            tabBody.removeAllViews()
            tabs.build(tab, tabBody)
        }
    }

    fun refreshTab() {
        tabStamp = ""
        render()
    }

    fun selectTab(t: Tab) {
        if (tab == t) return
        tab = t
        refreshTab()
    }

    private fun renderHeader() {
        header.removeAllViews()
        header.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_mark)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Palette.SKY_BOTTOM) }
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }, LinearLayout.LayoutParams(dp(56), dp(56)))
        header.addView(vbox(2).apply {
            addView(text("부릉부릉 잉글리시", 22, bold = true), wrap())
            addView(text("차 안 10분, 부릉이와 영어로 수다 떨기", 14, Palette.SUB), wrap())
        }, weightWrap(1f))
        val p = Hub.progress
        header.addView(chip("${p.nameKo} · Level ${p.level} ${levelName(p.level)}", Palette.BLUE_SOFT, Palette.BLUE), wrap())
        header.addView(chip("⭐ 스티커 ${p.stickers}", Palette.SAND, Palette.INK), wrap())
    }

    private fun renderLessonCard() {
        lessonCard.removeAllViews()
        if (!Hub.started) {
            lessonCard.addView(text("차량 연결을 준비하고 있어요", 24, bold = true), wrap())
            lessonCard.addView(text("차량 정보와 음성 권한을 허용하면 시작할 수 있어요.", 17, Palette.SUB).lp(matchWidth(), top = 8))
            lessonCard.addView(button("권한 다시 요청") { requestStartPermissions() }.lp(matchWidth(), top = 24))
            return
        }
        val e = Hub.engine
        if (e.isActive) renderActive() else renderIdle()
    }

    private fun renderIdle() {
        // without an AI key (a build without local.properties) the app is the recorded lesson
        if (!Hub.gemini.hasKey) {
            val preview = Hub.lessonPreview() ?: run {
                lessonCard.addView(text("준비하고 있어요", 18, Palette.SUB), wrap())
                return
            }
            renderIdleLesson(preview)
        } else {
            renderHero()
        }

        levelUpCard()?.let { lessonCard.addView(it.lp(matchWidth(), top = 14)) }

        val auto = if (Hub.progress.autoStart) "D로 출발하면 3초 뒤에 자동으로 시작해요" else "자동 시작이 꺼져 있어요 · 설정에서 켤 수 있어요"
        lessonCard.addView(text(auto, 14, Palette.SUB).apply { gravity = Gravity.CENTER }.lp(matchWidth(), top = 12))

        Hub.lastSummary?.let { s ->
            val min = minutes(s.activeSec)
            lessonCard.addView(divider().lp(matchWidth(), top = 22))
            val line = if (s.chat) "${s.episodeTitle} · ${min}분 · 아이가 말한 횟수 ${s.hits}번"
            else "${s.episodeTitle} · ${min}분" + if (s.asks > 0) " · 대답 ${s.hits}/${s.asks}" else ""
            lessonCard.addView(row(if (s.chat) "방금 대화" else "방금 수업", line, Palette.INK2).lp(matchWidth(), top = 6))
        }
    }

    /**
     * The home screen's one big button: a free conversation with 부릉이. Its first line is already known (made from
     * what the car knows), so the parent sees what the child will hear. Situations are in the tab beside it.
     */
    private fun renderHero() {
        val preview = Hub.chatPreview() ?: return
        val chat = preview.plan.chat ?: return
        val p = Hub.progress
        val hero = vbox(14).apply {
            background = SkyDrawable(Palette.SKY_TOP, Palette.SKY_BOTTOM, Palette.WHITE, dp(40).toFloat())
            setPadding(dp(32), dp(30), dp(32), dp(30))
            isClickable = true
            setOnClickListener { startChat(null) }
            addView(hbox(16).apply {
                addView(vbox(6).apply {
                    addView(text("부릉이와 영어 대화", 15, Palette.INK2, bold = true), wrap())
                    addView(text("바로 대화 시작하기", 36, Palette.INK, bold = true, lines = 1), wrap())
                }, weightWrap(1f))
                addView(ImageView(this@MainActivity).apply { setImageResource(R.drawable.ic_mark) }, LinearLayout.LayoutParams(dp(92), dp(92)))
            }, matchWidth())
            addView(text("부릉이가 먼저 말을 걸어요. 한국어를 섞어 대답해도 바른 영어 문장으로 다시 들려줘요.", 17, Palette.INK2), matchWidth())
            // 부릉이's first line, in a speech bubble
            addView(vbox(6).apply {
                background = rounded(Palette.WHITE, 22)
                setPadding(dp(22), dp(18), dp(22), dp(18))
                addView(text("💬 부릉이의 첫마디", 13, Palette.BLUE, bold = true), wrap())
                addView(text(chat.opener, 22, Palette.INK, bold = true), matchWidth())
            }, matchWidth())
            val dest = Hub.car.destination
            val length = if (dest != null) "${minutes(preview.plan.estimateSec)}분 · ${dest.name}까지" else "${minutes(preview.plan.estimateSec)}분 · 길안내 없음"
            val facts = listOfNotNull(length, "Level ${chat.level} ${levelName(chat.level)}", weather()).joinToString("   ·   ")
            addView(text(facts, 15, Palette.INK2), matchWidth())
            addView(button("▶  대화 시작", ButtonStyle.PRIMARY, 22) { startChat(null) }.apply { minHeight = dp(68) }.lp(matchWidth(), top = 6))
        }
        lessonCard.addView(hero, matchWidth())

        // what a tap will do first, when it is not simply "start"
        val note = when {
            !p.listenConsent || !p.aiTalk -> "누르면 보호자 동의 화면이 나와요"
            !Hub.micGranted() -> "누르면 마이크 권한을 물어봐요"
            !Hub.chatAllowed() -> "AI 대화: ${Hub.talkBlocker() ?: "쓸 수 없어요"} · 지금 누르면 녹음 수업을 들려줘요"
            !Hub.online() -> "인터넷 연결이 없어요 · 지금 누르면 녹음 수업을 들려줘요"
            else -> preview.reason + " · 도착 전에 부릉이가 알아서 마무리해요"
        }
        lessonCard.addView(text(note, 14, Palette.SUB).lp(matchWidth(), top = 12))

        // the navigation's destination suggests a situation ("○○ 할머니댁")
        Hub.suggestedScenario()?.let { s ->
            lessonCard.addView(hbox(14).apply {
                gravity = Gravity.CENTER_VERTICAL
                background = rounded(Palette.BLUE_SOFT, 18)
                setPadding(dp(20), dp(14), dp(16), dp(14))
                addView(text(s.emoji, 32), wrap())
                addView(vbox(2).apply {
                    addView(text("내비 목적지에 맞는 상황", 13, Palette.SUB, bold = true), wrap())
                    addView(text(s.titleKo, 18, Palette.INK, bold = true), wrap())
                }, weightWrap(1f))
                addView(button("이걸로 시작", ButtonStyle.SECONDARY, 15) { startChat(s) }, wrap())
            }.lp(matchWidth(), top = 14))
        }
    }

    /** A build without an AI key: the recorded lesson is the trip. */
    private fun renderIdleLesson(preview: Hub.Preview) {
        val plan = preview.plan
        val episode = plan.episode
        lessonCard.addView(text("녹음 수업", 15, Palette.SUB, bold = true), wrap())
        lessonCard.addView(hbox(16).apply {
            addView(vbox(4).apply {
                addView(text(episode.title, 28, bold = true, lines = 1), wrap())
                addView(text(episode.titleKo, 17, Palette.SUB, lines = 1), wrap())
            }, weightWrap(1f))
            addView(text(episode.emoji, 48), wrap())
        }.lp(matchWidth(), top = 6))
        val info = vbox().apply {
            addView(row("수업", if (plan.mode == TripMode.TO_SCHOOL) "등원 · 새 표현" else "하원 · 복습"), matchWidth())
            val dest = Hub.car.destination
            val length = "${minutes(plan.estimateSec)}분"
            addView(row("길이", if (dest != null) "$length · ${dest.name}까지 ${minutes(dest.remainNow())}분" else "$length · 길안내 없음"), matchWidth())
            if (Hub.car.usingDemo) addView(row("신호", "시연 도구 사용 중", Palette.RED), matchWidth())
        }
        lessonCard.addView(info.lp(matchWidth(), top = 14))
        lessonCard.addView(text("${preview.reason} · AI 대화: ${preview.offline ?: "쓸 수 없어요"}", 14, Palette.SUB).lp(matchWidth(), top = 4))
        lessonCard.addView(flow(plan, -1).lp(matchWidth(), top = 18))
        lessonCard.addView(button("수업 시작", ButtonStyle.PRIMARY, 18) { Hub.startLesson(recorded = true) }.lp(matchWidth(), top = 22))
    }

    private fun renderActive() {
        val e = Hub.engine
        val plan = e.plan ?: return
        val chat = plan.chat
        val playing = e.state == EngineState.PLAYING
        val status = when {
            !playing -> "잠깐 멈춤"
            chat != null -> "대화 중"
            else -> "수업 중 · ${e.currentBlock?.title ?: ""}"
        }
        lessonCard.addView(text(status, 15, if (playing) Palette.BLUE else Palette.SUB, bold = true), wrap())
        lessonCard.addView(hbox(16).apply {
            addView(vbox(4).apply {
                addView(text(if (chat != null) "${chat.scenario?.let { it.emoji + " " } ?: ""}${chat.scenario?.titleKo ?: "부릉이와 이야기 중"}" else plan.episode.title, 28, bold = true, lines = 1), wrap())
                val sub = when {
                    chat != null -> (if (plan.mode == TripMode.TO_SCHOOL) "등원길" else "하원길") + " · Level ${chat.level} ${levelName(chat.level)}"
                    plan.mode == TripMode.TO_SCHOOL -> "등원 · 새 표현"
                    else -> "하원 · 복습"
                }
                addView(text(sub, 16, Palette.SUB), wrap())
            }, weightWrap(1f))
            addView(text("약 ${minutes(e.restEstimate())}분 남음", 16, Palette.INK2, bold = true), wrap())
        }.lp(matchWidth(), top = 6))
        lessonCard.addView(flow(plan, e.blockIndex, e.blocks).lp(matchWidth(), top = 18))

        val box = vbox(14).apply {
            background = rounded(Palette.FILL, 20)
            setPadding(dp(26), dp(24), dp(26), dp(24))
        }
        val cap = Hub.caption
        when {
            Hub.listening -> {
                box.addView(text("🎤 지금 말해 주세요 · ${Hub.progress.nameKo}의 말을 듣고 있어요", 22, Palette.BLUE, bold = true), matchWidth())
                parkedLevel = LevelBar(this, Palette.ACTIVE, Palette.BLUE_MID).also { box.addView(it, LinearLayout.LayoutParams(dp(260), dp(56))) }
            }
            cap != null && cap.second == Lang.EN -> box.addView(text(cap.first, 28, Palette.INK, bold = true), matchWidth())
            cap != null -> box.addView(text(cap.first, 22, Palette.INK2), matchWidth())
            else -> box.addView(text(" ", 22), matchWidth())
        }
        if (chat != null) {
            Hub.lastTalk?.let { d ->
                box.addView(vbox(2).apply {
                    addView(text("아이: “${d.heard}”", 15, Palette.SUB), matchWidth())
                    d.recast?.let { addView(text("영어로: $it", 17, Palette.BLUE, bold = true), matchWidth()) }
                }, matchWidth())
            }
        }
        e.lastCard?.let { w ->
            box.addView(hbox(14).apply {
                addView(text(w.emoji, 40), wrap())
                addView(vbox(2).apply {
                    addView(text(w.en, 20, bold = true), wrap())
                    addView(text(w.ko, 15, Palette.SUB), wrap())
                }, wrap())
            }, matchWidth())
        }
        lessonCard.addView(box.lp(matchWidth(), top = 22))

        lessonCard.addView(hbox(12).apply {
            addView(
                button(if (playing) "잠깐 멈춤" else "이어 하기", if (playing) ButtonStyle.SECONDARY else ButtonStyle.PRIMARY) { Hub.togglePause() },
                weightWrap(1f),
            )
            addView(button("그만하기", ButtonStyle.GHOST) { Hub.stopLesson() }, weightWrap(1f))
        }.lp(matchWidth(), top = 22))
    }

    /** The trip's blocks as one bar with their names underneath (a conversation is one long block). */
    private fun flow(plan: Plan, current: Int, blocks: List<app.bureung.english.lesson.Block> = plan.blocks): View = vbox(10).apply {
        addView(SegmentBar(this@MainActivity).apply {
            set(blocks.map { b ->
                val talking = if (b.type == BlockType.TALK) plan.chat?.budgetSec ?: 0 else 0
                (Estimate.block(b).toFloat() + talking).coerceAtLeast(8f)
            }, current)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)))
        addView(text(blocks.joinToString("  ·  ") { it.title }, 14, Palette.SUB), matchWidth())
    }

    /** what the car knows that 부릉이 can talk about */
    private fun weather(): String? {
        val c = Hub.car.outsideCelsius()
        val parts = listOfNotNull(
            if (Hub.car.raining) "비 (와이퍼)" else null,
            c?.let { "바깥 ${it.toInt()}°C" + if (it <= 5f) " 추워요" else if (it >= 28f) " 더워요" else "" },
        )
        return parts.joinToString(" · ").ifEmpty { null }
    }

    private fun renderTabBar() {
        tabBar.removeAllViews()
        tabBar.addView(
            segmented(Tab.values().map { it to it.label }, tab, 17) { selectTab(it) },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
    }

    private fun celebrate(driving: Boolean) {
        val n = Hub.celebrate
        if (n <= 0 || driving) return
        Hub.celebrate = 0
        celebration.text = "오늘도 잘했어요! 스티커 ${n}개를 받았어요"
        celebration.visibility = View.VISIBLE
        celebration.alpha = 0f
        celebration.animate().alpha(1f).setDuration(250).withEndAction {
            celebration.postDelayed({
                celebration.animate().alpha(0f).setDuration(400).withEndAction { celebration.visibility = View.GONE }
            }, 2200)
        }
    }

    companion object {
        private const val REQ_START = 1
        private const val REQ_MIC = 2

        fun levelName(level: Int): String = Levels.name(level)

        /** seconds → whole minutes, at least 1 */
        fun minutes(sec: Int): Int = ((sec + 30) / 60).coerceAtLeast(1)
    }
}
