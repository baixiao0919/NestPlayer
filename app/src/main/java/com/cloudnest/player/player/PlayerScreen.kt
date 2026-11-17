package com.cloudnest.player.player

import android.media.AudioManager
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.cloudnest.player.data.Store
import com.cloudnest.player.net.FsManager
import com.cloudnest.player.net.MediaFiles
import com.cloudnest.player.net.RemoteEntry
import com.cloudnest.player.net.StreamProxy
import com.cloudnest.player.ui.theme.BrandCyan
import com.cloudnest.player.ui.theme.BrandGradient
import com.cloudnest.player.ui.theme.BrandPurple
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

@UnstableApi
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    activity: PlayerActivity,
    player: ExoPlayer,
    serverId: String,
    startPath: String,
    startName: String,
    dirPath: String
) {
    val scope = rememberCoroutineScope()

    var loading by remember { mutableStateOf(true) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var showControls by remember { mutableStateOf(true) }
    var locked by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var buffered by remember { mutableLongStateOf(0L) }
    var baseSpeed by remember { mutableFloatStateOf(Store.settings.defaultSpeed) }
    var boosting by remember { mutableStateOf(false) }
    var resizeMode by remember { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    var title by remember { mutableStateOf(startName) }
    var indicator by remember { mutableStateOf<String?>(null) }
    var seekPreview by remember { mutableStateOf<Long?>(null) }
    var seekFrom by remember { mutableLongStateOf(0L) }
    var showSpeed by remember { mutableStateOf(false) }
    var showSubtitle by remember { mutableStateOf(false) }
    var showAudio by remember { mutableStateOf(false) }
    var showEpisodes by remember { mutableStateOf(false) }
    var resumeAsk by remember { mutableStateOf(0L) }
    var currentIndex by remember { mutableIntStateOf(0) }
    var playlistSize by remember { mutableIntStateOf(1) }
    var episodes by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var durationUnknown by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }

    val seekStep = Store.settings.seekStep

    // ---- 载入播放列表 ----
    LaunchedEffect(startPath) {
        loading = true
        errorText = null
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val fs = FsManager.get(serverId)
                val siblings: List<RemoteEntry> = runCatching { fs.list(dirPath) }.getOrElse { emptyList() }
                val videos = siblings.filter { !it.isDir && MediaFiles.isVideo(it.name) }
                    .sortedBy { naturalOrder(it.name) }
                    .take(800)
                val target = videos.firstOrNull { it.path == startPath }
                    ?: RemoteEntry(startName, startPath, false, 0, 0)
                val list = if (videos.isEmpty()) listOf(target) else videos
                val subs = siblings.filter { !it.isDir && MediaFiles.isSubtitle(it.name) }
                val items = list.map { e ->
                    val url = StreamProxy.url(serverId, e.path, e.name)
                    val base = e.name.substringBeforeLast('.', e.name).lowercase()
                    val subsForFile = subs.filter { s ->
                        val sb = s.name.substringBeforeLast('.', s.name).lowercase()
                        sb == base || sb.startsWith("$base.") || sb.startsWith("${base}_") || sb.startsWith("${base}-")
                    }.sortedBy { s ->
                        if (s.name.contains("zh", true) || s.name.contains("中", true) || s.name.contains("chs", true)) 0 else 1
                    }
                    val subsConfigs = subsForFile.map { s ->
                        MediaItem.SubtitleConfiguration.Builder(Uri.parse(StreamProxy.url(serverId, s.path, s.name)))
                            .setMimeType(subMime(s.name))
                            .setLanguage(s.name.substringBeforeLast('.').lowercase())
                            .setLabel(s.name)
                            .setSelectionFlags(if (s == subsForFile.firstOrNull()) C.SELECTION_FLAG_DEFAULT else 0)
                            .build()
                    }
                    MediaItem.Builder()
                        .setUri(url)
                        .setMediaId(e.path)
                        .setMediaMetadata(
                            androidx.media3.common.MediaMetadata.Builder()
                                .setTitle(e.name.substringBeforeLast('.'))
                                .build()
                        )
                        .setSubtitleConfigurations(subsConfigs)
                        .build()
                }
                val names = list.map { it.name }
                val paths = list.map { it.path }
                Triple(items, list.indexOfFirst { it.path == startPath }.coerceAtLeast(0), names to paths)
            }
        }
        result.onSuccess { (items, index, namesAndPaths) ->
            val (names, paths) = namesAndPaths
            episodes = names.zip(paths)
            currentIndex = index
            playlistSize = items.size
            title = names.getOrElse(index) { startName }
            activity.bind("$serverId|$startPath", serverId, startPath, startName)
            val startPos = if (!Store.settings.autoResume) 0L else activity.resumePosition()
            if (startPos > 0) resumeAsk = startPos
            player.setMediaItems(items, index, 0L)
            player.setPlaybackSpeed(baseSpeed)
            player.prepare()
            player.playWhenReady = true
            loading = false
        }.onFailure { e ->
            errorText = e.message ?: "无法打开该文件"
            loading = false
        }
    }

    // ---- 状态轮询 ----
    LaunchedEffect(player) {
        var lastIndex = -1
        while (true) {
            isPlaying = player.isPlaying
            position = player.currentPosition.coerceAtLeast(0)
            duration = player.duration.coerceAtLeast(0)
            if (duration > 1000) durationUnknown = false
            else if (player.currentPosition > 3000 && player.isPlaying) durationUnknown = true
            buffered = player.bufferedPosition.coerceAtLeast(0)
            currentIndex = player.currentMediaItemIndex
            // 切集/自动连播后把续播记录挂到新文件上
            if (currentIndex != lastIndex && episodes.isNotEmpty() && currentIndex in episodes.indices) {
                val (n, p) = episodes[currentIndex]
                activity.bind("$serverId|$p", serverId, p, n)
                lastIndex = currentIndex
            }
            val md = player.currentMediaItem?.mediaMetadata?.title?.toString()
            if (!md.isNullOrBlank()) title = md
            activity.savePosition()
            delay(if (showControls) 200 else 700)
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                errorText = error.message ?: "播放失败"
            }
            override fun onPlaybackParametersChanged(params: PlaybackParameters) {
                if (!boosting) baseSpeed = params.speed
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    // 控件自动隐藏
    LaunchedEffect(showControls, isPlaying) {
        if (showControls && isPlaying) {
            delay(4000)
            showControls = false
        }
    }

    // 手势提示自动消失
    LaunchedEffect(indicator) {
        if (indicator != null) {
            delay(1200)
            indicator = null
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    setBackgroundColor(android.graphics.Color.BLACK)
                    this.player = player
                }
            },
            update = { view ->
                view.player = player
                view.resizeMode = resizeMode
            },
            modifier = Modifier.fillMaxSize()
        )

        // ---------- 手势层 ----------
        // 注意顺序：点击层在外、拖拽层在内。拖拽判定成立后会消费事件，
        // 点击/双击/长按自动取消，两种手势不会互相抢。
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(locked, seekStep) {
                    if (locked) return@pointerInput
                    detectTapGestures(
                        onTap = { showControls = !showControls },
                        onDoubleTap = { offset ->
                            val dur = duration
                            if (dur > 0) {
                                val left = offset.x < size.width / 2f
                                val target = if (left) (player.currentPosition - seekStep * 1000L).coerceAtLeast(0)
                                else (player.currentPosition + seekStep * 1000L).coerceAtMost(dur)
                                player.seekTo(target)
                                indicator = if (left) "◀ ${seekStep}s" else "${seekStep}s ▶"
                            } else {
                                indicator = "该文件没有时长信息，无法快进"
                            }
                        },
                        onLongPress = {
                            if (isPlaying && !boosting) {
                                boosting = true
                                player.setPlaybackSpeed(2f)
                                indicator = "2× 倍速播放"
                            }
                        },
                        onPress = {
                            tryAwaitRelease()
                            if (boosting) {
                                boosting = false
                                player.setPlaybackSpeed(baseSpeed)
                                indicator = null
                            }
                        }
                    )
                }
                .pointerInput(locked) {
                    if (locked) return@pointerInput
                    val slop = viewConfiguration.touchSlop
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var axis = 0          // 0 未定 1 横向快进 2 纵向（亮度/音量）
                        var acc = Offset.Zero
                        val startPos = player.currentPosition.coerceAtLeast(0)
                        var bright = activity.currentBrightness()
                        var vol = 0
                        var maxVol = 1
                        var lastSeek = 0L
                        val leftSide = down.position.x < size.width / 2f

                        while (true) {
                            val ev = awaitPointerEvent()
                            val change = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            acc += change.positionChange()
                            if (axis == 0 && acc.getDistance() > slop) {
                                axis = if (abs(acc.x) > abs(acc.y)) 1 else 2
                                if (axis == 1) {
                                    seekFrom = startPos
                                    showControls = false
                                } else {
                                    bright = activity.currentBrightness()
                                    vol = activity.audio().getStreamVolume(AudioManager.STREAM_MUSIC)
                                    maxVol = activity.audio().getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                                }
                            }
                            when (axis) {
                                1 -> {
                                    val dur = duration
                                    if (dur > 1000) {
                                        val ratio = (acc.x / size.width).coerceIn(-1f, 1f)
                                        // 划满一屏 ≈ 20~60 秒（长片不超过 1 分钟），跟主流播放器的手感一致
                                        val span = (dur / 12).coerceIn(20_000L, 60_000L)
                                        val target = (startPos + (ratio * span).toLong()).coerceIn(0, dur)
                                        seekPreview = target
                                        indicator = null
                                        val now = System.currentTimeMillis()
                                        if (now - lastSeek > 200) {
                                            player.seekTo(target)
                                            lastSeek = now
                                        }
                                    } else {
                                        indicator = "该文件没有时长信息，无法按时间快进"
                                    }
                                    change.consume()
                                }
                                2 -> {
                                    val dy = -acc.y / size.height
                                    if (leftSide) {
                                        bright = (bright + dy * 1.4f).coerceIn(0.02f, 1f)
                                        activity.setBrightness(bright)
                                        indicator = "亮度 ${(bright * 100).toInt()}%"
                                    } else {
                                        val target = (vol + (dy * maxVol * 1.4f).toInt()).coerceIn(0, maxVol)
                                        if (target != vol) {
                                            activity.audio().setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
                                            vol = target
                                        }
                                        indicator = "音量 ${(target * 100 / maxVol)}%"
                                    }
                                }
                            }
                            if (axis != 0) change.consume()
                        }
                        if (axis == 1) {
                            seekPreview?.let { player.seekTo(it) }
                            seekPreview = null
                            showControls = true
                        } else if (axis == 2) {
                            indicator = null
                        }
                    }
                }
        )

        if (loading) {
            Box(Modifier.align(Alignment.Center)) {
                CircularProgressIndicator(color = BrandCyan, strokeWidth = 3.dp)
            }
        }

        errorText?.let { msg ->
            Column(
                Modifier
                    .align(Alignment.Center)
                    .padding(28.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(Color(0xF014141B))
                    .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(22.dp))
                    .padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("播放失败", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(msg, color = Color.White.copy(alpha = 0.65f), fontSize = 12.5.sp)
                Spacer(Modifier.height(18.dp))
                Row {
                    GlassButton("返回") { activity.finish() }
                    Spacer(Modifier.width(10.dp))
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(BrandGradient)
                            .clickable {
                                errorText = null
                                loading = true
                                player.prepare()
                            }
                            .padding(horizontal = 18.dp, vertical = 10.dp)
                    ) {
                        Text("重试", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        // 手势提示 pill
        indicator?.let { text ->
            val align = when {
                text.startsWith("亮度") -> Alignment.CenterStart
                text.startsWith("音量") -> Alignment.CenterEnd
                else -> Alignment.TopCenter
            }
            val pad = when {
                text.startsWith("亮度") -> PaddingValues(start = 26.dp)
                text.startsWith("音量") -> PaddingValues(end = 26.dp)
                else -> PaddingValues(top = 76.dp)
            }
            val percent = text.substringAfterLast(' ').removeSuffix("%").toFloatOrNull()
            Column(
                Modifier
                    .align(align)
                    .padding(pad)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.Black.copy(alpha = 0.66f))
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text,
                    color = Color.White,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Medium
                )
                if (percent != null) {
                    Spacer(Modifier.height(6.dp))
                    Box(
                        Modifier
                            .width(74.dp)
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color.White.copy(alpha = 0.2f))
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth((percent / 100f).coerceIn(0f, 1f))
                                .height(3.dp)
                                .background(BrandGradient)
                        )
                    }
                }
            }
        }

        // 快进预览
        seekPreview?.let { preview ->
            Column(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 64.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.Black.copy(alpha = 0.72f))
                    .padding(horizontal = 22.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    fmt(preview),
                    color = Color.White,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "共 ${fmt(duration)}  ·  ${if (preview >= seekFrom) "+" else "-"}${fmt(abs(preview - seekFrom))}",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 11.5.sp
                )
            }
        }

        // ---------- 控制层 ----------
        AnimatedVisibility(visible = showControls && !locked, enter = fadeIn(), exit = fadeOut()) {
            PlayerControls(
                player = player,
                title = title,
                position = position,
                duration = duration,
                buffered = buffered,
                isPlaying = isPlaying,
                speed = if (boosting) 2f else baseSpeed,
                index = currentIndex,
                count = playlistSize,
                hasEpisodes = episodes.size > 1,
                durationUnknown = durationUnknown,
                onBack = {
                    activity.savePosition(true)
                    activity.finish()
                },
                onSeek = { player.seekTo(it) },
                onPlayPause = { if (player.isPlaying) player.pause() else player.play() },
                onPrev = { if (player.hasPreviousMediaItem()) player.seekToPreviousMediaItem() },
                onNext = { if (player.hasNextMediaItem()) player.seekToNextMediaItem() },
                onSeekBy = { delta ->
                    val t = (player.currentPosition + delta).coerceIn(0, duration.coerceAtLeast(0))
                    player.seekTo(t)
                },
                onSpeed = { showSpeed = true },
                onInfo = { showInfo = true },
                onSubtitle = { showSubtitle = true },
                onAudio = { showAudio = true },
                onEpisodes = { showEpisodes = true },
                onAspect = {
                    resizeMode = when (resizeMode) {
                        AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                        else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                    }
                },
                aspectLabel = when (resizeMode) {
                    AspectRatioFrameLayout.RESIZE_MODE_FIT -> "适应"
                    AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "裁剪"
                    else -> "拉伸"
                },
                onRotate = { activity.toggleOrientation() },
                onLock = { locked = true; showControls = false }
            )
        }

        if (locked) {
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(20.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f))
                    .clickable { locked = false; showControls = true }
                    .padding(10.dp)
            ) {
                Icon(Icons.Rounded.Lock, contentDescription = "解锁", tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }

        // ---------- 继续观看 ----------
        if (resumeAsk > 0) {
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 18.dp, vertical = 90.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xEE14141B))
                    .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(20.dp))
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("上次看到 ${fmt(resumeAsk)}", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text("从上次的位置继续？", color = Color.White.copy(alpha = 0.55f), fontSize = 11.sp)
                }
                GlassButton("从头") {
                    player.seekTo(0); resumeAsk = 0
                }
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(BrandGradient)
                        .clickable {
                            player.seekTo(resumeAsk); player.play(); resumeAsk = 0
                        }
                        .padding(horizontal = 14.dp, vertical = 9.dp)
                ) {
                    Text("继续", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        if (showInfo) MediaInfoDialog(player) { showInfo = false }

        if (showSpeed) {
            OptionSheet(
                title = "播放速度",
                options = listOf("0.5×", "0.75×", "1×", "1.25×", "1.5×", "1.75×", "2×", "2.5×", "3×", "4×"),
                selected = "${trimSpeed(baseSpeed)}×",
                onDismiss = { showSpeed = false }
            ) { label ->
                val v = label.removeSuffix("×").toFloat()
                baseSpeed = v
                player.setPlaybackSpeed(v)
                showSpeed = false
            }
        }

        if (showSubtitle) {
            val groups = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
            val labels = ArrayList<String>()
            labels.add("关闭字幕")
            var k = 0
            groups.forEach { g ->
                for (i in 0 until g.length) {
                    val fmtT = g.getTrackFormat(i)
                    labels.add(fmtT.label ?: fmtT.language ?: "字幕 ${k + 1}")
                    k++
                }
            }
            OptionSheet(title = "字幕", options = labels, selected = null, onDismiss = { showSubtitle = false }) { label ->
                if (label == "关闭字幕") {
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                        .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                        .build()
                } else {
                    var n = 0
                    var done = false
                    groups.forEach { g ->
                        for (i in 0 until g.length) {
                            val fmtT = g.getTrackFormat(i)
                            val l = fmtT.label ?: fmtT.language ?: "字幕 ${n + 1}"
                            if (!done && l == label) {
                                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                    .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                                    .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i))
                                    .build()
                                done = true
                            }
                            n++
                        }
                    }
                }
                showSubtitle = false
            }
        }

        if (showAudio) {
            val groups = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
            val labels = ArrayList<String>()
            groups.forEach { g ->
                for (i in 0 until g.length) {
                    val fmtT = g.getTrackFormat(i)
                    labels.add(fmtT.label ?: "音轨 ${fmtT.language ?: i + 1}")
                }
            }
            OptionSheet(
                title = "音轨",
                options = if (labels.isEmpty()) listOf("暂无音轨") else labels,
                selected = null,
                onDismiss = { showAudio = false }
            ) { label ->
                var done = false
                groups.forEach { g ->
                    for (i in 0 until g.length) {
                        if (!done) {
                            val l = g.getTrackFormat(i).label ?: "音轨 ${g.getTrackFormat(i).language ?: i + 1}"
                            if (l == label) {
                                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                                    .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                                    .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i))
                                    .build()
                                done = true
                            }
                        }
                    }
                }
                showAudio = false
            }
        }

        // ---------- 选集面板 ----------
        if (showEpisodes && episodes.isNotEmpty()) {
            ModalBottomSheet(
                onDismissRequest = { showEpisodes = false },
                containerColor = Color(0xFF12121A),
                dragHandle = { BottomSheetDefaults.DragHandle(color = Color.White.copy(alpha = 0.25f)) }
            ) {
                Column(Modifier.padding(bottom = 10.dp)) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("选集", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "共 ${episodes.size} 集",
                            color = Color.White.copy(alpha = 0.5f),
                            fontSize = 11.5.sp
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            "自动连播",
                            color = Color.White.copy(alpha = 0.5f),
                            fontSize = 11.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(BrandCyan.copy(alpha = 0.14f))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        itemsIndexed(episodes) { i, ep ->
                            val (name, path) = ep
                            val cur = i == currentIndex
                            val h = Store.historyFor(serverId, path)
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 2.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(if (cur) Color.White.copy(alpha = 0.07f) else Color.Transparent)
                                    .clickable {
                                        player.seekTo(i, 0)
                                        player.play()
                                        showEpisodes = false
                                    }
                                    .padding(horizontal = 10.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    Modifier
                                        .size(34.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(if (cur) BrandGradient else Brush.linearGradient(listOf(Color(0xFF232330), Color(0xFF232330)))),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        "${i + 1}",
                                        color = if (cur) Color.White else Color.White.copy(alpha = 0.6f),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        name.substringBeforeLast('.'),
                                        color = if (cur) Color.White else Color.White.copy(alpha = 0.82f),
                                        fontSize = 12.5.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        fontWeight = if (cur) FontWeight.SemiBold else FontWeight.Normal
                                    )
                                    if (h != null && h.progress > 0.02f) {
                                        Text(
                                            "已看 ${(h.progress * 100).toInt()}%",
                                            color = BrandCyan,
                                            fontSize = 10.sp
                                        )
                                    }
                                }
                                if (cur) Icon(Icons.Rounded.Check, null, tint = BrandCyan, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@UnstableApi
@Composable
private fun PlayerControls(
    player: ExoPlayer,
    title: String,
    position: Long,
    duration: Long,
    buffered: Long,
    isPlaying: Boolean,
    speed: Float,
    index: Int,
    count: Int,
    hasEpisodes: Boolean,
    durationUnknown: Boolean,
    onBack: () -> Unit,
    onSeek: (Long) -> Unit,
    onPlayPause: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onSpeed: () -> Unit,
    onInfo: () -> Unit,
    onSubtitle: () -> Unit,
    onAudio: () -> Unit,
    onEpisodes: () -> Unit,
    onAspect: () -> Unit,
    aspectLabel: String,
    onRotate: () -> Unit,
    onLock: () -> Unit
) {
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(140.dp)
                .align(Alignment.TopCenter)
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.8f), Color.Transparent)))
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(220.dp)
                .align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.9f))))
        )

        // 顶部
        Row(
            Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(top = 14.dp, start = 14.dp, end = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.12f))
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.ArrowBack, "返回", tint = Color.White, modifier = Modifier.size(18.dp))
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(
                    title,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (count > 1) {
                    Text(
                        "第 ${index + 1} / $count 集",
                        color = Color.White.copy(alpha = 0.55f),
                        fontSize = 10.5.sp
                    )
                }
            }
            if (hasEpisodes) {
                IconPill(Icons.Rounded.PlaylistPlay, "选集", onEpisodes)
                Spacer(Modifier.width(8.dp))
            }
            IconPill(Icons.Rounded.Lock, "锁定", onLock)
        }

        // 中央主控
        Row(
            Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            RoundIcon(Icons.Rounded.SkipPrevious, "上一个", 46, onPrev)
            RoundIcon(Icons.Rounded.FastRewind, "-10s", 46) { onSeekBy(-10_000) }
            Box(
                Modifier
                    .size(70.dp)
                    .clip(CircleShape)
                    .background(BrandGradient)
                    .clickable { onPlayPause() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = "播放/暂停",
                    tint = Color.White,
                    modifier = Modifier.size(34.dp)
                )
            }
            RoundIcon(Icons.Rounded.FastForward, "+10s", 46) { onSeekBy(10_000) }
            RoundIcon(Icons.Rounded.SkipNext, "下一个", 46, onNext)
        }

        // 底部
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(fmt(position), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                VideoSeekBar(
                    position = position,
                    buffered = buffered,
                    duration = duration,
                    modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                    onSeek = onSeek
                )
                Text(
                    if (duration > 0) fmt(duration) else "—",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 12.sp
                )
            }
            if (durationUnknown) {
                Text(
                    "这个文件没有时长信息（常见于 TS 录制片、索引损坏的 MP4），只能顺序播放；转成 MP4/MKV 后可正常拖动",
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 10.5.sp,
                    maxLines = 2
                )
            }
            Spacer(Modifier.height(2.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row {
                    GlassButton("${trimSpeed(speed)}×") { onSpeed() }
                    Spacer(Modifier.width(8.dp))
                    GlassButton("字幕") { onSubtitle() }
                    Spacer(Modifier.width(8.dp))
                    GlassButton("音轨") { onAudio() }
                }
                Row {
                    GlassButton("信息") { onInfo() }
                    Spacer(Modifier.width(8.dp))
                    GlassButton(aspectLabel) { onAspect() }
                    Spacer(Modifier.width(8.dp))
                    GlassButton("旋转") { onRotate() }
                    if (hasEpisodes) {
                        Spacer(Modifier.width(8.dp))
                        GlassButton("选集") { onEpisodes() }
                    }
                }
            }
        }
    }
}

