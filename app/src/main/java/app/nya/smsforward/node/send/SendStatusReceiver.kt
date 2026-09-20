package app.nya.smsforward.node.send

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsMessage
import android.util.Log
import app.nya.smsforward.node.node.NodeRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Receives the radio's answers to the SMS this phone sent for the server: "sent" per part, and delivery reports.
 *
 * Not exported: only the PendingIntents [AndroidSmsSender] hands to the system reach it, so no other app can forge a
 * receipt. The database work runs off the main thread under goAsync().
 */
class SendStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getStringExtra(EXTRA_TASK) ?: return
        val index = intent.getIntExtra(EXTRA_INDEX, 0)
        val count = intent.getIntExtra(EXTRA_COUNT, 1)
        val kind = intent.getStringExtra(EXTRA_KIND) ?: return
        val resultCode = resultCode
        val deliveryOk = if (kind == KIND_DELIVERED) deliveryVerdict(intent) else null

        val pending = goAsync()
        val runtime = NodeRuntime.get(context)
        runtime.scope.launch(Dispatchers.IO) {
            try {
                when (kind) {
                    KIND_SENT -> runtime.coordinator.onPartSent(taskId, index, count, SendStatusTracker.errorFor(resultCode, Activity.RESULT_OK))
                    // No verdict yet (the network is still trying): wait for the final report.
                    KIND_DELIVERED -> deliveryOk?.let { runtime.coordinator.onPartDelivered(taskId, index, count, it) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "could not record the result of a sent SMS", e)
            } finally {
                pending.finish()
            }
        }
    }

    /** Reads the delivery report PDU; null when it says the network is still trying. */
    @Suppress("DEPRECATION")
    private fun deliveryVerdict(intent: Intent): Boolean? {
        val pdu = intent.getByteArrayExtra("pdu") ?: return true // a report without details: it did arrive
        return try {
            SendStatusTracker.deliveryOk(SmsMessage.createFromPdu(pdu, intent.getStringExtra("format")).status)
        } catch (e: Exception) {
            true
        }
    }

    companion object {
        const val EXTRA_TASK = "task"
        const val EXTRA_INDEX = "index"
        const val EXTRA_COUNT = "count"
        const val EXTRA_KIND = "kind"
        const val KIND_SENT = "sent"
        const val KIND_DELIVERED = "delivered"
        private const val TAG = "SendStatusReceiver"
    }
}
