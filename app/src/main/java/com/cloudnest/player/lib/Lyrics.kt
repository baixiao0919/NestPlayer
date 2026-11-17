package com.cloudnest.player.lib

import com.cloudnest.player.net.RemoteFs
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * 歌词：支持标准 LRC（[mm:ss.xx] 时间轴），同目录同名 .lrc 与 .txt 都认，
 * 编码先按 UTF-8 严格解，失败回退 GB18030（很多国内下载的歌词是 GBK）。
 */
object Lyrics {

    data class Line(val timeMs: Long, val text: String)

    private val timeTag = Regex("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")

    fun parse(text: String): List<Line> {
        val lines = ArrayList<Line>()
        var offset = 0L
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEach
            timeTag.find(line)?.let { first ->
                // 元信息标签
                return@forEach
            }
            if (line.startsWith("[") && line.contains(":")) {
                val meta = line.substringAfter('[').substringBefore(']')
                val key = meta.substringBefore(':').lowercase()
                val value = meta.substringAfter(':', "")
                if (key == "offset") offset = value.trim().toLongOrNull() ?: 0L
            }
            val stamps = timeTag.findAll(line).toList()
            if (stamps.isEmpty()) return@forEach
            val content = timeTag.replace(line, "").trim()
            stamps.forEach { m ->
                val min = m.groupValues[1].toLongOrNull() ?: return@forEach
                val sec = m.groupValues[2].toLongOrNull() ?: return@forEach
                val fracRaw = m.groupValues[3]
                val frac = when (fracRaw.length) {
                    0 -> 0L
                    1 -> fracRaw.toLong() * 100
                    2 -> fracRaw.toLong() * 10
                    else -> fracRaw.take(3).toLong()
                }
                val ms = min * 60_000 + sec * 1000 + frac
                lines.add(Line(ms, content))
            }
        }
        val sorted = lines.sortedBy { it.timeMs }
        if (offset != 0L) return sorted.map { it.copy(timeMs = (it.timeMs + offset).coerceAtLeast(0)) }
        return sorted
    }

    /** 按当前播放位置取应该高亮的行下标，-1 表示还没到第一句 */
    fun indexAt(lines: List<Line>, positionMs: Long): Int {
        if (lines.isEmpty()) return -1
        var idx = -1
        for (i in lines.indices) {
            if (lines[i].timeMs <= positionMs) idx = i else break
        }
        return idx
    }

    /** 读远端歌词文件，自动判断编码 */
    fun read(fs: RemoteFs, path: String, name: String): String? = runCatching {
        val reader = fs.openReader(path)
        reader.use {
            val len = minOf(it.length, 512 * 1024L).toInt().coerceAtLeast(1)
            val buf = ByteArray(len)
            var got = 0
            while (got < len) {
                val n = it.read(got.toLong(), buf, got, len - got)
                if (n <= 0) break
                got += n
            }
            if (got <= 0) null else decode(buf.copyOf(got), name)
        }
    }.getOrNull()

    private fun decode(bytes: ByteArray, name: String): String {
        // UTF-8 严格解码，遇到非法字节抛异常后再回退 GB18030
        runCatching {
            val decoder = Charset.forName("UTF-8").newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            return decoder.decode(ByteBuffer.wrap(bytes)).toString().trimStart('\uFEFF')
        }
        return runCatching { String(bytes, Charset.forName("GB18030")) }
            .getOrElse { String(bytes, Charsets.ISO_8859_1) }
    }

    fun formatMs(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val m = total / 60
        val s = total % 60
        return "%d:%02d".format(m, s)
    }
}
