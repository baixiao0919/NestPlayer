package com.cloudnest.player.net

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 自动更新检查：直接读 GitHub 的 latest release。
 *
 * 仓库每次发版都会建 release 并挂 NestPlayer-x.y.z.apk，
 * 所以这里拿到的 tag 和附件就是最新的可安装包。
 */
object UpdateChecker {

    private const val API = "https://api.github.com/repos/baixiao0919/NestPlayer/releases/latest"

    data class Info(
        val version: String,
        val notes: String,
        val apkUrl: String?,
        val pageUrl: String,
        val size: Long
    )

    /** 网络请求失败返回 null；调用方自行决定是否提示 */
    fun latest(): Info? {
        val conn = (URL(API).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "NestPlayer-Android-UpdateChecker")
        }
        return try {
            if (conn.responseCode !in 200..299) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val tag = json.optString("tag_name").removePrefix("v")
            val notes = json.optString("body").take(1500)
            val page = json.optString("html_url")
            var apk: String? = null
            var size = 0L
            val assets = json.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.optJSONObject(i) ?: continue
                    val name = a.optString("name")
                    if (name.endsWith(".apk")) {
                        apk = a.optString("browser_download_url")
                        size = a.optLong("size")
                        break
                    }
                }
            }
            if (tag.isBlank()) null else Info(tag, notes, apk, page, size)
        } catch (e: Exception) {
            null
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    /** remote 是否比 current 新，例如 1.4.0 > 1.3.0 */
    fun isNewer(remote: String, current: String): Boolean {
        val r = parse(remote) ?: return false
        val c = parse(current) ?: return false
        val n = maxOf(r.size, c.size)
        for (i in 0 until n) {
            val a = r.getOrElse(i) { 0 }
            val b = c.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    private fun parse(v: String): List<Int>? = runCatching {
        v.trim().removePrefix("v").substringBefore('-').split('.')
            .map { it.takeWhile { ch -> ch.isDigit() }.toIntOrNull() ?: 0 }
    }.getOrNull()
}
