package app.bureung.english

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaMetadata
import android.net.ConnectivityManager
import android.net.Network
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import app.bureung.english.ai.BuddyVoice
import app.bureung.english.ai.Gemini
import app.bureung.english.ai.GeminiTalker
import app.bureung.english.car.CarSignals
import app.bureung.english.car.Destination
import app.bureung.english.car.Gear
import app.bureung.english.lesson.AnswerResult
import app.bureung.english.lesson.Block
import app.bureung.english.lesson.BlockType
import app.bureung.english.lesson.Cancel
import app.bureung.english.lesson.DiaryEntry
import app.bureung.english.lesson.EngineState
import app.bureung.english.lesson.Episode
import app.bureung.english.lesson.Lang
import app.bureung.english.lesson.Levels
import app.bureung.english.lesson.LessonEngine
import app.bureung.english.lesson.LessonEvents
import app.bureung.english.lesson.LessonSummary
import app.bureung.english.lesson.Lines
import app.bureung.english.lesson.Moment
import app.bureung.english.lesson.Plan
import app.bureung.english.lesson.Scenario
import app.bureung.english.lesson.Scenarios
import app.bureung.english.lesson.Said
import app.bureung.english.lesson.Scene
import app.bureung.english.lesson.TalkTurn
import app.bureung.english.lesson.Planner
import app.bureung.english.lesson.Scheduler
import app.bureung.english.lesson.Step
import app.bureung.english.lesson.TalkRules
import app.bureung.english.lesson.TripMode
import app.bureung.english.lesson.Word
import app.bureung.english.store.Episodes
import app.bureung.english.store.ModeChoice
import app.bureung.english.store.Progress
import app.bureung.english.voice.Mics
import app.bureung.english.voice.Chimes
import app.bureung.english.voice.EarsRouter
import app.bureung.english.voice.Focus
import app.bureung.english.voice.VoiceRouter
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * The app's single brain, living on the main thread for the whole process: it reads the car, plans and plays the
 * lesson, and keeps progress. The screen and the foreground service only watch it.
 */
object Hub : LessonEvents, CarSignals.Listener {
    private const val TAG = "Bureung"
    private const val AUTO_START_DELAY_MS = 3_000L
    private const val TICK_MS = 5_000L
    /** closer than this to the destination, the conversation wraps up */
    private const val NEAR_METERS = 1000

    val main = Handler(Looper.getMainLooper())
    private lateinit var app: Context
    lateinit var progress: Progress
        private set
    lateinit var episodes: Episodes
        private set
    lateinit var voice: VoiceRouter
        private set
    lateinit var ears: EarsRouter
        private set
    lateinit var car: CarSignals
        private set
    lateinit var engine: LessonEngine
        private set
    lateinit var gemini: Gemini
        private set
    lateinit var talker: GeminiTalker
        private set
    var buddy: BuddyVoice? = null
        private set
    /** the result of the "AI 대화 시험" button, for the settings screen */
    var talkTest: String? = null
        private set
    private lateinit var focus: Focus
    private var session: MediaSession? = null
    private var ready = false

    /** what is being said right now (shown only while parked) */
    var caption: Pair<String, Lang>? = null
        private set
    var listening = false
        private set
    /** the last moments of microphone loudness (0 to 1), newest just before [micHead]: the level bar draws these */
    val micLevels = FloatArray(15)
    var micHead = 0
        private set
    /** the screen redraws its level bar on this (not a full refresh) */
    var onMicLevel: (() -> Unit)? = null

    private fun micLevel(v: Float) {
        micLevels[micHead] = v
        micHead = (micHead + 1) % micLevels.size
        onMicLevel?.invoke()
    }
    /** stickers just earned, for the celebration on screen; the screen sets it back to 0 */
    var celebrate = 0
    var lastSummary: LessonSummary? = null
        private set
    /** the latest exchange of the conversation in progress, for the parked screen */
    var lastTalk: DiaryEntry? = null
        private set
    /** auto-start fires once per drive: it re-arms when the car is parked again */
    private var armed = true
    private val reviewedThisLesson = mutableSetOf<String>()
    val log = ArrayDeque<String>()
    private val watchers = LinkedHashSet<() -> Unit>()

