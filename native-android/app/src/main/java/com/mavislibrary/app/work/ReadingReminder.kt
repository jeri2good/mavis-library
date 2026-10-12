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
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.mavislibrary.app.MainActivity
import com.mavislibrary.app.MavisApp
import com.mavislibrary.app.data.kids.kidsPrefs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CancellationException
import java.time.Clock
import java.time.Duration
import java.time.LocalTime
import java.time.ZonedDateTime

fun nextReminderFire(time: LocalTime, clock: Clock): java.time.Instant {
    val now = ZonedDateTime.now(clock)
    var next = now.toLocalDate().atTime(time).atZone(clock.zone)
    if (!next.toInstant().isAfter(clock.instant())) next = now.toLocalDate().plusDays(1).atTime(time).atZone(clock.zone)
    return next.toInstant()
}

fun shouldSendReminder(enabled: Boolean, kidsMode: Boolean, notificationsAllowed: Boolean): Boolean =
    enabled && !kidsMode && notificationsAllowed

object ReminderScheduler {
    private const val NAME = "mavis-reading-reminder"

    suspend fun sync(context: Context) {
        val settings = (context.applicationContext as MavisApp).container.settings.reminder.first()
        if (settings.enabled) enqueue(context, settings.time, ExistingWorkPolicy.REPLACE)
        else WorkManager.getInstance(context).cancelUniqueWork(NAME)
    }

    fun enqueue(context: Context, time: LocalTime, policy: ExistingWorkPolicy) {
        val clock = Clock.systemDefaultZone()
        val delay = Duration.between(clock.instant(), nextReminderFire(time, clock))
        val request = OneTimeWorkRequestBuilder<ReadingReminderWorker>()
            .setInitialDelay(delay)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(NAME, policy, request)
    }
}

class ReadingReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        return try {
            val settings = (applicationContext as MavisApp).container.settings.reminder.first()
            if (settings.enabled) {
                val kids = applicationContext.kidsPrefs().state.first().enabled
                val allowed = Build.VERSION.SDK_INT < 33 ||
                    ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                if (shouldSendReminder(settings.enabled, kids, allowed)) notifyReader()
                ReminderScheduler.enqueue(applicationContext, settings.time, ExistingWorkPolicy.APPEND_OR_REPLACE)
            }
            Result.success()
        } catch (e: CancellationException) { throw e } catch (_: Exception) { Result.retry() }
    }

    private fun notifyReader() {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("reading_reminder", "Reading reminder", NotificationManager.IMPORTANCE_DEFAULT))
        val intent = Intent(applicationContext, MainActivity::class.java)
            .putExtra(MainActivity.WIDGET_DESTINATION, "shelf")
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(applicationContext, 30001, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(30001, NotificationCompat.Builder(applicationContext, "reading_reminder")
            .setSmallIcon(android.R.drawable.ic_menu_agenda)
            .setContentTitle("Reading reminder")
            .setContentText("A few minutes with your book?")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build())
    }
}
