package app.bureung.english.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler

/**
 * Audio focus for the lesson, so the car's own sounds come first. The lesson is media: navigation guidance ducks
 * it, a call or another app pauses it. Losses caused by our own Pleos voice ([isOwnVoice]) are ignored.
 */
class Focus(
    context: Context,
    private val main: Handler,
    private val isOwnVoice: () -> Boolean,
    private val onPause: () -> Unit,
    private val onResume: () -> Unit,
) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private var held = false
    private var pausedByFocus = false

    private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        .setWillPauseWhenDucked(false)
        .setOnAudioFocusChangeListener({ change -> onChange(change) }, main)
        .build()

    fun acquire() {
        if (held) return
        held = runCatching { audio?.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED }.getOrDefault(false)
        pausedByFocus = false
    }

    fun release() {
        if (!held) return
        runCatching { audio?.abandonAudioFocusRequest(request) }
        held = false
        pausedByFocus = false
    }

    private fun onChange(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> if (!isOwnVoice()) {
                pausedByFocus = true
                onPause()
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                if (isOwnVoice()) return
                held = false
                pausedByFocus = false
                onPause()
            }
            AudioManager.AUDIOFOCUS_GAIN -> if (pausedByFocus) {
                pausedByFocus = false
                onResume()
            }
            // AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK: the system lowers our volume under navigation guidance
        }
    }
}
