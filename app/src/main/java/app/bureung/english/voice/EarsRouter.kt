package app.bureung.english.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import app.bureung.english.lesson.Ears
import app.bureung.english.lesson.Lang
import app.bureung.english.store.Progress
import app.bureung.english.store.VoiceChoice

/**
 * Listening only happens when the parent has said yes in the settings and the microphone permission is granted.
 * Pleos STT first (on-device), then the system recogniser. Without either, the lesson still works: the child gets
 * time to answer and hears the model line.
 */
class EarsRouter(
    private val context: Context,
    private val main: Handler,
    private val progress: Progress,
    log: (String) -> Unit,
) : Ears {
    val pleos = PleosEars(context, main, log)
    val system = AndroidEars(context, main, log)
    /** records the child's answer for the AI to listen to; the recognisers above are the fallback */
    val catcher = VoiceCatcher(main, { progress.pauseSec * 1000 }, log)

    private val micGranted: Boolean
        get() = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** true while Pleos has the microphone (its audio focus requests must not pause the lesson) */
    val pleosBusy: Boolean get() = pleos.listening

    /** 검증 모드: the child's answers are typed on the screen and the microphone stays closed */
    var typing = false
        set(value) {
            field = value
            typedQueue.clear()
            finishTyped(null)
        }
    private val typedQueue = ArrayDeque<String>()
    private var typedWaiting: ((String?) -> Unit)? = null
    private val typedTimeout = Runnable { finishTyped(null) }

    /** A typed answer ("" or null: the child said nothing). It goes to the turn that is waiting, or to the next one. */
    fun typed(text: String?) {
        if (!typing) return
        if (typedWaiting != null) finishTyped(text.orEmpty()) else typedQueue.addLast(text.orEmpty())
    }

    private fun finishTyped(text: String?) {
        val waiting = typedWaiting ?: return
        typedWaiting = null
        main.removeCallbacks(typedTimeout)
        waiting(text?.trim()?.ifEmpty { null })
    }

    /** the AI hears the child's voice itself, unless the microphone cannot be opened */
    override val hearsVoice: Boolean get() = !typing && progress.listenConsent && micGranted && !catcher.broken

    /** the child gets the pause, and a little more, to start talking */
    override fun record(waitSec: Double, result: (ByteArray?) -> Unit) = catcher.record(progress.pauseSec + 3.0, result)

    override val available: Boolean
        get() = typing || progress.listenConsent && micGranted && (!catcher.broken || pleos.usable(Lang.EN) || pleos.usable(Lang.KO) || system.available)

    override fun listen(waitSec: Double, hints: List<String>, lang: Lang, result: (String?) -> Unit) {
        val preferSystem = progress.voiceChoice == VoiceChoice.ANDROID && system.available
        val other = if (lang == Lang.KO) Lang.EN else Lang.KO
        when {
            typing -> {
                typedWaiting = result
                main.removeCallbacks(typedTimeout)
                if (typedQueue.isNotEmpty()) {
                    val next = typedQueue.removeFirst()
                    main.post { finishTyped(next) }
                } else {
                    main.postDelayed(typedTimeout, TYPE_WAIT_MS)
                }
            }
            !available -> main.post { result(null) }
            pleos.usable(lang) && !preferSystem -> pleos.listen(waitSec, lang, result)
            system.available -> system.listen(waitSec, hints, lang, result)
            // the wanted language is not there: the other one still hears something
            pleos.usable(other) -> pleos.listen(waitSec, other, result)
            else -> main.post { result(null) }
        }
    }

    override fun cancel() {
        typedWaiting = null
        main.removeCallbacks(typedTimeout)
        catcher.cancel()
        pleos.cancel()
        system.cancel()
    }

    fun status(): String = when {
        typing -> "검증 모드: 대답을 타이핑으로 넣어요"
        !progress.listenConsent -> "꺼짐 (보호자 동의 전)"
        !micGranted -> "마이크 권한 없음"
        !catcher.broken -> "AI가 직접 들어요 (한국어·영어 섞어도 돼요)"
        pleos.usable(Lang.KO) || pleos.usable(Lang.EN) -> "Pleos 음성 인식 (${pleos.languages()})"
        system.available -> "시스템 음성 인식"
        else -> "인식기 없음 → 따라 말할 시간만 줘요"
    }

    private companion object {
        /** how long a turn waits for a typed answer before it counts as silence */
        const val TYPE_WAIT_MS = 180_000L
    }

    fun release() {
        pleos.release()
        system.release()
    }
}
