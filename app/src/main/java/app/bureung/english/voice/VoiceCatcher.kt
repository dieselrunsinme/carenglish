package app.bureung.english.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Records one answer from the child for the AI to listen to (so Korean and English mixed in one sentence come
 * through as spoken). It waits for speech, keeps recording while the child talks, and stops after a pause (the
 * parent sets how long: children think mid-sentence), so a
 * child is not cut off mid-sentence. The sound lives in memory only: it is sent once and dropped, never saved.
 */
class VoiceCatcher(private val main: Handler, private val pauseMs: () -> Int, private val log: (String) -> Unit) {
    /** how loud the microphone is right now, 0 to 1, a few times a second while recording (for the level bar) */
    @Volatile var onLevel: ((Float) -> Unit)? = null
    /** the microphone the last recording came from, as the car reported it */
    @Volatile var lastMic: String? = null
        private set
    @Volatile private var session = 0
    @Volatile var recording = false
        private set
    /** set when the microphone could not be opened: the caller falls back to on-device recognition */
    @Volatile var broken = false
        private set

    @SuppressLint("MissingPermission")
    fun record(waitSec: Double, result: (ByteArray?) -> Unit) {
        val my = ++session
        Thread {
            val wav = runCatching { capture(my, waitSec) }
                .onFailure {
                    broken = true
                    main.post { log("마이크 녹음 실패 → 차 안 음성 인식으로: ${it.message}") }
                }
                .getOrNull()
            recording = false
            main.post { if (my == session) result(wav) }
        }.apply { name = "voice-catcher"; isDaemon = true }.start()
    }

    fun cancel() {
        session += 1
    }

    @SuppressLint("MissingPermission")
    private fun capture(my: Int, waitSec: Double): ByteArray? {
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(min > 0) { "지원하지 않는 형식" }
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, FRAME * 8))
        check(rec.state == AudioRecord.STATE_INITIALIZED) { "마이크를 열 수 없음" }
        val pcm = ByteArrayOutputStream()
        val frame = ShortArray(FRAME)
        val lead = ArrayDeque<ShortArray>()
        // the room's noise level: the quietest stretch heard so far (a car is never silent, and a child may
        // start talking at once, so the first moments alone cannot be trusted)
        val recent = ArrayDeque<Double>()
        var floor = Double.MAX_VALUE
        var peak = 0.0
        var speechMs = 0
        var quietMs = 0
        var waitedMs = 0
        var frames = 0
        val pause = pauseMs()
        var started = false
        try {
            rec.startRecording()
            recording = true
            // which microphone the car gave us (the app asks for "voice recognition"; the car's audio system picks)
            val mic = rec.routedDevice?.let { Mics.describe(it) } ?: "알 수 없음"
            if (mic != lastMic) {
                lastMic = mic
                main.post { log("녹음에 쓰인 마이크: $mic") }
            }
            while (my == session) {
                val n = rec.read(frame, 0, FRAME)
                if (n <= 0) break
                val level = rms(frame, n)
                peak = maxOf(peak, level)
                recent.addLast(level)
                if (recent.size > NOISE_FRAMES) recent.removeFirst()
                if (recent.size == NOISE_FRAMES) floor = minOf(floor, recent.average())
                val threshold = maxOf(if (floor == Double.MAX_VALUE) 0.0 else floor * 2.5, MIN_LEVEL)
                val loud = level > threshold
                frames += 1
                if (frames % LEVEL_EVERY == 0) {
                    // below the speech threshold the bar barely moves; above it, it grows with the loudness
                    val shown = if (level < threshold) 0.1 * level / threshold else 0.18 + 0.82 * (log10(level / threshold) / 1.2).coerceIn(0.0, 1.0)
                    main.post { if (my == session) onLevel?.invoke(shown.toFloat()) }
                }
                if (!started) {
                    lead.addLast(frame.copyOf(n))
                    if (lead.size > LEAD_FRAMES) lead.removeFirst()
                    speechMs = if (loud) speechMs + FRAME_MS else 0
                    waitedMs += FRAME_MS
                    if (speechMs >= START_MS) {
                        started = true
                        lead.forEach { write(pcm, it, it.size) }
                        lead.clear()
                    } else if (waitedMs >= waitSec * 1000) {
                        break
                    }
                } else {
                    write(pcm, frame, n)
                    if (loud) {
                        speechMs += FRAME_MS
                        quietMs = 0
                    } else {
                        quietMs += FRAME_MS
                    }
                    // a pause ends the answer; a long answer is cut at the limit
                    if (quietMs >= pause || pcm.size() >= MAX_MS / 1000 * RATE * 2) break
                }
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
        if (my != session) return null
        val room = if (floor == Double.MAX_VALUE) 0 else floor.toInt()
        if (!started) {
            main.post { log("아이 말 없음 (주변 소리 $room, 가장 큰 소리 ${peak.toInt()})") }
            return null
        }
        val seconds = pcm.size() / 2.0 / RATE
        main.post { log("아이 말 ${"%.1f".format(seconds)}초 들음 (주변 소리 $room, 가장 큰 소리 ${peak.toInt()})") }
        return wav(pcm.toByteArray())
    }

    private fun rms(frame: ShortArray, n: Int): Double {
        var sum = 0.0
        for (i in 0 until n) sum += frame[i].toDouble() * frame[i]
        return sqrt(sum / n)
    }

    private fun write(out: ByteArrayOutputStream, frame: ShortArray, n: Int) {
        val bytes = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) bytes.putShort(frame[i])
        out.write(bytes.array(), 0, n * 2)
    }

    private fun wav(pcm: ByteArray): ByteArray {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + pcm.size); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(RATE); putInt(RATE * 2)
            putShort(2); putShort(16)
            put("data".toByteArray()); putInt(pcm.size)
        }.array()
        return header + pcm
    }

    private companion object {
        const val RATE = 16_000
        const val FRAME_MS = 20
        const val FRAME = RATE * FRAME_MS / 1000
        /** frames averaged for the room's noise level */
        const val NOISE_FRAMES = 8
        /** sound kept from just before speech was noticed, so the first syllable is not lost */
        const val LEAD_FRAMES = 20
        /** quieter than this never counts as speech */
        const val MIN_LEVEL = 450.0
        /** this much continuous sound counts as the start of speech */
        const val START_MS = 120
        const val MAX_MS = 20_000
        /** the level bar moves every third frame (60 ms) */
        const val LEVEL_EVERY = 3
    }
}
