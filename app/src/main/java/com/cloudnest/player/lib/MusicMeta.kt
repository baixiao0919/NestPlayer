package com.cloudnest.player.lib

import android.content.Context
import com.cloudnest.player.net.MediaFiles
import com.cloudnest.player.net.RemoteFs
import java.io.File
import java.security.MessageDigest

/**
 * 音频标签解析：ID3v2（mp3）/ Vorbis Comment + PICTURE（flac）。
 * 只读文件开头一段（默认 512KB），够覆盖绝大多数标签与内嵌封面，
 * 避免为了拿一首歌的信息把整个无损文件从 NAS 拖下来。
 */
object MusicMeta {

    data class Tags(
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val trackNo: Int? = null,
        val durationMs: Long = 0L,
        val cover: ByteArray? = null
    )

    private const val HEAD_LIMIT = 512 * 1024

    fun read(fs: RemoteFs, path: String, name: String, knownSize: Long = 0L): Tags {
        return runCatching {
            val reader = fs.openReader(path)
            reader.use {
                val size = if (knownSize > 0) knownSize else it.length
                val want = minOf(size, HEAD_LIMIT.toLong()).toInt()
                if (want <= 0) return Tags()
                val buf = ByteArray(want)
                var got = 0
                while (got < want) {
                    val n = it.read(got.toLong(), buf, got, want - got)
                    if (n <= 0) break
                    got += n
                }
                if (got <= 0) return Tags()
                val bytes = if (got == want) buf else buf.copyOf(got)
                when (MediaFiles.ext(name)) {
                    "mp3", "mp2", "wma", "aac" -> parseId3(bytes)
                    "flac" -> parseFlac(bytes)
                    else -> Tags()
                }
            }
        }.getOrDefault(Tags())
    }

    /** 内嵌封面落盘到 filesDir/covers，返回可直接给 Coil 用的 File */
    fun coverFile(context: Context, serverId: String, path: String, bytes: ByteArray): File {
        val dir = File(context.filesDir, "covers").apply { mkdirs() }
        val key = md5("$serverId|$path")
        val f = File(dir, "$key.img")
        if (!f.exists() || f.length() == 0L) runCatching { f.writeBytes(bytes) }
        return f
    }

    fun cachedCover(context: Context, serverId: String, path: String): File? {
        val f = File(File(context.filesDir, "covers"), md5("$serverId|$path") + ".img")
        return if (f.exists() && f.length() > 0) f else null
    }

    fun md5(s: String): String {
        val d = MessageDigest.getInstance("MD5").digest(s.toByteArray())
        return d.joinToString("") { "%02x".format(it) }
    }

    // ---------------- ID3v2 ----------------

    private fun parseId3(b: ByteArray): Tags {
        if (b.size < 10 || b[0] != 'I'.code.toByte() || b[1] != 'D'.code.toByte() || b[2] != '3'.code.toByte()) return Tags()
        val major = b[3].toInt() and 0xff
        if (major < 3 || major > 4) return Tags()
        val flags = b[5].toInt() and 0xff
        val tagSize = syncSafe(b, 6)
        var pos = 10
        // 扩展头
        if (flags and 0x40 != 0 && major == 4) {
            val ext = syncSafe(b, pos)
            pos += if (ext > 0) ext else 6
        } else if (flags and 0x40 != 0 && major == 3) {
            val ext = be32(b, pos)
            pos += 4 + ext
        }
        val end = minOf(b.size, 10 + tagSize)
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var trackNo: Int? = null
        var duration = 0L
        var cover: ByteArray? = null

        while (pos + 10 <= end) {
            val id = String(b, pos, 4, Charsets.ISO_8859_1)
            if (id[0] == '\u0000') break
            val frameSize = if (major == 4) syncSafe(b, pos + 4) else be32(b, pos + 4)
            val start = pos + 10
            if (frameSize <= 0 || start + frameSize > end) break
            when (id) {
                "TIT2" -> title = readText(b, start, frameSize)
                "TPE1" -> artist = readText(b, start, frameSize)
                "TALB" -> album = readText(b, start, frameSize)
                "TRCK" -> trackNo = readText(b, start, frameSize)?.substringBefore('/')?.trim()?.toIntOrNull()
                "TLEN" -> duration = (readText(b, start, frameSize)?.trim()?.toLongOrNull() ?: 0L) * 1000
                "APIC" -> cover = readApic(b, start, frameSize)
            }
            pos = start + frameSize
        }
        return Tags(title, artist, album, trackNo, duration, cover)
    }

    private fun readText(b: ByteArray, start: Int, size: Int): String? {
        if (size < 1) return null
        val enc = b[start].toInt() and 0xff
        val raw = b.copyOfRange(start + 1, minOf(start + size, b.size))
        val text = decodeText(raw, enc)?.trim()
        return text?.replace("\u0000", "")?.ifBlank { null }
    }