/** 自绘进度条：已播（渐变色）/ 缓冲（半透明白）/ 拖动实时跟手 */
@Composable
private fun VideoSeekBar(
    position: Long,
    buffered: Long,
    duration: Long,
    modifier: Modifier = Modifier,
    onSeek: (Long) -> Unit
) {
    var scrub by remember { mutableStateOf<Float?>(null) }
    val progress = when {
        duration <= 0 -> 0f
        scrub != null -> scrub!!
        else -> (position.toFloat() / duration).coerceIn(0f, 1f)
    }
    Box(
        modifier
            .height(30.dp)
            .pointerInput(duration) {
                if (duration <= 0) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    var f = (down.position.x / size.width).coerceIn(0f, 1f)
                    scrub = f
                    drag(down.id) { change ->
                        f = (change.position.x / size.width).coerceIn(0f, 1f)
                        scrub = f
                        change.consume()
                    }
                    onSeek((f * duration).toLong())
                    scrub = null
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxWidth().height(30.dp)) {
            val cy = size.height / 2f
            val th = 4.dp.toPx()
            val r = CornerRadius(th / 2, th / 2)
            // 轨道
            drawRoundRect(
                color = Color.White.copy(alpha = 0.16f),
                topLeft = Offset(0f, cy - th / 2),
                size = Size(size.width, th),
                cornerRadius = r
            )
            // 缓冲
            if (duration > 0) {
                val bf = (buffered.toFloat() / duration).coerceIn(0f, 1f)
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.30f),
                    topLeft = Offset(0f, cy - th / 2),
                    size = Size(size.width * bf, th),
                    cornerRadius = r
                )
            }
            // 已播
            drawRoundRect(
                brush = Brush.horizontalGradient(listOf(BrandPurple, BrandCyan)),
                topLeft = Offset(0f, cy - th / 2),
                size = Size(size.width * progress, th),
                cornerRadius = r
            )
            // 滑块
            val cx = size.width * progress
            drawCircle(color = Color.White, radius = 6.5.dp.toPx(), center = Offset(cx, cy))
            drawCircle(color = BrandCyan.copy(alpha = 0.35f), radius = 11.dp.toPx(), center = Offset(cx, cy))
        }
    }
}

