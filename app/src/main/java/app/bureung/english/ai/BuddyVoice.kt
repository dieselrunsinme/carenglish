package app.bureung.english.ai

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject

/**
 * 부릉이's voice: Gemini text-to-speech for the AI chat's free answers (the emulator has no English voice of its
 * own). Lines are fetched ahead of time where possible and cached as WAV files for the session.
 */
class BuddyVoice(
    context: Context,
    private val gemini: Gemini,
    private val main: Handler,
    private val log: (String) -> Unit,
    /** a TTS model name from local.properties, when the default has moved on */
    preferredModel: String = "",
) {
    private val dir = File(context.cacheDir, "buddy").apply { mkdirs() }
    private val pool = Executors.newFixedThreadPool(3)
    private val ready = HashMap<String, File>()
    private val waiting = HashMap<String, MutableList<(File?) -> Unit>>()
    private var player: MediaPlayer? = null
    private var token = 0
    @Volatile private var model = preferredModel.ifBlank { MODEL }
    @Volatile private var newShape = true
    var lastError: String? = null
        private set

    /** after "too many requests" the voice rests until this time, and the caller's fallback voice speaks */
    @Volatile private var restUntil = 0L

    val available: Boolean get() = gemini.hasKey

    fun prefetch(text: String) = fetch(text) {}

    /** true when [text] can be played at once (made in this run, or kept from an earlier one) */
    fun isReady(text: String): Boolean = ready.containsKey(text) || file(text).let { it.exists() && it.length() > 44 }

    /** Plays [text]; [done] gets false when no audio could be made (the caller falls back to another voice). */
    fun play(text: String, done: (Boolean) -> Unit) {
        val my = ++token
        stopPlayer()
        fetch(text) { file ->
            if (my != token) return@fetch
            if (file == null) return@fetch done(false)
            val started = runCatching {
                val p = MediaPlayer()
                p.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                p.setDataSource(file.absolutePath)
                p.setOnCompletionListener { if (my == token) { stopPlayer(); done(true) } }
                p.setOnErrorListener { _, _, _ -> if (my == token) { stopPlayer(); done(false) }; true }
                p.prepare()
                p.start()
                player = p
            }.isSuccess
            if (!started) done(false)
        }
    }

    fun stop() {
        token += 1
        stopPlayer()
    }

    private fun stopPlayer() {
        val p = player ?: return
        player = null
        runCatching { p.stop() }
        runCatching { p.release() }
    }

    private fun fetch(text: String, callback: (File?) -> Unit) {
        ready[text]?.let { f -> main.post { callback(f) }; return }
        waiting[text]?.let { it += callback; return }
        if (System.currentTimeMillis() < restUntil && !isReady(text)) {
            main.post { callback(null) }
            return
        }
        waiting[text] = mutableListOf(callback)
        pool.execute {
            val file = runCatching { synthesize(text) }
                .onFailure {
                    lastError = it.message
                    if (it is GeminiError && it.code == 429) restUntil = System.currentTimeMillis() + REST_MS
                    main.post { log("부릉이 목소리 실패: ${it.message}") }
                }
                .getOrNull()
            main.post {
                if (file != null) ready[text] = file
                waiting.remove(text)?.forEach { it(file) }
            }
        }
    }

    private fun synthesize(text: String): File {
        val out = file(text)
        if (out.exists() && out.length() > 44) return out
        val response = try {
            gemini.generate(model, request(text, newShape), READ_TIMEOUT_MS)
        } catch (e: GeminiError) {
            when {
                // the older request shape (prebuiltVoiceConfig, style in the text)
                e.code == 400 && newShape -> {
                    newShape = false
                    gemini.generate(model, request(text, false), READ_TIMEOUT_MS)
                }
                e.code == 404 && model != FALLBACK_MODEL -> {
                    model = FALLBACK_MODEL
                    gemini.generate(model, request(text, newShape), READ_TIMEOUT_MS)
                }
                else -> throw e
            }
        }
        val inline = Gemini.parts(response).firstNotNullOfOrNull { it.optJSONObject("inlineData") ?: it.optJSONObject("inline_data") }
            ?: error("음성 데이터 없음")
        val bytes = Base64.getDecoder().decode(inline.getString("data"))
        val mime = inline.optString("mimeType", inline.optString("mime_type"))
        out.writeBytes(if (isWav(bytes)) bytes else wav(bytes, rate(mime)))
        lastError = null
        return out
    }

    private fun request(text: String, newShape: Boolean): JSONObject {
        val part = if (newShape) {
            JSONObject().put("text", text).put("speech_metadata", JSONObject().put("style", STYLE))
        } else {
            JSONObject().put("text", "Say in a $STYLE voice: $text")
        }
        val voiceConfig = if (newShape) {
            JSONObject().put("voice", VOICE)
        } else {
            JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", VOICE))
        }
        return JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(part))))
            .put("generationConfig", JSONObject()
                .put("responseModalities", JSONArray().put("AUDIO"))
                .put("speechConfig", JSONObject().put("voiceConfig", voiceConfig)))
    }

    private fun isWav(b: ByteArray) = b.size > 12 && String(b, 0, 4, Charsets.US_ASCII) == "RIFF"

    private fun rate(mime: String): Int =
        Regex("rate=(\\d+)").find(mime)?.groupValues?.get(1)?.toIntOrNull() ?: 24_000

    /** 16-bit mono PCM → a WAV file MediaPlayer can open */
    private fun wav(pcm: ByteArray, rate: Int): ByteArray {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + pcm.size); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(rate); putInt(rate * 2)
            putShort(2); putShort(16)
            put("data".toByteArray()); putInt(pcm.size)
        }.array()
        return header + pcm
    }

    private fun file(text: String) = File(dir, hash(text) + ".wav")

    private fun hash(text: String): String =
        MessageDigest.getInstance("SHA-1").digest((VOICE + text).toByteArray()).joinToString("") { "%02x".format(it) }.take(16)

    companion object {
        const val MODEL = "gemini-3.8-flash-tts"
        const val FALLBACK_MODEL = "gemini-3.8-flash-lite-tts"
        /** an upbeat voice, unlike the teacher's recordings: 부릉이 is a different character */
        const val VOICE = "Puck"
        private const val READ_TIMEOUT_MS = 15_000
        private const val REST_MS = 30_000L
        const val STYLE = "cheerful, warm and slow, like a friendly cartoon car talking to a five-year-old"
    }
}
