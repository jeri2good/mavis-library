package com.mavislibrary.app.data.feeds

import java.net.InetAddress
import java.net.URI
import java.io.IOException

/** Pure URL/address checks. DNS results are checked by the transport at connection time. */
object UrlPolicy {
    fun normalize(input: String): String {
        val raw = input.trim()
        val value = if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(raw)) raw else "https://$raw"
        val uri = try { URI(value) } catch (_: Exception) { throw IOException("Enter a valid HTTPS site or feed address.") }
        val host = uri.host?.lowercase()?.trimEnd('.') ?: throw IOException("Enter a public site name.")
        if (uri.scheme != "https" || uri.rawUserInfo != null || uri.rawAuthority?.contains('@') == true ||
            uri.port !in listOf(-1, 443) || !host.contains('.') ||
            host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local") || host.endsWith(".internal") ||
            host.contains(':') || host.startsWith('[') ||
            host.split('.').all { it.matches(Regex("(?:[0-9]+|0x[0-9a-f]+)")) } ||
            !host.matches(Regex("[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?"))) {
            throw IOException("Use an HTTPS address with a public site name, without credentials.")
        }
        return URI("https", null, host, -1, uri.path.ifEmpty { "/" }, uri.query, null).toASCIIString()
    }

    fun requirePublic(addresses: List<InetAddress>): List<InetAddress> {
        if (addresses.isEmpty() || addresses.any { !isPublic(it) }) throw IOException("Private or local network addresses are not allowed.")
        return addresses
    }

    fun isPublic(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress) return false
        val b = address.address.map { it.toInt() and 255 }
        if (b.size == 4) return !(b[0] == 0 || b[0] == 10 || b[0] == 127 || b[0] >= 224 ||
            (b[0] == 100 && b[1] in 64..127) || (b[0] == 169 && b[1] == 254) ||
            (b[0] == 172 && b[1] in 16..31) || (b[0] == 192 && b[1] == 168) ||
            (b[0] == 192 && b[1] == 0) || (b[0] == 198 && b[1] in 18..19) ||
            (b[0] == 198 && b[1] == 51 && b[2] == 100) || (b[0] == 203 && b[1] == 0 && b[2] == 113))
        // Accept global unicast only; exclude translation/tunneling and documentation ranges.
        return b.size == 16 && b[0] in 0x20..0x3f &&
            !(b[0] == 0x20 && b[1] == 0x02) &&
            !(b[0] == 0x20 && b[1] == 0x01 && (b[2] < 2 || (b[2] == 0x0d && b[3] == 0xb8)))
    }

    fun redirect(from: String, location: String): String = normalize(URI(from).resolve(location).toString())
}
