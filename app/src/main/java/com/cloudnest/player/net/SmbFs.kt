package com.cloudnest.player.net

import com.cloudnest.player.data.ServerConfig
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.mserref.NtStatus
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File
import java.io.IOException
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/**
 * SMB2/3 访问。路径约定：所有 path 都相对于「共享根」，basePath 里的共享名固定，
 * basePath 里的子目录作为起点（例如 basePath=video/Movies 时，"" 就是 Movies）。
 */
class SmbFs(private val cfg: ServerConfig) : RemoteFs {

    private val shareName: String = cfg.basePath.trim('/').substringBefore('/').ifEmpty {
        throw IOException("请先在服务器设置里填写 SMB 共享名（例如 video）")
    }
    private val baseSub: String = cfg.basePath.trim('/').substringAfter('/', "").trim('/')

    private var client: SMBClient? = null
    private var connection: Connection? = null
    private var session: Session? = null
    private val openFiles: MutableSet<File> =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<File, Boolean>())

    @Synchronized
    private fun session(): Session {
        val s = session
        if (s != null && connection?.isConnected == true) return s
        closeQuietly()
        val conf = SmbConfig.builder()
            .withTimeout(30, TimeUnit.SECONDS)
            .withReadTimeout(60, TimeUnit.SECONDS)
            .withMultiProtocolNegotiate(true)
            .build()
        val c = SMBClient(conf)
        val conn = c.connect(cfg.host, cfg.portOrDefault)
        val ac = AuthenticationContext(
            cfg.username.ifBlank { "" },
            cfg.password.toCharArray(),
            null
        )
        val sess = conn.authenticate(ac)
        client = c
        connection = conn
        session = sess
        return sess
    }

    private var disk: DiskShare? = null

    @Synchronized
    private fun share(): DiskShare {
        disk?.let { return it }
        val d = session().connectShare(shareName) as DiskShare
        disk = d
        return d
    }

    private fun absPath(path: String): String {
        val p = path.trim('/')
        return if (baseSub.isEmpty()) p else if (p.isEmpty()) baseSub else "$baseSub/$p"
    }

    override fun list(path: String): List<RemoteEntry> {
        val target = absPath(path)
        val listings: List<FileIdBothDirectoryInformation> = share().list(target)
        val out = ArrayList<RemoteEntry>()
        for (info in listings) {
            val name = info.fileName
            if (name == "." || name == "..") continue
            val isDir = (info.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L
            out.add(
                RemoteEntry(
                    name = name,
                    path = joinPath(path, name),
                    isDir = isDir,
                    size = info.endOfFile,
                    modified = info.lastWriteTime.toEpochMillis()
                )
            )
        }
        return out.sortedWith(compareByDescending<RemoteEntry> { it.isDir }.thenBy { it.name.lowercase() })
    }

    override fun stat(path: String): RemoteEntry? = runCatching {
        if (path.isBlank()) RemoteEntry(shareName, "", true) else {
            val info = share().getFileInformation(absPath(path))
            val std = info.standardInformation
            RemoteEntry(
                name = path.substringAfterLast('/'),
                path = path,
                isDir = std.isDirectory,
                size = std.endOfFile,
                modified = info.basicInformation.lastWriteTime.toEpochMillis()
            )
        }
    }.getOrNull()

    private fun openFileHandle(path: String): File = share().openFile(
        absPath(path),
        EnumSet.of(AccessMask.GENERIC_READ),
        null,
        SMB2ShareAccess.ALL,
        SMB2CreateDisposition.FILE_OPEN,
        null
    )

    override fun openReader(path: String): RemoteReader {
        val f = openFileHandle(path)
        openFiles.add(f)
        val len = runCatching { f.fileInformation.standardInformation.endOfFile }.getOrDefault(-1L)
        return object : RemoteReader {
            override val length: Long = len

            @Synchronized
            override fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int {
                if (len <= 0) return 0
                val toRead = minOf(len, 65536)
                val n = try {
                    f.read(buf, pos, off, toRead)
                } catch (e: SMBApiException) {
                    if (e.status == NtStatus.STATUS_END_OF_FILE) -1 else throw IOException(e.message, e)
                } catch (e: Exception) {
                    throw IOException(e.message, e)
                }
                return if (n <= 0) -1 else n
            }

            @Synchronized
            override fun close() {
                openFiles.remove(f)
                runCatching { f.close() }
            }
        }
    }

    override fun isRoot(path: String): Boolean = path.isBlank()
    override fun parentOf(path: String): String = parentPathOf(path) ?: ""
    override fun displayPath(path: String): String =
        "/$shareName" + if (path.isBlank()) "" else "/$path"

    @Synchronized
    private fun closeQuietly() {
        openFiles.forEach { runCatching { it.close() } }
        openFiles.clear()
        runCatching { disk?.close() }
        disk = null
        runCatching { session?.close() }
        runCatching { connection?.close() }
        runCatching { client?.close() }
        session = null
        connection = null
        client = null
    }

    @Synchronized
    override fun close() = closeQuietly()
}