@Composable
private fun RoundIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    desc: String,
    size: Int,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.10f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, desc, tint = Color.White, modifier = Modifier.size((size * 0.42f).dp))
    }
}

@Composable
private fun IconPill(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    desc: String,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.10f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, desc, tint = Color.White, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun GlassButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = 0.10f))
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 6.dp)
    ) {
        Text(label, color = Color.White.copy(alpha = 0.92f), fontSize = 11.5.sp, fontWeight = FontWeight.Medium)
    }
}

/** 视频信息：分辨率 / 帧率 / 编码 / 码率 / 音轨，对齐迅雷影音的"媒体信息查看" */
@Composable
private fun MediaInfoDialog(player: androidx.media3.exoplayer.ExoPlayer, onDismiss: () -> Unit) {
    val v = player.videoFormat
    val a = player.audioFormat
    val rows = buildList {
        player.mediaMetadata.title?.toString()?.let { if (it.isNotBlank()) add("片名" to it) }
        if (v != null) {
            add("画面" to "${v.width}×${v.height}" + if (v.frameRate > 0) String.format(" · %.2f fps", v.frameRate) else "")
            v.sampleMimeType?.let { add("视频编码" to it.substringAfterLast('/').uppercase()) }
            if (v.bitrate > 0) add("视频码率" to "${v.bitrate / 1000} kbps")
            v.codecs?.let { add("编码规格" to it) }
        }
        if (a != null) {
            a.sampleMimeType?.let { add("音频编码" to it.substringAfterLast('/').uppercase()) }
            if (a.channelCount > 0) add("声道" to "${a.channelCount} 声道")
            if (a.sampleRate > 0) add("采样率" to "${a.sampleRate / 1000} kHz")
            if (a.bitrate > 0) add("音频码率" to "${a.bitrate / 1000} kbps")
        }
        val d = player.duration
        add("时长" to (if (d > 0) fmt(d) else "未知（TS 等无索引容器）"))
        add("音轨数" to "${player.currentTracks.groups.count { it.type == androidx.media3.common.C.TRACK_TYPE_AUDIO && it.isSupported }}")
        add("倍速" to "${trimSpeed(player.playbackParameters.speed)}×")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF12121A),
        title = { Text("视频信息", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp) },
        text = {
            Column {
                rows.forEach { (k, value) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                        Text(k, color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp, modifier = Modifier.width(72.dp))
                        Text(value, color = Color.White.copy(alpha = 0.9f), fontSize = 12.sp)
                    }
                }
            }
        },
        confirmButton = {
            Text(
                "知道了",
                color = BrandCyan,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                modifier = Modifier.clickable(onClick = onDismiss).padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    )
}

