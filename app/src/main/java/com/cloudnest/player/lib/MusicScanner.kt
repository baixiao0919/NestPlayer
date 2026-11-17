package com.cloudnest.player.lib

import com.cloudnest.player.data.MusicTrack
import com.cloudnest.player.net.MediaFiles
import com.cloudnest.player.net.RemoteEntry
import com.cloudnest.player.net.RemoteFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 音乐库扫描：只认音频文件，按文件夹当「专辑」，顺带找同名歌词与专辑封面。
 * 标签（ID3 / Vorbis）在播放或查看详情时再读，避免扫描阶段把每个文件都打开一遍。
 */
object MusicScanner {

    data class Progress(val scannedDirs: Int, val foundTracks: Int, val currentFolder: String)

    private val coverNames = listOf(
        "cover.jpg", "cover.png", "folder.jpg", "folder.png", "album.jpg", "album.png",
        "front.jpg", "front.png", "封面.jpg", "封面.png", "folder.jpeg", "cover.jpeg"
    )
    private val skipDirs = listOf(
        "#recycle", "#snapshot", "@eadir", "system volume information", "lost+found", ".git",
        "node_modules", "thumbs", ".thumbnails", "recycle", "video", "影音"
    )
    private val trackPrefix = Regex("^([0-9]{1,3})[.、_\\- ]+")
    private val bracketNoise = Regex("^[(\\[【].*?[)\\]】]")
    private val artistSplit = Regex("\\s*[-–—]\\s*")
    private val bracketTags = Regex("[(\\[【][^)】]*?(?:flac|mp3|aac|320k|128k|hires|无损|高清)[^)】]*[)\\]】]", RegexOption.IGNORE_CASE)

    suspend fun scan(
        fs: RemoteFs,
        serverId: String,
        roots: List<String>,
        maxDepth: Int = 8,
        maxTracks: Int = 8000,
        onProgress: (Progress) -> Unit = {}
    ): List<MusicTrack> = withContext(Dispatchers.IO) {
        val tracks = ArrayList<MusicTrack>()
        val queue = ArrayDeque<Pair<String, Int>>()
        roots.forEach { queue.add(it to 0) }
        val visited = HashSet<String>()
        var dirs = 0

        while (queue.isNotEmpty()) {
            if (tracks.size >= maxTracks) break
            val (path, depth) = queue.removeFirst()
            if (!visited.add(path)) continue
            dirs++
            onProgress(Progress(dirs, tracks.size, path))

            val entries = runCatching { fs.list(path) }.getOrElse { emptyList<RemoteEntry>() }
            if (entries.isEmpty()) continue

            val audios = entries.filter { !it.isDir && MediaFiles.isAudio(it.name) }
            val lrcs = entries.filter { !it.isDir && MediaFiles.ext(it.name) in setOf("lrc", "txt") }
            val images = entries.filter { !it.isDir && MediaFiles.isImage(it.name) }
            val folderCover = pickByName(images, coverNames)?.path
                ?: if (audios.isNotEmpty()) images.firstOrNull()?.path else null

            // 专辑名：优先 folder.jpg 同级目录名；根目录下的散播放到「未归类」
            val folderName = path.substringAfterLast('/').ifBlank { "根目录" }
            val album = if (audios.isEmpty()) folderName else folderName

            audios.forEach { e ->
                val lrcPath = pickLrc(lrcs, e.name)
                val parsed = parseName(e.name)
                tracks.add(
                    MusicTrack(
                        id = "$serverId|${e.path}",
                        serverId = serverId,
                        path = e.path,
                        name = e.name,
                        title = parsed.first,
                        artist = parsed.second,
                        album = album,
                        trackNo = parsed.third,
                        durationMs = 0L,
                        coverPath = folderCover,
                        lrcPath = lrcPath,
                        size = e.size,
                        addedAt = System.currentTimeMillis()
                    )
                )
                if (tracks.size >= maxTracks) return@forEach
            }

            for (e in entries) {
                if (!e.isDir) continue
                val low = e.name.lowercase()
                if (low.startsWith(".") || skipDirs.any { low == it }) continue
                if (depth + 1 <= maxDepth) queue.add(e.path to (depth + 1))
            }
        }
        onProgress(Progress(dirs, tracks.size, ""))
        tracks
    }

    /** 返回 (标题, 艺术家, 曲号) */
    private fun parseName(fileName: String): Triple<String, String, Int> {
        var base = fileName.substringBeforeLast('.', fileName)
        var no = 0
        trackPrefix.find(base)?.let {
            no = it.groupValues[1].toIntOrNull() ?: 0
            base = base.removeRange(it.range)
        }
        base = bracketNoise.replace(base, "").trim()
        base = bracketTags.replace(base, "").trim()
        base = base.replace('_', ' ').trim()

        // "艺术家 - 歌名" 或 "歌名 - 艺术家"
        val parts = artistSplit.split(base)
        return when {
            parts.size >= 2 -> {
                val a = parts[0].trim()
                val b = parts.drop(1).joinToString(" - ").trim()
                if (a.length in 1..40) Triple(b.ifBlank { base }, a, no) else Triple(base, "", no)
            }
            else -> Triple(base.ifBlank { fileName }, "", no)
        }
    }

    private fun pickLrc(lrcs: List<RemoteEntry>, audioName: String): String? {
        val audioBase = audioName.substringBeforeLast('.', audioName).lowercase()
        return lrcs.firstOrNull {
            it.name.substringBeforeLast('.', it.name).lowercase() == audioBase
        }?.path
    }

    private fun pickByName(images: List<RemoteEntry>, names: List<String>): RemoteEntry? {
        for (n in names) {
            images.firstOrNull { it.name.equals(n, ignoreCase = true) }?.let { return it }
        }
        return null
    }
}
