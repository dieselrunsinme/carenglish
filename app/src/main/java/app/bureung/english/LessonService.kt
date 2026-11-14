package app.bureung.english

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import app.bureung.english.lesson.EngineState
import app.bureung.english.ui.MainActivity

/**
 * Keeps the process in the foreground while a lesson can run: once the car is in D the driving screen covers the
 * app, and the lesson has to keep playing (and auto-start) behind it. Started from the visible screen, so it is
 * always allowed to start.
 */
class LessonService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = notification(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(ID, n)
        }
        return START_STICKY
    }

    companion object {
        private const val ID = 7
        private const val CHANNEL = "lesson"
        @Volatile private var running = false

        fun start(context: Context) {
            if (running) return refresh(context)
            runCatching {
                context.startForegroundService(Intent(context, LessonService::class.java))
                running = true
            }.onFailure { Hub.note("수업 서비스 시작 실패: ${it.message}") }
        }

        fun stop(context: Context) {
            running = false
            runCatching { context.stopService(Intent(context, LessonService::class.java)) }
        }

        fun refresh(context: Context) {
            if (!running) return
            runCatching { context.getSystemService(NotificationManager::class.java).notify(ID, notification(context)) }
        }

        private fun notification(context: Context): Notification {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, context.getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW),
                )
            }
            val text = if (!Hub.started) {
                "준비 중"
            } else {
                when (Hub.engine.state) {
                    EngineState.PLAYING ->
                        if (Hub.engine.plan?.chat != null) "부릉이와 영어로 이야기 중" else "수업 중 · ${Hub.engine.plan?.episode?.title ?: ""}"
                    EngineState.PAUSED -> "잠깐 멈춤"
                    else -> if (Hub.progress.autoStart) "출발하면 부릉이가 영어로 말을 걸어요" else "준비됨"
                }
            }
            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            return Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build()
        }
    }
}
