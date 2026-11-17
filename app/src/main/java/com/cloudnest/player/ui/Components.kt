package com.cloudnest.player.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.cloudnest.player.data.LibraryItem
import com.cloudnest.player.net.StreamProxy
import com.cloudnest.player.ui.theme.BrandCyan
import com.cloudnest.player.ui.theme.BrandGradient
import com.cloudnest.player.ui.theme.BrandPurple
import com.cloudnest.player.ui.theme.Gold
import com.cloudnest.player.ui.theme.TextSecondary

fun posterUrlOf(serverId: String, path: String?): String? {
    if (path.isNullOrBlank()) return null
    return StreamProxy.url(serverId, path, path.substringAfterLast('/').ifBlank { "poster.jpg" })
}

/** 统一圆角 + 渐变占位，加载失败也能看 */
@Composable
fun RemoteImage(
    serverId: String,
    path: String?,
    modifier: Modifier = Modifier,
    fallbackLabel: String? = null,
    corner: androidx.compose.ui.unit.Dp = 16.dp
) {
    val url = posterUrlOf(serverId, path)
    Box(
        modifier
            .clip(RoundedCornerShape(corner))
            .background(Brush.linearGradient(listOf(Color(0xFF2A2340), Color(0xFF15151E))))
    ) {
        if (fallbackLabel != null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    fallbackLabel.take(2).ifBlank { "?" },
                    color = Color.White.copy(alpha = 0.35f),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/** 海报卡：按压缩放 + 图上叠标题/评分 + 底部进度 */
@Composable
fun PosterCard(
    serverId: String,
    item: LibraryItem,
    progress: Float? = null,
    onClick: () -> Unit
) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.955f else 1f, tween(140), label = "poster")

    Column(
        Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(16.dp))
            .clickable(interactionSource = src, indication = null, onClick = onClick)
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(16.dp))) {
            RemoteImage(
                serverId = serverId,
                path = item.posterPath,
                modifier = Modifier.fillMaxSize(),
                fallbackLabel = item.title,
                corner = 16.dp
            )
            // 底部渐变 + 标题
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(66.dp)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.82f))
                        )
                    )
            )
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 9.dp, end = 9.dp, bottom = 9.dp)
            ) {
                Text(
                    item.title,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 15.sp
                )
                val sub = when {
                    item.seriesKey != null -> "第${item.season ?: 1}季" + (item.episode?.let { " · ${it}集" } ?: "")
                    item.year != null -> "${item.year}"
                    else -> null
                }
                if (sub != null) {
                    Text(sub, fontSize = 10.sp, color = Color.White.copy(alpha = 0.65f), maxLines = 1)
                }
            }
            // 评分角标
            val rating = item.rating
            if (rating != null && rating > 0) {
                Row(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(7.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.Star, null, tint = Gold, modifier = Modifier.size(10.dp))
                    Spacer(Modifier.width(2.dp))
                    Text("%.1f".format(rating), fontSize = 10.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
                }
            }
            if (progress != null && progress > 0.02f) {
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp)) {
                    Box(Modifier.fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.18f)))
                    Box(Modifier.fillMaxWidth(progress).height(3.dp).background(BrandGradient))
                }
            }
        }
    }
}

/** 继续观看用的大卡片（16:9） */
@Composable
fun ContinueCard(
    serverId: String,
    posterPath: String?,
    title: String,
    progress: Float,
    onClick: () -> Unit
) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, tween(140), label = "continue")

    Box(
        Modifier
            .width(218.dp)
            .height(126.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(18.dp))
            .clickable(interactionSource = src, indication = null, onClick = onClick)
    ) {
        RemoteImage(serverId, posterPath, Modifier.fillMaxSize(), title, corner = 18.dp)
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        listOf(Color.Black.copy(alpha = 0.72f), Color.Black.copy(alpha = 0.05f))
                    )
                )
        )
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
        ) {
            Text(
                title,
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 17.sp
            )
            Spacer(Modifier.height(4.dp))
            Text("已看 ${(progress * 100).toInt()}%", color = BrandCyan, fontSize = 10.sp, fontWeight = FontWeight.Medium)
        }
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 12.dp)
                .size(34.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.92f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Rounded.PlayArrow, null, tint = Color(0xFF17171F), modifier = Modifier.size(20.dp))
        }
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp)) {
            Box(Modifier.fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.16f)))
            Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(3.dp).background(BrandGradient))
        }
    }
}

@Composable
fun SectionHeader(title: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(width = 3.dp, height = 16.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(BrandGradient)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            title,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.2).sp,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f)
        )
        if (action != null && onAction != null) {
            Text(
                action,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onAction).padding(6.dp)
            )
        }
    }
}

