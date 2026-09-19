package app.nya.smsforward.node.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.nya.smsforward.node.node.NodeRuntime
import app.nya.smsforward.node.node.Notifier
import java.util.concurrent.TimeUnit

/** Reports queued SMS. All the decisions live in [Uploader]; this only maps its verdict to WorkManager's. */
class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val runtime = NodeRuntime.get(applicationContext)
        return when (val result = runtime.uploader.flush()) {
            is FlushResult.Done -> {
                runtime.refresh()
                Result.success()
            }
            // WorkManager backs off exponentially (from 10 s) and keeps retrying: a dead server or no network never
            // loses a message, the queue just waits.
            is FlushResult.Retry -> {
                runtime.refresh()
                Result.retry()
            }
            FlushResult.NeedsPairing -> {
                Notifier.needsPairing(applicationContext, runtime.outbox.pendingCount())
                runtime.refresh()
                Result.success() // retrying would change nothing until the user pairs again; pairing enqueues a new run
            }
            FlushResult.NotPaired -> Result.success()
        }
    }
}

object UploadScheduler {
    private const val NOW = "upload-now"
    private const val PERIODIC = "upload-periodic"

    private val needsNetwork = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Run an upload as soon as there is a network. Appends, so a run in progress cannot miss the newest SMS. */
    fun enqueue(context: Context) {
        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(needsNetwork)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    /** A slow safety net (every 15 minutes, the WorkManager minimum) in case an enqueue was ever missed. */
    fun schedulePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<UploadWorker>(15, TimeUnit.MINUTES)
            .setConstraints(needsNetwork)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}