    /** Permissions asked at first launch. The microphone is asked later, only when the parent turns listening on. */
    val PERMISSIONS: List<String> = buildList {
        add("pleos.car.permission.CAR_DRIVE")
        add("pleos.car.permission.READ_WINDSHIELD_WIPERS")
        add("pleos.car.permission.NAVI_ROUTE")
        add("pleos.car.permission.TTS_SERVICE")
        add("pleos.car.permission.STT_SERVICE")
        add("android.car.permission.CAR_POWERTRAIN")
        add("android.car.permission.CAR_EXTERIOR_ENVIRONMENT")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }

    fun missingPermissions(context: Context): List<String> =
        PERMISSIONS.filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

    /** true once the voice, the car and the engine exist (after the permission prompt) */
    var started = false
        private set

    fun init(context: Context) {
        if (ready) return
        app = context.applicationContext
        progress = Progress(app)
        episodes = Episodes(app, ::note)
        ready = true
        note("에피소드 ${episodes.all.size}개")
        // a restart without the screen (the service came back): permissions were settled before
        if (missingPermissions(app).all { it == Manifest.permission.POST_NOTIFICATIONS }) startSignals()
    }

    /**
     * Creates the Pleos SDK clients and connects to the car. They are created only after the permission prompt,
     * so they initialise with their permissions in place. Safe to call again.
     */
    fun startSignals() {
        if (!started) {
            // the key lives only in local.properties (never committed); without it the chat simply stays off
            gemini = Gemini(BuildConfig.GEMINI_API_KEY, app.packageName, signingSha1())
            talker = GeminiTalker(gemini, main, { progress.aiTalk }, ::childNames, ::note, BuildConfig.GEMINI_MODEL)
            buddy = if (gemini.hasKey) BuddyVoice(app, gemini, main, ::note, BuildConfig.GEMINI_TTS_MODEL) else null
            voice = VoiceRouter(app, main, progress, ::note, buddy)
            ears = EarsRouter(app, main, progress, ::note)
            ears.catcher.onLevel = ::micLevel
            car = CarSignals(app, main, ::note).also { it.listener = this }
            engine = LessonEngine(voice, ears, Chimes(main), MainScheduler, this, talker)
            focus = Focus(
                app, main,
                isOwnVoice = { voice.pleosBusy || ears.pleosBusy },
                onPause = { engine.pause() },
                onResume = { engine.resume() },
            )
            session = runCatching { mediaSession() }.getOrNull()
            started = true
            watchNetwork()
            note("녹음 파일 ${voice.clips.count}개 · AI 대화 ${if (gemini.hasKey) "키 있음" else "키 없음"}")
        }
        car.start()
        changed()
    }

    // --- watching ------------------------------------------------------------------------------------------------

    fun watch(w: () -> Unit) { watchers += w }
    fun unwatch(w: () -> Unit) { watchers -= w }
    private fun changed() = watchers.toList().forEach { it() }

    fun note(text: String) {
        Log.i(TAG, text)
        main.post {
            log.addLast(text)
            while (log.size > 60) log.removeFirst()
        }
    }

    // --- planning ------------------------------------------------------------------------------------------------

    fun today(): Long = LocalDate.now().toEpochDay()

    /**
     * What this trip would get, with the reason in Korean for the parent: a conversation with 부릉이 (free, or about a
     * chosen [Scenario]) when the AI can run, otherwise the recorded lesson ([offline] says why).
     */
    data class Preview(val plan: Plan, val reason: String, val tripSec: Int, val fromRoute: Boolean, val offline: String? = null)

    fun preview(scenario: Scenario? = null): Preview? = if (talkReady()) chatPreview(scenario) else lessonPreview()

    private fun moment(): Moment = Moment(
        childName = progress.nameEn.ifBlank { "friend" },
        childNameKo = progress.nameKo.ifBlank { "친구" },
        hour = LocalTime.now().hour,
        month = LocalDate.now().monthValue,
        outsideC = car.outsideCelsius(),
        raining = car.raining,
        companions = TalkRules.companions(progress.companions).map { it.first },
        level = progress.level,
    )

    private fun tripSec(): Int = car.destination?.remainNow() ?: (progress.defaultTripMin * 60)

