package app.nya.smsforward.node

import android.app.Application
import app.nya.smsforward.node.node.NodeRuntime
import app.nya.smsforward.node.work.SentSyncScheduler
import app.nya.smsforward.node.work.UploadScheduler

class NodeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        NodeRuntime.get(this)
        // Idempotent (KEEP): a slow background sweep that also picks up anything an earlier enqueue missed.
        UploadScheduler.schedulePeriodic(this)
        // Sync of sent messages is opt-in; once on, the observer has to be re-registered in every new process.
        if (NodeRuntime.get(this).settings.syncSent) SentSyncScheduler.enable(this)
    }
}
