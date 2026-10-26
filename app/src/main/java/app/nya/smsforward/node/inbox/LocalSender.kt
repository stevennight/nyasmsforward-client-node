package app.nya.smsforward.node.inbox

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.SmsMessage
import android.util.Log

/**
 * Sends an SMS the user typed in the inbox. The message is stored in the outbox first (as the default SMS app we
 * are the only one who stores it), then moved to "sent" or "failed" when the radio reports back. With delivery
 * reports on, the carrier's report later marks it 已送达 / 未送达.
 */
object LocalSender {
    fun send(context: Context, address: String, body: String, subId: Int?): Boolean {
        val app = context.applicationContext
        val store = SmsStore(app)
        val reports = SmsAppFeature.deliveryReports(app)
        val row = store.insertOutgoing(address, body, subId, status = if (reports) Telephony.Sms.STATUS_PENDING else Telephony.Sms.STATUS_NONE)
        return transmit(app, store, row, address, body, subId, reports)
    }

    /** "重新发送" on a failed message: the same row goes out again (so the conversation keeps one copy of it). */
    fun resend(context: Context, row: SmsRow): Boolean {
        val app = context.applicationContext
        val store = SmsStore(app)
        val reports = SmsAppFeature.deliveryReports(app)
        val uri = store.prepareResend(row.id, if (reports) Telephony.Sms.STATUS_PENDING else Telephony.Sms.STATUS_NONE) ?: return false
        return transmit(app, store, uri, row.address, row.body, row.subId, reports)
    }

    private fun transmit(app: Context, store: SmsStore, row: Uri?, address: String, body: String, subId: Int?, reports: Boolean): Boolean =
        try {
            val manager = managerFor(app, subId)
            val parts = manager.divideMessage(body)
            val sent = ArrayList<PendingIntent>(parts.size)
            for (i in parts.indices) sent += callback(app, row, LocalSendReceiver.ACTION_SENT, i, parts.size)
            // One delivery report, for the last part: the message counts as delivered when all of it has arrived.
            val delivered = ArrayList<PendingIntent?>(parts.size)
            for (i in parts.indices) delivered += if (reports && i == parts.size - 1) callback(app, row, LocalSendReceiver.ACTION_DELIVERED, i, parts.size) else null
            if (parts.size == 1) {
                manager.sendTextMessage(address, null, parts[0], sent[0], delivered[0])
            } else {
                manager.sendMultipartTextMessage(address, null, parts, sent, delivered)
            }
            true
        } catch (e: Exception) {
            // No SIM, no permission, an empty number: the row must not stay "sending" forever.
            Log.w(TAG, "local send failed", e)
            row?.let { store.setType(it, Telephony.Sms.MESSAGE_TYPE_FAILED) }
            false
        }

    private fun managerFor(context: Context, subId: Int?): SmsManager =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val base = context.getSystemService(SmsManager::class.java)
            if (subId != null) base.createForSubscriptionId(subId) else base
        } else {
            @Suppress("DEPRECATION")
            if (subId != null) SmsManager.getSmsManagerForSubscriptionId(subId) else SmsManager.getDefault()
        }

    private fun callback(context: Context, row: Uri?, action: String, index: Int, count: Int): PendingIntent {
        val intent = Intent(context, LocalSendReceiver::class.java)
            .setAction(action)
            .setData(Uri.parse("nyasms://local/${row?.lastPathSegment ?: "none"}/$action/$index"))
            .putExtra(LocalSendReceiver.EXTRA_ROW, row?.toString())
            .putExtra(LocalSendReceiver.EXTRA_INDEX, index)
            .putExtra(LocalSendReceiver.EXTRA_COUNT, count)
        // Mutable: the radio fills in the delivery report's PDU and format.
        val flags = if (action == LocalSendReceiver.ACTION_DELIVERED) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, 0, intent, flags or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /**
     * What a delivery report's status means for Telephony.Sms.STATUS (the way AOSP's messaging app reads it).
     * GSM (3GPP TS 23.040 TP-Status): below 0x20 delivered, below 0x40 still trying, else failed. CDMA reports
     * carry the error class in bits 24-25: 0 delivered, 2 still trying, 3 failed.
     */
    fun deliveryStatus(raw: Int, format: String?): Int =
        if (format == SmsMessage.FORMAT_3GPP2) {
            when ((raw shr 24) and 0x03) {
                0 -> Telephony.Sms.STATUS_COMPLETE
                2 -> Telephony.Sms.STATUS_PENDING
                else -> Telephony.Sms.STATUS_FAILED
            }
        } else {
            when {
                raw < 0x20 -> Telephony.Sms.STATUS_COMPLETE
                raw < 0x40 -> Telephony.Sms.STATUS_PENDING
                else -> Telephony.Sms.STATUS_FAILED
            }
        }

    private const val TAG = "LocalSender"
}

/** The radio's per-part result and the delivery report for [LocalSender]. Not exported: only our own PendingIntents reach it. */
class LocalSendReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val row = intent.getStringExtra(EXTRA_ROW)?.let(Uri::parse) ?: return
        val store = SmsStore(context)
        if (intent.action == ACTION_DELIVERED) {
            val pdu = intent.getByteArrayExtra("pdu") ?: return
            val format = intent.getStringExtra("format")
            val report = runCatching { SmsMessage.createFromPdu(pdu, format) }.getOrNull() ?: return
            store.setStatus(row, LocalSender.deliveryStatus(report.status, format))
            return
        }
        val index = intent.getIntExtra(EXTRA_INDEX, 0)
        val count = intent.getIntExtra(EXTRA_COUNT, 1)
        when {
            resultCode != android.app.Activity.RESULT_OK -> store.setType(row, Telephony.Sms.MESSAGE_TYPE_FAILED)
            index == count - 1 -> store.markSent(row)
        }
    }

    companion object {
        const val ACTION_SENT = "app.nya.smsforward.node.LOCAL_SENT"
        const val ACTION_DELIVERED = "app.nya.smsforward.node.LOCAL_DELIVERED"
        const val EXTRA_ROW = "row"
        const val EXTRA_INDEX = "index"
        const val EXTRA_COUNT = "count"
    }
}
