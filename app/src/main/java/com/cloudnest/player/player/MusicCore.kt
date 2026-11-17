package com.cloudnest.player.player

import android.content.Context
import android.content.Intent
import android.media.audiofx.Equalizer
import android.net.Uri
import android.os.Build
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.cloudnest.player.data.HistoryEntry
import com.cloudnest.player.data.MusicTrack
import com.cloudnest.player.data.Store
import com.cloudnest.player.lib.MusicMeta
import com.cloudnest.player.net.FsManager
import com.cloudnest.player.net.StreamProxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * 音乐播放内核（进程内单例）。
 *
 * 为什么不把播放器和 UI 绑死在一个 Activity：音乐要能在后台/锁屏继续放，
 * 所以 ExoPlayer 与 MediaSession 都挂在 MusicService 上，UI 只是操作同一个实例。
 */
object MusicCore {

    private var exo: ExoPlayer? = null
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    var queue: List<MusicTrack> = emptyList()
        private set

    var currentId: String? = null
        private set

    /** 内嵌封面 / 歌词缓存，按曲目 id 存 */
    private val coverCache = HashMap<String, File>()
    private val lrcCache = HashMap<String, List<com.cloudnest.player.lib.Lyrics.Line>>()

    private var eq: Equalizer? = null
    private var sleepJob: Job? = null
    var sleepEndsAt: Long = 0L
        private set

    val player: ExoPlayer? get() = exo

