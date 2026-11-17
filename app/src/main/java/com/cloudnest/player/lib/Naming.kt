package com.cloudnest.player.lib

/** 从文件名里猜片名 / 年份 / 季集，尽量贴近常见命名规则 */
object Naming {

    private val seasonEpisode = Regex("(?i)S(\\d{1,2})[ ._-]?E(\\d{1,3})")
    private val seasonEpisodeCn = Regex("第(\\d{1,2})季.*?第(\\d{1,3})集")
    private val cnSeason = Regex("第(\\d{1,2})季")
    private val cnEpisode = Regex("第(\\d{1,3})集|第(\\d{1,3})话")
    private val episodeOnly = Regex("(?i)(?:^|[^A-Za-z])E(\\d{1,3})(?:[^0-9]|$)")
    private val yearRegex = Regex("(?<![0-9])(19|20)(\\d{2})(?![0-9])")

    private val noiseTokens = listOf(
        "2160p", "1080p", "1080i", "720p", "480p", "4k", "8k", "uhd", "hdr", "hdr10", "hdr10+", "dolby",
        "bluray", "blu-ray", "bdrip", "brrip", "webrip", "web-dl", "webdl", "hdtv", "dvdrip", "hdrip",
        "x264", "x265", "h264", "h265", "hevc", "avc", "10bit", "8bit", "aac", "ac3", "dts", "truehd",
        "atmos", "ddp", "dd5.1", "5.1", "7.1", "remux", "proper", "repack", "internal", "extended",
        "imax", "complete", "multi", "dual", "chs", "cht", "eng", "gb", "big5", "国语", "粤语", "中字",
        "双语", "中英", "特效", "字幕", "内嵌", "无水印", "合集", "全集", "高清", "超清", "蓝光",
        "hq", "sdr", "yuv420p10", "flac", "mp3", "eac3"
    )

    data class Parsed(
        val title: String,
        val year: Int?,
        val season: Int?,
        val episode: Int?
    )

    fun parse(fileName: String): Parsed {
        var base = fileName.substringBeforeLast('.', fileName)
        var season: Int? = null
        var episode: Int? = null

        seasonEpisode.find(base)?.let {
            season = it.groupValues[1].toIntOrNull()
            episode = it.groupValues[2].toIntOrNull()
        }
        if (season == null) {
            seasonEpisodeCn.find(base)?.let {
                season = it.groupValues[1].toIntOrNull()
                episode = it.groupValues[2].toIntOrNull()
            }
        }
        if (season == null) {
            season = cnSeason.find(base)?.groupValues?.get(1)?.toIntOrNull()
        }
        if (episode == null) {
            episode = cnEpisode.find(base)?.let { m ->
                (m.groupValues[1].ifBlank { m.groupValues[2] }).toIntOrNull()
            }
        }
        if (episode == null) {
            episode = episodeOnly.find(base)?.groupValues?.get(1)?.toIntOrNull()
        }

        // 去掉季集标记，剩下的当标题
        base = seasonEpisode.replace(base, " ")
        base = seasonEpisodeCn.replace(base, " ")
        base = cnSeason.replace(base, " ")
        base = cnEpisode.replace(base, " ")
        base = episodeOnly.replace(base, " ")

        var year: Int? = null
        yearRegex.find(base)?.let {
            val y = (it.groupValues[1] + it.groupValues[2]).toIntOrNull()
            if (y != null && y in 1900..2100) year = y
        }
        if (year != null) base = base.replace(year.toString(), " ")

        // 括号内容里通常是发布组/画质信息，直接去掉
        base = base.replace(Regex("\\[[^\\]]*\\]"), " ")
        base = base.replace(Regex("【[^】]*】"), " ")
        base = base.replace(Regex("\\([^)]*\\)"), " ")

        base = base.replace('.', ' ').replace('_', ' ')
        base = base.replace(Regex("[-~]+"), " ")

        var tokens = base.split(' ').filter { it.isNotBlank() }
        tokens = tokens.filter { t ->
            val low = t.lowercase()
            noiseTokens.none { n -> low == n || low.startsWith("$n-") } && !low.matches(Regex("@?[0-9a-f]{8,}"))
        }
        var title = tokens.joinToString(" ").trim()
        if (title.isEmpty()) title = fileName.substringBeforeLast('.', fileName)

        // 常见于剧集：标题后面挂着 “粤语/国语” 之类
        title = title.replace(Regex("^[ .-]+"), "").trim()
        return Parsed(title, year, season, episode)
    }

    fun seriesKey(title: String, season: Int?): String = title.lowercase().trim() + "#" + (season ?: -1)

    /** 同名字幕候选（不含扩展名比较） */
    fun subtitleBaseName(name: String): String = name.substringBeforeLast('.', name).lowercase().trim()

    fun prettyTitle(title: String): String = title
        .split(' ')
        .filter { it.isNotBlank() }
        .joinToString(" ") { w -> w.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() } }
}