/** 底部弹层的选项面板（倍速 / 字幕 / 音轨） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OptionSheet(
    title: String,
    options: List<String>,
    selected: String?,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF12121A),
        dragHandle = { BottomSheetDefaults.DragHandle(color = Color.White.copy(alpha = 0.25f)) }
    ) {
        Column(Modifier.padding(bottom = 18.dp)) {
            Text(
                title,
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 20.dp, bottom = 8.dp)
            )
            LazyColumn(Modifier.heightIn(max = 400.dp)) {
                items(options.size) { i ->
                    val label = options[i]
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 2.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                if (label == selected) Color.White.copy(alpha = 0.07f) else Color.Transparent
                            )
                            .clickable { onSelect(label) }
                            .padding(horizontal = 10.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            label,
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = 13.5.sp,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (label == selected) {
                            Icon(Icons.Rounded.Check, null, tint = BrandCyan, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
    }
}

fun fmt(ms: Long): String {
    if (ms <= 0) return "00:00"
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%02d:%02d", m, s)
}

private fun trimSpeed(v: Float): String =
    if (v % 1f == 0f) v.toInt().toString() else v.toString().trimEnd('0').trimEnd('.')

private fun subMime(name: String): String = when (MediaFiles.ext(name)) {
    "srt" -> "application/x-subrip"
    "ass", "ssa" -> "text/x-ssa"
    "vtt" -> "text/vtt"
    else -> "application/x-subrip"
}

private fun naturalOrder(s: String): String = s.lowercase()
