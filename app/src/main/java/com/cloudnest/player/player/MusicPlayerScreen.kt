package com.cloudnest.player.player

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.app.Activity
import androidx.media3.common.Player
import coil.compose.AsyncImage
import com.cloudnest.player.data.MusicTrack
import com.cloudnest.player.data.Store
import com.cloudnest.player.lib.Lyrics
import com.cloudnest.player.lib.MusicMeta
import com.cloudnest.player.ui.RemoteImage
import com.cloudnest.player.ui.theme.BrandCyan
import com.cloudnest.player.ui.theme.BrandViolet
import com.cloudnest.player.ui.theme.NestTheme
import kotlinx.coroutines.delay
import java.io.File

class MusicPlayerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.cloudnest.player.data.Store.init(this)
        com.cloudnest.player.net.StreamProxy.start()
        MusicCore.ensure(this)
        setContent {
            NestTheme(dark = true) {
                MusicPlayerScreen()
            }
        }
    }

    override fun onStop() {
        MusicCore.saveProgress(this)
        super.onStop()
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, MusicPlayerActivity::class.java)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicPlayerScreen() {
    val context = LocalContext.current
    var tick by remember { mutableStateOf(0L) }
    var favTick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(300)
            tick++
        }
    }
    val player = MusicCore.player
    val track: MusicTrack? = remember(tick, MusicCore.currentId) {
        MusicCore.queue.firstOrNull { it.id == MusicCore.currentId }
    }
    val duration = (player?.duration ?: 0L).takeIf { it > 0 }
        ?: track?.durationMs ?: 0L
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableStateOf(0f) }
    val position = if (dragging) dragValue.toLong() else (player?.currentPosition ?: 0L)
    val playing = player?.isPlaying == true
    val shuffle = player?.shuffleModeEnabled == true
    val repeat = player?.repeatMode ?: Player.REPEAT_MODE_OFF
    val speed = player?.playbackParameters?.speed ?: 1f

    var showLyrics by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf(0) } // 0无 1队列 2倍速 3定时 4音效

    val fav = remember(track?.id, favTick) {
        track != null && Store.isFavorite(track.serverId, track.path)
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF07070C))
    ) {
        // 背景：封面大图 + 压暗
        CoverBackdrop(track, context)

        Column(
            Modifier
                .fillMaxSize()
                .padding(top = 44.dp, bottom = 18.dp)
                .padding(horizontal = 20.dp)
        ) {
            // 顶部栏
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.ArrowBack, "返回",
                    tint = Color.White.copy(alpha = 0.8f),
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.08f))
                        .clickable { (context as? Activity)?.finish() }
                        .padding(6.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        track?.title ?: "没有正在播放的歌曲",
                        color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        listOfNotNull(
                            track?.artist?.ifBlank { null },
                            track?.album?.ifBlank { null }
                        ).joinToString(" · ").ifBlank { "云巢影音" },
                        color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(
                    Icons.Rounded.QueueMusic, "播放列表",
                    tint = Color.White.copy(alpha = 0.8f),
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.08f))
                        .clickable { sheet = 1 }
                        .padding(6.dp)
                )
            }

            Spacer(Modifier.weight(1f))

            // 封面 / 歌词
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(4.2f),
                contentAlignment = Alignment.Center
            ) {
                if (showLyrics) {
                    val lines = remember(track?.id) {
                        MusicCore.lyricsOf(track?.id)
                            .ifEmpty { track?.let { loadLyrics(it) } ?: emptyList() }
                    }
                    if (lines.isEmpty()) {
                        Text(
                            "暂无歌词\n把同名 .lrc 放进歌曲同一目录即可显示",
                            color = Color.White.copy(alpha = 0.5f),
                            fontSize = 13.sp, textAlign = TextAlign.Center, lineHeight = 20.sp
                        )
                    } else {
                        LyricView(lines, position)
                    }
                } else {
                    TrackCover(track, Modifier.fillMaxHeight(0.92f).aspectRatio(1f), context)
                }
            }

            Spacer(Modifier.weight(1f))

            // 进度
            Slider(
                value = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f,
                onValueChange = {
                    dragging = true
                    dragValue = it * duration
                },
                onValueChangeFinished = {
                    MusicCore.seekTo(dragValue.toLong())
                    dragging = false
                },
                colors = SliderDefaults.colors(
                    thumbColor = BrandCyan,
                    activeTrackColor = BrandCyan,
                    inactiveTrackColor = Color.White.copy(alpha = 0.18f)
                ),
                modifier = Modifier.fillMaxWidth()
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                Text(Lyrics.formatMs(position), color = Color.White.copy(alpha = 0.55f), fontSize = 11.sp)
                Spacer(Modifier.weight(1f))
                Text(
                    if (duration > 0) Lyrics.formatMs(duration) else "--:--",
                    color = Color.White.copy(alpha = 0.55f), fontSize = 11.sp
                )
            }

            Spacer(Modifier.height(10.dp))

            // 主控制
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Icon(
                    if (shuffle) Icons.Rounded.Shuffle else Icons.Rounded.Shuffle, "随机",
                    tint = if (shuffle) BrandCyan else Color.White.copy(alpha = 0.5f),
                    modifier = Modifier.size(28.dp).clickable { MusicCore.setShuffle(!shuffle) }
                )
                Icon(
                    Icons.Rounded.SkipPrevious, "上一首",
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(34.dp).clickable { MusicCore.previous() }
                )
                Box(
                    Modifier
                        .size(66.dp)
                        .clip(CircleShape)
                        .background(Brush.linearGradient(listOf(BrandViolet, BrandCyan)))
                        .clickable { MusicCore.toggle(context) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        "播放/暂停",
                        tint = Color.White,
                        modifier = Modifier.size(34.dp)
                    )
                }
                Icon(
                    Icons.Rounded.SkipNext, "下一首",
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(34.dp).clickable { MusicCore.next() }
                )
                Icon(
                    when (repeat) {
                        Player.REPEAT_MODE_ONE -> Icons.Rounded.RepeatOne
                        else -> Icons.Rounded.Repeat
                    }, "循环",
                    tint = if (repeat != Player.REPEAT_MODE_OFF) BrandCyan else Color.White.copy(alpha = 0.5f),
                    modifier = Modifier.size(28.dp).clickable { MusicCore.cycleRepeat() }
                )
            }

            Spacer(Modifier.height(14.dp))

            // 副功能
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                SubAction(Icons.Rounded.Speed, if (speed == 1f) "倍速" else "${trimSpeed(speed)}×") { sheet = 2 }
                SubAction(Icons.Rounded.Timer, sleepLabel()) { sheet = 3 }
                SubAction(Icons.Rounded.Tune, "音效") { sheet = 4 }
                SubAction(
                    if (fav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    "收藏",
                    tint = if (fav) BrandCyan else Color.White.copy(alpha = 0.55f)
                ) {
                    track?.let {
                        val added = Store.toggleFavorite(it.serverId, it.path, it.name, false)
                        favTick++
                        Toast.makeText(context, if (added) "已加入收藏" else "已取消收藏", Toast.LENGTH_SHORT).show()
                    }
                }
                SubAction(Icons.Rounded.MusicNote, "歌词", tint = if (showLyrics) BrandCyan else Color.White.copy(alpha = 0.55f)) {
                    showLyrics = !showLyrics
                }
            }
        }

        if (sheet == 1) {
            ModalBottomSheet(onDismissRequest = { sheet = 0 }, containerColor = Color(0xFF12121A)) {
                QueueSheet()
            }
        }
        if (sheet == 2) {
            ModalBottomSheet(onDismissRequest = { sheet = 0 }, containerColor = Color(0xFF12121A)) {
                SheetTitle("播放速度")
                listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 2.5f, 3f).forEach { s ->
                    SheetRow(if (s == 1f) "1.0×（原速）" else "${trimSpeed(s)}×", selected = speed == s) {
                        MusicCore.setSpeed(s)
                        sheet = 0
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
        }
        if (sheet == 3) {
            ModalBottomSheet(onDismissRequest = { sheet = 0 }, containerColor = Color(0xFF12121A)) {
                SheetTitle("定时关闭")
                listOf(0 to "关闭", 15 to "15 分钟", 30 to "30 分钟", 45 to "45 分钟", 60 to "60 分钟", 90 to "90 分钟").forEach { (min, label) ->
                    SheetRow(label, selected = false) {
                        MusicCore.sleepTimer(min)
                        sheet = 0
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
        }
        if (sheet == 4) {
            ModalBottomSheet(onDismissRequest = { sheet = 0 }, containerColor = Color(0xFF12121A)) {
                SheetTitle("音效（均衡器）")
                SheetRow("关闭", selected = false) {
                    MusicCore.applyEq(-1)
                    sheet = 0
                }
                MusicCore.eqPresets().forEach { preset ->
                    SheetRow(preset.name, selected = false) {
                        MusicCore.applyEq(preset.index.toInt())
                        sheet = 0
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

private fun trimSpeed(s: Float): String =
    if (s == s.toInt().toFloat()) s.toInt().toString() else s.toString()

private fun sleepLabel(): String {
    val left = MusicCore.sleepEndsAt
    if (left <= 0) return "定时"
    val mins = ((left - System.currentTimeMillis()) / 60_000).coerceAtLeast(0)
    return "${mins}分"
}

private fun loadLyrics(track: MusicTrack): List<Lyrics.Line> = runCatching {
    val lrcPath = track.lrcPath ?: return emptyList()
    val fs = com.cloudnest.player.net.FsManager.get(track.serverId)
    val text = Lyrics.read(fs, lrcPath, lrcPath.substringAfterLast('/')) ?: return emptyList()
    Lyrics.parse(text)
}.getOrDefault(emptyList())

@Composable
private fun CoverBackdrop(track: MusicTrack?, context: Context) {
    val file = remember(track?.id) {
        track?.id?.let { MusicCore.coverOf(it) }
            ?: track?.let { MusicMeta.cachedCover(context, it.serverId, it.path) }
    }
    Box(Modifier.fillMaxSize()) {
        when {
            file != null -> AsyncImage(
                model = file, contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            track != null && !track.coverPath.isNullOrBlank() -> RemoteImage(
                track.serverId, track.coverPath, Modifier.fillMaxSize(), null, 0.dp
            )
            else -> Box(
                Modifier.fillMaxSize().background(
                    Brush.radialGradient(listOf(BrandViolet.copy(alpha = 0.5f), Color(0xFF07070C)))
                )
            ) { }
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0xFF07070C).copy(alpha = 0.72f),
                            Color(0xFF07070C).copy(alpha = 0.92f),
                            Color(0xFF07070C)
                        )
                    )
                )
        )
    }
}

@Composable
private fun TrackCover(track: MusicTrack?, modifier: Modifier = Modifier, context: Context) {
    val file = remember(track?.id) {
        track?.id?.let { MusicCore.coverOf(it) }
            ?: track?.let { MusicMeta.cachedCover(context, it.serverId, it.path) }
    }
    Box(
        modifier
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(listOf(BrandViolet.copy(alpha = 0.45f), BrandCyan.copy(alpha = 0.22f))))
    ) {
        when {
            file != null -> AsyncImage(
                model = file, contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            track != null && !track.coverPath.isNullOrBlank() ->
                RemoteImage(track.serverId, track.coverPath, Modifier.fillMaxSize(), track.title, 24.dp)
            else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.MusicNote, "封面", tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(64.dp))
            }
        }
    }
}

@Composable
private fun LyricView(lines: List<Lyrics.Line>, position: Long) {
    val state = rememberLazyListState()
    val index = Lyrics.indexAt(lines, position)
    LaunchedEffect(index) {
        if (index >= 0) {
            runCatching { state.animateScrollToItem(index.coerceAtLeast(0), -140) }
        }
    }
    LazyColumn(
        state = state,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 140.dp)
    ) {
        items(lines) { line ->
            val i = lines.indexOf(line)
            val active = i == index
            Text(
                line.text.ifBlank { " " },
                color = if (active) BrandCyan else Color.White.copy(alpha = 0.45f),
                fontSize = if (active) 17.sp else 14.sp,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp, horizontal = 12.dp)
            )
        }
    }
}

@Composable
private fun SubAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color = Color.White.copy(alpha = 0.55f),
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 8.dp)
    ) {
        Icon(icon, label, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(3.dp))
        Text(label, color = tint, fontSize = 10.5.sp)
    }
}

