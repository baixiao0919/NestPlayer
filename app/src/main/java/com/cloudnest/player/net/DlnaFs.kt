package com.cloudnest.player.net

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Request
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

object HttpStreams {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun contentLength(url: String): Long {
        val head = Request.Builder().url(url).head().build()
        runCatching {
            client.newCall(head).execute().use { r ->
                r.header("Content-Length")?.toLongOrNull()?.let { if (it > 0) return it }
            }
        }
        runCatching {
            val b = Request.Builder().url(url).header("Range", "bytes=0-0").build()
            client.newCall(b).execute().use { r ->
                val cr = r.header("Content-Range")
                if (cr != null && cr.contains('/')) cr.substringAfterLast('/').trim().toLongOrNull() ?: -1L
                else r.header("Content-Length")?.toLongOrNull() ?: -1L
            }
        }
        return -1L
    }

    fun stream(url: String, offset: Long): InputStream? {
        val b = Request.Builder().url(url)
        if (offset > 0) b.header("Range", "bytes=$offset-")
        val resp = client.newCall(b.build()).execute()
        if (!resp.isSuccessful) {
            resp.close()
            return null
        }
        val body = resp.body ?: run { resp.close(); return null }
        val input = body.byteStream()
        val code = resp.code
        return object : InputStream() {
            override fun read(): Int = input.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int = input.read(b, off, len)
            override fun close() {
                runCatching { input.close() }
                runCatching { resp.close() }
            }
        }.let { ins ->
            if (code == 200 && offset > 0) {
                var skipped = 0L
                while (skipped < offset) {
                    val n = ins.skip(offset - skipped)
                    if (n <= 0) break
                    skipped += n
                }
            }
            ins
        }
    }
}

class DlnaFs(private val cfg: com.cloudnest.player.data.ServerConfig) : RemoteFs {

    private val controlUrl: String = cfg.controlUrl.ifBlank { throw IOException("DLNA 设备未完成发现，请重新扫描") }
    private val parents = HashMap<String, String>()

    private fun soap(action: String, body: String): String {
        val req = Request.Builder()
            .url(controlUrl)
            .header("SOAPAction", "\"urn:schemas-upnp-org:service:ContentDirectory:1#$action\"")
            .header("Content-Type", "text/xml; charset=\"utf-8\"")
            .post(body.toRequestBody())
            .build()
        return HttpStreams.client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("DLNA 浏览失败：HTTP ${r.code}")
            r.body?.string() ?: ""
        }
    }

    private fun browseBody(objectId: String): String = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <u:Browse xmlns:u="urn:schemas-upnp-org:service:ContentDirectory:1">
      <ObjectID>${objectId.x()}</ObjectID>
      <BrowseFlag>BrowseDirectChildren</BrowseFlag>
      <Filter>*</Filter>
      <StartingIndex>0</StartingIndex>
      <RequestedCount>0</RequestedCount>
      <SortCriteria></SortCriteria>
    </u:Browse>
  </s:Body>
</s:Envelope>"""

    private fun String.x(): String = replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    override fun list(path: String): List<RemoteEntry> {
        val objectId = when {
            path.isBlank() || path == "/" -> "0"
            path.startsWith("cid:") -> path.removePrefix("cid:")
            else -> return emptyList()
        }
        val xml = soap("Browse", browseBody(objectId))
        val didlStart = xml.indexOf("<DIDL-Lite")
        val xmlPart = if (didlStart >= 0) xml.substring(didlStart) else xml
        val entries = ArrayList<RemoteEntry>()

        val parser = android.util.Xml.newPullParser()
        parser.setFeature(org.xmlpull.v1.XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(xmlPart.reader())

        var kind: String? = null
        var id = ""
        var title = ""
        var resUrl: String? = null
        var resSize = 0L
        var art: String? = null
        var modified = 0L
        var depth = 0

        var ev = parser.eventType
        while (ev != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            when (ev) {
                org.xmlpull.v1.XmlPullParser.START_TAG -> {
                    val name = parser.name.substringAfter(':')
                    depth++
                    when (name) {
                        "container", "item" -> {
                            kind = name
                            id = parser.getAttributeValue(null, "id") ?: ""
                            title = ""; resUrl = null; resSize = 0L; art = null
                        }
                        "title" -> title = parser.nextText()
                        "res" -> {
                            var u = parser.nextText().trim()
                            if (u.startsWith("/")) {
                                val origin = runCatching {
                                    val uri = java.net.URI(controlUrl)
                                    "${uri.scheme}://${uri.host}" + if (uri.port > 0) ":${uri.port}" else ""
                                }.getOrDefault("")
                                u = origin + u
                            }
                            resUrl = u
                            resSize = parser.getAttributeValue(null, "size")?.toLongOrNull() ?: 0L
                        }
                        "albumArtURI" -> art = parser.nextText().trim()
                        "date" -> modified = parseIso(parser.nextText())
                    }
                }
                org.xmlpull.v1.XmlPullParser.END_TAG -> {
                    val name = parser.name.substringAfter(':')
                    depth--
                    when (name) {
                        "container" -> {
                            if (id.isNotEmpty()) {
                                val p = "cid:$id"
                                entries.add(RemoteEntry(title, p, true))
                                parents[p] = if (path.isBlank()) "/" else path
                            }
                        }
                        "item" -> {
                            val url = resUrl
                            if (url != null) {
                                val p = "res:$url"
                                entries.add(
                                    RemoteEntry(
                                        name = title.ifBlank { url.substringAfterLast('/') },
                                        path = p,
                                        isDir = false,
                                        size = resSize,
                                        modified = modified,
                                        objectId = id,
                                        albumArt = art
                                    )
                                )
                                parents[p] = if (path.isBlank()) "/" else path
                            }
                        }
                    }
                    if (name == "container" || name == "item") kind = null
                }
            }
            ev = parser.next()
        }
        return entries
    }

    private fun parseIso(s: String): Long = runCatching {
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
            .parse(s.take(19))?.time ?: 0L
    }.getOrDefault(0L)

    override fun openReader(path: String): RemoteReader {
        if (!path.startsWith("res:")) throw IOException("不是可读取的媒体文件")
        val url = path.removePrefix("res:")
        val len = HttpStreams.contentLength(url)
        return StreamReader(len) { offset -> HttpStreams.stream(url, offset) ?: throw IOException("DLNA 读取失败") }
    }

    override fun stat(path: String): RemoteEntry? {
        if (path.startsWith("res:")) {
            return RemoteEntry(path.substringAfterLast('/'), path, false, HttpStreams.contentLength(path.removePrefix("res:")))
        }
        return RemoteEntry("目录", path, true)
    }

    override fun isRoot(path: String): Boolean = path.isBlank() || path == "/" || path == "cid:0"
    override fun parentOf(path: String): String = parents[path] ?: "/"
    override fun displayPath(path: String): String = path

    override fun close() = Unit
}

private fun String.toRequestBody(): okhttp3.RequestBody =
    this.toRequestBody("text/xml; charset=utf-8".toMediaType())
