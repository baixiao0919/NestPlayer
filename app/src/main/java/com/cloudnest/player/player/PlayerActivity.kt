package com.cloudnest.player.player

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.cloudnest.player.data.HistoryEntry
import com.cloudnest.player.data.Store
import com.cloudnest.player.net.FsManager
import com.cloudnest.player.net.MediaFiles
import com.cloudnest.player.net.StreamProxy
import com.cloudnest.player.ui.theme.NestTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@UnstableApi
class PlayerActivity : ComponentActivity() {

    companion object {
        const val EXTRA_SERVER = "server_id"
        const val EXTRA_PATH = "path"
        const val EXTRA_NAME = "name"
        const val EXTRA_DIR = "dir_path"

        fun intent(context: Context, serverId: String, path: String, name: String, dirPath: String): Intent =
            Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_SERVER, serverId)
                .putExtra(EXTRA_PATH, path)
                .putExtra(EXTRA_NAME, name)
                .putExtra(EXTRA_DIR, dirPath)
    }

    private var player: ExoPlayer? = null
    private val audioManager by lazy { getSystemService(AudioManager::class.java) }
    private var savedPosition = 0L
    private var lastSave = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        StreamProxy.start()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        when (Store.settings.playerOrientation) {
            1 -> requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            2 -> requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else -> requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
        }

        val serverId = intent.getStringExtra(EXTRA_SERVER) ?: ""
        val path = intent.getStringExtra(EXTRA_PATH) ?: ""
        val name = intent.getStringExtra(EXTRA_NAME) ?: ""
        val dirPath = intent.getStringExtra(EXTRA_DIR) ?: path.substringBeforeLast('/', "")

        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this, TsSeek.factory()))
            .build()
        player = exo

        setContent {
            NestTheme(dark = true) {
                PlayerScreen(
                    activity = this,
                    player = exo,
                    serverId = serverId,
                    startPath = path,
                    startName = name,
                    dirPath = dirPath
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        player?.playWhenReady = true
    }

    override fun onStop() {
        super.onStop()
        savePosition(force = true)
        player?.pause()
    }

    override fun onDestroy() {
        savePosition(force = true)
        player?.release()
        player = null
        super.onDestroy()
    }

    private var currentKey: String? = null
    private var currentServer: String = ""
    private var currentPath: String = ""
    private var currentName: String = ""

    fun bind(key: String, serverId: String, path: String, name: String) {
        currentKey = key
        currentServer = serverId
        currentPath = path
        currentName = name
    }

    fun savePosition(force: Boolean = false) {
        val p = player ?: return
        val key = currentKey ?: return
        if (key.isEmpty()) return
        val pos = p.currentPosition
        val dur = p.duration.takeIf { it > 0 } ?: return
        if (dur <= 0) return
        val now = System.currentTimeMillis()
        if (!force && now - lastSave < 2000) return
        lastSave = now
        runCatching {
            Store.saveHistory(
                HistoryEntry(
                    key = key,
                    serverId = currentServer,
                    path = currentPath,
                    name = currentName,
                    positionMs = pos,
                    durationMs = dur,
                    updatedAt = now,
                    finished = pos >= dur - 15000 && dur > 0
                )
            )
        }
    }

    fun resumePosition(): Long {
        val key = currentKey ?: return 0L
        val h = Store.historyFor(currentServer, currentPath) ?: return 0L
        return if (h.positionMs > 10000L && h.positionMs < h.durationMs * 0.95) h.positionMs else 0L
    }

    fun audio(): AudioManager = audioManager

    fun setBrightness(v: Float) {
        val lp = window.attributes
        lp.screenBrightness = v.coerceIn(0.02f, 1f)
        window.attributes = lp
    }

    fun currentBrightness(): Float {
        val b = window.attributes.screenBrightness
        return if (b < 0) 0.5f else b
    }

    fun toggleOrientation() {
        requestedOrientation = if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE)
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    }
}
