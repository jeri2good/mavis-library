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
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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
            // From the background Android drops startActivity, so the confirmation also rides a notification.
            confirm?.let {
                UpdateNotifications.confirm(context, Intent(it))
                try { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { }
            }
            return
        }
        (context.applicationContext as MavisApp).container.appUpdater.installFinished(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
    }
}

/** Remembers which version was announced, so each update is notified once; also holds the auto-install choice. */
class UpdateNotices(private val store: DataStore<Preferences>) {
    private val key = intPreferencesKey("update_notified_version")
    private val autoKey = booleanPreferencesKey("update_auto_install")
    /** The version an automatic install was started for; cleared once the new build reports itself running. */
    private val pendingKey = intPreferencesKey("update_auto_pending_version")
    val autoInstall: Flow<Boolean> = store.data.map { it[autoKey] ?: false }
    suspend fun setAutoInstall(on: Boolean) { store.edit { it[autoKey] = on } }
    suspend fun shouldNotify(versionCode: Int): Boolean {
        var fresh = false
        store.edit { if ((it[key] ?: 0) < versionCode) { it[key] = versionCode; fresh = true } }
        return fresh
    }
    private val notesKey = stringPreferencesKey("update_auto_pending_notes")
    suspend fun markAutoPending(versionCode: Int, notes: String) { store.edit { it[pendingKey] = versionCode; it[notesKey] = notes.take(300) } }
    /** The release notes, exactly once, on the first launch of a build that an automatic install put in place. */
    suspend fun consumeAutoInstalled(running: Int): String? {
        var done: String? = null
        store.edit { val p = it[pendingKey] ?: 0; if (p in 1..running) { done = it[notesKey].orEmpty(); it.remove(pendingKey); it.remove(notesKey) } }
        return done
    }
}

/** Android installs only builds signed like the running app; check first so a bad file fails quietly, never prompts. */
object UpdateSignature {
    @Suppress("DEPRECATION")
    private fun signers(info: android.content.pm.PackageInfo?): Set<String> =
        info?.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet().orEmpty()
    fun matchesInstalled(context: Context, apk: File): Boolean {
        val pm = context.packageManager
        val flag = PackageManager.GET_SIGNING_CERTIFICATES
        val mine = signers(pm.getPackageInfo(context.packageName, flag))
        val theirs = signers(pm.getPackageArchiveInfo(apk.path, flag))
        val name = pm.getPackageArchiveInfo(apk.path, 0)?.packageName
        return name == context.packageName && mine.isNotEmpty() && mine == theirs
    }
}

object UpdateScheduler {
    private const val PERIODIC = "mavis-update-check"
    private const val ON_OPEN = "mavis-update-check-open"
    private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** A check shortly after each launch, plus one every 6 hours. Skipped under Robolectric so tests never reach GitHub. */
    fun schedule(context: Context) {
        if (Build.FINGERPRINT == "robolectric") return
        val work = WorkManager.getInstance(context)
        // UPDATE replaces the old once-a-day request on existing installs.
        work.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<UpdateCheckWorker>(6, TimeUnit.HOURS).setConstraints(online).build())
        work.enqueueUniqueWork(ON_OPEN, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<UpdateCheckWorker>().setInitialDelay(15, TimeUnit.SECONDS).setConstraints(online)
                .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
    }
    /** Run a check right away, e.g. when auto-install is switched on. */
    fun checkSoon(context: Context) {
        if (Build.FINGERPRINT == "robolectric") return
        WorkManager.getInstance(context).enqueueUniqueWork(ON_OPEN, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<UpdateCheckWorker>().setConstraints(online).build())
    }
}

object UpdateNotifications {
    const val CHANNEL = "app_updates"
    const val READY_ID = 30003
    const val DONE_ID = 30004
    fun allowed(context: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    private fun manager(context: Context) = context.getSystemService(NotificationManager::class.java).also {
        it.createNotificationChannel(NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_DEFAULT))
    }
    private fun openUpdates(context: Context) = PendingIntent.getActivity(context, READY_ID,
        Intent(context, MainActivity::class.java).putExtra(MainActivity.WIDGET_DESTINATION, MainActivity.UPDATES)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun ready(context: Context, info: UpdateInfo) {
        if (!allowed(context)) return
        manager(context).notify(READY_ID, NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Mavis update ready")
            .setContentText("Version ${info.versionName}. Tap to install.")
            .setContentIntent(openUpdates(context)).setAutoCancel(true).build())
    }
    /** Android wants one tap for this install: hand its own confirmation screen to a notification. */
    fun confirm(context: Context, confirm: Intent) {
        if (!allowed(context)) return
        val pending = PendingIntent.getActivity(context, READY_ID, confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager(context).notify(READY_ID, NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Mavis update downloaded")
            .setContentText("Tap to finish installing. Android asks once; later updates install on their own.")
            .addAction(0, "Install", pending)
            .setContentIntent(pending).setAutoCancel(true).build())
    }
    fun updated(context: Context, versionName: String, notes: String?) {
        if (!allowed(context)) return
        val text = "Mavis updated to $versionName" + notes?.takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty()
        manager(context).notify(DONE_ID, NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Mavis updated to $versionName")
            .setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openUpdates(context)).setAutoCancel(true).build())
    }
}

/** Auto-install policy, separate from Android so it is unit-testable. */
object AutoUpdatePolicy {
    enum class Action { NOTIFY, INSTALL, NONE }
    fun decide(autoOn: Boolean, canInstall: Boolean, kidsMode: Boolean): Action = when {
        kidsMode -> Action.NONE
        autoOn && canInstall -> Action.INSTALL
        else -> Action.NOTIFY
    }
}

class UpdateCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as MavisApp).container
        val notices = container.updateNotices
        val action = AutoUpdatePolicy.decide(notices.autoInstall.first(), UpdateInstaller.canInstall(applicationContext),
            applicationContext.kidsPrefs().state.first().enabled)
        android.util.Log.i("MavisUpdate", "check: action=$action")
        if (action == AutoUpdatePolicy.Action.NONE) return Result.success()
        val info = try { container.updateChecker.check() } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                // Network not ready yet (e.g. DNS right after boot/launch): try again with WorkManager's backoff.
                android.util.Log.w("MavisUpdate", "check failed: $e")
                return if (runAttemptCount < 5) Result.retry() else Result.success()
            } ?: return Result.success().also { android.util.Log.i("MavisUpdate", "no newer version") }
        android.util.Log.i("MavisUpdate", "found ${info.versionCode}")
        if (action == AutoUpdatePolicy.Action.INSTALL) {
            val apk = try { container.updateDownloader.download(info) } catch (e: CancellationException) { throw e }
                catch (e: Exception) { android.util.Log.w("MavisUpdate", "download failed: $e"); null }
            val signed = apk != null && UpdateSignature.matchesInstalled(applicationContext, apk)
            android.util.Log.i("MavisUpdate", "downloaded=${apk != null} signatureMatches=$signed")
            if (apk != null && signed) {
                notices.markAutoPending(info.versionCode, info.notes)
                try { UpdateInstaller.install(applicationContext, apk); android.util.Log.i("MavisUpdate", "install session committed"); return Result.success() }
                catch (e: CancellationException) { throw e } catch (e: Exception) { android.util.Log.w("MavisUpdate", "install failed: $e") }
            }
            if (apk == null) return Result.retry()
        }
        if (notices.shouldNotify(info.versionCode)) UpdateNotifications.ready(applicationContext, info)
        return Result.success()
    }
}
