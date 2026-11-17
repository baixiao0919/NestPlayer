package com.cloudnest.player.net

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Xml
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.URI
import kotlin.concurrent.thread

/** SSDP 发现局域网内的 DLNA / UPnP 媒体服务器 */
object DlnaDiscovery {

    data class Device(
        val name: String,
        val host: String,
        val controlUrl: String,
        val udn: String,
        val model: String = ""
    )

    fun discover(context: Context, timeoutMs: Long = 5000L): List<Device> {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = wifi?.createMulticastLock("nestplayer-dlna")?.apply {
            setReferenceCounted(false)
            acquire()
        }
        val locations = LinkedHashMap<String, String>()
        val socket = DatagramSocket()
        try {
            socket.soTimeout = 900
            val msg = buildString {
                append("M-SEARCH * HTTP/1.1\r\n")
                append("HOST: 239.255.255.250:1900\r\n")
                append("MAN: \"ssdp:discover\"\r\n")
                append("MX: 3\r\n")
                append("ST: urn:schemas-upnp-org:device:MediaServer:1\r\n")
                append("\r\n")
            }.toByteArray()
            val group = InetAddress.getByName("239.255.255.250")
            val deadline = System.currentTimeMillis() + timeoutMs
            var rounds = 0
            while (System.currentTimeMillis() < deadline) {
                if (rounds < 3) {
                    runCatching { socket.send(DatagramPacket(msg, msg.size, group, 1900)) }
                    rounds++
                }
                val buf = ByteArray(4096)
                val packet = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(packet)
                    val text = String(buf, 0, packet.length)
                    val location = text.lineSequence()
                        .firstOrNull { it.startsWith("LOCATION:", ignoreCase = true) }
                        ?.substringAfter(':')?.trim()
                    if (!location.isNullOrBlank()) locations[location] = location
                } catch (_: Exception) {
                }
            }
        } finally {
            runCatching { socket.close() }
            runCatching { lock?.release() }
        }
        return locations.values.mapNotNull { parseDevice(it) }
    }

    private fun parseDevice(location: String): Device? = runCatching {
        val req = Request.Builder().url(location).build()
        val xml = HttpStreams.client.newCall(req).execute().use { it.body?.string() ?: "" }
        if (xml.isBlank()) return null
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(xml.reader())

        var friendlyName = ""
        var model = ""
        var udn = ""
        var controlUrl = ""
        var inContentDirectory = false
        var ev = parser.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                when (parser.name.substringAfter(':')) {
                    "friendlyName" -> if (friendlyName.isEmpty()) friendlyName = parser.nextText()
                    "modelName" -> model = parser.nextText()
                    "UDN" -> udn = parser.nextText()
                    "serviceType" -> {
                        val t = parser.nextText()
                        inContentDirectory = t.contains("ContentDirectory")
                    }
                    "controlURL" -> {
                        val u = parser.nextText().trim()
                        if (inContentDirectory && controlUrl.isEmpty()) controlUrl = u
                    }
                }
            }
            ev = parser.next()
        }
        if (controlUrl.isEmpty()) return null
        val resolved = runCatching { URI(location).resolve(controlUrl).toString() }.getOrDefault(controlUrl)
        val host = runCatching { URI(location).host ?: "" }.getOrDefault("")
        Device(friendlyName.ifBlank { host }, host, resolved, udn, model)
    }.getOrNull()

    fun discoverAsync(context: Context, onDone: (List<Device>) -> Unit) {
        thread(name = "dlna-discover") { onDone(discover(context)) }
    }
}
