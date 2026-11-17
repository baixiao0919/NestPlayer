package com.cloudnest.player.net

import android.util.Log
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * 本地 HTTP 流服务（127.0.0.1）。
 *
 * 存在的意义：ExoPlayer 只认 http/file 之类标准协议，而 NAS 可能是 SMB/NFS/FTP/DLNA。
 * 这里把上述协议统一包成一个支持 Range 的本地 HTTP 源，播放器、字幕、封面都走这一个入口。
 */
object StreamProxy {

    private const val TAG = "StreamProxy"
    private const val PREFERRED_PORT = 18990

    private data class Session(val serverId: String, val path: String, val name: String, val createdAt: Long)

    private val sessions = ConcurrentHashMap<String, Session>()
    /** 同一个 (服务器,路径) 复用同一个 token，避免每次重组都生成新 URL 让图片缓存失效 */
    private val stableTokens = ConcurrentHashMap<String, String>()
    /** 已经探明的文件长度缓存：key = 服务器|路径 */
    private val lengths = ConcurrentHashMap<String, Long>()
    private val pool = Executors.newCachedThreadPool()
    private var serverSocket: ServerSocket? = null

    var port: Int = PREFERRED_PORT
        private set

    @Synchronized
    fun start() {
        if (serverSocket != null) return
        val ss = try {
            ServerSocket(PREFERRED_PORT, 64, InetAddress.getByName("127.0.0.1"))
        } catch (e: IOException) {
            ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
        }
        port = ss.localPort
        serverSocket = ss
        Thread {
            while (!ss.isClosed) {
                val socket = try {
                    ss.accept()
                } catch (e: IOException) {
                    break
                }
                pool.execute { runCatching { handle(socket) } }
            }
        }.apply { name = "nestproxy-accept"; isDaemon = true }.start()
        Log.i(TAG, "proxy started on $port")
    }

    fun base(): String = "http://127.0.0.1:$port"

    fun url(serverId: String, path: String, name: String): String {
        start()
        cleanup()
        val key = "$serverId|$path|$name"
        val existing = stableTokens[key]
        if (existing != null && sessions.containsKey(existing)) {
            return "${base()}/s/$existing/${encodeName(name)}"
        }
        val token = UUID.randomUUID().toString().replace("-", "").take(16)
        sessions[token] = Session(serverId, path, name, System.currentTimeMillis())
        stableTokens[key] = token
        return "${base()}/s/$token/${encodeName(name)}"
    }

    /** 供播放器在准备阶段探测 TS 时长/大小用 */
    fun sessionOf(url: String): Triple<String, String, String>? {
        val idx = url.indexOf("/s/")
        if (idx < 0) return null
        val token = url.substring(idx + 3).substringBefore('/')
        val s = sessions[token] ?: return null
        return Triple(s.serverId, s.path, s.name)
    }

    /** 复用已探明的长度（探测 TS 时也走这一条） */
    fun knownLength(serverId: String, path: String): Long = lengths[serverId + "|" + path] ?: 0L
    fun rememberLength(serverId: String, path: String, len: Long) { if (len > 0) lengths[serverId + "|" + path] = len }

    private fun encodeName(name: String): String =
        URLEncoder.encode(name.ifBlank { "media" }, "UTF-8").replace("+", "%20")

    private fun cleanup() {
        val now = System.currentTimeMillis()
        val removed = ArrayList<String>()
        sessions.entries.forEach { e ->
            if (now - e.value.createdAt > 3 * 60 * 60 * 1000L) removed.add(e.key)
        }
        removed.forEach { sessions.remove(it) }
        if (removed.isNotEmpty()) {
            stableTokens.entries.removeAll { it.value in removed }
        }
    }

