package com.mavislibrary.app.data.podsub

import com.mavislibrary.app.data.podcast.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

interface PodsubService {
    suspend fun get(): SubscriptionResponse
    suspend fun put(document: SubscriptionDocument): SubscriptionResponse
    suspend fun runNow(): SubscriptionRun
    suspend fun today(): List<PodcastJob>
    suspend fun runs(): List<SubscriptionRun>
}
/** Same gateway client, adult gate and account/owner headers as manual podcasts. */
class PodsubApi(private val gateway: PodcastApi) : PodsubService {
    override suspend fun get() = podcastJson.decodeFromString<SubscriptionResponse>(gateway.request("/api/podsub"))
    override suspend fun put(document: SubscriptionDocument) = podcastJson.decodeFromString<SubscriptionResponse>(
        gateway.request("/api/podsub", "PUT", podcastJson.encodeToString(document.validated())))
    override suspend fun runNow() = podcastJson.decodeFromString<SubscriptionRun>(gateway.request("/api/podsub/run-now", "POST", "{}"))
    // The contract specifies the list contents, but does not prescribe a list envelope.
    private fun list(body: String, key: String): JsonArray {
        val value = podcastJson.parseToJsonElement(body)
        return if (value is JsonArray) value else (value.jsonObject[key] ?: value.jsonObject["jobs"] ?: value.jsonObject["items"])?.jsonArray
            ?: error("The gateway returned an invalid $key list.")
    }
    override suspend fun today() = list(gateway.request("/api/podsub/today"), "episodes").map { podcastJson.decodeFromJsonElement<PodcastJob>(it) }
    override suspend fun runs() = list(gateway.request("/api/podsub/runs"), "runs").map { podcastJson.decodeFromJsonElement<SubscriptionRun>(it) }
}