/** 分类胶囊 */
@Composable
fun PillChip(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val alpha by animateFloatAsState(if (pressed) 0.75f else 1f, tween(120), label = "chip")
    Box(
        modifier
            .graphicsLayer { this.alpha = alpha }
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) Color.Transparent else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
            .then(if (selected) Modifier.background(BrandGradient) else Modifier)
            .clickable(interactionSource = src, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp)
    ) {
        Text(
            text,
            fontSize = 12.5.sp,
            color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

/** 渐变主按钮 */
@Composable
fun GradientButton(
    text: String,
    icon: ImageVector? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, tween(120), label = "btn")
    Row(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(14.dp))
            .background(BrandGradient)
            .clickable(interactionSource = src, indication = null, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, color = Color.White, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** 悬浮底部导航（只在首页出现，风格轻一点） */
@Composable
fun BottomNavBar(index: Int, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    data class Item(val label: String, val icon: ImageVector)

    val items = listOf(
        Item("首页", Icons.Rounded.Home),
        Item("音乐", Icons.Rounded.MusicNote),
        Item("浏览", Icons.Rounded.Folder),
        Item("搜索", Icons.Rounded.Search),
        Item("设置", Icons.Rounded.Settings)
    )
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f))
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f), RoundedCornerShape(26.dp))
            .padding(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items.forEachIndexed { i, item ->
            val sel = i == index
            Row(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(20.dp))
                    .then(if (sel) Modifier.background(BrandGradient) else Modifier)
                    .clickable { onSelect(i) }
                    .padding(vertical = 9.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    item.icon, null,
                    tint = if (sel) Color.White else TextSecondary,
                    modifier = Modifier.size(18.dp)
                )
                if (sel) {
                    Spacer(Modifier.width(6.dp))
                    Text(item.label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
fun EmptyState(title: String, desc: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.size(64.dp).clip(CircleShape).background(BrandPurple.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Rounded.Folder, null, tint = BrandPurple, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.height(14.dp))
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(6.dp))
        Text(desc, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 18.sp)
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(16.dp))
            GradientButton(actionLabel, onClick = onAction)
        }
    }
}

/** 全局迷你播放条：视频/浏览/设置页都能看到正在放的音乐 */
@Composable
fun MiniPlayerBar(modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var tick by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(500)
            tick++
        }
    }
    val id = com.cloudnest.player.player.MusicCore.currentId ?: return
    val track = remember(tick, id) {
        com.cloudnest.player.player.MusicCore.queue.firstOrNull { it.id == id }
    } ?: return
    val playing = com.cloudnest.player.player.MusicCore.player?.isPlaying == true
    val pos = com.cloudnest.player.player.MusicCore.player?.currentPosition ?: 0L
    val dur = (com.cloudnest.player.player.MusicCore.player?.duration ?: 0L)
        .takeIf { it > 0 } ?: track.durationMs

    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF15151E).copy(alpha = 0.97f))
            .clickable {
                context.startActivity(com.cloudnest.player.player.MusicPlayerActivity.intent(context))
            }
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(38.dp).clip(RoundedCornerShape(10.dp))
                    .background(Brush.linearGradient(listOf(BrandPurple.copy(alpha = 0.5f), BrandCyan.copy(alpha = 0.3f))))
            ) {
                if (!track.coverPath.isNullOrBlank()) {
                    RemoteImage(track.serverId, track.coverPath, Modifier.fillMaxSize(), track.title, 10.dp)
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(track.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Text(
                    track.artist.ifBlank { track.album },
                    color = TextSecondary, fontSize = 11.sp, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
            Icon(
                if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "播放/暂停",
                tint = Color.White,
                modifier = Modifier.size(30.dp).clip(CircleShape)
                    .clickable {
                        com.cloudnest.player.player.MusicCore.toggle(context)
                        tick++
                    }.padding(4.dp)
            )
            Icon(
                Icons.Rounded.SkipNext, "下一首",
                tint = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.size(28.dp).clip(CircleShape)
                    .clickable {
                        com.cloudnest.player.player.MusicCore.next()
                        tick++
                    }.padding(4.dp)
            )
        }
        val frac = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
        Box(Modifier.fillMaxWidth().height(2.dp).background(Color.White.copy(alpha = 0.12f))) {
            Box(
                Modifier.fillMaxHeight().fillMaxWidth(frac)
                    .background(Brush.horizontalGradient(listOf(BrandPurple, BrandCyan)))
            )
        }
    }
}
