package com.cloudnest.player.data

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class ProtocolType(val label: String, val defaultPort: Int) {
    SMB("SMB / 局域网共享", 445),
    WEBDAV("WebDAV", 5005),
    FTP("FTP", 21),
    NFS("NFS (v3)", 2049),
    DLNA("DLNA / UPnP 媒体服务器", 0);

    companion object {
        fun fromName(n: String): ProtocolType = entries.firstOrNull { it.name == n } ?: SMB
    }
}

@Serializable
data class ServerConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val protocol: ProtocolType = ProtocolType.SMB,
    val host: String = "",
    val port: Int = 0,
    val username: String = "",
    val password: String = "",
    /** SMB: 共享名/子目录；WebDAV: 起始路径；FTP: 起始目录；NFS: 导出路径；DLNA: 容器 ID */
    val basePath: String = "",
    val libraryPaths: List<String> = emptyList(),
    val searchPathsNote: String = "",
    /** DLNA 的 ContentDirectory 控制地址 */
    val controlUrl: String = "",
    val udn: String = "",
    val lastScan: Long = 0L
) {
    val portOrDefault: Int get() = if (port > 0) port else protocol.defaultPort
    val hostLabel: String get() = if (protocol == ProtocolType.DLNA) host else "$host:${portOrDefault}"

    fun defaultName(): String = when (protocol) {
        ProtocolType.SMB -> "SMB · $host"
        ProtocolType.WEBDAV -> "WebDAV · $host"
        ProtocolType.FTP -> "FTP · $host"
        ProtocolType.NFS -> "NFS · $host"
        ProtocolType.DLNA -> "DLNA · $host"
    }
}

@Serializable
data class HistoryEntry(
    val key: String,
    val serverId: String,
    val path: String,
    val name: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
    val finished: Boolean = false
) {
    val progress: Float
        get() = if (durationMs <= 0) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
}

@Serializable
data class LibraryItem(
    val id: String,
    val serverId: String,
    val path: String,
    val name: String,
    val title: String,
    val year: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val seriesKey: String? = null,
    val size: Long = 0L,
    val modified: Long = 0L,
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val overview: String? = null,
    val tmdbId: Long? = null,
    val rating: Double? = null,
    val addedAt: Long = 0L
) {
    val isSeries: Boolean get() = seriesKey != null
}

@Serializable
data class FavoriteEntry(
    val id: String,
    val serverId: String,
    val path: String,
    val name: String,
    val isDir: Boolean,
    val addedAt: Long
)

@Serializable
data class Settings(
    val themeMode: Int = 0,          // 0 跟随系统 1 深色 2 浅色
    val tmdbKey: String = "",
    val autoResume: Boolean = true,
    val seekStep: Int = 10,          // 双击快进秒数
    val defaultSpeed: Float = 1f,
    val gridColumns: Int = 3,
    val showHiddenFiles: Boolean = false,
    val hardwareDecode: Boolean = true,
    val playerOrientation: Int = 0,  // 0 自动 1 竖屏 2 横屏
    val scanDepth: Int = 6,
    val videoExtensionsOnly: Boolean = true,
    /** .ts/.m2ts 等无索引容器：估时后是否接管 seek（关掉则退回顺序播放，兼容性最保守） */
    val tsSeekEnabled: Boolean = true
)

@Serializable
data class AppData(
    val servers: List<ServerConfig> = emptyList(),
    val library: List<LibraryItem> = emptyList(),
    val history: List<HistoryEntry> = emptyList(),
    val favorites: List<FavoriteEntry> = emptyList(),
    val settings: Settings = Settings(),
    val metadataCache: Map<String, MetadataInfo> = emptyMap()
)

@Serializable
data class MetadataInfo(
    val key: String,
    val title: String? = null,
    val overview: String? = null,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val year: Int? = null,
    val rating: Double? = null,
    val tmdbId: Long? = null
)
