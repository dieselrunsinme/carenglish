package app.bureung.english.voice

import android.content.Context
import android.os.Handler
import app.bureung.english.ai.BuddyVoice
import app.bureung.english.lesson.Estimate
import app.bureung.english.lesson.Lang
import app.bureung.english.lesson.Sentences
import app.bureung.english.lesson.Speaker
import app.bureung.english.lesson.TalkRules
import app.bureung.english.store.Progress
import app.bureung.english.store.VoiceChoice

/**
 * Picks a voice for each line: a recording if there is one, then Pleos TTS or the system TTS by the parent's
 * choice (by default English goes to the system's native en-US voice and Korean to Pleos). A line is spoken one
 * sentence at a time ("Hi, Harin!" then "Welcome back!"), because an engine that reports "done" after its first
 * sentence would otherwise cut the rest. If nothing can speak, the lesson keeps its rhythm with the caption on
 * screen. A watchdog makes sure a lost callback never stalls a lesson.
 */
class VoiceRouter(
    context: Context,
    private val main: Handler,
    private val progress: Progress,
    private val log: (String) -> Unit,
    /** 부릉이's cloud voice for the AI chat (null without a key) */
    val buddy: BuddyVoice? = null,
) : Speaker {
    val clips = ClipVoice(context, log)
    val pleos = PleosVoice(context, main, log)
    val system = AndroidVoice(context, main, log)

    private enum class Engine(val label: String) {
        CLIP("녹음된 목소리"),
        BUDDY("부릉이 목소리(Gemini)"),
        PLEOS("Pleos TTS"),
        SYSTEM("시스템 TTS"),
        NONE("목소리 없음(자막만)"),
    }

    /** bumped by stop(): every callback from an older line is ignored */
    private var session = 0
    private var watchdog: Runnable? = null
    private val lastEngine = HashMap<Lang, Engine>()

    /** true while Pleos is speaking: its own audio focus requests must not pause the lesson */
    val pleosBusy: Boolean get() = pleos.speaking

    /** which engine spoke the last line of each language, for the settings screen */
    fun engineFor(lang: Lang): String = lastEngine[lang]?.label ?: "아직 없음"

    override fun prefetchBuddy(texts: List<String>) {
        val b = buddy ?: return
        if (b.available) texts.forEach(b::prefetch)
    }

    override fun hasBuddy(text: String): Boolean = buddy?.let { it.available && it.isReady(text) } == true

    /** 부릉이's line: the cloud voice, or the usual English voices when it cannot be made. */
    override fun speakBuddy(text: String, done: () -> Unit) {
        val b = buddy
        if (b == null || !b.available) return speak(text, Lang.EN, done)
        val my = ++session
        clearWatchdog()
        sayOne(my, text, Lang.EN, done, extraSec = NETWORK_SEC) { once ->
            b.play(text) { ok ->
                if (my != session) return@play
                if (ok) {
                    used(Lang.EN, Engine.BUDDY)
                    once()
                } else {
                    // no cloud voice: the recordings, one sentence at a time (every fixed line of the conversation
                    // is recorded), then any voice the car has
                    chain(Sentences.split(text), 0, Lang.EN, my, once)
                }
            }
        }
    }

    override fun speak(text: String, lang: Lang, done: () -> Unit) {
        val my = ++session
        clearWatchdog()
        if (clips.has(text, lang)) {
            sayOne(my, text, lang, done) { once -> if (!clips.play(text, lang, once)) once() }
            return
        }
        chain(Sentences.split(text), 0, lang, my, done)
    }

    private fun chain(parts: List<String>, i: Int, lang: Lang, my: Int, done: () -> Unit) {
        if (my != session) return
        if (i >= parts.size) return done()
        sayOne(my, parts[i], lang, {
            if (i + 1 < parts.size) main.postDelayed({ chain(parts, i + 1, lang, my, done) }, GAP_MS)
            else chain(parts, i + 1, lang, my, done)
        }) { once -> sentence(parts[i], lang, once) }
    }

    /**
     * One sentence: the recording if there is one (the same teacher's voice everywhere), the recording without a
     * child's name (this child's or a friend's riding along), then synthesis.
     */
    private fun sentence(text: String, lang: Lang, once: () -> Unit) {
        val names = listOf(progress.nameEn) + TalkRules.companions(progress.companions).map { it.first }
        val recorded = text.takeIf { clips.has(it, lang) }
            ?: names.firstNotNullOfOrNull { n -> Sentences.withoutName(text, n)?.takeIf { clips.has(it, lang) } }
        if (recorded != null && clips.play(recorded, lang, once)) {
            used(lang, Engine.CLIP)
            return
        }
        speakWith(order(lang), text, lang, once)
    }

    /** Runs [start] with a once-only completion guarded by the session and a watchdog. */
    private fun sayOne(
        my: Int,
        text: String,
        lang: Lang,
        next: () -> Unit,
        extraSec: Double = 0.0,
        start: (once: () -> Unit) -> Unit,
    ) {
        var finished = false
        val dog = Runnable {
            if (!finished && my == session) {
                finished = true
                log("목소리 응답 없음 → 다음으로: ${text.take(24)}")
                stopEngines()
                next()
            }
        }
        val once: () -> Unit = {
            if (!finished && my == session) {
                finished = true
                main.removeCallbacks(dog)
                next()
            }
        }
        clearWatchdog()
        watchdog = dog
        main.postDelayed(dog, ((Estimate.speech(text, lang) * 2.5 + 5.0 + extraSec) * 1000).toLong())
        start(once)
    }

    private fun speakWith(engines: List<Engine>, text: String, lang: Lang, done: () -> Unit) {
        engines.forEachIndexed { i, engine ->
            val started = when (engine) {
                Engine.PLEOS -> pleos.speak(text, lang, done) { speakWith(engines.drop(i + 1), text, lang, done) }
                Engine.SYSTEM -> system.speak(text, lang, done)
                Engine.CLIP, Engine.BUDDY, Engine.NONE -> false
            }
            if (started) {
                used(lang, engine)
                return
            }
        }
        // no voice at all: keep the rhythm, the caption is on screen
        used(lang, Engine.NONE)
        main.postDelayed({ done() }, (Estimate.speech(text, lang) * 1000).toLong())
    }

    private fun used(lang: Lang, engine: Engine) {
        if (lastEngine[lang] != engine) {
            lastEngine[lang] = engine
            log("${if (lang == Lang.EN) "영어" else "한국어"} 목소리 → ${engine.label}")
        }
    }

    private fun order(lang: Lang): List<Engine> = when (progress.voiceChoice) {
        VoiceChoice.PLEOS -> listOf(Engine.PLEOS, Engine.SYSTEM)
        VoiceChoice.ANDROID -> listOf(Engine.SYSTEM, Engine.PLEOS)
        VoiceChoice.AUTO -> if (lang == Lang.EN) listOf(Engine.SYSTEM, Engine.PLEOS) else listOf(Engine.PLEOS, Engine.SYSTEM)
    }

    override fun stop() {
        session += 1
        clearWatchdog()
        stopEngines()
    }

    private fun clearWatchdog() {
        watchdog?.let(main::removeCallbacks)
        watchdog = null
    }

    private fun stopEngines() {
        buddy?.stop()
        clips.stop()
        pleos.stop()
        system.stop()
    }

    fun release() {
        stop()
        pleos.release()
        system.release()
    }

    companion object {
        /** a short breath between sentences of one line */
        const val GAP_MS = 180L
        /** time allowed for 부릉이's voice to come back from the network */
        const val NETWORK_SEC = 12.0
    }
}
