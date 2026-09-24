package app.nya.smsforward.node.inbox

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsManager
import android.util.Log

/**
 * Sends an SMS the user typed in the full edition. The message is stored in the outbox first (as the default SMS app we
 * are the only one who stores it), then moved to "sent" or "failed" when the radio reports back.
 */
object LocalSender {
    fun send(context: Context, address: String, body: String, subId: Int?): Boolean {
        val app = context.applicationContext
        val store = SmsStore(app)
        val row = store.insertOutgoing(address, body, subId)
        return try {
            val manager = managerFor(app, subId)
            val parts = manager.divideMessage(body)
            val sent = ArrayList<PendingIntent>(parts.size)
            for (i in parts.indices) sent += callback(app, row, i, parts.size)
            if (parts.size == 1) {
                manager.sendTextMessage(address, null, parts[0], sent[0], null)
            } else {
                manager.sendMultipartTextMessage(address, null, parts, sent, null)
            }
            true
        } catch (e: Exception) {
            // No SIM, no permission, an empty number: the row must not stay "sending" forever.
            Log.w(TAG, "local send failed", e)
            row?.let { store.setType(it, Telephony.Sms.MESSAGE_TYPE_FAILED) }
            false
        }
    }

    private fun managerFor(context: Context, subId: Int?): SmsManager =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val base = context.getSystemService(SmsManager::class.java)
            if (subId != null) base.createForSubscriptionId(subId) else base
        } else {
            @Suppress("DEPRECATION")
            if (subId != null) SmsManager.getSmsManagerForSubscriptionId(subId) else SmsManager.getDefault()
        }

    private fun callback(context: Context, row: Uri?, index: Int, count: Int): PendingIntent {
        val intent = Intent(context, LocalSendReceiver::class.java)
            .setData(Uri.parse("nyasms://local/${row?.lastPathSegment ?: "none"}/$index"))
            .putExtra(LocalSendReceiver.EXTRA_ROW, row?.toString())
            .putExtra(LocalSendReceiver.EXTRA_INDEX, index)
            .putExtra(LocalSendReceiver.EXTRA_COUNT, count)
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private const val TAG = "LocalSender"
}

/** The radio's per-part result for [LocalSender]. Not exported: only our own PendingIntents reach it. */
class LocalSendReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val row = intent.getStringExtra(EXTRA_ROW)?.let(Uri::parse) ?: return
        val index = intent.getIntExtra(EXTRA_INDEX, 0)
        val count = intent.getIntExtra(EXTRA_COUNT, 1)
        val store = SmsStore(context)
        when {
            resultCode != android.app.Activity.RESULT_OK -> store.setType(row, Telephony.Sms.MESSAGE_TYPE_FAILED)
            index == count - 1 -> store.markSent(row)
        }
    }

    companion object {
        const val EXTRA_ROW = "row"
        const val EXTRA_INDEX = "index"
        const val EXTRA_COUNT = "count"
    }
}
