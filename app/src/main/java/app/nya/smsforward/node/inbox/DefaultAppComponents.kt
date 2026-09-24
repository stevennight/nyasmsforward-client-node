package app.nya.smsforward.node.inbox

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.provider.Telephony
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import app.nya.smsforward.node.sms.SmsAssembler
import app.nya.smsforward.node.sms.SmsPart

/*
 * The components Android requires before it lets the full edition become the default SMS app (registered only in
 * src/full/AndroidManifest.xml). Forwarding to the server does not live here: SMS_RECEIVED still reaches
 * sms.SmsReceiver exactly as in the lite edition, so both editions report messages the same way.
 */

/**
 * SMS_DELIVER goes only to the default SMS app, and from then on nobody else stores incoming messages: this writes
 * them to the inbox and posts the notification.
 */
class SmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        val pdus = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (pdus.isNullOrEmpty()) return
        val subId = intent.getIntExtra(
            SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX,
            intent.getIntExtra("subscription", SubscriptionManager.INVALID_SUBSCRIPTION_ID),
        ).takeIf { it != SubscriptionManager.INVALID_SUBSCRIPTION_ID }
        val sentAt = pdus.first().timestampMillis
        val messages = SmsAssembler.assemble(pdus.map { SmsPart(it.originatingAddress, it.messageBody, it.timestampMillis) })

        val pending = goAsync()
        Thread {
            try {
                val store = SmsStore(context)
                for (m in messages) {
                    store.insertIncoming(m.peer, m.body, System.currentTimeMillis(), sentAt, subId)
                    InboxNotifier.newMessage(context, store.threadIdFor(m.peer), m.peer, m.body)
                }
            } catch (e: Exception) {
                Log.e(TAG, "could not store an incoming SMS", e)
            } finally {
                pending.finish()
            }
        }.start()
    }

    private companion object {
        const val TAG = "SmsDeliverReceiver"
    }
}

/**
 * WAP_PUSH_DELIVER (MMS) must be handled by a default SMS app. Downloading MMS is not supported yet; the notification
 * at least tells the user one arrived instead of dropping it silently.
 */
class MmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION) return
        InboxNotifier.newMessage(context, 0, "彩信", "收到一条彩信。当前版本还不能显示彩信内容。")
    }
}

/** "Reply with a message" from the incoming-call screen: the dialer hands the text to the default SMS app. */
class RespondViaMessageService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == TelephonyManager.ACTION_RESPOND_VIA_MESSAGE) {
            val address = recipientOf(intent.data)
            val text = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!address.isNullOrBlank() && !text.isNullOrBlank()) {
                Thread { LocalSender.send(this, address, text, null) }.start()
            }
        }
        stopSelf(startId)
        return START_NOT_STICKY
    }
}

/** The number in an sms:, smsto:, mms: or mmsto: URI ("smsto:138…?body=…"); the first one when several are listed. */
fun recipientOf(uri: Uri?): String? {
    val raw = uri?.schemeSpecificPart ?: return null
    return raw.substringBefore('?').split(',', ';').firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
}
