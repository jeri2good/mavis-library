package com.mavislibrary.app.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.mavislibrary.app.MainActivity
import com.mavislibrary.app.MavisApp
import com.mavislibrary.app.data.kids.kidsPrefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Downloading(val info: UpdateInfo, val done: Long, val total: Long) : UpdateState
    /** Android needs the one-time "install unknown apps" permission for Mavis first. */
    data class NeedsPermission(val info: UpdateInfo) : UpdateState
    data class Installing(val info: UpdateInfo) : UpdateState
    data class Failed(val message: String, val info: UpdateInfo? = null) : UpdateState
}

/** Checks for, downloads, and hands published builds to Android's installer. Runs in the app scope so leaving Settings doesn't stop a download. */
class AppUpdater(
    private val checker: UpdateChecker,
    private val downloader: UpdateDownloader,
    private val scope: CoroutineScope,
    private val canInstall: () -> Boolean,
    private val installApk: (File) -> Unit,
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()
    private var job: Job? = null

    fun check() {
        if (job?.isActive == true) return
        job = scope.launch {
            _state.value = UpdateState.Checking
            _state.value = try { checker.check()?.let { UpdateState.Available(it) } ?: UpdateState.UpToDate }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { UpdateState.Failed(e.message ?: "Couldn't check for updates.") }
        }
    }

    fun install(info: UpdateInfo) {
        if (job?.isActive == true) return
        if (!canInstall()) { _state.value = UpdateState.NeedsPermission(info); return }
        job = scope.launch {
            try {
                val apk = downloader.download(info) { done, total -> _state.value = UpdateState.Downloading(info, done, total) }
                _state.value = UpdateState.Installing(info)
                withContext(Dispatchers.IO) { installApk(apk) }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                _state.value = UpdateState.Failed(e.message ?: "The update couldn't be installed.", info)
            }
        }
    }

    /** Android's answer, relayed by [UpdateReceiver]. On success this app is replaced and restarts. */
    fun installFinished(status: Int, message: String?) {
        val info = (_state.value as? UpdateState.Installing)?.info
        _state.value = when (status) {
            PackageInstaller.STATUS_SUCCESS -> UpdateState.UpToDate
            PackageInstaller.STATUS_FAILURE_ABORTED -> info?.let { UpdateState.Available(it) } ?: UpdateState.Idle
            PackageInstaller.STATUS_FAILURE_CONFLICT -> UpdateState.Failed(
                "Android won't update this copy because it was signed with a different key. Uninstall Mavis, then install the latest version from the releases page.", info)
            PackageInstaller.STATUS_FAILURE_STORAGE -> UpdateState.Failed("There isn't enough storage space for the update.", info)
            else -> UpdateState.Failed(message?.takeIf(String::isNotBlank)?.let { "Android couldn't install the update: $it" }
                ?: "Android couldn't install the update.", info)
        }
    }
}

object UpdateInstaller {
    private const val ACTION = "com.mavislibrary.app.UPDATE_STATUS"

    fun canInstall(context: Context) = context.packageManager.canRequestPackageInstalls()

    fun allowIntent(context: Context) = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())

    fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            // Once Mavis has installed itself, Android 12+ may skip the confirmation for its own updates.
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("mavis.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(context, UpdateReceiver::class.java).setAction(ACTION).setPackage(context.packageName)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            session.commit(PendingIntent.getBroadcast(context, id, intent, flags).intentSender)
        }
    }
}

/** Not exported: only the installer session's own PendingIntent reaches it. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
            confirm?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            return
        }
        (context.applicationContext as MavisApp).container.appUpdater.installFinished(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
    }
}

/** Remembers which version was announced, so each update is notified once. */
class UpdateNotices(private val store: DataStore<Preferences>) {
    private val key = intPreferencesKey("update_notified_version")
    suspend fun shouldNotify(versionCode: Int): Boolean {
        var fresh = false
        store.edit { if ((it[key] ?: 0) < versionCode) { it[key] = versionCode; fresh = true } }
        return fresh
    }
}

object UpdateScheduler {
    private const val DAILY = "mavis-update-check"
    private const val ON_OPEN = "mavis-update-check-open"
    private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** A check shortly after each launch, plus a daily one. Skipped under Robolectric so tests never reach GitHub. */
    fun schedule(context: Context) {
        if (Build.FINGERPRINT == "robolectric") return
        val work = WorkManager.getInstance(context)
        work.enqueueUniquePeriodicWork(DAILY, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<UpdateCheckWorker>(1, TimeUnit.DAYS).setConstraints(online).build())
        work.enqueueUniqueWork(ON_OPEN, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<UpdateCheckWorker>().setInitialDelay(20, TimeUnit.SECONDS).setConstraints(online).build())
    }
}

class UpdateCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as MavisApp).container
        if (applicationContext.kidsPrefs().state.first().enabled) return Result.success()
        val info = try { container.updateChecker.check() } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            ?: return Result.success()
        if (container.updateNotices.shouldNotify(info.versionCode)) announce(info)
        return Result.success()
    }

    private fun announce(info: UpdateInfo) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("app_updates", "App updates", NotificationManager.IMPORTANCE_DEFAULT))
        val intent = Intent(applicationContext, MainActivity::class.java)
            .putExtra(MainActivity.WIDGET_DESTINATION, MainActivity.UPDATES)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(applicationContext, 30003, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(30003, NotificationCompat.Builder(applicationContext, "app_updates")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Mavis update ready")
            .setContentText("Version ${info.versionName}. Tap to install.")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build())
    }
}
