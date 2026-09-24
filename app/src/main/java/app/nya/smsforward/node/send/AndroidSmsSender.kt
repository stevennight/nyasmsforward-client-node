package app.nya.smsforward.node.send

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsManager
import app.nya.smsforward.node.BuildConfig
import app.nya.smsforward.node.inbox.SmsStore
import app.nya.smsforward.node.net.SimInfo
import app.nya.smsforward.node.sms.SimSlots

/** [SmsSender] on the phone's SMS radio. */
class AndroidSmsSender(context: Context, private val manualNumbers: () -> Map<Int, String> = { emptyMap() }) : SmsSender {
    private val app = context.applicationContext

    override fun canSend(): Boolean = app.checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    override fun canReadSims(): Boolean = app.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

    override fun activeSims(): List<SimInfo> = SimSlots.activeSims(app, manualNumbers())

    override fun send(task: SendTask, sim: SimChoice) {
        val manager = managerFor(sim)
        val parts = manager.divideMessage(task.body)
        val count = parts.size
        val sent = ArrayList<PendingIntent>(count)
        val delivered = ArrayList<PendingIntent>(count)
        for (i in 0 until count) {
            sent += callback(task.taskId, i, count, SendStatusReceiver.KIND_SENT)
            delivered += callback(task.taskId, i, count, SendStatusReceiver.KIND_DELIVERED)
        }
        // Long texts go out as one multipart message so the recipient's phone joins them; the radio reports every part.
        if (count == 1) {
            manager.sendTextMessage(task.to, null, parts[0], sent[0], delivered[0])
        } else {
            manager.sendMultipartTextMessage(task.to, null, parts, sent, delivered)
        }
        // As the default SMS app (full edition) nobody else records what we send, so the phone's own inbox would
        // otherwise never show replies made from the web.
        if (BuildConfig.FULL_EDITION && SmsStore.isDefaultSmsApp(app)) {
            SmsStore(app).insertOutgoing(task.to, task.body, (sim as? SimChoice.Subscription)?.id, Telephony.Sms.MESSAGE_TYPE_SENT)
        }
    }

    private fun managerFor(sim: SimChoice): SmsManager = when (sim) {
        is SimChoice.Subscription ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                app.getSystemService(SmsManager::class.java).createForSubscriptionId(sim.id)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getSmsManagerForSubscriptionId(sim.id)
            }
        else ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                app.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
    }

    /** An explicit, immutable broadcast to [SendStatusReceiver]; the data URI keeps every (task, part, kind) distinct. */
    private fun callback(taskId: String, index: Int, count: Int, kind: String): PendingIntent {
        val intent = Intent(app, SendStatusReceiver::class.java)
            .setData(Uri.parse("nyasms://send/$taskId/$index/$kind"))
            .putExtra(SendStatusReceiver.EXTRA_TASK, taskId)
            .putExtra(SendStatusReceiver.EXTRA_INDEX, index)
            .putExtra(SendStatusReceiver.EXTRA_COUNT, count)
            .putExtra(SendStatusReceiver.EXTRA_KIND, kind)
        return PendingIntent.getBroadcast(app, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}
