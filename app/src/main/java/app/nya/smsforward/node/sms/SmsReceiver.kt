package app.nya.smsforward.node.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import app.nya.smsforward.node.node.NodeRuntime
import app.nya.smsforward.node.work.UploadScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Receives every incoming SMS (`SMS_RECEIVED`), even when the app is not running.
 *
 * The receiver does the bare minimum: it puts the message into the local queue and asks WorkManager to report it. It
 * never touches the network itself, so a slow or dead server cannot delay or lose anything. The manifest entry demands
 * the BROADCAST_SMS permission, which only the system holds, so other apps cannot feed it fake messages.
 */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        val parts = messages.map { SmsPart(it.originatingAddress, it.messageBody, it.timestampMillis) }
        val slot = SimSlots.slotOf(intent)

        // The database must not be opened on the main thread, and the queue write must finish before the process may be
        // killed: goAsync() keeps the broadcast alive (for a few seconds) while it runs on a background dispatcher.
        val pending = goAsync()
        val runtime = NodeRuntime.get(context)
        runtime.scope.launch(Dispatchers.IO) {
            try {
                val cardNumber = slot?.let { wanted ->
                    SimSlots.activeSims(context).firstOrNull { it.slot == wanted }?.number
                }
                if (runtime.incoming.onReceived(parts, slot, cardNumber) > 0) {
                    UploadScheduler.enqueue(context.applicationContext)
                    runtime.refresh()
                }
            } catch (e: Exception) {
                // Never let a bug here take the whole process (and the next SMS) down.
                Log.e(TAG, "could not queue an incoming SMS", e)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "SmsReceiver"
    }
}
