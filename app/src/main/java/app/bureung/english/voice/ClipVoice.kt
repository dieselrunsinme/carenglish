package app.bureung.english.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import app.bureung.english.lesson.Lang
import java.security.MessageDigest

/**
 * Recorded lines in assets/voice, named `<en|ko>_<first 10 hex of SHA-1 of the text>.<mp3|m4a|ogg|wav>`.
 * A recording always wins over synthesis, so chants or a teacher's own voice can be dropped in line by line
 * (tools/lines.py prints every line with its file name). Lines with the child's name are always synthesised.
 */
class ClipVoice(context: Context, private val log: (String) -> Unit) {
    private val app = context.applicationContext
    private val files: Map<String, String> = runCatching {
        app.assets.list("voice").orEmpty().associateBy { it.substringBeforeLast('.') }
    }.getOrDefault(emptyMap())
    private var player: MediaPlayer? = null

    val count: Int get() = files.size

    fun has(text: String, lang: Lang): Boolean = key(text, lang) in files

    /** Returns false when there is no recording; otherwise [done] runs once when it ends or fails. */
    fun play(text: String, lang: Lang, done: () -> Unit): Boolean {
        val file = files[key(text, lang)] ?: return false
        stop()
        return runCatching {
            val p = MediaPlayer()
            app.assets.openFd("voice/$file").use { fd -> p.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length) }
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            p.setOnCompletionListener { stop(); done() }
            p.setOnErrorListener { _, _, _ -> stop(); done(); true }
            p.prepare()
            p.start()
            player = p
            true
        }.onFailure { log("녹음 재생 실패 $file: ${it.message}") }.getOrDefault(false)
    }

    fun stop() {
        val p = player ?: return
        player = null
        runCatching { p.stop() }
        runCatching { p.release() }
    }

    companion object {
        fun key(text: String, lang: Lang): String {
            val digest = MessageDigest.getInstance("SHA-1").digest(text.trim().toByteArray(Charsets.UTF_8))
            val hex = digest.joinToString("") { "%02x".format(it) }
            return (if (lang == Lang.EN) "en_" else "ko_") + hex.take(10)
        }
    }
}