    /** The conversation this trip would be, even before the parent has agreed (for the home screen). */
    fun chatPreview(scenario: Scenario? = null): Preview? {
        if (!started) return null
        val (mode, reason) = decideMode()
        val tripSec = tripSec()
        val plan = Planner.chat(
            mode, tripSec, moment(),
            review = progress.duePhrases(today()).take(MAX_REVIEW_PHRASES),
            listen = if (progress.listenEnglish) Lang.EN else Lang.KO,
            scenario = scenario,
        )
        // "오전이라 등원 수업이에요" → "오전이라 등원길이에요"
        val why = reason.replace(" 수업이에요", "길이에요").replace(" 수업으로", "길로")
        return Preview(plan, why, tripSec, car.destination != null)
    }

    /** The recorded lesson: what the trip gets without the AI, so it is never silent. */
    fun lessonPreview(): Preview? {
        if (!started || episodes.all.isEmpty()) return null
        val (mode, reason) = decideMode()
        val today = today()
        // the recorded lessons come in two steps: the first, and everything above it
        val level = if (progress.level <= 1) 1 else 2
        val due = progress.dueWordIds(today).mapNotNull { episodes.word(it) }
        val list = episodes.forLevel(level)
        val pointer = progress.nextEpisodeIndex(level)
        val episode: Episode
        val tripMode: TripMode
        if (mode == TripMode.FROM_SCHOOL && due.isNotEmpty()) {
            episode = progress.todayEpisode(today)?.let { episodes.byId(it) } ?: list[(pointer - 1).mod(list.size)]
            tripMode = TripMode.FROM_SCHOOL
        } else {
            episode = list[pointer.mod(list.size)]
            tripMode = TripMode.TO_SCHOOL
        }
        val tripSec = tripSec()
        val plan = Planner.plan(episode, tripMode, tripSec, moment(), due)
        val why = if (mode == TripMode.FROM_SCHOOL && tripMode == TripMode.TO_SCHOOL) "$reason. 복습할 단어가 없어서 새 표현을 배워요" else reason
        return Preview(plan, why, tripSec, car.destination != null, offline = talkBlocker() ?: "AI 대화를 쓸 수 없어요")
    }

    /** the situation the navigation's destination suggests ("○○ 할머니댁" → 할머니댁에 가요), or null */
    fun suggestedScenario(): Scenario? = if (started) Scenarios.forDestination(car.destination?.name) else null

    private const val MAX_REVIEW_PHRASES = 3
    private const val MAX_NEW_PHRASES = 5

    // --- AI conversation -----------------------------------------------------------------------------------------

    /** One tap for the parent: agree to listening and to the AI conversation (the screen then asks for the microphone). */
    fun enableChat() {
        progress.listenConsent = true
        progress.aiTalk = true
        changed()
    }

    /** Parked only: tap a sentence to hear it in 부릉이's voice. */
    fun sayPhrase(text: String) {
        if (!started || engine.isActive) return
        voice.speakBuddy(text) {}
    }

    /** The conversation could run once there is a network: the parent agreed, there is a key, and a way to hear the child. */
    fun chatAllowed(): Boolean = started && (verify || progress.aiTalk) && gemini.hasKey && ears.available

    /** The conversation can run: the parent turned it on, there is a key and a network, and a way to hear the child. */
    fun talkReady(): Boolean = started && (verify || progress.aiTalk) && gemini.hasKey && ears.available && online()

    /** Why the chat is off, in Korean for the settings screen (null when it is ready). */
    fun talkBlocker(): String? = when {
        !started -> "준비 중"
        !gemini.hasKey -> "API 키 없음 (local.properties)"
        verify -> if (online()) null else "인터넷 연결 없음"
        !progress.aiTalk -> "꺼져 있어요"
        !progress.listenConsent -> "보호자 동의 전"
        !micGranted() -> "마이크 권한이 꺼져 있어요"
        !ears.available -> "음성 인식기 없음"
        !online() -> "인터넷 연결 없음"
        else -> null
    }

    fun micGranted(): Boolean = app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /**
     * Offline only when the system has no network at all. Anything less certain counts as online: the conversation
     * itself falls back to recorded lines when the AI does not answer. A recent AI answer also counts as online.
     */
    fun online(): Boolean {
        if (started && System.currentTimeMillis() - talker.lastOkAt < 10 * 60_000L) return true
        return runCatching { app.getSystemService(ConnectivityManager::class.java).activeNetwork != null }.getOrDefault(true)
    }

