package com.mavislibrary.app.work

import android.content.Context
import androidx.work.*
import com.mavislibrary.app.MavisApp
import com.mavislibrary.app.data.kids.kidsPrefs
import com.mavislibrary.app.data.podcast.SavedPodcast
import com.mavislibrary.app.data.podsub.downloadExpired
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.util.concurrent.TimeUnit

/** Retire all legacy creation work, including already queued retries. Only the server generates episodes. */
object BriefingScheduler {
    fun syncSoon(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork("mavis-podsub-sync", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<PodsubDownloadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
    }
    suspend fun sync(context: Context, restore: Boolean = false) {
        val manager = WorkManager.getInstance(context)
        listOf("mavis-daily-briefing", "mavis-briefing-now", "mavis-briefing-check").forEach(manager::cancelUniqueWork)
        manager.enqueueUniquePeriodicWork("mavis-podsub-downloads", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<PodsubDownloadWorker>(1, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
    }
}
/** Kept as inert classes so persisted WorkManager requests from older APKs safely complete. */
class DailyBriefingWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork() = Result.success()
}
class BriefingCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork() = Result.success()
}
class PodsubDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as MavisApp).container
        if (applicationContext.kidsPrefs().state.first().enabled || container.podcastAccountId() == null) return Result.success()
        return try {
            val account = container.podcastAccountId()
            val subscription = container.podsubRepository.load()
            val now = Instant.now()
            val api = container.podcastApi()
            container.audioFiles.cleanupSubscriptionDownloads(container.podcastSettings, api.address, account, subscription.keepDays, now)
            var failed = false
            for (job in container.podsubRepository.today().filter { it.status == "ready" }) {
                if (downloadExpired(job.createdAt, subscription.keepDays, now)) continue
                if (container.podcastAccountId() != account || applicationContext.kidsPrefs().state.first().enabled) break
                try {
                    val file = container.audioFiles.downloadPodcast(api, job.id)
                    container.podcastSettings.save(SavedPodcast(job, api.address, file.absolutePath, accountId = account))
                } catch (e: CancellationException) { throw e } catch (_: Exception) { failed = true }
            }
            if (failed) Result.retry() else Result.success()
        } catch (e: CancellationException) { throw e } catch (_: Exception) { Result.retry() }
    }
}
