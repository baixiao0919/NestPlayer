package com.cloudnest.player.net

import com.cloudnest.player.data.ServerConfig
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile
import org.apache.commons.net.ftp.FTPReply
import java.io.IOException
import java.io.InputStream

class FtpFs(private val cfg: ServerConfig) : RemoteFs {

    private var client: FTPClient? = null

    @Synchronized
    private fun ftp(): FTPClient {
        client?.let { if (it.isConnected) return it }
        val c = FTPClient()
        c.controlEncoding = "UTF-8"
        c.connectTimeout = 20000
        c.defaultTimeout = 20000
        c.dataTimeout = java.time.Duration.ofSeconds(60)
        c.connect(cfg.host, cfg.portOrDefault)
        if (!FTPReply.isPositiveCompletion(c.replyCode)) {
            val code = c.replyCode
            runCatching { c.disconnect() }
            throw IOException("FTP 连接失败：$code")
        }
        val user = cfg.username.ifBlank { "anonymous" }
        val pass = if (cfg.username.isBlank()) "anonymous@" else cfg.password
        if (!c.login(user, pass)) {
            runCatching { c.disconnect() }
            throw IOException("FTP 登录失败")
        }
        c.enterLocalPassiveMode()
        c.setFileType(FTP.BINARY_FILE_TYPE)
        runCatching { c.setControlKeepAliveTimeout(30) }
        client = c
        return c
    }

    private fun absPath(path: String): String {
        val p = path.trim()
        return if (p.isEmpty() || p == "/") "/" else if (p.startsWith("/")) p else "/$p"
    }

    override fun list(path: String): List<RemoteEntry> {
        val c = ftp()
        val target = absPath(path)
        val files: Array<FTPFile> = synchronized(c) {
            c.changeWorkingDirectory(target)
            c.listFiles() ?: emptyArray()
        }
        val out = ArrayList<RemoteEntry>()
        for (f in files) {
            val name = f.name
            if (name == "." || name == ".." || name.isEmpty()) continue
            out.add(
                RemoteEntry(
                    name = name,
                    path = if (target == "/") "/$name" else "$target/$name",
                    isDir = f.isDirectory,
                    size = f.size,
                    modified = f.timestamp?.timeInMillis ?: 0L
                )
            )
        }
        return out.sortedWith(compareByDescending<RemoteEntry> { it.isDir }.thenBy { it.name.lowercase() })
    }

    override fun stat(path: String): RemoteEntry? {
        val c = ftp()
        val target = absPath(path)
        if (target == "/") return RemoteEntry("根目录", "/", true)
        val name = target.substringAfterLast('/')
        val dir = target.substringBeforeLast('/').ifEmpty { "/" }
        val dirTarget = if (dir.isEmpty()) "/" else dir
        return runCatching {
            val files = synchronized(c) {
                c.changeWorkingDirectory(dirTarget)
                c.listFiles(name)
            }
            files?.firstOrNull()?.let { f ->
                RemoteEntry(name, target, f.isDirectory, f.size, f.timestamp?.timeInMillis ?: 0L)
            }
        }.getOrNull()
    }

    override fun openReader(path: String): RemoteReader {
        val target = absPath(path)
        val size = stat(path)?.size ?: -1L
        return StreamReader(size) { offset -> openStream(target, offset) }
    }

    private fun openStream(target: String, offset: Long): InputStream {
        val c = FTPClient()
        c.controlEncoding = "UTF-8"
        c.connectTimeout = 20000
        c.defaultTimeout = 20000
        c.dataTimeout = java.time.Duration.ofSeconds(60)
        c.connect(cfg.host, cfg.portOrDefault)
        val user = cfg.username.ifBlank { "anonymous" }
        val pass = if (cfg.username.isBlank()) "anonymous@" else cfg.password
        if (!c.login(user, pass)) {
            runCatching { c.disconnect() }
            throw IOException("FTP 登录失败")
        }
        c.enterLocalPassiveMode()
        c.setFileType(FTP.BINARY_FILE_TYPE)
        if (offset > 0) c.setRestartOffset(offset)
        val stream = c.retrieveFileStream(target)
            ?: run {
                c.logout(); c.disconnect()
                throw IOException("FTP 无法读取文件：$target")
            }
        return object : InputStream() {
            override fun read(): Int = stream.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int = stream.read(b, off, len)
            override fun close() {
                runCatching { stream.close() }
                runCatching { c.completePendingCommand() }
                runCatching { c.logout() }
                runCatching { c.disconnect() }
            }
        }
    }

    override fun isRoot(path: String): Boolean = path.trim('/').isEmpty()
    override fun parentOf(path: String): String = parentPathOf(absPath(path)) ?: "/"
    override fun displayPath(path: String): String = absPath(path)

    @Synchronized
    override fun close() {
        runCatching { client?.logout() }
        runCatching { client?.disconnect() }
        client = null
    }
}
