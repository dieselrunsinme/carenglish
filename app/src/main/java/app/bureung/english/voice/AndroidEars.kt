package app.bureung.english.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import app.bureung.english.lesson.Lang
import app.bureung.english.lesson.Matcher

/** The system speech recogniser (en-US), used when Pleos STT is not available. Results are text only. */
class AndroidEars(private val context: Context, private val main: Handler, private val log: (String) -> Unit) {
    private var recognizer: SpeechRecognizer? = null
    private var session = 0
    private var callback: ((String?) -> Unit)? = null
    private var hints: List<String> = emptyList()
    private var latest: String? = null
    private val giveUp = Runnable { complete(session, latest) }

    val available: Boolean by lazy {
        runCatching { SpeechRecognizer.isRecognitionAvailable(context) }.getOrDefault(false)
    }

    fun listen(waitSec: Double, hints: List<String>, lang: Lang, result: (String?) -> Unit) {
        if (!available) {
            main.post { result(null) }
            return
        }
        val r = recognizer ?: runCatching { SpeechRecognizer.createSpeechRecognizer(context.applicationContext) }
            .onFailure { log("시스템 음성 인식 생성 실패: ${it.message}") }
            .getOrNull()
            ?.also { recognizer = it }
        if (r == null) {
            main.post { result(null) }
            return
        }
        session += 1
        val my = session
        callback = result
        this.hints = hints
        latest = null
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
            override fun onError(error: Int) = complete(my, latest)
            override fun onPartialResults(partialResults: Bundle?) {
                best(partialResults)?.let { latest = it }
            }
            override fun onResults(results: Bundle?) = complete(my, best(results) ?: latest)
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (lang == Lang.KO) "ko-KR" else "en-US")
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        val started = runCatching { r.startListening(intent) }.isSuccess
        if (!started) {
            complete(my, null)
            return
        }
        main.removeCallbacks(giveUp)
        main.postDelayed(giveUp, (waitSec * 1000).toLong())
    }

    /** of the alternatives, the one that matches what we hoped for, else the most likely */
    private fun best(bundle: Bundle?): String? {
        val list = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.filter { it.isNotBlank() }
        if (list.isNullOrEmpty()) return null
        return list.firstOrNull { Matcher.matches(it, hints) } ?: list.first()
    }

    private fun complete(inSession: Int, text: String?) {
        if (inSession != session) return
        val cb = callback ?: return
        callback = null
        main.removeCallbacks(giveUp)
        runCatching { recognizer?.cancel() }
        cb(text)
    }

    fun cancel() {
        session += 1
        callback = null
        main.removeCallbacks(giveUp)
        runCatching { recognizer?.cancel() }
    }

    fun status(): String = if (available) "있음" else "없음"

    fun release() {
        cancel()
        runCatching { recognizer?.destroy() }
        recognizer = null
    }
}
