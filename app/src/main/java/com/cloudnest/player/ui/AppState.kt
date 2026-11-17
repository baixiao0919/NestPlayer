package com.cloudnest.player.ui

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cloudnest.player.data.LibraryItem
import com.cloudnest.player.data.ServerConfig
import com.cloudnest.player.data.Store
import com.cloudnest.player.lib.LibraryScanner
import com.cloudnest.player.net.FsManager
import com.cloudnest.player.net.MediaFiles
import com.cloudnest.player.net.StreamProxy
import com.cloudnest.player.net.UpdateChecker
import com.cloudnest.player.player.PlayerActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Screen {
    data object Home : Screen
    data class Browse(val serverId: String, val path: String, val title: String) : Screen
    data class Detail(val itemId: String) : Screen
    data object Servers : Screen
    data object Settings : Screen
    data object Search : Screen
    data object Music : Screen
    data object Login : Screen
}

class AppState(val context: Context, private val scope: CoroutineScope) {

    var stack by mutableStateOf<List<Screen>>(listOf(Screen.Home))
        private set

    val current: Screen get() = stack.last()

    var activeServerId by mutableStateOf<String?>(Store.data.value.servers.firstOrNull()?.id)
    var scanningMessage by mutableStateOf<String?>(null)
    var musicScanningMessage by mutableStateOf<String?>(null)
    var message by mutableStateOf<String?>(null)
    var updateInfo by mutableStateOf<UpdateChecker.Info?>(null)

    fun push(screen: Screen) {
        stack = stack + screen
    }

    fun pop(): Boolean {
        if (stack.size <= 1) return false
        stack = stack.dropLast(1)
        return true
    }

    fun backToHome() {
        stack = listOf(Screen.Home)
    }

    fun notify(text: String) {
        message = text
    }

    fun currentServer(): ServerConfig? =
        activeServerId?.let { Store.server(it) } ?: Store.data.value.servers.firstOrNull()

    fun activeLibrary(): List<LibraryItem> {
        val id = activeServerId
        return Store.data.value.library.filter { id == null || it.serverId == id }
    }

    fun posterUrl(serverId: String, path: String?): String? {
        if (path.isNullOrBlank()) return null
        return StreamProxy.url(serverId, path, path.substringAfterLast('/'))
    }

    fun playFile(serverId: String, path: String, name: String, dirPath: String) {
        val intent = PlayerActivity.intent(context, serverId, path, name, dirPath)
        context.startActivity(intent)
    }

    fun playItem(item: LibraryItem) {
        playFile(item.serverId, item.path, item.name, item.path.substringBeforeLast('/'))
    }

    /** 扫描影库；paths 为空时用服务器根路径 */
    fun scanServer(server: ServerConfig, paths: List<String> = emptyList()) {
        if (scanningMessage != null) return
        scope.launch {
            scanningMessage = "正在连接 ${server.name.ifBlank { server.defaultName() }}…"
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val fs = FsManager.get(server.id)
                    val roots = paths.ifEmpty {
                        val base = server.basePath.trim('/')
                        when (server.protocol) {
                            // SMB / WebDAV 的路径都相对服务器自己配置的起始路径，直接从根开始
                            com.cloudnest.player.data.ProtocolType.SMB,
                            com.cloudnest.player.data.ProtocolType.WEBDAV -> listOf("")
                            com.cloudnest.player.data.ProtocolType.DLNA -> listOf("cid:0")
                            com.cloudnest.player.data.ProtocolType.FTP -> listOf(if (base.isEmpty()) "/" else "/" + base)
                            else -> listOf("/")
                        }
                    }
                    val depth = Store.settings.scanDepth
                    LibraryScanner.scan(fs, server.id, roots, depth, 6000) { p ->
                        scanningMessage = "已扫描 ${p.scannedDirs} 个文件夹 · 找到 ${p.foundVideos} 个视频"
                    }
                }
            }
            result.onSuccess { items ->
                Store.replaceLibrary(server.id, items)
                Store.setLastScan(server.id)
                scanningMessage = null
                notify(
                    if (items.isEmpty()) "没有找到视频文件，检查路径或协议设置"
                    else "扫描完成，共 ${items.size} 个视频"
                )
            }.onFailure {
                scanningMessage = null
                notify("扫描失败：${it.message ?: "未知错误"}")
            }
        }
    }

    fun scanFolder(serverId: String, path: String) {
        val server = Store.server(serverId) ?: return
        val existing = server.libraryPaths.toMutableList()
        if (path !in existing) {
            Store.upsertServer(server.copy(libraryPaths = existing + path))
        }
        scanServer(Store.server(serverId) ?: server, listOf(path))
    }

    /** 扫描音乐库：与视频扫描共用协议层，只是过滤器换成音频 */
    fun scanMusic(serverId: String) {
        if (musicScanningMessage != null) return
        val server = Store.server(serverId) ?: return
        scope.launch {
            musicScanningMessage = "正在扫描 ${server.name.ifBlank { server.defaultName() }} 的音乐…"
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val fs = FsManager.get(server.id)
                    val base = server.basePath.trim('/')
                    val roots = when (server.protocol) {
                        com.cloudnest.player.data.ProtocolType.SMB,
                        com.cloudnest.player.data.ProtocolType.WEBDAV -> listOf("")
                        com.cloudnest.player.data.ProtocolType.DLNA -> listOf("cid:0")
                        com.cloudnest.player.data.ProtocolType.FTP -> listOf(if (base.isEmpty()) "/" else "/" + base)
                        else -> listOf("/")
                    }
                    com.cloudnest.player.lib.MusicScanner.scan(
                        fs, server.id, roots, maxDepth = 8, maxTracks = 8000
                    ) { p ->
                        musicScanningMessage = "已扫描 ${p.scannedDirs} 个文件夹 · 找到 ${p.foundTracks} 首"
                    }
                }
            }
            result.onSuccess { tracks ->
                Store.replaceMusic(server.id, tracks)
                musicScanningMessage = null
                notify(if (tracks.isEmpty()) "没有找到音频文件，检查路径或协议设置" else "扫描完成，共 ${tracks.size} 首")
            }.onFailure {
                musicScanningMessage = null
                notify("音乐扫描失败：${it.message ?: "未知错误"}")
            }
        }
    }

    /** 检查更新：manual=true 时无论结果都提示；自动检查 24 小时最多一次 */
    fun checkUpdate(manual: Boolean = false) {
        val ctx = context
        val prefs = ctx.getSharedPreferences("nest_update", android.content.Context.MODE_PRIVATE)
        val last = prefs.getLong("last_check", 0L)
        if (!manual && System.currentTimeMillis() - last < 24L * 60 * 60 * 1000) return
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { UpdateChecker.latest() } }
            prefs.edit().putLong("last_check", System.currentTimeMillis()).apply()
            val info = r.getOrNull()
            if (info == null) {
                if (manual) notify("检查更新失败，检查一下网络")
                return@launch
            }
            if (UpdateChecker.isNewer(info.version, com.cloudnest.player.BuildConfig.VERSION_NAME)) {
                updateInfo = info
            } else if (manual) {
                notify("已是最新版本 ${com.cloudnest.player.BuildConfig.VERSION_NAME}")
            }
        }
    }

    fun openLogin() {
        push(Screen.Login)
    }

    fun openMusicPlayer() {
        context.startActivity(com.cloudnest.player.player.MusicPlayerActivity.intent(context))
    }

    fun isVideo(name: String) = MediaFiles.isVideo(name)

    fun openWithIntent(intent: Intent) = context.startActivity(intent)
}
