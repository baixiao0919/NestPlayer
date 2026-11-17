package com.cloudnest.player.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.cloudnest.player.data.Store
import com.cloudnest.player.lib.Tmdb
import com.cloudnest.player.net.MediaFiles
import com.cloudnest.player.ui.AppState
import com.cloudnest.player.ui.posterUrlOf
import com.cloudnest.player.ui.theme.BrandCyan
import com.cloudnest.player.ui.theme.BrandGradient
import com.cloudnest.player.ui.theme.Gold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(app: AppState, itemId: String) {
    val data by Store.data.collectAsState()
    val item = data.library.firstOrNull { it.id == itemId }
    val server = item?.let { Store.server(it.serverId) }
    val history = item?.let { h -> data.history.firstOrNull { it.path == h.path && it.serverId == h.serverId } }
    val meta = data.metadataCache[itemId]
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }

    if (item == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("条目已不存在") }
        return
    }

    val episodes = remember(data.library, item) {
        if (item.seriesKey == null) emptyList()
        else data.library.filter { it.serverId == item.serverId && it.seriesKey == item.seriesKey }
            .sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))
    }

    val backdrop = meta?.backdropUrl ?: posterUrlOf(item.serverId, item.backdropPath)
    val posterModel = meta?.posterUrl ?: posterUrlOf(item.serverId, item.posterPath)
    val bg = MaterialTheme.colorScheme.background

    LazyColumn(Modifier.fillMaxSize().background(bg)) {

        // ---------- 头图 ----------
        item {
            Box(Modifier.fillMaxWidth().height(330.dp)) {
                if (backdrop != null) {
                    AsyncImage(
                        model = backdrop,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else if (posterModel != null) {
                    AsyncImage(
                        model = posterModel,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().alpha(0.55f)
                    )
                }
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Black.copy(alpha = 0.35f),
                                0.5f to Color.Black.copy(alpha = 0.72f),
                                1f to bg
                            )
                        )
                )

                // 返回 + 刷新
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 10.dp, end = 12.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.42f))
                            .clickable { app.pop() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Rounded.ArrowBack, "返回", tint = Color.White, modifier = Modifier.size(19.dp))
                    }
                    Spacer(Modifier.weight(1f))
                    Box(
                        Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.42f))
                            .clickable {
                                scope.launch {
                                    busy = true
                                    val apiKey = Store.settings.tmdbKey
                                    if (apiKey.isBlank()) {
                                        app.notify("请先在设置中填写 TMDB API Key")
                                    } else {
                                        val result = withContext(Dispatchers.IO) {
                                            Tmdb.search(apiKey, item.id, item.title, item.seriesKey != null, item.year, item.season)
                                        }
                                        if (result != null) {
                                            Store.putMetadata(result)
                                            Store.updateLibraryItem(
                                                item.copy(
                                                    overview = result.overview ?: item.overview,
                                                    year = result.year ?: item.year,
                                                    rating = result.rating ?: item.rating,
                                                    tmdbId = result.tmdbId
                                                )
                                            )
                                            app.notify("已更新影片信息")
                                        } else {
                                            app.notify("没找到匹配的电影信息")
                                        }
                                    }
                                    busy = false
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                        else Icon(Icons.Rounded.Refresh, "获取影片信息", tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                }

                // 海报 + 信息
                Row(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 18.dp, end = 18.dp, bottom = 14.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    Box(
                        Modifier
                            .size(width = 96.dp, height = 144.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xFF1B1B24))
                    ) {
                        if (posterModel != null) {
                            AsyncImage(
                                model = posterModel,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(item.title.take(2), color = Color.White.copy(alpha = 0.4f), fontSize = 22.sp)
                            }
                        }
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            meta?.title ?: item.title,
                            fontSize = 21.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            lineHeight = 25.sp
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val r = meta?.rating ?: item.rating
                            if (r != null && r > 0) {
                                InfoPill {
                                    Icon(Icons.Rounded.Star, null, tint = Gold, modifier = Modifier.size(11.dp))
                                    Spacer(Modifier.width(3.dp))
                                    Text("%.1f".format(r), fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
                                }
                                Spacer(Modifier.width(6.dp))
                            }
                            (meta?.year ?: item.year)?.let {
                                InfoPill { Text("$it", fontSize = 11.sp, color = Color.White) }
                                Spacer(Modifier.width(6.dp))
                            }
                            if (item.seriesKey != null) {
                                InfoPill {
                                    Text("第${item.season ?: 1}季", fontSize = 11.sp, color = Color.White)
                                }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            buildString {
                                append(MediaFiles.formatSize(item.size))
                                server?.let { append(" · ${it.name.ifBlank { it.defaultName() }}") }
                            },
                            fontSize = 11.sp,
                            color = Color.White.copy(alpha = 0.62f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        // ---------- 操作区 ----------
        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp).offset(y = (-8).dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(BrandGradient)
                        .clickable { app.playItem(item) }
                        .padding(vertical = 13.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(19.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(
                        if (history != null && history.progress in 0.01f..0.95f)
                            "继续播放 · ${(history.progress * 100).toInt()}%" else "立即播放",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.width(10.dp))
                val fav = Store.isFavorite(item.serverId, item.path)
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                        .clickable {
                            Store.toggleFavorite(item.serverId, item.path, item.name, false)
                            app.notify(if (fav) "已取消收藏" else "已加入收藏")
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (fav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        "收藏",
                        tint = if (fav) BrandCyan else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        // ---------- 简介 ----------
        val overview = meta?.overview ?: item.overview
        if (!overview.isNullOrBlank()) {
            item {
                Column(Modifier.padding(horizontal = 18.dp, vertical = 6.dp)) {
                    Text(
                        overview,
                        fontSize = 12.5.sp,
                        lineHeight = 19.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 8,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // ---------- 剧集 ----------
        if (episodes.size > 1) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(width = 3.dp, height = 16.dp).clip(RoundedCornerShape(2.dp)).background(BrandGradient))
                    Spacer(Modifier.width(8.dp))
                    Text("选集", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "共 ${episodes.size} 集",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            items(episodes, key = { it.id }) { ep ->
                val h = data.history.firstOrNull { it.path == ep.path && it.serverId == ep.serverId }
                val isCurrent = ep.path == item.path
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            if (isCurrent) BrandCyan.copy(alpha = 0.10f)
                            else MaterialTheme.colorScheme.surface.copy(alpha = 0.0f)
                        )
                        .clickable { app.playItem(ep) }
                        .padding(horizontal = 10.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(11.dp))
                            .background(
                                if (isCurrent) BrandGradient
                                else Brush.linearGradient(
                                    listOf(
                                        MaterialTheme.colorScheme.surfaceVariant,
                                        MaterialTheme.colorScheme.surfaceVariant
                                    )
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "${ep.episode ?: "-"}",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isCurrent) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            ep.name.substringBeforeLast('.'),
                            fontSize = 12.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                MediaFiles.formatSize(ep.size),
                                fontSize = 10.5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (h != null && h.progress > 0.01f) {
                                Text(
                                    "  ·  已看 ${(h.progress * 100).toInt()}%",
                                    fontSize = 10.5.sp,
                                    color = BrandCyan
                                )
                            }
                        }
                    }
                    if (isCurrent) {
                        Text("当前", fontSize = 10.sp, color = BrandCyan, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        // ---------- 文件信息 ----------
        item {
            Column(Modifier.padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 28.dp)) {
                Text("文件信息", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Text(item.name, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f))
                Text("路径：${item.path}", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f))
            }
        }
    }
}

@Composable
private fun InfoPill(content: @Composable () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White.copy(alpha = 0.14f))
            .padding(horizontal = 7.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) { content() }
}
