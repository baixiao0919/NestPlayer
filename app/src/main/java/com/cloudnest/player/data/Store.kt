package com.cloudnest.player.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 本地持久化：整份数据存放在 filesDir/appdata.json。
 * 用 JSON 而不是数据库，是为了少一层依赖、冷启动更快。
 */
object Store {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        allowStructuredMapKeys = true
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var dataFile: File
    private var saveJob: Job? = null

    private val _data = MutableStateFlow(AppData())
    val data: StateFlow<AppData> = _data.asStateFlow()

    fun init(context: Context) {
        if (::dataFile.isInitialized) return
        dataFile = File(context.filesDir, "appdata.json")
        runCatching {
            if (dataFile.exists() && dataFile.length() > 0) {
                _data.value = json.decodeFromString(AppData.serializer(), dataFile.readText())
            }
        }
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(400)
            persist()
        }
    }

    fun persistNow() {
        scope.launch { persist() }
    }

    private fun persist() {
        runCatching {
            val tmp = File(dataFile.parentFile, "appdata.json.tmp")
            tmp.writeText(json.encodeToString(AppData.serializer(), _data.value))
            if (dataFile.exists()) dataFile.delete()
            tmp.renameTo(dataFile)
        }
    }

    fun update(block: (AppData) -> AppData) {
        _data.update(block)
        scheduleSave()
    }

    fun snapshot(): AppData = _data.value

    val settings: Settings get() = _data.value.settings

    // ---------- 服务器 ----------
    fun upsertServer(server: ServerConfig) = update { cur ->
        val list = cur.servers.toMutableList()
        val idx = list.indexOfFirst { it.id == server.id }
        if (idx >= 0) list[idx] = server else list.add(server)
        cur.copy(servers = list)
    }

    fun deleteServer(id: String) = update { cur ->
        cur.copy(
            servers = cur.servers.filterNot { it.id == id },
            library = cur.library.filterNot { it.serverId == id },
            history = cur.history.filterNot { it.serverId == id },
            favorites = cur.favorites.filterNot { it.serverId == id }
        )
    }

    fun server(id: String): ServerConfig? = _data.value.servers.firstOrNull { it.id == id }

    // ---------- 观看记录 ----------
    fun saveHistory(entry: HistoryEntry) = update { cur ->
        val list = cur.history.filterNot { it.key == entry.key }.toMutableList()
        list.add(0, entry)
        cur.copy(history = list.take(300))
    }

    fun historyFor(serverId: String, path: String): HistoryEntry? =
        _data.value.history.firstOrNull { it.serverId == serverId && it.path == path }

    fun removeHistory(serverId: String, path: String) = update { cur ->
        cur.copy(history = cur.history.filterNot { it.serverId == serverId && it.path == path })
    }

    fun clearHistory() = update { it.copy(history = emptyList()) }

    // ---------- 收藏 ----------
    fun toggleFavorite(serverId: String, path: String, name: String, isDir: Boolean): Boolean {
        var added = false
        update { cur ->
            val existing = cur.favorites.firstOrNull { it.serverId == serverId && it.path == path }
            if (existing != null) {
                cur.copy(favorites = cur.favorites.filterNot { it.id == existing.id })
            } else {
                added = true
                cur.copy(
                    favorites = cur.favorites + FavoriteEntry(
                        id = "$serverId|$path", serverId = serverId, path = path,
                        name = name, isDir = isDir, addedAt = System.currentTimeMillis()
                    )
                )
            }
        }
        return added
    }

    fun isFavorite(serverId: String, path: String): Boolean =
        _data.value.favorites.any { it.serverId == serverId && it.path == path }

    // ---------- 影库 ----------
    fun replaceLibrary(serverId: String, items: List<LibraryItem>) = update { cur ->
        cur.copy(library = cur.library.filterNot { it.serverId == serverId } + items)
    }

    fun updateLibraryItem(item: LibraryItem) = update { cur ->
        cur.copy(library = cur.library.map { if (it.id == item.id) item else it })
    }

    fun libraryOf(serverId: String?): List<LibraryItem> =
        _data.value.library.filter { serverId == null || it.serverId == serverId }

    fun setSettings(s: Settings) = update { it.copy(settings = s) }

    fun putMetadata(info: MetadataInfo) = update { cur ->
        cur.copy(metadataCache = cur.metadataCache + (info.key to info))
    }

    fun metadata(key: String): MetadataInfo? = _data.value.metadataCache[key]

    fun setLastScan(serverId: String) = update { cur ->
        cur.copy(servers = cur.servers.map { if (it.id == serverId) it.copy(lastScan = System.currentTimeMillis()) else it })
    }
}
