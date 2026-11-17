package com.cloudnest.player.player

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.ts.TsExtractor
import com.cloudnest.player.data.Store
import com.cloudnest.player.net.FsManager
import com.cloudnest.player.net.MediaFiles
import com.cloudnest.player.net.StreamProxy
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * TS / M2TS 可拖动支持。
 *
 * MPEG-TS 容器里没有索引表，Media3 的 TsExtractor 只有在能从流里找到 PCR（或者能从文件尾读到时）
 * 才会给出时长；很多录制的 .ts 两者都没有，于是变成"Unseekable"，表现就是总时长 0:00、拖不动。
 *
 * 这里的做法（也是 VLC / MX 的做法）：自己读文件头和文件尾的 PES 时间戳，算出总时长，
 * 再按"时长 ↔ 字节位置"线性映射造一个 SeekMap 塞给 TsExtractor。
 * ExoPlayer 拿到 SeekMap 后就会用 Range 请求跳字节，ts 也就能拖了。
 */
@UnstableApi
object TsSeek {

    private val tsExt = setOf("ts", "m2ts", "mts", "tp", "trp", "m2v", "evo")

    private data class Info(val durationUs: Long, val size: Long)

    private val cache = ConcurrentHashMap<String, Info>()
    /** 探测失败也记一笔，避免反复探测拖慢准备阶段 */
    private val failed = ConcurrentHashMap<String, Boolean>()

    fun isTs(name: String): Boolean = MediaFiles.ext(name) in tsExt

    private val baseFactory = DefaultExtractorsFactory()

    fun factory(): ExtractorsFactory = object : ExtractorsFactory {
        override fun createExtractors(): Array<Extractor> = baseFactory.createExtractors()

        override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> {
            val plain = baseFactory.createExtractors(uri, responseHeaders)
            val name = uri.lastPathSegment ?: ""
            if (!isTs(name)) return plain
            if (!Store.data.value.settings.tsSeekEnabled) return plain
            return try {
                val info = infoFor(uri.toString())
                if (info == null) plain else arrayOf(SeekableTsExtractor(TsExtractor(), info.durationUs, info.size))
            } catch (t: Throwable) {
                plain
            }
        }
    }

    private fun infoFor(url: String): Info? {
        cache[url]?.let { return it }
        if (failed[url] == true) return null
        val info = runCatching { probe(url) }.getOrNull()
        // 合理性校验：单集视频 5 秒 ~ 24 小时，否则不接管（避免时间戳异常文件把 seek 轴拉坏）
        if (info != null && info.size > 0 &&
            info.durationUs in 5_000_000L..(24L * 3600 * 1_000_000L)
        ) {
            cache[url] = info
            return info
        }
        failed[url] = true
        return null
    }

    private fun probe(url: String): Info {
        val session = StreamProxy.sessionOf(url) ?: throw IOException("会话不存在")
        val (serverId, path, name) = session
        val fs = FsManager.get(serverId)
        val reader = fs.openReader(path)
        try {
            var size = reader.length
            if (size <= 0) size = StreamProxy.knownLength(serverId, path)
            if (size <= 0) size = runCatching { fs.stat(path)?.size ?: 0L }.getOrDefault(0L)
            if (size <= 0) throw IOException("拿不到文件大小")
            StreamProxy.rememberLength(serverId, path, size)

            val window = 768 * 1024
            val headLen = minOf(window.toLong(), size).toInt()
            val head = ByteArray(headLen)
            readFully(reader, 0, head, headLen)

            val tailLen = minOf(window.toLong(), size).toInt()
            val tail = ByteArray(tailLen)
            readFully(reader, size - tailLen, tail, tailLen)

            val first = firstPts(head, headLen) ?: throw IOException("头部没有时间戳")
            val last = lastPts(tail, tailLen) ?: throw IOException("尾部没有时间戳")
            var diff = last - first
            if (diff < 0) diff += (1L shl 33) // 33 位 PTS 回绕
            if (diff <= 0) throw IOException("时间戳无跨度")
            // PTS 是 90kHz 单位 → 微秒
            val durationUs = diff * 1_000_000L / 90_000L
            return Info(durationUs, size)
        } finally {
            runCatching { reader.close() }
        }
    }

    private fun readFully(reader: com.cloudnest.player.net.RemoteReader, pos: Long, buf: ByteArray, len: Int) {
        var got = 0
        while (got < len) {
            val n = reader.read(pos + got, buf, got, len - got)
            if (n <= 0) break
            got += n
        }
    }

