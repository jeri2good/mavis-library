package com.mavislibrary.app.data.feeds

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

const val MAX_FEED_BYTES = 2 * 1024 * 1024

data class FeedResponse(val url: String, val status: Int, val body: String)
fun interface FeedFetcher { suspend fun fetch(url: String): FeedResponse }

/** Independent client: no owner code, app interceptors, credentials, cookies or system proxy. */
class DeviceFeedFetcher : FeedFetcher {
    private val client = OkHttpClient.Builder()
        .cookieJar(CookieJar.NO_COOKIES).proxy(Proxy.NO_PROXY)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .dns(object : Dns { override fun lookup(hostname: String) = UrlPolicy.requirePublic(Dns.SYSTEM.lookup(hostname)) })
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).callTimeout(10, TimeUnit.SECONDS)
        .build()

    override suspend fun fetch(url: String): FeedResponse = withContext(Dispatchers.IO) {
        var next = UrlPolicy.normalize(url)
        repeat(4) { hop ->
            val call = client.newCall(Request.Builder().url(next)
                .header("User-Agent", "Mozilla/5.0 (compatible; Feedbot/1.0)")
                .header("Accept", "application/rss+xml, application/atom+xml, application/xml, text/html;q=0.8").build())
            val response = suspendCancellableCoroutine<Response> { continuation ->
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) { if (!continuation.isCancelled) continuation.resumeWithException(e) }
                    override fun onResponse(call: Call, response: Response) {
                        continuation.resume(response) { _, value, _ -> value.close() }
                    }
                })
            }
            response.use { r ->
                if (r.code in setOf(301, 302, 303, 307, 308)) {
                    if (hop == 3) throw IOException("Too many redirects.")
                    next = UrlPolicy.redirect(next, r.header("Location") ?: throw IOException("Invalid redirect."))
                } else {
                    val body = r.body ?: throw IOException("Empty response.")
                    if (body.contentLength() > MAX_FEED_BYTES) throw IOException("The feed is larger than 2 MB.")
                    val source = body.source()
                    source.request(MAX_FEED_BYTES.toLong() + 1)
                    if (source.buffer.size > MAX_FEED_BYTES) throw IOException("The feed is larger than 2 MB.")
                    return@withContext FeedResponse(next, r.code, source.readString(body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8))
                }
            }
        }
        throw IOException("Too many redirects.")
    }
}
