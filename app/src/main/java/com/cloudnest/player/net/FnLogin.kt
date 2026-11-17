package com.cloudnest.player.net

import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 「账号登录」用的探测：用 NAS 的账号密码走一次 SMB 认证，
 * 认证通过后再试着连几个常见共享名，把能用的共享挑出来。
 *
 * 这样用户只填地址 + 账号 + 密码，不用懂 SMB/WebDAV 这些协议细节。
 */
object FnLogin {

    data class Ok(val host: String, val port: Int, val user: String, val shares: List<String>)

    /** 飞牛 / 群晖 / 威联通常见的默认共享名，用来猜 */
    private val COMMON_SHARES = listOf(
        "homes", "home", "public", "share", "shared",
        "video", "Video", "video1", "Movies", "movies",
        "music", "Music", "media", "Media",
        "photo", "Photo", "downloads", "disk1", "volume1"
    )

    fun parseHost(raw: String): String =
        raw.trim()
            .removePrefix("http://").removePrefix("https://")
            .substringBefore('/')
            .substringBefore(':')
            .trim()

    fun probe(rawHost: String, port: Int, user: String, pass: String, shareHint: String): Ok {
        val host = parseHost(rawHost)
        if (host.isBlank()) throw IOException("请填写 NAS 地址，例如 192.168.1.10")
        if (user.isBlank()) throw IOException("请填写账号")
        val p = if (port > 0) port else 445
        val conf = SmbConfig.builder()
            .withTimeout(15, TimeUnit.SECONDS)
            .withReadTimeout(20, TimeUnit.SECONDS)
            .withMultiProtocolNegotiate(true)
            .build()
        val client = SMBClient(conf)
        try {
            val conn = client.connect(host, p)
            val sess = conn.authenticate(
                AuthenticationContext(user, pass.toCharArray(), null)
            )
            val candidates = LinkedHashSet<String>()
            if (shareHint.isNotBlank()) candidates.add(shareHint.trim('/'))
            candidates.addAll(COMMON_SHARES)
            val ok = ArrayList<String>()
            for (name in candidates) {
                val reachable = runCatching { sess.connectShare(name) }.isSuccess
                if (reachable) ok.add(name)
                if (ok.size >= 6) break
            }
            if (ok.isEmpty()) {
                throw IOException("账号密码验证通过，但没找到可访问的共享，请手动填写共享名")
            }
            return Ok(host, p, user, ok)
        } finally {
            runCatching { client.close() }
        }
    }
}