    /** redraws the home screen when the network comes or goes */
    private fun watchNetwork() = runCatching {
        app.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { main.post { changed() } }
            override fun onLost(network: Network) { main.post { changed() } }
        })
    }

    /** the app's signing certificate, for a key restricted to this app in Google Cloud */
    private fun signingSha1(): String? = runCatching {
        val info = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val cert = info.signingInfo!!.apkContentsSigners.first().toByteArray()
        MessageDigest.getInstance("SHA-1").digest(cert).joinToString("") { "%02X".format(it) }
    }.getOrNull()

    /** every child's English name → its spellings (the riding child's Korean name too) */
    private fun childNames(): Map<String, List<String>> {
        val names = LinkedHashMap<String, List<String>>()
        names[progress.nameEn] = listOf(progress.nameEn, progress.nameKo)
        TalkRules.companions(progress.companions).forEach { (en, ko) -> names[en] = listOfNotNull(en, ko) }
        return names
    }

    /**
     * The settings button: one sample answer to Gemini and 부릉이's voice back, so the parent (and the demo) can
     * check the key and the network before a drive. Sends only the fixed sample sentence below.
     */
    // --- 검증 모드 -----------------------------------------------------------------------------------------------

    /**
     * 검증 모드: the whole conversation can be tried at a desk. The child's answers are typed, the car's signals
     * (gear, wipers, outside temperature, route) are set by hand, and everything said is written out with word
     * counts. Nothing from it goes into the parent's report.
     */
    var verify = false
        private set
    /** who said what, for the 검증 모드 screen (the newest last) */
    val verifyLog = ArrayList<Pair<String, String>>()
    var verifyStamp = 0
        private set

    fun verifyMode(on: Boolean) {
        if (!started || on == verify) return
        if (engine.isActive) engine.stop()
        verify = on
        ears.typing = on
        verifyLog.clear()
        verifyStamp += 1
        if (on) verifyNote("앱", "검증 모드를 켰어요. 대답은 타이핑으로, 차량 정보는 오른쪽에서 바꿔요.") else car.clearDemo()
        note("검증 모드 ${if (on) "켬" else "끔"}")
        changed()
    }

    fun verifyNote(who: String, text: String) {
        if (!verify) return
        verifyLog += who to text
        while (verifyLog.size > 120) verifyLog.removeAt(0)
        verifyStamp += 1
    }

    /** A typed answer for the child (null: said nothing). */
    fun typeAnswer(text: String?) {
        if (!verify) return
        verifyNote("아이", text?.trim()?.ifEmpty { null } ?: "(말 없음)")
        ears.typed(text)
        changed()
    }

    /** the result of [testLevels], for the settings screen */
    var levelTest: String? = null
        private set

    /**
     * The settings button that checks the steps: the same sample answer is sent at each of the five steps, and
     * 부릉이's question and the sentence to repeat are shown with their word counts next to each step's limits.
     * Five calls to Gemini; sends only the fixed sample sentence.
     */
    fun testLevels() {
        if (!started || engine.isActive) return
        if (!gemini.hasKey) {
            levelTest = "API 키가 없어요. local.properties에 gemini.apiKey를 넣고 다시 빌드해 주세요."
            return changed()
        }
        val name = progress.nameEn.ifBlank { "friend" }
        val scene = Scene(car.outsideCelsius(), car.raining)
        val hour = LocalTime.now().hour
        val opener = TalkRules.opener(name, TripMode.FROM_SCHOOL, hour, scene)
        val lines = ArrayList<String>()
        var within = 0
        val asked = ArrayList<Int>()
        fun step(n: Int) {
            if (n > Levels.MAX) {
                val grows = asked.size == Levels.MAX && asked.last() > asked.first()
                lines += "결과: ${Levels.MAX}단계 중 ${within}단계가 기준 안 · 질문 길이 ${asked.joinToString("→")}단어" +
                    if (grows) " (단계가 오르며 길어짐)" else " (길이 차이가 뚜렷하지 않음)"
                levelTest = lines.joinToString("\n")
                note("단계별 시험: 기준 안 $within/${Levels.MAX}, 질문 ${asked.joinToString("→")}단어")
                return changed()
            }
            val level = Levels.of(n)
            levelTest = (lines + "Level $n 물어보는 중…").joinToString("\n")
            changed()
            val turn = TalkTurn(
                child = name, level = n, mode = TripMode.FROM_SCHOOL,
                hour = hour, month = LocalDate.now().monthValue,
                scene = scene, events = emptyList(), secondsLeft = 300, last = false, switch = false, silent = false,
                review = emptyList(), history = listOf(Said(false, opener)),
                heard = "오늘 친구랑 블록 놀이 했어",
            )
            talker.reply(turn) { raw ->
                val reply = raw?.let { TalkRules.clean(it, name) }
                if (reply == null) {
                    lines += "Level $n ${level.nameKo}: 실패 (${talker.lastError ?: "안전 규칙을 통과하지 못했어요"})"
                } else {
                    val ask = TalkRules.words(reply.ask)
                    val repeat = TalkRules.words(reply.repeat)
                    val ok = ask <= level.maxWords && repeat <= level.maxRepeat
                    if (ok) within += 1
                    asked += ask
                    lines += "Level $n ${level.nameKo} (질문 ${level.maxWords}단어 이하, 따라 말하기 ${level.maxRepeat}단어 이하) ${if (ok) "✓" else "✗ 기준보다 김"}\n" +
                        "  질문: ${reply.ask ?: "-"} (${ask}단어)\n  따라 말하기: ${reply.repeat ?: "-"} (${repeat}단어)"
                }
                step(n + 1)
            }
        }
        lines += "아이 대답(공통): \"오늘 친구랑 블록 놀이 했어\""
        step(1)
    }

    /** the microphones the car exposes to apps, and the one the last recording came from */
    fun micReport(): String {
        val all = Mics.all(app)
        val used = ears.catcher.lastMic
        return (if (used != null) "방금 쓴 마이크: $used\n" else "아직 녹음한 적이 없어요. 대화를 한 번 하면 실제로 쓰인 마이크가 나와요.\n") +
            "차가 앱에 내주는 입력 ${all.size}개: ${all.joinToString(" / ").ifEmpty { "없음" }}"
    }

    fun testTalk() {
        if (!started || engine.isActive) return
        if (!gemini.hasKey) {
            talkTest = "API 키가 없어요. local.properties에 gemini.apiKey를 넣고 다시 빌드해 주세요."
            return changed()
        }
        talkTest = "Gemini에 물어보는 중…"
        changed()
        val startedAt = SystemClock.elapsedRealtime()
        val name = progress.nameEn.ifBlank { "friend" }
        val scene = Scene(car.outsideCelsius(), car.raining)
        val mode = decideMode().first
        val turn = TalkTurn(
            child = name, level = progress.level, mode = mode,
            hour = LocalTime.now().hour, month = LocalDate.now().monthValue,
            scene = scene, events = emptyList(), secondsLeft = 300, last = false, switch = false, silent = false,
            review = emptyList(),
            history = listOf(Said(false, TalkRules.opener(name, mode, LocalTime.now().hour, scene))),
            heard = "오늘 친구랑 블록 놀이 했어",
        )
        talker.reply(turn) { raw ->
            val reply = raw?.let { TalkRules.clean(it, name) }
            if (reply == null) {
                talkTest = "대화 실패: ${talker.lastError ?: "답이 안전 규칙을 통과하지 못했어요"}"
                note("AI 대화 시험 실패 (${talker.modelName})")
                return@reply changed()
            }
            val ms = SystemClock.elapsedRealtime() - startedAt
            val line = listOfNotNull(reply.say, reply.repeat, reply.ask).joinToString(" ")
            val text = "아이: \"오늘 친구랑 블록 놀이 했어\"\n" +
                "부릉이: ${reply.say ?: ""}\n따라 말하기: ${reply.repeat ?: "-"}\n다음 질문: ${reply.ask ?: "-"}\n" +
                "일기: ${reply.diaryKo ?: "-"}\n대화 OK · ${ms / 100 / 10.0}초 · ${talker.modelName}"
            talkTest = "$text\n목소리 만드는 중…"
            changed()
            val voiceAt = SystemClock.elapsedRealtime()
            voice.speakBuddy(line) {
                val made = voice.engineFor(Lang.EN).startsWith("부릉이")
                talkTest = "$text\n" + if (made) "목소리 OK (Gemini TTS)" else "목소리 실패 → 다른 목소리로 대신했어요 (${buddy?.lastError ?: "알 수 없음"})"
                note("AI 대화 시험: 대화 ${ms}ms, 목소리까지 ${SystemClock.elapsedRealtime() - voiceAt}ms")
                changed()
            }
        }
    }

    private fun decideMode(): Pair<TripMode, String> {
        when (progress.modeChoice) {
            ModeChoice.TO_SCHOOL -> return TripMode.TO_SCHOOL to "설정에서 등원 수업으로 정해 두었어요"
            ModeChoice.FROM_SCHOOL -> return TripMode.FROM_SCHOOL to "설정에서 하원 수업으로 정해 두었어요"
            ModeChoice.AUTO -> Unit
        }
        val d = car.destination
        if (d != null) {
            val keyword = progress.schoolKeyword
            if (keyword.isNotBlank() && d.name.contains(keyword)) return TripMode.TO_SCHOOL to "목적지가 ${ira(d.name)} 등원 수업이에요"
            val home = car.home
            if (home != null && (d.latitude != 0.0 || d.longitude != 0.0) && meters(home.first, home.second, d.latitude, d.longitude) < 400) {
                return TripMode.FROM_SCHOOL to "목적지가 집이라 하원 수업이에요"
            }
            if (listOf("집", "home", "Home", "HOME").any { d.name.contains(it) }) return TripMode.FROM_SCHOOL to "목적지가 ${ira(d.name)} 하원 수업이에요"
        }
        return if (LocalTime.now().hour < 12) TripMode.TO_SCHOOL to "오전이라 등원 수업이에요" else TripMode.FROM_SCHOOL to "오후라 하원 수업이에요"
    }

    /** "해님유치원" → "해님유치원이라", "피아노" → "피아노라" */
    private fun ira(name: String): String {
        val last = name.lastOrNull() ?: return name
        val hasFinal = last in '가'..'힣' && (last - '가') % 28 != 0
        return name + if (hasFinal || last !in '가'..'힣') "이라" else "라"
    }

    private fun meters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dy = (lat2 - lat1) * 111_000.0
        val dx = (lon2 - lon1) * 111_000.0 * cos(Math.toRadians((lat1 + lat2) / 2))
        return sqrt(dx * dx + dy * dy)
    }

    // --- playing -------------------------------------------------------------------------------------------------

    /**
     * Starts the trip: a conversation (free, or about [scenario]) when the AI can run, else the recorded lesson.
     * [recorded] asks for the recorded lesson on purpose.
     */
    fun startLesson(scenario: Scenario? = null, recorded: Boolean = false) {
        if (!started || engine.isActive) return
        car.navi.refresh()
        val p = (if (recorded) lessonPreview() else preview(scenario))?.plan ?: return
        armed = false
        reviewedThisLesson.clear()
        caption = null
        lastTalk = null
        LessonService.start(app)
        focus.acquire()
        session?.isActive = true
        updateSession(p.episode.title, PlaybackState.STATE_PLAYING)
        note("${if (p.chat != null) "대화" else "수업"} 시작: ${p.episode.id} ${p.mode} · 계획 ${p.estimateSec / 60}분 ${p.estimateSec % 60}초 / 예산 ${p.budgetSec}초")
        verifyNote("앱", if (p.chat != null) "대화 시작 · Level ${p.chat.level} ${Levels.name(p.chat.level)} · ${if (p.mode == TripMode.TO_SCHOOL) "등원길" else "하원길"} · ${p.chat.scenario?.titleKo ?: "자유 대화"} · ${p.budgetSec / 60}분"
            else "AI 대화를 쓸 수 없어 녹음 수업을 시작했어요 (${talkBlocker() ?: "이유 모름"})")
        engine.start(p)
        engine.scene = Scene(car.outsideCelsius(), car.raining)
        startTicker()
    }

    fun stopLesson() {
        if (started) engine.stop()
    }

    fun togglePause() {
        if (!started) return
        when (engine.state) {
            EngineState.PLAYING -> engine.pause()
            EngineState.PAUSED -> {
                focus.acquire()
                engine.resume()
            }
            else -> startLesson()
        }
    }

    /** Parked only: tap a word card to hear it. */
    fun sayWord(word: Word) {
        if (!started || engine.isActive) return
        voice.speak(word.en, Lang.EN) {}
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (!engine.isActive) return
            engine.scene = Scene(car.outsideCelsius(), car.raining)
            val d = car.destination
            if (d != null) {
                val left = d.remainNow()
                engine.updateRemaining(left)
                engine.nearDestination(d.metersNow() in 1 until NEAR_METERS)
                if (left == 0 && car.demoDestination != null) car.demoArrive()
            }
            changed()
            main.postDelayed(this, TICK_MS)
        }
    }

    private fun startTicker() {
        main.removeCallbacks(ticker)
        main.postDelayed(ticker, TICK_MS)
    }

    // --- engine events -------------------------------------------------------------------------------------------

    override fun onState(state: EngineState) {
        val title = engine.plan?.episode?.title ?: appName()
        when (state) {
            EngineState.PLAYING -> updateSession(title, PlaybackState.STATE_PLAYING)
            EngineState.PAUSED -> updateSession(title, PlaybackState.STATE_PAUSED)
            EngineState.FINISHED, EngineState.IDLE -> {
                updateSession(title, PlaybackState.STATE_STOPPED)
                session?.isActive = false
                focus.release()
                listening = false
            }
        }
        LessonService.refresh(app)
        if (state == EngineState.FINISHED && !progress.autoStart) LessonService.stop(app)
        changed()
    }

    private fun appName(): String = app.getString(R.string.app_name)

    override fun onBlock(index: Int, block: Block) = changed()
    override fun onPlanChanged() = changed()

    override fun onLine(text: String, lang: Lang) {
        caption = text to lang
        verifyNote(if (lang == Lang.EN) "부릉이" else "안내", if (lang == Lang.EN) "$text (${TalkRules.words(text)}단어)" else text)
        changed()
    }

    override fun onCard(word: Word) = changed()

    override fun onListening(listening: Boolean) {
        if (this.listening == listening) return
        this.listening = listening
        micLevels.fill(0f)
        changed()
    }

    override fun onAnswer(ask: Step.Ask, heard: String?, result: AnswerResult) {
        val plan = engine.plan ?: return
        val id = ask.wordId
        val today = today()
        if (id != null && id in plan.reviewIds && reviewedThisLesson.add(id)) {
            progress.reviewed(id, today, success = result != AnswerResult.SILENT)
        }
        if (id == Lines.RAIN.id && result != AnswerResult.SILENT && id !in progress.words()) {
            progress.introduce(listOf(id), today)
        }
        note("대답 ${ask.answer} ← ${heard ?: "(없음)"} : $result")
        changed()
    }

    override fun onTalk(entry: DiaryEntry) {
        lastTalk = entry
        verifyNote("AI가 들은 말", "${entry.heard} · ${when (entry.lang) { "en" -> "영어로만"; "mixed" -> "섞어서"; "ko" -> "한국어로만"; else -> "언어 표시 없음" }}" +
            (entry.recast?.let { " · 따라 말할 문장: $it (${TalkRules.words(it)}단어)" } ?: " · 고칠 것 없음") +
            (entry.ko?.let { " · 부모용: $it" } ?: "") + if (entry.worry) " · 보호자 확인" else "")
        note("대화 L${progress.level} ${entry.child}: ${entry.heard} → ${entry.recast?.let { "$it (${TalkRules.words(it)}단어)" } ?: entry.en ?: "-"}${if (entry.worry) " (보호자 확인)" else ""}")
        changed()
    }

    override fun onFinished(summary: LessonSummary) {
        val plan = engine.plan
        val today = today()
        if (verify) {
            // a trial run: nothing goes into the report, the stickers or the sentences to bring back
            lastSummary = null
            caption = null
            main.removeCallbacks(ticker)
            verifyNote("앱", "대화 끝 · ${summary.activeSec}초 · 아이가 말한 횟수 ${summary.hits}번${if (summary.completed) " · 인사까지 마침" else " · 중간에 멈춤"}")
            note("검증 대화 끝: ${summary.activeSec}초 · 대답 ${summary.hits}/${summary.asks}")
            return changed()
        }
        if (plan != null && summary.mode == TripMode.TO_SCHOOL) {
            val newIds = summary.cardsShown.filter { id -> plan.episode.words.any { it.id == id } && id !in plan.reviewIds }
            progress.introduce(newIds, today)
            if (BlockType.EXPRESSION in summary.blocksDone) {
                progress.advanceEpisode(plan.episode.level, episodes.forLevel(plan.episode.level).size)
                progress.setTodayEpisode(today, plan.episode.id)
            }
        }
        if (plan?.chat != null) {
            // sentences the child was given to repeat come back in later conversations (same day, +3, +7 days)
            if (summary.hits >= 2) plan.chat.review.forEach { progress.phraseHeardAgain(it, today) }
            progress.learnPhrases(summary.diary.mapNotNull { it.recast }.distinct().take(MAX_NEW_PHRASES), today)
        }
        val bonus = if (summary.hits >= 3) 1 else 0
        val stickers = if (summary.completed) 1 + bonus else 0
        progress.record(summary, today, stickers)
        celebrate = stickers
        lastSummary = summary
        caption = null
        main.removeCallbacks(ticker)
        note("${if (summary.chat) "대화" else "수업"} 끝: ${summary.episodeId} · ${summary.activeSec}초 · 대답 ${summary.hits}/${summary.asks}")
        changed()
    }

    // --- car events ----------------------------------------------------------------------------------------------

    override fun onGearChanged(old: Gear, new: Gear) {
        note("기어 $old → $new")
        verifyNote("차", "기어 $old → $new")
        if (new == Gear.PARK) armed = true
        if (new == Gear.DRIVE && progress.autoStart && armed && !engine.isActive) {
            main.postDelayed({
                // the parent may still be setting the navigation: start a moment after D, if still driving
                // starting by itself in D is always the free talk: a situation is only what the parent picks
                if (car.driving && armed && !engine.isActive) startLesson(null)
            }, AUTO_START_DELAY_MS)
        }
        changed()
    }

    override fun onRainStarted() {
        note("와이퍼 작동 → 비")
        verifyNote("차", "와이퍼 켜짐 → 부릉이의 다음 대답에 '비가 온다'가 전달돼요")
        if (engine.isActive) {
            engine.scene = Scene(car.outsideCelsius(), raining = true)
            // a conversation hears about it in 부릉이's next answer; a recorded lesson gets the surprise word
            engine.tell("It just started raining. The wipers are on.")
            engine.offerSurprise(Lines.rainSurprise())
        }
        changed()
    }

    override fun onDestinationChanged(destination: Destination?) {
        if (destination != null && engine.isActive) {
            engine.updateRemaining(destination.remainNow())
            engine.nearDestination(destination.metersNow() in 1 until NEAR_METERS)
        }
        changed()
    }

    override fun onArrived() {
        verifyNote("차", "도착")
        if (engine.isActive) engine.arrived(progress.nameEn, progress.nameKo)
        changed()
    }

    // --- steering-wheel media keys -----------------------------------------------------------------------------------

    private fun mediaSession(): MediaSession = MediaSession(app, "bureung").apply {
        setCallback(object : MediaSession.Callback() {
            override fun onPlay() {
                main.post { togglePause() }
            }

            override fun onPause() {
                main.post { engine.pause() }
            }

            override fun onStop() {
                main.post { stopLesson() }
            }
        })
        setPlaybackState(state(PlaybackState.STATE_STOPPED))
    }

    private fun state(s: Int) = PlaybackState.Builder()
        .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP)
        .setState(s, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f, SystemClock.elapsedRealtime())
        .build()

    private fun updateSession(title: String, s: Int) {
        val ms = session ?: return
        runCatching {
            ms.setPlaybackState(state(s))
            ms.setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, appName())
                    .build(),
            )
        }
    }

    /** The scheduler the engine runs on: the main thread. */
    private object MainScheduler : Scheduler {
        override fun post(delayMs: Long, action: () -> Unit): Cancel {
            val r = Runnable { action() }
            main.postDelayed(r, delayMs.coerceAtLeast(0))
            return Cancel { main.removeCallbacks(r) }
        }

        override fun now(): Long = System.currentTimeMillis()
    }
}
