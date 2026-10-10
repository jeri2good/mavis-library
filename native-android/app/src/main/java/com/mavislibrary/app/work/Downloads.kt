package com.mavislibrary.app.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.mavislibrary.app.MavisApp
import com.mavislibrary.app.data.db.DownloadQueueEntity

/**
 * M1 stub: proves the WorkManager wiring and the Wi-Fi-only constraint. It reads the queue and leaves
 * items queued; real downloading (EPUB, audio, Bible text) arrives in M3.
 */
class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? MavisApp ?: return Result.success()
        val queued = app.container.database.downloads().withState(DownloadQueueEntity.STATE_QUEUED)
        android.util.Log.i(TAG, "Download queue checked: ${queued.size} item(s) waiting (stub, nothing downloaded)")
        return Result.success()
    }

    companion object {
        const val TAG = "MavisDownloads"
    }
}

object DownloadScheduler {
    const val UNIQUE_NAME = "mavis-downloads"

    /** Unmetered (Wi-Fi/Ethernet) only, and never when storage is low. */
    val constraints: Constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.UNMETERED)
        .setRequiresStorageNotLow(true)
        .build()

    fun request(): OneTimeWorkRequest = OneTimeWorkRequestBuilder<DownloadWorker>()
        .setConstraints(constraints)
        .addTag(DownloadWorker.TAG)
        .build()

    fun schedule(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, request())
    }
}
