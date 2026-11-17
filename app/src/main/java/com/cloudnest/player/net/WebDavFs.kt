package com.cloudnest.player.net

import android.util.Xml
import com.cloudnest.player.data.ServerConfig
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

class WebDavFs(private val cfg: ServerConfig) : RemoteFs {

    private val base: String = buildBase(cfg)
    private val auth: String? = if (cfg.username.isNotBlank())
        Credentials.basic(cfg.username, cfg.password) else null

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .build()

    private val propfindBody = """<?xml version="1.0" encoding="utf-8" ?>
<d:propfind xmlns:d="DAV:">
  <d:prop>
    <d:displayname/>
    <d:resourcetype/>
    <d:getcontentlength/>
    <d:getcontenttype/>
    <d:getlastmodified/>
  </d:prop>
</d:propfind>"""

    private fun buildBase(cfg: ServerConfig): String {
        val raw = if (cfg.host.startsWith("http://") || cfg.host.startsWith("https://")) {
            cfg.host.trimEnd('/')
        } else {
            "http://${cfg.host}:${cfg.portOrDefault}"
        }
        val bp = cfg.basePath.trim('/')
        return if (bp.isEmpty()) raw else raw + "/" + bp.split('/').joinToString("/") { encode(it) }
    }

    private fun encode(s: String): String =
        URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun urlFor(path: String, dir: Boolean): String {
        val rel = path.trim('/')
        val sb = StringBuilder(base)
        if (rel.isNotEmpty()) {
            sb.append('/')
            sb.append(rel.split('/').joinToString("/") { encode(it) })
        }
        if (dir) sb.append('/')
        return sb.toString()
    }

    private fun newRequest(url: String, method: String, body: String? = null): Request {
        val b = Request.Builder().url(url)
        auth?.let { b.header("Authorization", it) }
        if (method == "PROPFIND") {
            b.header("Depth", "1")
            b.header("Content-Type", "application/xml; charset=utf-8")
            b.method("PROPFIND", (body ?: propfindBody).toRequestBody(null))
        } else {
            b.method(method, null)
        }
        return b.build()
    }

    private fun relFromHref(href: String): String {
        var h = href.trim()
        if (h.startsWith("http://") || h.startsWith("https://")) {
            h = h.substringAfter("://").substringAfter('/', "")
            h = "/" + h
        }
        h = URLDecoder.decode(h, "UTF-8")
        val basePath = base.substringAfter("://").substringAfter('/', "").trim('/')
        if (basePath.isNotEmpty()) {
            if (h.startsWith("/$basePath")) h = h.removePrefix("/$basePath")
            else if (h == basePath) h = ""
        }
        return h.trim('/')
    }

    override fun list(path: String): List<RemoteEntry> {
        val url = urlFor(path, dir = true)
        val resp = client.newCall(newRequest(url, "PROPFIND")).execute()
        resp.use { r ->
            if (!r.isSuccessful) throw IOException("WebDAV 拒绝访问：HTTP ${r.code}")
            val body = r.body?.string() ?: return emptyList()
            return parseMultistatus(body, path)
        }
    }

    private fun parseMultistatus(xml: String, parent: String): List<RemoteEntry> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(xml.reader())

        val out = ArrayList<RemoteEntry>()
        var href: String? = null
        var isDir = false
        var len = 0L
        var modified = 0L
        var inProp = false

        var ev = parser.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                when (parser.name.substringAfter(':')) {
                    "response" -> {
                        href = null; isDir = false; len = 0L; modified = 0L
                    }
                    "prop" -> inProp = true
                    "collection" -> if (inProp) isDir = true
                    "getcontentlength" -> {
                        len = parser.nextText().trim().toLongOrNull() ?: 0L
                    }
                    "getlastmodified" -> {
                        modified = parseDate(parser.nextText().trim())
                    }
                    "href" -> {
                        href = parser.nextText().trim()
                    }
                }
            } else if (ev == XmlPullParser.END_TAG) {
                when (parser.name.substringAfter(':')) {
                    "prop" -> inProp = false
                    "response" -> {
                        val h = href
                        if (h != null) {
                            val rel = relFromHref(h)
                            val self = rel == parent.trim('/')
                            if (!self && rel.isNotEmpty()) {
                                val name = rel.substringAfterLast('/')
                                if (name.isNotEmpty()) {
                                    out.add(RemoteEntry(name, rel, isDir, len, modified))
                                }
                            }
                        }
                    }
                }
            }
            ev = parser.next()
        }
        return out.sortedWith(compareByDescending<RemoteEntry> { it.isDir }.thenBy { it.name.lowercase() })
    }

    private fun parseDate(s: String): Long = runCatching {
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).parse(s)?.time ?: 0L
    }.getOrDefault(0L)

    override fun stat(path: String): RemoteEntry? = runCatching {
        val rel = path.trim('/')
        if (rel.isEmpty()) return RemoteEntry("根目录", "/", true)
        val url = urlFor(parentOf(path) ?: "/", dir = false)
        val resp = client.newCall(newRequest(url, "PROPFIND")).execute()
        resp.use { r ->
            if (!r.isSuccessful) return null
            val list = parseMultistatus(r.body?.string() ?: "", parentOf(path) ?: "/")
            list.firstOrNull { it.name == rel.substringAfterLast('/') }
        }
    }.getOrNull()

    override fun openReader(path: String): RemoteReader {
        val url = urlFor(path, dir = false)
        val headReq = Request.Builder().url(url).head().apply { auth?.let { header("Authorization", it) } }.build()
        val length = runCatching {
            client.newCall(headReq).execute().use { it.header("Content-Length")?.toLongOrNull() ?: -1L }
        }.getOrDefault(-1L)
        val self = this
        return StreamReader(length) { offset ->
            self.rangeStream(url, offset) ?: throw IOException("WebDAV 读取失败")
        }
    }

    /** 打开带 Range 的响应流 */
    fun rangeStream(url: String, offset: Long): java.io.InputStream? {
        val b = Request.Builder().url(url)
        auth?.let { b.header("Authorization", it) }
        if (offset > 0) b.header("Range", "bytes=$offset-")
        val resp = client.newCall(b.build()).execute()
        if (!resp.isSuccessful) {
            resp.close()
            return null
        }
        val body = resp.body ?: run { resp.close(); return null }
        val code = resp.code
        val input = body.byteStream()
        return object : java.io.InputStream() {
            override fun read(): Int = input.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int = input.read(b, off, len)
            override fun close() {
                runCatching { input.close() }
                runCatching { resp.close() }
            }

            init {
                if (code == 200 && offset > 0) {
                    var skipped = 0L
                    while (skipped < offset) {
                        val n = input.skip(offset - skipped)
                        if (n <= 0) break
                        skipped += n
                    }
                }
            }
        }
    }

    override fun isRoot(path: String): Boolean = path.trim('/').isEmpty()
    override fun parentOf(path: String): String = parentPathOf(path) ?: "/"
    override fun displayPath(path: String): String = "/" + path.trim('/')

    override fun close() {
        runCatching { client.dispatcher.executorService.shutdown() }
        runCatching { client.connectionPool.evictAll() }
    }
}
