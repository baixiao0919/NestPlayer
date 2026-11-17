package com.cloudnest.player.lib

import com.cloudnest.player.data.LibraryItem
import com.cloudnest.player.net.MediaFiles
import com.cloudnest.player.net.RemoteEntry
import com.cloudnest.player.net.RemoteFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 影库扫描：遍历 NAS 目录，收集视频并按文件夹里的 poster/folder.jpg 之类找封面。
 */
object LibraryScanner {

    data class Progress(val scannedDirs: Int, val foundVideos: Int, val currentFolder: String)

    private val posterNames = listOf(
        "poster.jpg", "poster.png", "folder.jpg", "folder.png", "cover.jpg", "cover.png",
        "movie.jpg", "movie.png", "show.jpg", "show.png", "海报.jpg", "海报.png", "封面.jpg", "封面.png"
    )
    private val backdropNames = listOf(
        "fanart.jpg", "fanart.png", "backdrop.jpg", "backdrop.png", "background.jpg", "背景.jpg"
    )
    private val skipDirs = listOf(
        "#recycle", "#snapshot", "@eadir", "system volume information", "lost+found", ".git",
        "node_modules", "thumbs", ".thumbnails", "recycle"
    )

    suspend fun scan(
        fs: RemoteFs,
        serverId: String,
        roots: List<String>,
        maxDepth: Int = 6,
        maxVideos: Int = 6000,
        onProgress: (Progress) -> Unit = {}
    ): List<LibraryItem> = withContext(Dispatchers.IO) {
        val items = ArrayList<LibraryItem>()
        val queue = ArrayDeque<Pair<String, Int>>()
        roots.forEach { queue.add(it to 0) }
        val visited = HashSet<String>()
        var dirs = 0

        while (queue.isNotEmpty()) {
            val (path, depth) = queue.removeFirst()
            if (!visited.add(path)) continue
            if (items.size >= maxVideos) break
            dirs++
            onProgress(Progress(dirs, items.size, path))

            val entries: List<RemoteEntry> = runCatching { fs.list(path) }.getOrElse {
                emptyList<RemoteEntry>()
            }
            if (entries.isEmpty()) continue

            val images = entries.filter { !it.isDir && MediaFiles.isImage(it.name) }
            val poster = pickByName(images, posterNames) ?: images.firstOrNull()?.takeIf {
                entries.count { e -> MediaFiles.isVideo(e.name) } == 1
            }
            val backdrop = pickByName(images, backdropNames)

            for (e in entries) {
                if (e.isDir) {
                    val low = e.name.lowercase()
                    if (low.startsWith(".") || skipDirs.any { low.contains(it) }) continue
                    if (depth + 1 <= maxDepth) queue.add(e.path to (depth + 1))
                } else if (MediaFiles.isVideo(e.name)) {
                    val parsed = Naming.parse(e.name)
                    val siblingPoster = pickVideoPoster(images, e.name)
                    items.add(
                        LibraryItem(
                            id = "$serverId|${e.path}",
                            serverId = serverId,
                            path = e.path,
                            name = e.name,
                            title = Naming.prettyTitle(parsed.title),
                            year = parsed.year,
                            season = parsed.season,
                            episode = parsed.episode,
                            seriesKey = if (parsed.episode != null) Naming.seriesKey(parsed.title, parsed.season) else null,
                            size = e.size,
                            modified = e.modified,
                            posterPath = siblingPoster ?: poster?.path,
                            backdropPath = backdrop?.path ?: poster?.path,
                            addedAt = System.currentTimeMillis()
                        )
                    )
                    if (items.size >= maxVideos) break
                }
            }
        }
        onProgress(Progress(dirs, items.size, ""))
        items
    }

    private fun pickByName(images: List<RemoteEntry>, names: List<String>): RemoteEntry? {
        for (n in names) {
            images.firstOrNull { it.name.equals(n, ignoreCase = true) }?.let { return it }
        }
        return null
    }

    private fun pickVideoPoster(images: List<RemoteEntry>, videoName: String): String? {
        val base = videoName.substringBeforeLast('.', videoName).lowercase()
        return images.firstOrNull { img ->
            val imgBase = img.name.substringBeforeLast('.', img.name).lowercase()
            imgBase == base || imgBase == "$base-poster" || imgBase == "${base}_poster"
        }?.path
    }

    /** 同目录同名字幕 */
    fun findSubtitles(fs: RemoteFs, dirPath: String, videoName: String): List<String> {
        val entries = runCatching { fs.list(dirPath) }.getOrElse { return emptyList() }
        val base = Naming.subtitleBaseName(videoName)
        return entries.filter { !it.isDir && MediaFiles.isSubtitle(it.name) }
            .filter { e ->
                val subBase = Naming.subtitleBaseName(e.name)
                subBase == base || subBase.startsWith("$base.") || subBase.startsWith("${base}_") ||
                    subBase.startsWith("${base}-") || base.startsWith(subBase)
            }
            .map { it.path }
    }
}