@Composable
private fun SheetTitle(text: String) {
    Text(
        text, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 20.dp, bottom = 8.dp)
    )
}

@Composable
private fun SheetRow(text: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text,
            color = if (selected) BrandCyan else Color.White.copy(alpha = 0.85f),
            fontSize = 14.sp,
            modifier = Modifier.weight(1f)
        )
        if (selected) Text("当前", color = BrandCyan, fontSize = 11.sp)
    }
}

@Composable
private fun QueueSheet() {
    Column {
        SheetTitle("播放列表 · ${MusicCore.queue.size} 首")
        LazyColumn(Modifier.heightIn(max = 460.dp)) {
            items(MusicCore.queue, key = { it.id }) { t ->
                val current = t.id == MusicCore.currentId
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            MusicCore.player?.seekTo(MusicCore.queue.indexOf(t), 0)
                            MusicCore.player?.playWhenReady = true
                        }
                        .padding(horizontal = 20.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (current) "♪" else " ",
                        color = BrandCyan, fontSize = 13.sp,
                        modifier = Modifier.width(18.dp)
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            t.title,
                            color = if (current) BrandCyan else Color.White.copy(alpha = 0.9f),
                            fontSize = 13.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            t.artist.ifBlank { t.album },
                            color = Color.White.copy(alpha = 0.45f), fontSize = 11.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (t.durationMs > 0) {
                        Text(Lyrics.formatMs(t.durationMs), color = Color.White.copy(alpha = 0.4f), fontSize = 11.sp)
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}
