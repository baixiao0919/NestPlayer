package com.cloudnest.player.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cloudnest.player.data.MusicTrack
import com.cloudnest.player.data.Store
import com.cloudnest.player.player.MusicCore
import com.cloudnest.player.player.MusicPlayerActivity
import com.cloudnest.player.ui.AppState
import com.cloudnest.player.ui.BottomNavBar
import com.cloudnest.player.ui.PillChip
import com.cloudnest.player.ui.RemoteImage
import com.cloudnest.player.ui.EmptyState
import com.cloudnest.player.ui.theme.BrandCyan
import com.cloudnest.player.ui.theme.BrandGradient
import com.cloudnest.player.ui.theme.BrandViolet

/**
 * 音乐库：专辑 / 艺术家 / 歌曲 三个视图，交互对齐主流播放器（网易云 / Poweramp / VLC）。
 */
@Composable
fun MusicScreen(app: AppState) {
    val data = Store.data.collectAsState().value
    val serverId = app.activeServerId ?: data.servers.firstOrNull()?.id
    val tracks = remember(serverId, data.music) {
        if (serverId == null) data.music else data.music.filter { it.serverId == serverId }
    }
    var tab by remember { mutableStateOf(0) }
    var query by remember { mutableStateOf("") }
    var searchOpen by remember { mutableStateOf(false) }

    val filtered = remember(tracks, query) {
        if (query.isBlank()) tracks else tracks.filter {
            it.title.contains(query, true) || it.artist.contains(query, true) || it.album.contains(query, true)
        }
    }

    val albums = remember(filtered) {
        filtered.groupBy { it.album.ifBlank { "未归类" } + "|" + it.artist }
            .map { (key, list) ->
                AlbumGroup(
                    name = list.first().album.ifBlank { "未归类" },
                    artist = list.first().artist,
                    tracks = list.sortedBy { it.trackNo }
                )
            }
            .sortedByDescending { it.tracks.size }
    }
    val artists = remember(filtered) {
        filtered.filter { it.artist.isNotBlank() }
            .groupBy { it.artist }
            .map { (name, list) -> name to list }
            .sortedByDescending { it.second.size }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize().padding(top = 44.dp)) {
            // 标题 + 扫描
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "音乐",
                        fontSize = 26.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = BrandCyan
                    )
                    Text(
                        "${tracks.size} 首 · ${albums.size} 张专辑" +
                            (serverId?.let { id -> Store.server(id)?.let { " · " + it.name.ifBlank { it.defaultName() } } } ?: ""),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                }
                Icon(
                    Icons.Rounded.Refresh, "扫描",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                        .clickable { serverId?.let { app.scanMusic(it) } }
                        .padding(7.dp)
                )
            }

            Spacer(Modifier.height(14.dp))

            // 搜索条
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .clickable { searchOpen = !searchOpen }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (query.isBlank()) "搜索歌曲 / 艺术家 / 专辑" else query,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f)
                )
                if (query.isNotBlank()) {
                    Text(
                        "清除",
                        color = BrandCyan,
                        fontSize = 12.sp,
                        modifier = Modifier.clickable { query = "" }
                    )
                }
            }
            if (searchOpen) {
                androidx.compose.material3.OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text("输入关键词", fontSize = 13.sp) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)
                )
            }

            Spacer(Modifier.height(10.dp))

            Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("专辑", "艺术家", "歌曲").forEachIndexed { i, label ->
                    PillChip(label, selected = tab == i, onClick = { tab = i })
                }
            }

            Spacer(Modifier.height(8.dp))

            when {
                tracks.isEmpty() -> EmptyState(
                    "还没有音乐",
                    "在设置里填好服务器后点右上角刷新，会扫描服务器里的音频文件",
                    "去扫描"
                ) { serverId?.let { app.scanMusic(it) } }

                tab == 0 -> LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 160.dp, top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(albums, key = { it.name + it.artist }) { album ->
                        AlbumCard(album) {
                            play(app, album.tracks, 0)
                        }
                    }
                }

                tab == 1 -> LazyColumn(
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 160.dp, top = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(artists, key = { it.first }) { (name, list) ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                                .clickable { play(app, list, 0) }
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                Modifier
                                    .size(46.dp)
                                    .clip(CircleShape)
                                    .background(BrandGradient),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    name.take(1).uppercase(),
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(name, color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                Text("${list.size} 首", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                            }
                            Icon(Icons.Rounded.PlayArrow, "播放", tint = BrandCyan, modifier = Modifier.size(24.dp))
                        }
                    }
                }

                else -> LazyColumn(
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 160.dp, top = 6.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(filtered, key = { it.id }) { track ->
                        TrackRow(track, isCurrent = track.id == MusicCore.currentId) {
                            play(app, filtered, filtered.indexOf(track))
                        }
                    }
                }
            }
        }

        BottomNavBar(1, Modifier.align(Alignment.BottomCenter)) { i -> navToMusic(app, i) }
    }
}

private data class AlbumGroup(val name: String, val artist: String, val tracks: List<MusicTrack>)

@Composable
private fun AlbumCard(album: AlbumGroup, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(18.dp))
                .background(Brush.linearGradient(listOf(BrandViolet.copy(alpha = 0.35f), BrandCyan.copy(alpha = 0.2f))))
        ) {
            val cover = album.tracks.firstOrNull { !it.coverPath.isNullOrBlank() }
            if (cover != null) {
                RemoteImage(
                    serverId = cover.serverId,
                    path = cover.coverPath,
                    modifier = Modifier.fillMaxSize(),
                    fallbackLabel = album.name,
                    corner = 18.dp
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.MusicNote, "专辑", tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(40.dp))
                }
            }
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.PlayArrow, "播放", tint = Color.White, modifier = Modifier.size(18.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            album.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        Text(
            "${album.artist.ifBlank { "未知艺术家" }} · ${album.tracks.size} 首",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun TrackRow(track: MusicTrack, isCurrent: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .then(
                if (isCurrent) Modifier.background(
                    Brush.linearGradient(listOf(BrandViolet.copy(alpha = 0.22f), BrandCyan.copy(alpha = 0.14f)))
                ) else Modifier
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
        ) {
            if (!track.coverPath.isNullOrBlank()) {
                RemoteImage(track.serverId, track.coverPath, Modifier.fillMaxSize(), track.title, 12.dp)
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.MusicNote, "音乐", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                fontSize = 14.sp,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                color = if (isCurrent) BrandCyan else MaterialTheme.colorScheme.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(
                listOfNotNull(track.artist.ifBlank { null }, track.album.ifBlank { null }).joinToString(" · "),
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        if (track.durationMs > 0) {
            Text(
                com.cloudnest.player.lib.Lyrics.formatMs(track.durationMs),
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun play(app: AppState, tracks: List<MusicTrack>, index: Int) {
    if (tracks.isEmpty()) return
    MusicCore.playQueue(app.context, tracks, index, MusicCore.resumeOf(tracks[index]))
    app.context.startActivity(MusicPlayerActivity.intent(app.context))
}

private fun navToMusic(app: AppState, index: Int) {
    val server = app.currentServer()
    when (index) {
        0 -> app.backToHome()
        2 -> app.push(com.cloudnest.player.ui.Screen.Browse(server?.id ?: "", "", server?.name ?: "文件"))
        3 -> app.push(com.cloudnest.player.ui.Screen.Search)
        4 -> app.push(com.cloudnest.player.ui.Screen.Settings)
    }
}