    private fun handle(socket: Socket) {
        socket.use { s ->
            s.soTimeout = 30000
            val input = s.getInputStream()
            val out = BufferedOutputStream(s.getOutputStream(), 16384)
            val first = readLine(input) ?: return
            val parts = first.split(' ')
            if (parts.size < 3) return
            val method = parts[0]
            val rawPath = parts[1]
            val headers = HashMap<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
            }
            if (method != "GET" && method != "HEAD") {
                respondError(out, 405, "Method Not Allowed")
                return
            }
            if (rawPath.startsWith("/ping")) {
                respond(out, 200, "OK", mapOf("Content-Type" to "text/plain"), "pong".toByteArray(), 0, "pong".length.toLong(), method == "HEAD")
                return
            }
            val segments = rawPath.trimStart('/').split('/')
            if (segments.size < 2 || segments[0] != "s") {
                respondError(out, 404, "Not Found")
                return
            }
            val session = sessions[segments[1]]
            if (session == null) {
                respondError(out, 404, "Session expired")
                return
            }
            val name = URLDecoder.decode(segments.getOrElse(2) { session.name }, "UTF-8")

            val fs = FsManager.get(session.serverId)
            if (MediaFiles.isSubtitle(name)) {
                serveSubtitle(fs, session, name, method == "HEAD", out)
                return
            }
            serveBinary(fs, session, name, headers, method == "HEAD", out)
        }
    }

    private fun serveSubtitle(fs: RemoteFs, session: Session, name: String, head: Boolean, out: OutputStream) {
        val reader = fs.openReader(session.path)
        reader.use { r ->
            val len = r.length
            val bytes = if (len in 1..20_000_000L) {
                readAll(r, len.toInt())
            } else {
                readAllUnknown(r)
            }
            val text = decodeText(bytes)
            val encoded = text.toByteArray(Charsets.UTF_8)
            respond(
                out, 200, "OK",
                mapOf("Content-Type" to "text/plain; charset=utf-8", "Accept-Ranges" to "bytes"),
                encoded, 0, encoded.size.toLong(), head
            )
        }
    }

    private fun serveBinary(
        fs: RemoteFs, session: Session, name: String,
        headers: Map<String, String>, head: Boolean, out: OutputStream
    ) {
        val reader = fs.openReader(session.path)
        try {
            var total = reader.length
            if (total <= 0) total = resolveLength(fs, session, name, reader)
            val rangeHeader = headers["range"]
            var start = 0L
            var end = if (total > 0) total - 1 else -1L
            var partial = false
            if (rangeHeader != null && total > 0) {
                val spec = rangeHeader.removePrefix("bytes=").trim()
                val parts = spec.split('-')
                if (parts.isNotEmpty()) {
                    if (parts[0].isNotBlank()) {
                        start = parts[0].toLongOrNull() ?: 0L
                        if (parts.size > 1 && parts[1].isNotBlank()) {
                            end = parts[1].toLongOrNull() ?: end
                        }
                    } else if (parts.size > 1 && parts[1].isNotBlank()) {
                        val suffix = parts[1].toLongOrNull() ?: 0L
                        start = (total - suffix).coerceAtLeast(0)
                    }
                    if (start > total - 1) {
                        respond(
                            out, 416, "Range Not Satisfiable",
                            mapOf("Content-Range" to "bytes */$total", "Content-Length" to "0"),
                            ByteArray(0), 0, 0, head
                        )
                        return
                    }
                    if (end >= total) end = total - 1
                    partial = true
                }
            }
            val length = if (total > 0) (end - start + 1) else -1L
            val status = if (partial) "206 Partial Content" else "OK"
            val hdrs = mutableMapOf(
                "Content-Type" to MediaFiles.mimeOf(name),
                "Accept-Ranges" to "bytes",
                "Cache-Control" to "no-cache"
            )
            if (total > 0) {
                hdrs["Content-Length"] = length.toString()
                if (partial) hdrs["Content-Range"] = "bytes $start-$end/$total"
            }
            writeHeaders(out, if (partial) 206 else 200, status, hdrs)
            if (head) {
                out.flush()
                return
            }
            var pos = start
            val buf = ByteArray(128 * 1024)
            while (true) {
                if (total > 0 && pos > end) break
                val want = if (total > 0) minOf(buf.size.toLong(), (end - pos + 1)).toInt() else buf.size
                if (want <= 0) break
                val n = reader.read(pos, buf, 0, want)
                if (n <= 0) break
                out.write(buf, 0, n)
                pos += n
            }
            out.flush()
        } catch (e: IOException) {
            runCatching { respondError(out, 500, e.message ?: "IO Error") }
        } finally {
            runCatching { reader.close() }
        }
    }

    /**
     * 长度兜底链：reader.length → stat() → 列父目录比对大小。
     * ExoPlayer 必须知道文件总长才能按字节 seek；MP4 把 moov 放在文件尾时，
     * 没有长度连时长都解析不出来（表现就是"总时长 0:00、不能快进"）。
     */
    private fun resolveLength(fs: RemoteFs, session: Session, name: String, reader: RemoteReader): Long {
        val key = session.serverId + "|" + session.path
        lengths[key]?.let { if (it > 0) return it }
        var len = reader.length
        if (len <= 0) len = runCatching { fs.stat(session.path)?.size ?: 0L }.getOrDefault(0L)
        if (len <= 0) {
            val parent = session.path.substringBeforeLast('/', "")
            len = runCatching {
                fs.list(parent).firstOrNull { it.path == session.path || it.name == name }?.size ?: 0L
            }.getOrDefault(0L)
        }
        if (len > 0) lengths[key] = len
        return len
    }

    private fun readAll(r: RemoteReader, size: Int): ByteArray {
        val buf = ByteArray(size)
        var pos = 0
        while (pos < size) {
            val n = r.read(pos.toLong(), buf, pos, size - pos)
            if (n <= 0) break
            pos += n
        }
        return if (pos == size) buf else buf.copyOf(pos)
    }

    private fun readAllUnknown(r: RemoteReader, limit: Int = 8 * 1024 * 1024): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(4096)
        var pos = 0L
        while (out.size() < limit) {
            val n = r.read(pos, buf, 0, buf.size)
            if (n <= 0) break
            out.write(buf, 0, n)
            pos += n
        }
        return out.toByteArray()
    }

    /** 字幕多半是 UTF-8 或 GB18030，自动嗅探一次 */
    private fun decodeText(bytes: ByteArray): String {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            runCatching { String(bytes, Charset.forName("GB18030")) }.getOrDefault(String(bytes, Charsets.ISO_8859_1))
        }
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        var b = input.read()
        if (b == -1) return null
        while (b != -1 && b != '\n'.code) {
            if (b != '\r'.code) sb.append(b.toChar())
            b = input.read()
        }
        return sb.toString()
    }

    private fun respond(
        out: OutputStream, code: Int, status: String, headers: Map<String, String>,
        body: ByteArray, bodyStart: Int, bodyLen: Long, head: Boolean
    ) {
        writeHeaders(out, code, status, headers)
        if (!head) {
            if (bodyLen > 0) out.write(body, bodyStart, bodyLen.toInt())
        }
        out.flush()
    }

    private fun respondError(out: OutputStream, code: Int, msg: String) {
        val body = msg.toByteArray()
        writeHeaders(
            out, code, "Error",
            mapOf("Content-Type" to "text/plain; charset=utf-8", "Content-Length" to body.size.toString())
        )
        runCatching { out.write(body) }
        runCatching { out.flush() }
    }

    private fun writeHeaders(out: OutputStream, code: Int, status: String, headers: Map<String, String>) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 $code $status\r\n")
        sb.append("Connection: close\r\n")
        headers.forEach { (k, v) -> sb.append("$k: $v\r\n") }
        sb.append("\r\n")
        out.write(sb.toString().toByteArray())
    }
}
