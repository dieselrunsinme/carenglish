package app.bureung.english.voice

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import app.bureung.english.lesson.Lang
import java.util.Locale

/** The system text-to-speech (e.g. Google TTS). Used for English by default: its en-US voice is a native one. */
class AndroidVoice(context: Context, private val main: Handler, private val log: (String) -> Unit) {
    private var tts: TextToSpeech? = null
    var ready = false
        private set
    private var englishOk = false
    private var koreanOk = false
    private var seq = 0
    private val callbacks = HashMap<String, () -> Unit>()

    init {
        tts = runCatching {
            TextToSpeech(context.applicationContext) { status -> main.post { onInit(status) } }
        }.onFailure { log("Android TTS 없음: ${it.message}") }.getOrNull()
    }

    private fun onInit(status: Int) {
        val t = tts ?: return
        if (status != TextToSpeech.SUCCESS) {
            log("Android TTS 초기화 실패 ($status)")
            return
        }
        englishOk = runCatching { t.isLanguageAvailable(Locale.US) >= TextToSpeech.LANG_AVAILABLE }.getOrDefault(false)
        koreanOk = runCatching { t.isLanguageAvailable(Locale.KOREA) >= TextToSpeech.LANG_AVAILABLE }.getOrDefault(false)
        t.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = finish(utteranceId)
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finish(utteranceId)
            override fun onError(utteranceId: String?, errorCode: Int) = finish(utteranceId)
        })
        ready = true
        log("Android TTS 준비됨 (영어 ${if (englishOk) "O" else "X"}, 한국어 ${if (koreanOk) "O" else "X"})")
    }

    private fun finish(id: String?) {
        main.post { id?.let { callbacks.remove(it) }?.invoke() }
    }

    fun supports(lang: Lang): Boolean = ready && when (lang) {
        Lang.EN -> englishOk
        Lang.KO -> koreanOk
    }

    /** Returns false when it could not even start; [done] is then never called. */
    fun speak(text: String, lang: Lang, done: () -> Unit): Boolean {
        val t = tts ?: return false
        if (!supports(lang)) return false
        t.language = if (lang == Lang.EN) Locale.US else Locale.KOREA
        // a little slower for young children; Korean stays natural
        t.setSpeechRate(if (lang == Lang.EN) 0.85f else 1.0f)
        val id = "u${++seq}"
        callbacks[id] = done
        val result = t.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        if (result != TextToSpeech.SUCCESS) {
            callbacks.remove(id)
            return false
        }
        return true
    }

    fun stop() {
        callbacks.clear()
        runCatching { tts?.stop() }
    }

    fun status(): String = when {
        tts == null -> "없음"
        !ready -> "준비 중"
        else -> "영어 ${if (englishOk) "✓" else "✗"} · 한국어 ${if (koreanOk) "✓" else "✗"}"
    }

    fun release() {
        stop()
        runCatching { tts?.shutdown() }
        tts = null
    }
}