    private fun readApic(b: ByteArray, start: Int, size: Int): ByteArray? {
        var p = start
        if (p >= b.size) return null
        val enc = b[p].toInt() and 0xff
        p += 1
        while (p < b.size && b[p] != 0.toByte()) p++
        p++ // mime 结束
        p++ // 图片类型
        // 描述
        if (enc == 1 || enc == 2) {
            while (p + 1 < b.size && !(b[p] == 0.toByte() && b[p + 1] == 0.toByte())) p += 2
            p += 2
        } else {
            while (p < b.size && b[p] != 0.toByte()) p++
            p++
        }
        if (p + 1 >= b.size) return null
        // 跳过 UTF-16 的 BOM 情况
        val data = b.copyOfRange(p, minOf(start + size, b.size))
        return if (data.size > 64) data else null
    }

    // ---------------- FLAC ----------------

    private fun parseFlac(b: ByteArray): Tags {
        if (b.size < 8 || b[0] != 'f'.code.toByte() || b[1] != 'L'.code.toByte()) return Tags()
        var pos = 4
        var duration = 0L
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var cover: ByteArray? = null

        while (pos + 4 <= b.size) {
            val header = b[pos].toInt() and 0xff
            val last = header and 0x80 != 0
            val type = header and 0x7f
            val len = (b[pos + 1].toInt() and 0xff) * 65536 +
                (b[pos + 2].toInt() and 0xff) * 256 + (b[pos + 3].toInt() and 0xff)
            val start = pos + 4
            if (start + len > b.size) break
            when (type) {
                0 -> if (len >= 34) {
                    val sr = ((b[start + 10].toInt() and 0xff) shl 12) or
                        ((b[start + 11].toInt() and 0xff) shl 4) or
                        ((b[start + 12].toInt() and 0xff) shr 4)
                    var total = (b[start + 13].toLong() and 0x0f) shl 32
                    total = total or ((b[start + 14].toLong() and 0xff) shl 24)
                    total = total or ((b[start + 15].toLong() and 0xff) shl 16)
                    total = total or ((b[start + 16].toLong() and 0xff) shl 8)
                    total = total or (b[start + 17].toLong() and 0xff)
                    if (sr > 0 && total > 0) duration = total * 1000L / sr
                }
                4 -> {
                    var p = start
                    val vendorLen = le32(b, p); p += 4 + vendorLen
                    val count = if (p + 4 <= b.size) le32(b, p) else 0
                    p += 4
                    repeat(count) {
                        if (p + 4 > b.size) return@repeat
                        val n = le32(b, p); p += 4
                        if (p + n > b.size) return@repeat
                        val kv = String(b, p, n, Charsets.UTF_8)
                        p += n
                        val k = kv.substringBefore('=').uppercase()
                        val v = kv.substringAfter('=', "").ifBlank { null }
                        when (k) {
                            "TITLE" -> title = v
                            "ARTIST" -> artist = v
                            "ALBUM" -> album = v
                        }
                    }
                }
                6 -> {
                    var p = start + 4
                    val mimeLen = if (p + 4 <= b.size) be32(b, p) else 0; p += 4 + mimeLen
                    val descLen = if (p + 4 <= b.size) be32(b, p) else 0; p += 4 + descLen
                    p += 16
                    val dataLen = if (p + 4 <= b.size) be32(b, p) else 0; p += 4
                    if (dataLen > 64 && p + dataLen <= b.size) cover = b.copyOfRange(p, p + dataLen)
                }
            }
            if (last) break
            pos = start + len
        }
        return Tags(title, artist, album, null, duration, cover)
    }

    // ---------------- 小工具 ----------------

    private fun syncSafe(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0x7f) shl 21) or ((b[off + 1].toInt() and 0x7f) shl 14) or
            ((b[off + 2].toInt() and 0x7f) shl 7) or (b[off + 3].toInt() and 0x7f)

    private fun be32(b: ByteArray, off: Int): Int =
        if (off + 4 > b.size) 0
        else ((b[off].toInt() and 0xff) shl 24) or ((b[off + 1].toInt() and 0xff) shl 16) or
            ((b[off + 2].toInt() and 0xff) shl 8) or (b[off + 3].toInt() and 0xff)

    private fun le32(b: ByteArray, off: Int): Int =
        if (off + 4 > b.size) 0
        else (b[off].toInt() and 0xff) or ((b[off + 1].toInt() and 0xff) shl 8) or
            ((b[off + 2].toInt() and 0xff) shl 16) or ((b[off + 3].toInt() and 0xff) shl 24)

    private fun decodeText(raw: ByteArray, enc: Int): String? = runCatching {
        when (enc) {
            0 -> String(raw, Charsets.ISO_8859_1)
            1 -> if (raw.size >= 2 && raw[0] == 0xFF.toByte() && raw[1] == 0xFE.toByte())
                String(raw, 2, raw.size - 2, Charsets.UTF_16LE)
            else String(raw, Charsets.UTF_16BE)
            2 -> String(raw, Charsets.UTF_16BE)
            else -> String(raw, Charsets.UTF_8)
        }
    }.getOrNull()
}
