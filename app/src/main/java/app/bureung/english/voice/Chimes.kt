package app.bureung.english.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import app.bureung.english.lesson.Sounds
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/** Sounds made in code (no audio files to license): the intro tune, ding for a hit, chime to start, yay at the end. */
class Chimes(private val main: Handler) : Sounds {
    private data class Note(val hz: Double, val at: Double, val length: Double)

    private val cache = HashMap<String, ShortArray>()

    private fun notes(name: String): List<Note> = when (name) {
        "ding" -> listOf(Note(1318.5, 0.0, 0.35), Note(1760.0, 0.09, 0.45))
        "chime" -> listOf(Note(784.0, 0.0, 0.35), Note(988.0, 0.13, 0.35), Note(1174.7, 0.26, 0.55))
        "yay" -> listOf(Note(1046.5, 0.0, 0.2), Note(1318.5, 0.12, 0.2), Note(1568.0, 0.24, 0.2), Note(2093.0, 0.36, 0.6))
        "pop" -> listOf(Note(880.0, 0.0, 0.12))
        // "your turn": two quick rising notes, then the microphone opens
        // 띵동: the turn passes to the child (up), and back to 부릉이 (down)
        "listen" -> listOf(Note(784.0, 0.0, 0.18), Note(1174.7, 0.14, 0.32))
        "over" -> listOf(Note(1174.7, 0.0, 0.18), Note(784.0, 0.14, 0.32))
        "intro" -> INTRO
        "beat" -> (0 until 4).map { Note(if (it == 3) 1400.0 else 1000.0, it * 0.5, 0.08) }
        else -> listOf(Note(1046.5, 0.0, 0.25))
    }

    private fun synth(name: String): ShortArray {
        val list = notes(name)
        val seconds = list.maxOf { it.at + it.length } + 0.05
        val samples = DoubleArray((seconds * RATE).toInt())
        for (n in list) {
            val start = (n.at * RATE).toInt()
            val count = (n.length * RATE).toInt()
            for (i in 0 until count) {
                val t = i.toDouble() / RATE
                // soft attack, bell-like decay, a touch of the octave for warmth
                val env = minOf(1.0, t / 0.005) * exp(-t * 7.0)
                val v = sin(2 * PI * n.hz * t) + 0.25 * sin(4 * PI * n.hz * t)
                if (start + i < samples.size) samples[start + i] += v * env * 0.22
            }
        }
        return ShortArray(samples.size) { (samples[it].coerceIn(-1.0, 1.0) * Short.MAX_VALUE).toInt().toShort() }
    }

    override fun play(name: String) {
        val pcm = cache.getOrPut(name) { synth(name) }
        runCatching {
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * 2)
                .build()
            track.write(pcm, 0, pcm.size)
            track.play()
            main.postDelayed({ runCatching { track.release() } }, pcm.size * 1000L / RATE + 300)
        }
    }

    private companion object {
        const val RATE = 22050

        /**
         * The opening tune (about four seconds): "English time is starting". A rising bell melody over a simple
         * bass, ending on two little car-horn beeps.
         */
        val INTRO: List<Note> = run {
            val beat = 0.17
            val melody = listOf(
                523.25 to 1, 659.25 to 1, 783.99 to 1, 1046.5 to 2,
                880.0 to 1, 783.99 to 1, 659.25 to 1, 783.99 to 2,
                698.46 to 1, 880.0 to 1, 1046.5 to 1, 1318.5 to 2,
                1174.7 to 1, 1046.5 to 3,
            )
            var at = 0.0
            val notes = mutableListOf<Note>()
            melody.forEach { (hz, beats) ->
                notes += Note(hz, at, beats * beat + 0.25)
                at += beats * beat
            }
            // bass on each bar: C, A, F, G, C
            listOf(261.63 to 0, 220.0 to 5, 174.61 to 10, 196.0 to 15, 261.63 to 16).forEach { (hz, b) ->
                notes += Note(hz, b * beat, 0.7)
            }
            // beep-beep
            notes += Note(987.77, at + 0.25, 0.1)
            notes += Note(987.77, at + 0.42, 0.16)
            notes
        }
    }
}
