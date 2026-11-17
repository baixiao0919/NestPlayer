package com.cloudnest.player.lib

import com.cloudnest.player.data.MetadataInfo
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * TMDB 元数据（可选）。用户没填 API Key 时全部走本地海报，不会联网。
 */
object Tmdb {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private const val IMG = "https://image.tmdb.org/t/p/w500"

    fun search(
        apiKey: String,
        key: String,
        title: String,
        isTv: Boolean,
        year: Int?,
        season: Int?
    ): MetadataInfo? {
        if (apiKey.isBlank() || title.isBlank()) return null
        val query = URLEncoder.encode(title, "UTF-8")
        val type = if (isTv) "tv" else "movie"
        val yearParam = if (isTv) "first_air_year" else "year"
        val url = "https://api.themoviedb.org/3/search/$type?api_key=$apiKey&language=zh-CN&query=$query" +
            (year?.let { "&$yearParam=$it" } ?: "")
        val json = runCatching {
            val req = Request.Builder().url(url).build()
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return null
                r.body?.string() ?: ""
            }
        }.getOrNull() ?: return null
        val results = runCatching { JSONObject(json).optJSONArray("results") }.getOrNull() ?: return null
        if (results.length() == 0) return null
        val first = results.optJSONObject(0) ?: return null
        val name = first.optString("title").ifBlank { first.optString("name") }
        val release = first.optString("release_date").ifBlank { first.optString("first_air_date") }
        return MetadataInfo(
            key = key,
            title = name.ifBlank { title },
            overview = first.optString("overview").ifBlank { null },
            posterUrl = first.optString("poster_path").takeIf { it.isNotBlank() }?.let { IMG + it },
            backdropUrl = first.optString("backdrop_path").takeIf { it.isNotBlank() }?.let { IMG + it },
            year = release.take(4).toIntOrNull(),
            rating = first.optDouble("vote_average", 0.0).takeIf { it > 0 },
            tmdbId = first.optLong("id")
        )
    }

    fun imageUrl(path: String?): String? = path
}
