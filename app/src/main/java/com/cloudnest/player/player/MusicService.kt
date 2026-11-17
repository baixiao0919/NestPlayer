package com.cloudnest.player.player

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * 后台音乐播放服务：持有 MediaSession，负责通知栏、锁屏、耳机按键、蓝牙控制。
 *
 * 关键点：Android 规定 startForegroundService() 之后 5 秒内必须 startForeground()，
 * 而 Media3 是在播放器进入播放态时才挂通知，NAS 取流慢就会超时闪退。
 * 所以这里自己在 onCreate/onStartCommand 里先挂一条基础通知完成前台化，
 * 之后 Media3 用同一个通知 id 覆盖成完整的播放控制通知。
 */
@OptIn(UnstableApi::class)
class MusicService : MediaSessionService() {

    private var session: MediaSession? = null
    private var foregrounded = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // 让 Media3 使用和我们相同的通知 id，后续更新时直接覆盖这条基础通知
        runCatching {
            setMediaNotificationProvider(
                DefaultMediaNotificationProvider.Builder(this)
                    .setNotificationId(NOTIF_ID)
                    .build()
            )
        }
        val player = MusicCore.ensure(this)
        session = MediaSession.Builder(this, player).build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 无论 Media3 什么时候挂通知，这里先把 5 秒限制满足掉
        ensureForeground()
        val result = runCatching { super.onStartCommand(intent, flags, startId) }
        return result.getOrDefault(START_STICKY)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val playing = MusicCore.player?.let { it.isPlaying || it.playWhenReady } == true
        if (!playing) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        MusicCore.saveProgress(this)
        session?.release()
        session = null
        foregrounded = false
        super.onDestroy()
    }

    // ---------------- 前台通知 ----------------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "音乐播放",
                        NotificationManager.IMPORTANCE_LOW
                    ).apply {
                        description = "后台音乐播放控制"
                        setShowBadge(false)
                    }
                )
            }
        }
    }

    private fun ensureForeground() {
        if (foregrounded) return
        runCatching {
            createChannel()
            val icon = applicationInfo.icon.takeIf { it != 0 }
                ?: android.R.drawable.ic_media_play
            // 点击通知回到 App
            val launch = packageManager.getLaunchIntentForPackage(packageName)
            val pi = launch?.let {
                PendingIntent.getActivity(
                    this, 0, it,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            }
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(icon)
                .setContentTitle("云巢影音")
                .setContentText("音乐播放")
                .setOngoing(true)
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(pi)
                .build()
            startForeground(NOTIF_ID, notification)
            foregrounded = true
        }
    }

    companion object {
        private const val CHANNEL_ID = "nestplayer_music"
        private const val NOTIF_ID = 1001

        fun start(context: Context) = MusicServiceLauncher.start(context)
    }
}
