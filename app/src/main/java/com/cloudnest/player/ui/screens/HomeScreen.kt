package com.cloudnest.player.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
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
import com.cloudnest.player.data.Store
import com.cloudnest.player.ui.AppState
import com.cloudnest.player.ui.BottomNavBar
import com.cloudnest.player.ui.ContinueCard
import com.cloudnest.player.ui.EmptyState
import com.cloudnest.player.ui.PillChip
import com.cloudnest.player.ui.PosterCard
import com.cloudnest.player.ui.Screen
import com.cloudnest.player.ui.SectionHeader
import com.cloudnest.player.ui.theme.BrandCyan
import com.cloudnest.player.ui.theme.BrandPurple

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(app: AppState) {
    val data by Store.data.collectAsState()
    val servers = data.servers
    val activeServer = servers.firstOrNull { it.id == app.activeServerId } ?: servers.firstOrNull()
    val library = data.library.filter { activeServer == null || it.serverId == activeServer.id }
    val history = data.history.filter { activeServer == null || it.serverId == activeServer.id }

    var tab by remember { mutableStateOf(0) }
    var menuOpen by remember { mutableStateOf(false) }

    val favorites = data.favorites.filter { activeServer == null || it.serverId == activeServer.id }
    val allItems = remember(library, favorites, tab) {
        when (tab) {
            1 -> library.sortedByDescending { it.addedAt }
            2 -> library.filter { item -> favorites.any { it.serverId == item.serverId && it.path == item.path } }
                .sortedBy { it.title }
            else -> library.sortedWith(
                compareBy({ it.seriesKey ?: it.title.lowercase() }, { it.season ?: 0 }, { it.episode ?: 0 })
            )
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize()) {

            // ---------- 顶部品牌区 ----------
            Row(
                Modifier.fillMaxWidth().padding(start = 18.dp, end = 12.dp, top = 16.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "云巢影音",
                        style = MaterialTheme.typography.headlineLarge.copy(
                            brush = Brush.linearGradient(listOf(BrandPurple, BrandCyan, BrandPurple)),
                            fontSize = 25.sp
                        )
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        if (activeServer == null) "把 NAS 里的片子都收进来"
                        else "${library.size} 部影片 · ${activeServer.name.ifBlank { activeServer.defaultName() }}",
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                CircleIconButton(Icons.Rounded.Search, "搜索") { app.push(Screen.Search) }
                Spacer(Modifier.width(2.dp))
                CircleIconButton(Icons.Rounded.Folder, "文件浏览") {
                    activeServer?.let { app.push(Screen.Browse(it.id, "", it.name.ifBlank { it.defaultName() })) }
                        ?: app.push(Screen.Servers)
                }
                Box {
                    CircleIconButton(Icons.Rounded.MoreVert, "更多") { menuOpen = true }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("重新扫描影库") }, onClick = {
                            menuOpen = false
                            activeServer?.let { app.scanServer(it) }
                        })
                        DropdownMenuItem(text = { Text("服务器管理") }, onClick = {
                            menuOpen = false
                            app.push(Screen.Servers)
                        })
                        DropdownMenuItem(text = { Text("设置") }, onClick = {
                            menuOpen = false
                            app.push(Screen.Settings)
                        })
                    }
                }
            }

            // ---------- 服务器切换胶囊 ----------
            if (servers.isNotEmpty()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    servers.forEach { s ->
                        PillChip(
                            s.name.ifBlank { s.defaultName() },
                            selected = s.id == (activeServer?.id ?: ""),
                            modifier = Modifier.padding(end = 8.dp)
                        ) { app.activeServerId = s.id }
                    }
                    Box(
                        Modifier
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
                            .clickable { app.push(Screen.Servers) }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Add, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(3.dp))
                            Text("添加", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            if (servers.isEmpty()) {
                EmptyState(
                    title = "还没有连接 NAS",
                    desc = "支持 SMB / WebDAV / FTP / NFS / DLNA\n添加服务器后即可浏览并播放上面的视频",
                    actionLabel = "添加服务器",
                    onAction = { app.push(Screen.Servers) }
                )
                Box(Modifier.weight(1f))
                BottomNavBar(0) { i -> navTo(app, i) }
                return
            }

            if (library.isEmpty()) {
                EmptyState(
                    title = "影库还是空的",
                    desc = app.scanningMessage ?: "扫描服务器上的视频目录，自动生成海报墙",
                    actionLabel = if (app.scanningMessage != null) null else "开始扫描",
                    onAction = { activeServer?.let { app.scanServer(it) } }
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    OutlinedButton(
                        onClick = {
                            activeServer?.let { app.push(Screen.Browse(it.id, "", it.name.ifBlank { it.defaultName() })) }
                        },
                        shape = RoundedCornerShape(14.dp)
                    ) { Text("直接浏览文件", fontSize = 13.sp) }
                }
                Box(Modifier.weight(1f))
                BottomNavBar(0) { i -> navTo(app, i) }
                return
            }

            val continueList = history.filter { it.progress in 0.01f..0.95f }
                .sortedByDescending { it.updatedAt }
                .take(12)

            LazyVerticalGrid(
                columns = GridCells.Fixed(Store.settings.gridColumns),
                contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 104.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                if (continueList.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column {
                            SectionHeader("继续观看", "清空") {
                                continueList.forEach { h -> Store.removeHistory(h.serverId, h.path) }
                            }
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                items(continueList, key = { it.key }) { h ->
                                    val poster = library.firstOrNull { it.path == h.path && it.serverId == h.serverId }?.posterPath
                                        ?: data.library.firstOrNull { it.path == h.path && it.serverId == h.serverId }?.posterPath
                                    ContinueCard(
                                        serverId = h.serverId,
                                        posterPath = poster,
                                        title = h.name.substringBeforeLast('.'),
                                        progress = h.progress
                                    ) {
                                        app.playFile(h.serverId, h.path, h.name, h.path.substringBeforeLast('/'))
                                    }
                                }
                            }
                        }
                    }
                }

                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                        listOf("全部", "最近添加", "收藏").forEachIndexed { i, label ->
                            PillChip(label, selected = tab == i, modifier = Modifier.padding(end = 8.dp)) { tab = i }
                        }
                        Spacer(Modifier.weight(1f))
                        CircleIconButton(Icons.Rounded.Refresh, "重新扫描", size = 34) {
                            activeServer?.let { app.scanServer(it) }
                        }
                    }
                }

                if (tab == 2 && favorites.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        EmptyState("还没有收藏", "在文件浏览里长按文件或文件夹即可收藏")
                    }
                }

                items(allItems, key = { it.id }) { item ->
                    val progress = history.firstOrNull { it.path == item.path && it.serverId == item.serverId }?.progress
                    PosterCard(
                        serverId = item.serverId,
                        item = item,
                        progress = progress,
                        onClick = { app.push(Screen.Detail(item.id)) }
                    )
                }
            }
        }

        BottomNavBar(0, Modifier.align(Alignment.BottomCenter)) { i -> navTo(app, i) }
    }
}

private fun navTo(app: AppState, index: Int) {
    val server = app.currentServer() ?: return
    when (index) {
        1 -> app.push(Screen.Browse(server.id, "", server.name.ifBlank { server.defaultName() }))
        2 -> app.push(Screen.Search)
        3 -> app.push(Screen.Settings)
    }
}

@Composable
private fun CircleIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    desc: String,
    size: Int = 38,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .padding(start = 6.dp)
            .size(size.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon, desc,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size((size * 0.46f).dp)
        )
    }
}
