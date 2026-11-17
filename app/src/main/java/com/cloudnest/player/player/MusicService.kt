package com.cloudnest.player.player

import android.content.Context
import android.content.Intent
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * 后台音乐播放服务：持有 MediaSession，负责通知栏、锁屏、耳机按键、蓝牙控制。
 * 播放器实例由 MusicCore 统一管理，这里只做会话壳。
 */
class MusicService : MediaSessionService() {

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = MusicCore.ensure(this)
        session = MediaSession.Builder(this, player).build()
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
        super.onDestroy()
    }

    companion object {
        fun start(context: Context) = MusicServiceLauncher.start(context)
    }
}
