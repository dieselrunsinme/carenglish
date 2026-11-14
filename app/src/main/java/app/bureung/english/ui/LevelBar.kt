package app.bureung.english.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import app.bureung.english.Hub

/**
 * Shows that the child's voice is coming in: a row of bars that move with the loudness of the microphone while it
 * is the child's turn. No words to read, so it is fine on the driving screen too.
 */
class LevelBar(context: Context, private val color: Int, private val quiet: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        val levels = Hub.micLevels
        val n = levels.size
        val gap = width / (n * 2f - 1f)
        val head = Hub.micHead
        for (i in 0 until n) {
            // oldest on the left, newest on the right
            val v = levels[(head + i) % n]
            val h = (height * (0.12f + 0.88f * v)).coerceAtLeast(gap)
            val x = i * gap * 2f
            paint.color = if (v > 0.14f) color else quiet
            canvas.drawRoundRect(x, (height - h) / 2f, x + gap, (height + h) / 2f, gap / 2f, gap / 2f, paint)
        }
    }
}
