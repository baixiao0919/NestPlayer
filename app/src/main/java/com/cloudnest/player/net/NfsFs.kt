package com.cloudnest.player.net

import com.cloudnest.player.data.ServerConfig
import com.emc.ecs.nfsclient.nfs.io.Nfs3File
import com.emc.ecs.nfsclient.nfs.io.NfsFileInputStream
import com.emc.ecs.nfsclient.nfs.nfs3.Nfs3
import java.io.IOException
import java.io.InputStream

/**
 * NFS v3 客户端（纯 Java 实现，无需 root / 无需内核 mount）。
 * basePath 填 NFS 导出路径，例如 /volume1/video 或 /data
 */
class NfsFs(private val cfg: ServerConfig) : RemoteFs {

    private val exportPath: String = cfg.basePath.ifBlank { "/" }.let {
        if (it.startsWith("/")) it else "/$it"
    }

    private val nfs: Nfs3 = Nfs3("${cfg.host}:$exportPath", 0, 0, 3)
    private val cache = HashMap<String, Nfs3File>()

    @Synchronized
    private fun file(path: String): Nfs3File {
        val key = if (path.isEmpty()) "/" else path
        cache[key]?.let { return it }
        val f = Nfs3File(nfs, key)
        cache[key] = f
        return f
    }

    override fun list(path: String): List<RemoteEntry> {
        val dir = file(if (path.isBlank()) "/" else path)
        val children: List<Nfs3File> = dir.listFiles() ?: return emptyList()
        val parent = if (path.isBlank()) "/" else path.trimEnd('/')
        val out = ArrayList<RemoteEntry>()
        for (c in children) {
            val name = c.name ?: continue
            if (name.isEmpty() || name == "." || name == "..") continue
            val isDir = runCatching { c.isDirectory }.getOrDefault(false)
            out.add(
                RemoteEntry(
                    name = name,
                    path = joinPath(parent, name),
                    isDir = isDir,
                    size = if (isDir) 0L else runCatching { c.lengthEx() }.getOrDefault(0L),
                    modified = runCatching { c.lastModified() }.getOrDefault(0L)
                )
            )
            cache[joinPath(parent, name)] = c
        }
        return out.sortedWith(compareByDescending<RemoteEntry> { it.isDir }.thenBy { it.name.lowercase() })
    }

    override fun stat(path: String): RemoteEntry? {
        if (path.isBlank() || path == "/") return RemoteEntry("导出目录", "/", true)
        return runCatching {
            val f = file(path)
            val isDir = f.isDirectory
            RemoteEntry(
                name = f.name ?: path.substringAfterLast('/'),
                path = path,
                isDir = isDir,
                size = if (isDir) 0L else f.lengthEx(),
                modified = f.lastModified()
            )
        }.getOrNull()
    }

    override fun openReader(path: String): RemoteReader {
        val f = file(path)
        val len = runCatching { f.lengthEx() }.getOrElse { -1L }
        return StreamReader(len) { offset ->
            openAt(f, offset)
        }
    }

    private fun openAt(f: Nfs3File, offset: Long): InputStream {
        val ins = NfsFileInputStream(f, offset, 131072)
        return ins as InputStream
    }

    override fun isRoot(path: String): Boolean = path.isBlank() || path == "/"
    override fun parentOf(path: String): String = parentPathOf(path) ?: "/"
    override fun displayPath(path: String): String = if (path.isBlank()) "/" else path

    override fun close() {
        synchronized(cache) { cache.clear() }
    }
}
