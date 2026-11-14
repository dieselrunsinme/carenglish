package app.bureung.english.voice

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.ErrorType
import ai.pleos.playground.stt.constant.Mode
import ai.pleos.playground.stt.listener.OnInitializeListener
import ai.pleos.playground.stt.listener.ResultListener
import android.content.Context
import android.os.Handler
import app.bureung.english.lesson.Lang
import java.util.Locale

/**
 * Pleos SpeechToText in ON_DEVICE mode. English for the lesson's "say it with me", Korean for the AI chat (children
 * answer "what did you do today?" in Korean). The language is set for each listen; a language that keeps failing
 * steps aside for the system recogniser. The child's voice is turned into text inside the car and dropped.
 */
class PleosEars(context: Context, private val main: Handler, private val log: (String) -> Unit) {
    private val stt: SpeechToText? = runCatching { SpeechToText(context.applicationContext, Mode.ON_DEVICE) }
        .onFailure { log("Pleos STT 없음: ${it.message}") }
        .getOrNull()
    var ready = false
        private set
    /** true while the microphone is open (Pleos may take audio focus for it) */
    var listening = false
        private set
    private val errors = HashMap<Lang, Int>()
    private val broken = HashSet<Lang>()
    private var current: Lang? = null
    private var session = 0
    private var callback: ((String?) -> Unit)? = null
    private var latest: String? = null
    private val giveUp = Runnable { complete(session, latest) }

    private val results = object : ResultListener {
        override fun onReady() = Unit
        override fun onStartedRecognition() = Unit
        override fun onUpdatedEpdData(on: Long, off: Long) = Unit
        override fun onUpdatedRms(rms: Double) = Unit
        override fun onEndedRecognition() {
            main.post {
                val s = session
                // the final text usually follows the end of speech; give it a moment
                main.postDelayed({ complete(s, latest) }, END_GRACE_MS)
            }
        }
        override fun onError(errorType: ErrorType) {
            main.post {
                val lang = current
                log("Pleos STT 오류(${lang?.let(::label) ?: "?"}): $errorType")
                if (lang != null) strike(lang)
                complete(session, latest)
            }
        }
        override fun onUpdated(stt: String, completed: Boolean) {
            main.post {
                if (callback == null) return@post
                if (stt.isNotBlank()) latest = stt
                if (completed) complete(session, latest)
            }
        }
    }

    init {
        val s = stt
        if (s != null) runCatching {
            s.initialize(object : OnInitializeListener {
                override fun onSuccess() {
                    main.post {
                        // the listener goes on only after initialize has finished
                        runCatching { s.addListener(results) }
                        ready = true
                        log("Pleos STT 준비됨 (ON_DEVICE)")
                    }
                }

                override fun onFailure(errorType: ErrorType) {
                    main.post { log("Pleos STT 초기화 실패: $errorType") }
                }
            })
        }.onFailure { log("Pleos STT 초기화 불가: ${it.message}") }
    }

    fun usable(lang: Lang): Boolean = stt != null && ready && lang !in broken

    /** "한국어·영어", for the settings screen */
    fun languages(): String =
        listOf(Lang.KO, Lang.EN).filter(::usable).joinToString("·") { label(it) }.ifEmpty { "없음" }

    fun listen(waitSec: Double, lang: Lang, result: (String?) -> Unit) {
        val s = stt
        if (s == null || !usable(lang)) {
            main.post { result(null) }
            return
        }
        if (current != lang) {
            val set = runCatching { s.setLocale(locale(lang)) }
            if (set.isFailure) {
                broken += lang
                log("Pleos STT: ${label(lang)} 인식 미지원 → 시스템 인식으로 (${set.exceptionOrNull()?.message})")
                main.post { result(null) }
                return
            }
            current = lang
        }
        session += 1
        callback = result
        latest = null
        listening = true
        val started = runCatching { s.request() }.isSuccess
        if (!started) {
            strike(lang)
            complete(session, null)
            return
        }
        main.removeCallbacks(giveUp)
        main.postDelayed(giveUp, (waitSec * 1000).toLong())
    }

    private fun strike(lang: Lang) {
        val n = (errors[lang] ?: 0) + 1
        errors[lang] = n
        if (n >= MAX_ERRORS && broken.add(lang)) log("Pleos STT: ${label(lang)} 오류 반복 → 시스템 인식으로")
    }

    private fun complete(inSession: Int, text: String?) {
        if (inSession != session) return
        val cb = callback ?: return
        callback = null
        listening = false
        main.removeCallbacks(giveUp)
        runCatching { stt?.stop() }
        if (text != null) current?.let { errors[it] = 0 }
        cb(text)
    }

    fun cancel() {
        session += 1
        callback = null
        listening = false
        main.removeCallbacks(giveUp)
        runCatching { stt?.stop() }
    }

    fun status(): String = when {
        stt == null -> "없음"
        !ready -> "준비 중"
        else -> languages()
    }

    fun release() {
        cancel()
        runCatching { stt?.removeListener(results) }
        runCatching { stt?.release() }
    }

    private companion object {
        const val END_GRACE_MS = 700L
        const val MAX_ERRORS = 3

        fun locale(lang: Lang): Locale = if (lang == Lang.KO) Locale.KOREA else Locale.US
        fun label(lang: Lang) = if (lang == Lang.KO) "한국어" else "영어"
    }
}
