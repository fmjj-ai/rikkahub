package me.rerere.rikkahub.data.network

import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI

/** 将设置页中的代理地址转换为 OkHttp 可用的 Java Proxy。 */
fun String.toProxyOrNull(): Proxy? {
    val raw = trim()
    if (raw.isBlank()) return null

    val normalized = if ("://" in raw) raw else "http://$raw"
    val uri = runCatching { URI(normalized) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase() ?: return null
    val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
    val port = when {
        uri.port in 1..65535 -> uri.port
        uri.port == -1 && scheme in setOf("http", "https") -> 80
        uri.port == -1 && scheme in setOf("socks", "socks5", "socks5h") -> 1080
        else -> return null
    }
    val type = when (scheme) {
        "socks", "socks5", "socks5h" -> Proxy.Type.SOCKS
        "http", "https" -> Proxy.Type.HTTP
        else -> return null
    }
    return Proxy(type, InetSocketAddress.createUnresolved(host, port))
}
