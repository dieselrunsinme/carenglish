package app.bureung.english.voice

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.SystemClock
import app.bureung.english.lesson.Lang
import java.util.Locale

/**
 * Pleos TextToSpeech SDK in ON_DEVICE mode: no Client ID/Secret, nothing leaves the car. Korean lines use it by
 * default. If a language fails once it is marked and the router hands that language to the system TTS.
 */
class PleosVoice(context: Context, private val main: Handler, private val log: (String) -> Unit) {
    private val tts: TextToSpeech? = runCatching { TextToSpeech(context.applicationContext, Mode.ON_DEVICE) }
        .onFailure { log("Pleos TTS 없음: ${it.message}") }
        .getOrNull()
    var ready = false
        private set
    /** true while a Pleos utterance is playing (its own audio focus changes are not ours to react to) */
    var speaking = false
        private set
    private val failed = mutableSetOf<Lang>()
    private val failures = HashMap<Lang, Int>()
    private var locale: Locale? = null
    private var onDone: (() -> Unit)? = null
    private var onFail: (() -> Unit)? = null

    private val events = object : EventListener {
        override fun onStart() = Unit
        override fun onReady() = Unit
        override fun onUpdatedRms(rms: Double) = Unit
        override fun onDone() { main.post { finish(ok = true) } }
        override fun onStop() {
            main.post {
                // a stop we asked for (pause, skip) may report late, after the next line already started
                if (SystemClock.uptimeMillis() - lastStopMs > LATE_STOP_MS) finish(ok = true)
            }
        }
        override fun onError(errorType: ErrorType) {
            main.post {
                log("Pleos TTS 오류: $errorType")
                finish(ok = false)
            }
        }
    }

    private var lastStopMs = 0L

    init {
        val t = tts
        if (t != null) runCatching {
            t.initialize(object : OnInitializeListener {
                override fun onSuccess() {
                    main.post {
                        runCatching {
                            t.setAudioAttributes(
                                AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_MEDIA)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                    .build(),
                            )
                        }
                        runCatching { t.addEventListener(events) }
                        ready = true
                        log("Pleos TTS 준비됨 (ON_DEVICE)")
                    }
                }

                override fun onFailure(errorType: ErrorType) {
                    main.post { log("Pleos TTS 초기화 실패: $errorType") }
                }
            })
        }.onFailure { log("Pleos TTS 초기화 불가: ${it.message}") }
    }

    fun supports(lang: Lang): Boolean = ready && lang !in failed

    /** Returns false when it could not start. Otherwise exactly one of [done] / [fallback] runs later. */
    fun speak(text: String, lang: Lang, done: () -> Unit, fallback: () -> Unit): Boolean {
        val t = tts ?: return false
        if (!supports(lang)) return false
        val want = if (lang == Lang.EN) Locale.US else Locale.KOREA
        if (want != locale) {
            val ok = runCatching { t.setLocale(want) }.isSuccess
            if (!ok) {
                failed += lang
                log("Pleos TTS: ${want.toLanguageTag()} 미지원 → 다른 목소리로")
                return false
            }
            locale = want
        }
        if (speaking) {
            // never queue behind an unfinished line
            lastStopMs = SystemClock.uptimeMillis()
            runCatching { t.stop() }
        }
        onDone = {
            failures[lang] = 0
            done()
        }
        onFail = {
            // one error can be a hiccup; two in a row and this language goes to the other voice
            val n = (failures[lang] ?: 0) + 1
            failures[lang] = n
            if (n >= 2) failed += lang
            fallback()
        }
        speaking = true
        val started = runCatching { t.speak(text, false) }.isSuccess
        if (!started) {
            onDone = null
            onFail = null
            speaking = false
            failed += lang
            return false
        }
        return true
    }

    private fun finish(ok: Boolean) {
        speaking = false
        val done = onDone
        val fail = onFail
        onDone = null
        onFail = null
        if (ok) done?.invoke() else fail?.invoke()
    }

    fun stop() {
        onDone = null
        onFail = null
        speaking = false
        lastStopMs = SystemClock.uptimeMillis()
        runCatching { tts?.stop() }
    }

    private companion object {
        const val LATE_STOP_MS = 1_500L
    }

    fun status(): String = when {
        tts == null -> "없음"
        !ready -> "준비 중"
        failed.isEmpty() -> "준비됨"
        else -> "준비됨 (${failed.joinToString { it.name }} 실패 → 대체)"
    }

    fun release() {
        stop()
        runCatching { tts?.release() }
    }
}
