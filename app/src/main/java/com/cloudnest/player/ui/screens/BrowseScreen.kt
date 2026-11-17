package com.cloudnest.player.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cloudnest.player.data.Store
import com.cloudnest.player.net.FsManager
import com.cloudnest.player.net.MediaFiles
import com.cloudnest.player.net.RemoteEntry
import com.cloudnest.player.net.RemoteFs
import com.cloudnest.player.ui.AppState
import com.cloudnest.player.ui.EmptyState
import com.cloudnest.player.ui.theme.BrandCyan
import com.cloudnest.player.ui.theme.BrandPurple
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(app: AppState, serverId: String, initialPath: String, initialTitle: String) {
    val scope = rememberCoroutineScope()
    var path by remember { mutableStateOf(initialPath) }
    var entries by remember { mutableStateOf<List<RemoteEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var sortBy by remember { mutableStateOf(0) } // 0 名称 1 大小 2 时间
    var menu by remember { mutableStateOf(false) }
    var searchMode by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var sheetEntry by remember { mutableStateOf<RemoteEntry?>(null) }
    var fsRef by remember { mutableStateOf<RemoteFs?>(null) }

    LaunchedEffect(path, sortBy) {
        loading = true
        error = null
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val fs = FsManager.get(serverId)
                fsRef = fs
                val list = fs.list(path).filter {
                    Store.settings.showHiddenFiles || !it.name.startsWith(".")
                }
                when (sortBy) {
                    1 -> list.sortedWith(compareByDescending<RemoteEntry> { it.isDir }.thenByDescending { it.size })
                    2 -> list.sortedWith(compareByDescending<RemoteEntry> { it.isDir }.thenByDescending { it.modified })
                    else -> list.sortedWith(compareByDescending<RemoteEntry> { it.isDir }.thenBy { it.name.lowercase() })
                }
            }
        }
        result.onSuccess { entries = it; loading = false }
            .onFailure { error = it.message ?: "无法读取目录"; loading = false }
    }

    val title = remember(path, initialTitle) {
        if (path.isBlank() || path == "/") initialTitle else path.trimEnd('/').substringAfterLast('/')
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 10.dp, end = 8.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                    .clickable {
                        val fs = fsRef
                        val norm = path.trim('/')
                        val rootNorm = initialPath.trim('/')
                        if (fs == null || norm == rootNorm) {
                            app.pop()
                        } else {
                            val parent = fs.parentOf(path)
                            if (parent == null || parent.trim('/') == norm) app.pop() else path = parent
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.ArrowBack, "返回", modifier = Modifier.size(18.dp))
            }
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(
                    title.ifBlank { "根目录" },
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    path.ifBlank { "/" },
                    fontSize = 10.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = { searchMode = !searchMode; query = "" }) {
                Icon(
                    if (searchMode) Icons.Rounded.Close else Icons.Rounded.Search,
                    "搜索",
                    modifier = Modifier.size(19.dp)
                )
            }
            IconButton(onClick = {
                scope.launch {
                    loading = true
                    val result = withContext(Dispatchers.IO) { runCatching { FsManager.get(serverId).list(path) } }
                    result.onSuccess { entries = it; loading = false }
                        .onFailure { error = it.message; loading = false }
                }
            }) { Icon(Icons.Rounded.Refresh, "刷新", modifier = Modifier.size(19.dp)) }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Rounded.MoreVert, "更多", modifier = Modifier.size(19.dp))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    listOf("按名称", "按大小", "按时间").forEachIndexed { i, label ->
                        DropdownMenuItem(text = { Text(label) }, onClick = { sortBy = i; menu = false })
                    }
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("扫描此目录加入影库") }, onClick = {
                        menu = false
                        app.scanFolder(serverId, path)
                        app.notify("已开始扫描当前目录")
                    })
                    DropdownMenuItem(text = { Text("收藏当前位置") }, onClick = {
                        menu = false
                        Store.toggleFavorite(serverId, path, title, true)
                        app.notify("已切换收藏状态")
                    })
                }
            }
        }

        if (searchMode) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.Search, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                TextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("在当前目录搜索", fontSize = 13.sp) },
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier.weight(1f)
                )
            }
        }

        if (loading) {
            LinearProgressIndicator(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(2.dp),
                color = BrandCyan,
                trackColor = Color.Transparent
            )
        }

        error?.let {
            EmptyState("打不开这个目录", it)
            return@Column
        }

        val filtered = entries.filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
        if (filtered.isEmpty() && !loading) {
            EmptyState("这里没有内容", "试试返回上一层，或在服务器设置里换个起始目录")
            return@Column
        }

        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 4.dp)) {
            items(filtered, key = { it.path }) { entry ->
                FileRow(
                    entry = entry,
                    onClick = {
                        if (entry.isDir) {
                            path = entry.path
                            query = ""
                            searchMode = false
                        } else if (MediaFiles.isVideo(entry.name)) {
                            app.playFile(serverId, entry.path, entry.name, path)
                        } else if (MediaFiles.isSubtitle(entry.name)) {
                            app.notify("字幕文件会在播放时自动匹配")
                        } else {
                            app.notify("不支持的文件类型")
                        }
                    },
                    onLongClick = { sheetEntry = entry }
                )
            }
        }
    }

    sheetEntry?.let { entry ->
        ModalBottomSheet(
            onDismissRequest = { sheetEntry = null },
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
                Text(
                    entry.name,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    (if (entry.isDir) "文件夹" else MediaFiles.formatSize(entry.size)) + " · " + entry.path,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
                if (entry.isDir) {
                    SheetAction("扫描此文件夹加入影库") {
                        app.scanFolder(serverId, entry.path)
                        sheetEntry = null
                        app.notify("开始扫描 ${entry.name}")
                    }
                }
                if (!entry.isDir && MediaFiles.isVideo(entry.name)) {
                    SheetAction("立即播放") {
                        app.playFile(serverId, entry.path, entry.name, path)
                        sheetEntry = null
                    }
                }
                SheetAction(if (Store.isFavorite(serverId, entry.path)) "取消收藏" else "收藏") {
                    Store.toggleFavorite(serverId, entry.path, entry.name, entry.isDir)
                    sheetEntry = null
                }
                SheetAction("复制路径") {
                    val cm = app.context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("path", entry.path))
                    sheetEntry = null
                    app.notify("路径已复制")
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(entry: RemoteEntry, onClick: () -> Unit, onLongClick: () -> Unit) {
    val isVideo = MediaFiles.isVideo(entry.name)
    val isSub = MediaFiles.isSubtitle(entry.name)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(14.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 8.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(
                    if (entry.isDir) Brush.linearGradient(
                        listOf(BrandCyan.copy(alpha = 0.20f), BrandCyan.copy(alpha = 0.06f))
                    ) else Brush.linearGradient(
                        listOf(BrandPurple.copy(alpha = 0.20f), BrandPurple.copy(alpha = 0.06f))
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                when {
                    entry.isDir -> Icons.Rounded.Folder
                    isVideo -> Icons.Rounded.Movie
                    isSub -> Icons.Rounded.Subtitles
                    else -> Icons.Rounded.Folder
                },
                contentDescription = null,
                tint = if (entry.isDir) BrandCyan else BrandPurple,
                modifier = Modifier.size(19.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.name,
                fontSize = 13.5.sp,
                fontWeight = if (entry.isDir) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                if (entry.isDir) "文件夹" else MediaFiles.formatSize(entry.size),
                fontSize = 10.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            Icons.Rounded.ChevronRight, null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(16.dp)
        )
    }
}

@Composable
private fun SheetAction(label: String, onClick: () -> Unit) {
    Text(
        label,
        fontSize = 14.sp,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 13.dp, horizontal = 4.dp)
    )
}
