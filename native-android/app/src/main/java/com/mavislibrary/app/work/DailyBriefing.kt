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
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.mavislibrary.app.MainActivity
import com.mavislibrary.app.MavisApp
import com.mavislibrary.app.data.briefing.BriefingChecker
import com.mavislibrary.app.data.briefing.BriefingOutcome
import com.mavislibrary.app.data.briefing.PendingEpisode
import com.mavislibrary.app.data.kids.kidsPrefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.time.Clock
import java.time.Duration
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/** Daily podcast from followed sites: a run at the chosen time, then checks until the episodes are ready. */
object BriefingScheduler {
    private const val DAILY = "mavis-daily-briefing"
    private const val NOW = "mavis-briefing-now"
    private const val CHECK = "mavis-briefing-check"
    internal const val MANUAL = "manual"
    private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** [restore] keeps a run that is already scheduled (app start); otherwise the schedule is replaced. */
    suspend fun sync(context: Context, restore: Boolean = false) {
        val settings = (context.applicationContext as MavisApp).container.briefingStore.settings.first()
        if (settings.enabled) enqueue(context, settings.time, if (restore) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE)
        else WorkManager.getInstance(context).cancelUniqueWork(DAILY)
    }

    fun enqueue(context: Context, time: LocalTime, policy: ExistingWorkPolicy) {
        val clock = Clock.systemDefaultZone()
        WorkManager.getInstance(context).enqueueUniqueWork(DAILY, policy, run(Duration.between(clock.instant(), nextReminderFire(time, clock)), false))
    }

    /** "Make one now": a second tap while one is waiting does nothing. */
    fun runNow(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, run(Duration.ZERO, true))
    }

    fun check(context: Context, delayMinutes: Long = 10) {
        val request = OneTimeWorkRequestBuilder<BriefingCheckWorker>().setInitialDelay(delayMinutes, TimeUnit.MINUTES).setConstraints(online).build()
        WorkManager.getInstance(context).enqueueUniqueWork(CHECK, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    private fun run(delay: Duration, manual: Boolean): OneTimeWorkRequest = OneTimeWorkRequestBuilder<DailyBriefingWorker>()
        .setInitialDelay(delay).setConstraints(online)
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
        .setInputData(workDataOf(MANUAL to manual))
        .build()
}

class DailyBriefingWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as MavisApp).container
        val manual = inputData.getBoolean(BriefingScheduler.MANUAL, false)
        val settings = container.briefingStore.settings.first()
        if (!manual && !settings.enabled) return Result.success()
        if (!applicationContext.kidsPrefs().state.first().enabled) {
            val outcome = try { container.briefingRunner().run() } catch (e: CancellationException) { throw e } catch (_: Exception) {
                // The studio may be waking up or the phone between networks: try again in 10, 20, then 40 minutes.
                if (runAttemptCount < 3) return Result.retry()
                container.briefingStore.setStatus(System.currentTimeMillis(), "Couldn't reach your sites or podcast studio. Mavis will try again " +
                    if (settings.enabled) "at the next briefing." else "when you tap Make one now.")
                null
            }
            if (outcome is BriefingOutcome.Started) BriefingScheduler.check(applicationContext)
        }
        if (!manual) BriefingScheduler.enqueue(applicationContext, settings.time, ExistingWorkPolicy.APPEND_OR_REPLACE)
        return Result.success()
    }
}

class BriefingCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as MavisApp).container
        if (applicationContext.kidsPrefs().state.first().enabled) {
            BriefingScheduler.check(applicationContext, 30)
            return Result.success()
        }
        val api = container.podcastApi()
        val repository = container.podcastRepository()
        val more = BriefingChecker(container.briefingStore, repository::check, { id ->
            val file = container.audioFiles.downloadPodcast(api, id)
            container.podcastSettings.library.first().find { it.job.id == id && it.studio == api.address }
                ?.let { container.podcastSettings.save(it.copy(localFile = file.absolutePath)) }
        }, ::announce).run()
        if (more) BriefingScheduler.check(applicationContext)
        return Result.success()
    }

    private fun announce(episode: PendingEpisode, message: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "New podcasts", NotificationManager.IMPORTANCE_DEFAULT))
        val intent = Intent(applicationContext, MainActivity::class.java)
            .putExtra(MainActivity.WIDGET_DESTINATION, MainActivity.LOUNGE)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(applicationContext, 30002, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(episode.id.hashCode(), NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(episode.title)
            .setContentText(message)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build())
    }

    private companion object { const val CHANNEL = "podcast_ready" }
}