    /** 取窗口里第一条 PES 的时间戳 */
    private fun firstPts(buf: ByteArray, len: Int): Long? {
        var i = 0
        while (i + 188 <= len) {
            val pts = ptsAt(buf, i)
            if (pts != null) return pts
            i++
        }
        return null
    }

    /** 取窗口里最后一条 PES 的时间戳 */
    private fun lastPts(buf: ByteArray, len: Int): Long? {
        var found: Long? = null
        var i = 0
        while (i + 188 <= len) {
            val pts = ptsAt(buf, i)
            if (pts != null) found = pts
            i++
        }
        return found
    }

    /** 如果 i 处正好是一个带 PTS 的 TS 包的包头，返回 PTS（90kHz） */
    private fun ptsAt(buf: ByteArray, i: Int): Long? {
        if (buf[i].toInt() and 0xff != 0x47) return null
        val b1 = buf[i + 1].toInt() and 0xff
        if (b1 and 0x40 == 0) return null // 不是 PES 起始
        val afc = (buf[i + 3].toInt() ushr 4) and 0x3
        if (afc and 0x1 == 0) return null // 没有载荷
        var p = i + 4
        if (afc and 0x2 != 0) {
            if (p >= buf.size) return null
            val afLen = buf[p].toInt() and 0xff
            p += 1 + afLen
        }
        if (p + 14 > buf.size) return null
        if (buf[p].toInt() and 0xff != 0x00 || buf[p + 1].toInt() and 0xff != 0x00 ||
            buf[p + 2].toInt() and 0xff != 0x01
        ) return null
        val flags = buf[p + 7].toInt() and 0xff
        if (flags and 0x80 == 0) return null // 没有 PTS/DTS
        val b = p + 9
        if (b + 5 > buf.size) return null
        val p0 = buf[b].toLong() and 0xff
        val p1 = buf[b + 1].toLong() and 0xff
        val p2 = buf[b + 2].toLong() and 0xff
        val p3 = buf[b + 3].toLong() and 0xff
        val p4 = buf[b + 4].toLong() and 0xff
        return ((p0 and 0x0E) shl 29) or ((p1 and 0xFF) shl 22) or ((p2 and 0xFE) shl 14) or
            ((p3 and 0xFF) shl 7) or ((p4 and 0xFE) shr 1)
    }
}

/** 包一层 TsExtractor，把它给出的 Unseekable 换成我们按字节线性映射的 SeekMap */
@UnstableApi
class SeekableTsExtractor(
    private val delegate: TsExtractor,
    private val totalUs: Long,
    private val totalBytes: Long
) : Extractor {

    private var emitted = false

    // 注意：这里绝不能直接引用名为 durationUs 的属性——SeekMap.getDurationUs() 会在匿名对象里
    // 生成同名合成属性，导致 getDurationUs 自己调自己（StackOverflowError）。
    private val linearMap = object : SeekMap {
        override fun isSeekable(): Boolean = true

        override fun getDurationUs(): Long = totalUs

        override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
            val clamped = timeUs.coerceIn(0L, totalUs)
            val raw = (clamped.toDouble() / totalUs.toDouble() * totalBytes.toDouble()).toLong()
            // 对齐到 TS 包边界，方便解析器快速同步
            val pos = (raw / 188L) * 188L
            val safePos = pos.coerceIn(0L, (totalBytes - 1L).coerceAtLeast(0L))
            return SeekMap.SeekPoints(SeekPoint(clamped, safePos))
        }
    }

    override fun sniff(input: ExtractorInput): Boolean = try {
        delegate.sniff(input)
    } catch (e: Exception) {
        throw IOException(e)
    }

    override fun init(output: ExtractorOutput) {
        emitted = false
        delegate.init(object : ExtractorOutput {
            override fun track(id: Int, type: Int) = output.track(id, type)
            override fun endTracks() = output.endTracks()
            override fun seekMap(seekMap: SeekMap) {
                if (!emitted) {
                    emitted = true
                    output.seekMap(linearMap)
                }
            }
        })
        if (!emitted) {
            emitted = true
            output.seekMap(linearMap)
        }
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int = try {
        delegate.read(input, seekPosition)
    } catch (e: IOException) {
        throw e
    } catch (e: Exception) {
        // 解析异常绝不能冒到进程级：包成 IO 异常让播放器自己报错
        throw IOException(e)
    }

    override fun seek(position: Long, timeUs: Long) = try {
        delegate.seek(position, timeUs)
    } catch (e: Exception) {
        throw IOException(e)
    }

    override fun release() = delegate.release()
}