    fun ensure(context: Context): ExoPlayer {
        exo?.let { return it }
        val p = ExoPlayer.Builder(context.applicationContext)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        p.repeatMode = Player.REPEAT_MODE_ALL
        p.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                currentId = mediaItem?.mediaId
                mediaItem?.mediaId?.let { id ->
                    val track = queue.firstOrNull { it.id == id }
                    if (track != null) prepareTrackAssets(context, track)
                }
            }
        })
        exo = p
        return p
    }

    /** 供 MediaSessionService 使用 */
    fun attach(context: Context): ExoPlayer {
        val p = ensure(context)
        runCatching { MusicService.start(context) }
        return p
    }

    private fun itemOf(context: Context, track: MusicTrack): MediaItem {
        val url = StreamProxy.url(track.serverId, track.path, track.name)
        val cover = coverCache[track.id] ?: MusicMeta.cachedCover(context, track.serverId, track.path)
        val md = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist.ifBlank { track.album })
            .setAlbumTitle(track.album)
            .apply { cover?.let { setArtworkUri(Uri.fromFile(it)) } }
            .build()
        return MediaItem.Builder()
            .setUri(url)
            .setMediaId(track.id)
            .setMediaMetadata(md)
            .build()
    }

    fun playQueue(context: Context, tracks: List<MusicTrack>, startIndex: Int, startPositionMs: Long = 0L) {
        if (tracks.isEmpty()) return
        val p = attach(context)
        queue = tracks
        val idx = startIndex.coerceIn(0, tracks.size - 1)
        p.setMediaItems(tracks.map { itemOf(context, it) }, idx, startPositionMs.coerceAtLeast(0))
        p.prepare()
        p.playWhenReady = true
        currentId = tracks[idx].id
        prepareTrackAssets(context, tracks[idx])
    }

    /** 补歌名/艺术家/时长/内嵌封面/歌词；只在切歌时跑一次 */
    private fun prepareTrackAssets(context: Context, track: MusicTrack) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                val fs = FsManager.get(track.serverId)
                if (MusicMeta.cachedCover(context, track.serverId, track.path) == null) {
                    val tags = MusicMeta.read(fs, track.path, track.name, track.size)
                    tags.cover?.let { bytes ->
                        val f = MusicMeta.coverFile(context, track.serverId, track.path, bytes)
                        coverCache[track.id] = f
                    }
                    val patch = track.copy(
                        title = tags.title?.takeIf { it.isNotBlank() } ?: track.title,
                        artist = tags.artist?.takeIf { it.isNotBlank() } ?: track.artist,
                        album = tags.album?.takeIf { it.isNotBlank() } ?: track.album,
                        trackNo = tags.trackNo ?: track.trackNo,
                        durationMs = if (tags.durationMs > 0) tags.durationMs else track.durationMs
                    )
                    if (patch != track) Store.updateMusicTrack(patch)
                    // 通知里的封面也要跟着更新
                    scope.launch(Dispatchers.Main) { refreshArtwork(context, patch) }
                }
                if (!lrcCache.containsKey(track.id) && !track.lrcPath.isNullOrBlank()) {
                    LyricsLoader.load(fs, track)?.let { lrcCache[track.id] = it }
                }
            }
        }
    }

    private fun refreshArtwork(context: Context, track: MusicTrack) {
        val p = exo ?: return
        // 播放中替换 MediaItem 会让位置复位，直接跳过：下一次切歌会带着封面
        if (p.isPlaying) return
        val idx = p.currentMediaItemIndex
        if (idx < 0 || p.getMediaItemAt(idx).mediaId != track.id) return
        val cover = coverCache[track.id] ?: return
        val md = p.getMediaItemAt(idx).mediaMetadata.buildUpon()
            .setArtworkUri(Uri.fromFile(cover))
            .build()
        p.replaceMediaItem(idx, itemOf(context, track).buildUpon().setMediaMetadata(md).build())
    }

    object LyricsLoader {
        fun load(fs: com.cloudnest.player.net.RemoteFs, track: MusicTrack): List<com.cloudnest.player.lib.Lyrics.Line>? {
            val path = track.lrcPath ?: return null
            val text = com.cloudnest.player.lib.Lyrics.read(fs, path, path.substringAfterLast('/')) ?: return null
            val lines = com.cloudnest.player.lib.Lyrics.parse(text)
            return lines.ifEmpty { null }
        }
    }

    fun lyricsOf(trackId: String?): List<com.cloudnest.player.lib.Lyrics.Line> =
        trackId?.let { lrcCache[it] } ?: emptyList()

    fun coverOf(trackId: String?): File? = trackId?.let { coverCache[it] }

    fun toggle(context: Context) {
        val p = attach(context)
        if (p.isPlaying) p.pause() else {
            if (p.playbackState == Player.STATE_IDLE) p.prepare()
            p.playWhenReady = true
        }
    }

    fun pause() {
        exo?.pause()
    }

    fun next() {
        exo?.seekToNext()
        exo?.playWhenReady = true
    }

    fun previous() {
        val p = exo ?: return
        if (p.currentPosition > 3000) p.seekTo(0) else p.seekToPrevious()
        p.playWhenReady = true
    }

    fun seekTo(ms: Long) = exo?.seekTo(ms)

    fun setSpeed(speed: Float) = exo?.setPlaybackSpeed(speed)

    fun setShuffle(on: Boolean) { exo?.shuffleModeEnabled = on }

    fun cycleRepeat(): Int {
        val p = exo ?: return Player.REPEAT_MODE_OFF
        p.repeatMode = when (p.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        return p.repeatMode
    }

    fun sleepTimer(minutes: Int) {
        sleepJob?.cancel()
        sleepEndsAt = 0L
        if (minutes <= 0) return
        sleepEndsAt = System.currentTimeMillis() + minutes * 60_000L
        sleepJob = scope.launch {
            while (System.currentTimeMillis() < sleepEndsAt) delay(500)
            exo?.pause()
            sleepEndsAt = 0L
        }
    }

    // ---------------- 均衡器 ----------------

    data class EqPreset(val index: Short, val name: String)

    fun eqPresets(): List<EqPreset> {
        val e = equalizer() ?: return emptyList()
        return runCatching {
            (0 until e.numberOfPresets.toInt()).map { EqPreset(it.toShort(), e.getPresetName(it.toShort())) }
        }.getOrDefault(emptyList())
    }

    private fun equalizer(): Equalizer? {
        val p = exo ?: return null
        if (eq == null) {
            val session = p.audioSessionId
            if (session != C.AUDIO_SESSION_ID_UNSET && session != 0) {
                eq = runCatching { Equalizer(0, session) }.getOrNull()
                runCatching { eq?.enabled = true }
            }
        }
        return eq
    }

    fun applyEq(preset: Int) {
        val e = equalizer() ?: return
        runCatching {
            if (preset < 0) {
                e.enabled = false
            } else {
                e.enabled = true
                e.usePreset(preset.toShort())
            }
        }
    }

    // ---------------- 进度持久化 ----------------

    fun saveProgress(context: Context) {
        val p = exo ?: return
        val track = queue.firstOrNull { it.id == currentId } ?: return
        val pos = p.currentPosition
        val dur = p.duration.takeIf { it > 0 } ?: track.durationMs
        if (pos <= 0) return
        runCatching {
            Store.saveHistory(
                HistoryEntry(
                    key = "music|${track.id}",
                    serverId = track.serverId,
                    path = track.path,
                    name = track.name,
                    positionMs = pos,
                    durationMs = dur,
                    updatedAt = System.currentTimeMillis(),
                    finished = dur > 0 && pos >= dur - 3000
                )
            )
        }
    }

    fun resumeOf(track: MusicTrack): Long {
        val h = Store.historyFor(track.serverId, track.path) ?: return 0L
        return if (h.finished) 0L else h.positionMs
    }
}

/** 让 Service 与 UI 用同一套启动方式 */
object MusicServiceLauncher {
    fun start(context: Context) {
        val intent = Intent(context, MusicService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
        else context.startService(intent)
    }
}
