package com.mavislibrary.app.data.feeds

import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test

class UrlPolicyTest {
    @Test fun normalizesPublicDomains() {
        assertEquals("https://intheworldofai.com/", UrlPolicy.normalize(" intheworldofai.com "))
        assertEquals("https://example.com/feed?q=rss", UrlPolicy.normalize("https://EXAMPLE.com:443/feed?q=rss#top"))
    }
    @Test fun rejectsUnsafeUrls() {
        listOf("http://example.com", "file:///etc/passwd", "content://example.com", "ftp://example.com", "javascript:alert(1)",
            "https://127.0.0.1", "https://10.0.0.1", "https://192.168.1.1", "https://169.254.1.1", "https://[::1]",
            "https://localhost", "https://x.localhost", "https://x.local", "https://x.internal", "https://100.64.1.1",
            "https://user:pass@example.com", "https://127.0.0.1@example.com", "https://example.com@127.0.0.1", "https://user%40host@example.com",
            "https://2130706433", "https://0x7f000001", "https://0177.0.0.1", "https://0x7f.0.0.1", "https://127.1",
            "https://example.com:8080", "https://example.com\\@localhost", "https://[::ffff:127.0.0.1]").forEach {
            assertTrue(it, runCatching { UrlPolicy.normalize(it) }.isFailure)
        }
    }
    @Test fun rejectsNonPublicDnsIncludingMixedAnswers() {
        listOf("127.0.0.1", "10.1.2.3", "172.16.0.1", "172.31.255.255", "192.168.0.1", "169.254.1.1", "100.64.0.1", "100.127.255.255",
            "0.0.0.0", "224.0.0.1", "240.0.0.1", "::1", "::", "fc00::1", "fd00::1", "fe80::1", "ff02::1", "::ffff:10.0.0.1", "64:ff9b::a00:1", "2002:a00:1::1").forEach {
            assertFalse(it, UrlPolicy.isPublic(InetAddress.getByName(it)))
            assertTrue(runCatching { UrlPolicy.requirePublic(listOf(InetAddress.getByName("8.8.8.8"), InetAddress.getByName(it))) }.isFailure)
        }
        listOf("8.8.8.8", "100.63.255.255", "100.128.0.0", "172.32.0.1", "2606:4700:4700::1111").forEach { assertTrue(it, UrlPolicy.isPublic(InetAddress.getByName(it))) }
        assertTrue(runCatching { UrlPolicy.requirePublic(emptyList()) }.isFailure)
    }
    @Test fun redirectsAreRevalidated() {
        listOf("https://127.0.0.1/feed", "http://example.com/feed", "//10.1.1.1/feed", "https://user@example.com").forEach {
            assertTrue(runCatching { UrlPolicy.redirect("https://example.com", it) }.isFailure)
        }
        assertEquals("https://example.com/rss", UrlPolicy.redirect("https://example.com/feed", "/rss"))
    }
}
