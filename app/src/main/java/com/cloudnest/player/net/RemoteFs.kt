package com.cloudnest.player.net

import com.cloudnest.player.data.ProtocolType
import com.cloudnest.player.data.ServerConfig
import com.cloudnest.player.data.Store
import java.io.Closeable
import java.io.IOException
import java.io.InputStream

data class RemoteEntry(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val size: Long = 0L,
    val modified: Long = 0L,
    /** DLNA 的 objectId / 其它协议备用 */
    val objectId: String = "",
    /** DLNA 专辑封面地址 */
    val albumArt: String? = null
)

interface RemoteReader : Closeable {
    val length: Long
    /** 从 pos 读取，返回实际字节数，-1 表示结束 */
    fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int
}

interface RemoteFs : Closeable {
    fun list(path: String): List<RemoteEntry>
    fun openReader(path: String): RemoteReader
    fun stat(path: String): RemoteEntry?
    fun isRoot(path: String): Boolean
    fun parentOf(path: String): String?
    fun displayPath(path: String): String = path
}

/** 通用的顺序流包装：随机定位时重新打开流（必要时跳过) */
class StreamReader(
    override val length: Long,
    private val opener: (Long) -> InputStream
) : RemoteReader {
    private var ins: InputStream? = null
    private var curPos = -1L

    @Synchronized
    override fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int {
        if (len <= 0) return 0
        if (ins == null || pos != curPos) {
            runCatching { ins?.close() }
            ins = opener(pos)
            curPos = pos
        }
        val n = ins!!.read(buf, off, len)
        if (n > 0) curPos += n
        return n
    }

    @Synchronized
    override fun close() {
        runCatching { ins?.close() }
        ins = null
        curPos = -1L
    }
}

object FsManager {
    private val cache = HashMap<String, RemoteFs>()

    @Synchronized
    fun get(serverId: String): RemoteFs {
        cache[serverId]?.let { return it }
        val cfg = Store.server(serverId) ?: throw IOException("服务器不存在")
        val fs = create(cfg)
        cache[serverId] = fs
        return fs
    }

    @Synchronized
    fun invalidate(serverId: String) {
        cache.remove(serverId)?.let { runCatching { it.close() } }
    }

    @Synchronized
    fun closeAll() {
        cache.values.forEach { runCatching { it.close() } }
        cache.clear()
    }

    fun create(cfg: ServerConfig): RemoteFs = when (cfg.protocol) {
        ProtocolType.SMB -> SmbFs(cfg)
        ProtocolType.WEBDAV -> WebDavFs(cfg)
        ProtocolType.FTP -> FtpFs(cfg)
        ProtocolType.NFS -> NfsFs(cfg)
        ProtocolType.DLNA -> DlnaFs(cfg)
    }
}

fun joinPath(parent: String, name: String): String =
    if (parent.isEmpty() || parent == "/") name else parent.trimEnd('/') + "/" + name

fun parentPathOf(path: String): String? {
    if (path.isEmpty() || path == "/") return null
    if (path.startsWith("cid:")) return null
    val trimmed = path.trimEnd('/')
    val idx = trimmed.lastIndexOf('/')
    return if (idx <= 0) "/" else trimmed.substring(0, idx)
}

object MediaFiles {
    val videoExt = setOf(
        "mkv", "mp4", "avi", "mov", "wmv", "flv", "ts", "m2ts", "mts", "m4v",
        "rmvb", "rm", "webm", "mpg", "mpeg", "3gp", "ogv", "vob", "divx", "asf",
        "f4v", "m2v", "tp", "trp", "evo", "ogm", "dat", "iso"
    )
    val subExt = setOf("srt", "ass", "ssa", "vtt", "sub", "smi", "txt")

    fun ext(name: String): String = name.substringAfterLast('.', "").lowercase()

    fun isVideo(name: String): Boolean = ext(name) in videoExt

    fun isSubtitle(name: String): Boolean = ext(name) in subExt

    fun isImage(name: String): Boolean = ext(name) in setOf("jpg", "jpeg", "png", "webp", "bmp", "gif")

    fun mimeOf(name: String): String = when (ext(name)) {
        "mkv" -> "video/x-matroska"
        "mp4", "m4v" -> "video/mp4"
        "avi" -> "video/x-msvideo"
        "mov" -> "video/quicktime"
        "wmv", "asf" -> "video/x-ms-wmv"
        "flv" -> "video/x-flv"
        "ts", "m2ts", "mts", "tp", "trp" -> "video/mp2t"
        "webm" -> "video/webm"
        "mpg", "mpeg", "m2v" -> "video/mpeg"
        "3gp" -> "video/3gpp"
        "ogv", "ogm" -> "video/ogg"
        "vob" -> "video/mpeg"
        "rm", "rmvb" -> "application/vnd.rn-realmedia"
        "iso" -> "application/octet-stream"
        "srt" -> "application/x-subrip"
        "ass", "ssa" -> "text/x-ssa"
        "vtt" -> "text/vtt"
        "sub" -> "text/plain"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        else -> "application/octet-stream"
    }

    fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var i = 0
        while (value >= 1024 && i < units.size - 1) {
            value /= 1024
            i++
        }
        return if (i == 0) "${bytes} B" else String.format("%.1f %s", value, units[i])
    }
}
