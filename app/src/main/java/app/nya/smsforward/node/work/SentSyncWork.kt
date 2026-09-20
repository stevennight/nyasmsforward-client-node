package app.nya.smsforward.node.work

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.nya.smsforward.node.node.NodeRuntime
import java.util.concurrent.TimeUnit

/** Reads the sent box (and the history, once) and queues what is new; the uploader reports it. */
class SentSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val runtime = NodeRuntime.get(applicationContext)
        val queued = runtime.sentSync.syncSent() + runtime.sentSync.backfill()
        if (queued > 0) {
            UploadScheduler.enqueue(applicationContext)
            runtime.refresh()
        }
        return Result.success()
    }
}

/**
 * When the sent box is read. A ContentObserver notices a message the user just sent (within a few seconds) while the
 * process is alive; a 15 minute periodic pass catches up after the process was killed. Both exist only while the user has
 * switched "sync sent messages" on.
 */
object SentSyncScheduler {
    private const val NOW = "sent-sync-now"
    private const val PERIODIC = "sent-sync-periodic"
    private val SMS: Uri = Uri.parse("content://sms")

    private var observer: ContentObserver? = null

    /** Runs a pass soon; bursts of changes (one SMS touches the provider several times) collapse into one. */
    fun requestSoon(context: Context) {
        val request = OneTimeWorkRequestBuilder<SentSyncWorker>().setInitialDelay(3, TimeUnit.SECONDS).build()
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, request)
    }

    @Synchronized
    fun enable(context: Context) {
        val app = context.applicationContext
        val request = PeriodicWorkRequestBuilder<SentSyncWorker>(15, TimeUnit.MINUTES).setConstraints(Constraints.NONE).build()
        WorkManager.getInstance(app).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
        if (observer == null) {
            val o = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) = requestSoon(app)
            }
            app.contentResolver.registerContentObserver(SMS, true, o)
            observer = o
        }
        requestSoon(app)
    }

    @Synchronized
    fun disable(context: Context) {
        val app = context.applicationContext
        WorkManager.getInstance(app).cancelUniqueWork(PERIODIC)
        WorkManager.getInstance(app).cancelUniqueWork(NOW)
        observer?.let { app.contentResolver.unregisterContentObserver(it) }
        observer = null
    }
}
