package com.mavislibrary.app.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import com.mavislibrary.app.MainActivity
import com.mavislibrary.app.MavisApp
import com.mavislibrary.app.R
import com.mavislibrary.app.data.briefing.*
import com.mavislibrary.app.data.kids.kidsPrefs
import com.mavislibrary.app.data.podcast.SavedPodcast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

object PodcastReadyScheduler {
    private fun network() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    fun start(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("mavis-podcast-ready-periodic", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<PodcastReadyWorker>(15, TimeUnit.MINUTES).setConstraints(network())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
        soon(context)
    }
    fun soon(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork("mavis-podcast-ready-now", ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<PodcastReadyWorker>().setConstraints(network())
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
    }
}
object PodcastReadyNotifications {
    const val CHANNEL = "podcasts-ready"
    fun channel(context: Context) { context.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "Podcasts ready", NotificationManager.IMPORTANCE_DEFAULT)) }
    fun build(context: Context, notice: ReadyNotice): android.app.Notification {
        channel(context)
        val open = Intent(context, MainActivity::class.java).putExtra(MainActivity.WIDGET_DESTINATION, MainActivity.LOUNGE)
            .setData(android.net.Uri.parse("mavis://ready/${notice.job.id}/${notice.outcome}"))
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return NotificationCompat.Builder(context, CHANNEL).setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(notice.title).setContentText(notice.text).setStyle(NotificationCompat.BigTextStyle().bigText(notice.text))
            .setAutoCancel(true).setOnlyAlertOnce(true)
            .setContentIntent(PendingIntent.getActivity(context, (notice.job.id + notice.outcome).hashCode(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .apply { if (notice.play) addAction(android.R.drawable.ic_media_play, "Play", PodcastPlaybackAction.pending(context, notice.job.id, false)) }
            .build()
    }
    fun post(context: Context, notice: ReadyNotice) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        try { NotificationManagerCompat.from(context).notify("podcast/${notice.job.id}/${notice.outcome}", 840, build(context, notice)) } catch (_: SecurityException) { }
    }
}
class PodcastReadyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun getForegroundInfo(): ForegroundInfo {
        PodcastReadyNotifications.channel(applicationContext)
        return ForegroundInfo(841, NotificationCompat.Builder(applicationContext, PodcastReadyNotifications.CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play).setContentTitle("Checking your podcasts").setSilent(true).build())
    }
    override suspend fun doWork(): Result {
        val c = (applicationContext as MavisApp).container
        val account = c.podcastAccountId() ?: return Result.success()
        suspend fun allowed() = !applicationContext.kidsPrefs().state.first().enabled && c.podcastAccountId() == account
        if (!allowed()) return Result.success()
        return try {
            val api = c.podcastApi()
            val saved = c.podcastSettings.library.first().filter { it.studio == api.address && it.accountId == account }
            var failed = false
            val today = try { c.podsubRepository.today() } catch (e: CancellationException) { throw e } catch (_: Exception) { failed = true; emptyList() }
            val repo = c.podcastRepository()
            // Prefer pending local snapshots so completion during the first HTTP check is still announced.
            val candidates = (saved.filter { !it.job.terminal }.map { it.job } + today + saved.map { it.job }).distinctBy { it.id }
            val retry = PodcastReadyRunner(c.podcastReadyStore).run(account, candidates, repo::check, download = { job ->
                val file = c.audioFiles.downloadPodcast(api, job.id)
                if (allowed()) c.podcastSettings.save(SavedPodcast(job, api.address, file.absolutePath, accountId = account))
                WidgetUpdater.refresh(applicationContext)
            }, allowed = ::allowed, notify = { PodcastReadyNotifications.post(applicationContext, it) })
            if (retry || failed) Result.retry() else Result.success()
        } catch (e: CancellationException) { throw e } catch (_: Exception) { Result.retry() }
    }
}
